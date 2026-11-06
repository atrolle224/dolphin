// Copyright 2020 Dolphin Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

#include <cerrno>
#include <cstring>

#include <fcntl.h>
#include <netdb.h>
#include <sys/select.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

#include "Common/Logging/Log.h"
#include "Common/StringUtil.h"
#include "Core/Config/MainSettings.h"
#include "Core/HW/EXI/EXI_Device.h"
#include "Core/HW/EXI/EXI_DeviceEthernet.h"

namespace ExpansionInterface
{
// A tapserver: a local socket (a path, as on macOS) or a TCP server (host:port, such as an
// online service's), which exchanges Ethernet frames, each after its 16-bit length.

#ifndef MSG_NOSIGNAL
#define MSG_NOSIGNAL 0
#endif

static int ConnectUnix(const std::string& path)
{
  sockaddr_un sun = {};
  if (path.size() + 1 > sizeof(sun.sun_path))
  {
    ERROR_LOG_FMT(SP1, "tapserver socket path is too long: {}", path);
    return -1;
  }
  sun.sun_family = AF_UNIX;
  std::strcpy(sun.sun_path, path.c_str());

  const int fd = socket(AF_UNIX, SOCK_STREAM, 0);
  if (fd == -1)
    return -1;
  if (connect(fd, reinterpret_cast<sockaddr*>(&sun), sizeof(sun)) == -1)
  {
    close(fd);
    return -1;
  }
  return fd;
}

static int ConnectTcp(const std::string& host, const std::string& port)
{
  addrinfo hints = {};
  hints.ai_family = AF_UNSPEC;
  hints.ai_socktype = SOCK_STREAM;
  addrinfo* found = nullptr;
  if (getaddrinfo(host.c_str(), port.c_str(), &hints, &found) != 0)
    return -1;

  int fd = -1;
  for (addrinfo* ai = found; ai && fd == -1; ai = ai->ai_next)
  {
    fd = socket(ai->ai_family, ai->ai_socktype, ai->ai_protocol);
    if (fd != -1 && connect(fd, ai->ai_addr, ai->ai_addrlen) == -1)
    {
      close(fd);
      fd = -1;
    }
  }
  freeaddrinfo(found);
  return fd;
}

// TCP may deliver a frame in pieces
static bool ReadExact(int fd, u8* buffer, size_t size)
{
  while (size > 0)
  {
    const ssize_t n = read(fd, buffer, size);
    if (n <= 0)
      return false;
    buffer += n;
    size -= n;
  }
  return true;
}

static bool WriteAll(int fd, const u8* buffer, size_t size)
{
  while (size > 0)
  {
    const ssize_t n = send(fd, buffer, size, MSG_NOSIGNAL);
    if (n <= 0)
      return false;
    buffer += n;
    size -= n;
  }
  return true;
}

bool CEXIETHERNET::TAPServerNetworkInterface::Activate()
{
  if (IsActivated())
    return true;

  const std::string destination = Config::Get(Config::MAIN_BBA_TAPSERVER_DESTINATION);
  const size_t colon = destination.rfind(':');
  if (destination.empty() || destination[0] == '/' || colon == std::string::npos)
    fd = ConnectUnix(destination);
  else
    fd = ConnectTcp(destination.substr(0, colon), destination.substr(colon + 1));

  if (fd == -1)
  {
    ERROR_LOG_FMT(SP1, "Couldn't connect to tapserver {} ({}), unable to init BBA", destination,
                  errno);
    return false;
  }

  INFO_LOG_FMT(SP1, "BBA initialized with tapserver {}", destination);
  return RecvInit();
}

bool CEXIETHERNET::TAPServerNetworkInterface::SendFrame(const u8* frame, u32 size)
{
  DEBUG_LOG_FMT(SP1, "SendFrame {:x}\n{}", size, ArrayToString(frame, size, 0x10));

  const u16 size16 = u16(size);
  if (!WriteAll(fd, reinterpret_cast<const u8*>(&size16), 2) || !WriteAll(fd, frame, size))
  {
    ERROR_LOG_FMT(SP1, "SendFrame(): could not write a frame of {} bytes, err={}", size, errno);
    return false;
  }
  m_eth_ref->SendComplete();
  return true;
}

void CEXIETHERNET::TAPServerNetworkInterface::ReadThreadHandler()
{
  while (!readThreadShutdown.IsSet())
  {
    fd_set rfds;
    FD_ZERO(&rfds);
    FD_SET(fd, &rfds);

    timeval timeout;
    timeout.tv_sec = 0;
    timeout.tv_usec = 50000;
    if (select(fd + 1, &rfds, nullptr, nullptr, &timeout) <= 0)
      continue;

    u16 size;
    if (!ReadExact(fd, reinterpret_cast<u8*>(&size), 2) || size > BBA_RECV_SIZE)
    {
      ERROR_LOG_FMT(SP1, "Failed to read a frame size from the tapserver, err={}", errno);
      break;
    }
    if (!ReadExact(fd, m_eth_ref->mRecvBuffer.get(), size))
    {
      ERROR_LOG_FMT(SP1, "Failed to read frame data from the tapserver, err={}", errno);
      break;
    }
    if (readEnabled.IsSet())
    {
      DEBUG_LOG_FMT(SP1, "Read data: {}", ArrayToString(m_eth_ref->mRecvBuffer.get(), size, 0x10));
      m_eth_ref->mRecvBufferLength = size;
      m_eth_ref->RecvHandlePacket();
    }
  }
}

bool CEXIETHERNET::TAPServerNetworkInterface::RecvInit()
{
  readThread = std::thread(&CEXIETHERNET::TAPServerNetworkInterface::ReadThreadHandler, this);
  return true;
}

}  // namespace ExpansionInterface
