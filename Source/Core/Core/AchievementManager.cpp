// Copyright 2023 Dolphin Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

#include "Core/AchievementManager.h"

#include <condition_variable>
#include <cstdio>
#include <memory>

#include <fmt/format.h>
#include <rcheevos/include/rc_client.h>
#include <rcheevos/include/rc_hash.h>

#include "Common/Common.h"
#include "Common/HttpRequest.h"
#include "Common/Logging/Log.h"
#include "Common/Version.h"
#include "Core/Config/AchievementSettings.h"
#include "Core/ConfigManager.h"
#include "Core/PowerPC/MMU.h"
#include "DiscIO/Volume.h"
#include "VideoCommon/OnScreenDisplay.h"

namespace
{
struct DiscReader
{
  std::unique_ptr<DiscIO::Volume> volume;
  s64 position = 0;
};

// rcheevos hashes the disc through these, so compressed images (RVZ, GCZ, WIA, CISO) work
void* FileOpen(const char* path)
{
  auto reader = std::make_unique<DiscReader>();
  reader->volume = DiscIO::CreateVolume(path);
  return reader->volume ? reader.release() : nullptr;
}

void FileSeek(void* handle, int64_t offset, int origin)
{
  auto* reader = static_cast<DiscReader*>(handle);
  if (origin == SEEK_SET)
    reader->position = offset;
  else if (origin == SEEK_CUR)
    reader->position += offset;
  else if (origin == SEEK_END)
    reader->position = reader->volume->GetSize() + offset;
}

int64_t FileTell(void* handle)
{
  return static_cast<DiscReader*>(handle)->position;
}

size_t FileRead(void* handle, void* buffer, size_t bytes)
{
  auto* reader = static_cast<DiscReader*>(handle);
  if (!reader->volume->Read(reader->position, bytes, static_cast<u8*>(buffer),
                            DiscIO::PARTITION_NONE))
  {
    return 0;
  }
  reader->position += bytes;
  return bytes;
}

void FileClose(void* handle)
{
  delete static_cast<DiscReader*>(handle);
}

std::string UserAgent(rc_client_t* client)
{
  char clause[64] = {};
  rc_client_get_user_agent_clause(client, clause, sizeof(clause));
  return fmt::format("DolphinMMJR2-477M/{} {}", Common::scm_desc_str, clause);
}
}  // namespace

AchievementManager& AchievementManager::GetInstance()
{
  static AchievementManager s_instance;
  return s_instance;
}

bool AchievementManager::EnsureClient()
{
  std::lock_guard lg{m_lock};
  if (m_client)
    return true;

  m_queue.Reset([](std::function<void()> job) { job(); });
  m_client = rc_client_create(MemoryPeeker, [](const rc_api_request_t* request,
                                               rc_client_server_callback_t callback,
                                               void* callback_data, rc_client_t* client) {
    std::string url = request->url;
    std::string post_data = request->post_data ? request->post_data : "";
    std::string agent = UserAgent(client);
    std::string content_type =
        request->content_type ? request->content_type : "application/x-www-form-urlencoded";
    GetInstance().m_queue.EmplaceItem([url, post_data, agent, content_type, callback,
                                       callback_data] {
      Common::HttpRequest http;
      Common::HttpRequest::Headers headers = {{"User-Agent", agent}};
      if (!post_data.empty())
        headers.emplace("Content-Type", content_type);
      const auto response =
          post_data.empty() ?
              http.Get(url, headers, Common::HttpRequest::AllowedReturnCodes::All) :
              http.Post(url, post_data, headers, Common::HttpRequest::AllowedReturnCodes::All);

      rc_api_server_response_t server_response = {};
      if (response && !response->empty())
      {
        server_response.body = reinterpret_cast<const char*>(response->data());
        server_response.body_length = response->size();
        server_response.http_status_code = 200;
      }
      else
      {
        static constexpr char error[] = "Failed HTTP request.";
        server_response.body = error;
        server_response.body_length = sizeof(error) - 1;
        server_response.http_status_code = RC_API_SERVER_RESPONSE_RETRYABLE_CLIENT_ERROR;
      }
      callback(&server_response, callback_data);
    });
  });
  if (!m_client)
    return false;
  rc_client_set_event_handler(m_client, EventHandler);
  rc_client_set_hardcore_enabled(m_client, Config::Get(Config::RA_HARDCORE_ENABLED));

  static rc_hash_filereader reader = {FileOpen, FileSeek, FileTell, FileRead, FileClose};
  rc_hash_init_custom_filereader(&reader);
  return true;
}

std::string AchievementManager::Login(const std::string& username, const std::string& password)
{
  if (!EnsureClient())
    return "RetroAchievements could not start.";

  struct Result
  {
    std::mutex lock;
    std::condition_variable done;
    bool finished = false;
    std::string error;
  };
  auto result = std::make_shared<Result>();
  auto* keep = new std::shared_ptr<Result>(result);

  {
    std::lock_guard lg{m_lock};
    rc_client_begin_login_with_password(
        m_client, username.c_str(), password.c_str(),
        [](int code, const char* error_message, rc_client_t* client, void* userdata) {
          auto* held = static_cast<std::shared_ptr<Result>*>(userdata);
          auto r = *held;
          delete held;
          std::string error;
          if (code == RC_OK)
          {
            const auto* user = rc_client_get_user_info(client);
            Config::SetBaseOrCurrent(Config::RA_USERNAME, std::string(user->username));
            Config::SetBaseOrCurrent(Config::RA_API_TOKEN, std::string(user->token));
            Config::Save();
            OSD::AddMessage(fmt::format("RetroAchievements: signed in as {}", user->display_name),
                            OSD::Duration::NORMAL, OSD::Color::GREEN);
          }
          else
          {
            error = error_message ? error_message : "Sign-in failed.";
          }
          std::lock_guard lg{r->lock};
          r->error = error;
          r->finished = true;
          r->done.notify_all();
        },
        keep);
  }

  std::unique_lock ul{result->lock};
  if (!result->done.wait_for(ul, std::chrono::seconds(30), [&] { return result->finished; }))
    return "RetroAchievements did not answer.";
  return result->error;
}

void AchievementManager::Logout()
{
  CloseGame();
  {
    std::lock_guard lg{m_lock};
    if (m_client)
      rc_client_logout(m_client);
  }
  Config::SetBaseOrCurrent(Config::RA_API_TOKEN, std::string());
  Config::Save();
}

std::string AchievementManager::GetUsername() const
{
  if (Config::Get(Config::RA_API_TOKEN).empty())
    return "";
  return Config::Get(Config::RA_USERNAME);
}

void AchievementManager::LoginWithToken()
{
  const auto* user = rc_client_get_user_info(m_client);
  if (user)
    return;
  rc_client_begin_login_with_token(
      m_client, Config::Get(Config::RA_USERNAME).c_str(), Config::Get(Config::RA_API_TOKEN).c_str(),
      [](int code, const char* error_message, rc_client_t*, void*) {
        if (code != RC_OK)
        {
          OSD::AddMessage(fmt::format("RetroAchievements: {}",
                                      error_message ? error_message : "sign-in failed"),
                          OSD::Duration::VERY_LONG, OSD::Color::RED);
        }
      },
      nullptr);
}

void AchievementManager::LoadGame(const std::string& disc_path, bool wii)
{
  if (!Config::Get(Config::RA_ENABLED) || GetUsername().empty() || !EnsureClient())
    return;

  std::lock_guard lg{m_lock};
  rc_client_set_hardcore_enabled(m_client, Config::Get(Config::RA_HARDCORE_ENABLED));
  LoginWithToken();
  m_game_loaded = false;
  rc_client_begin_identify_and_load_game(
      m_client, wii ? RC_CONSOLE_WII : RC_CONSOLE_GAMECUBE, disc_path.c_str(), nullptr, 0,
      [](int code, const char* error_message, rc_client_t* client, void*) {
        if (code == RC_NO_GAME_LOADED)
        {
          OSD::AddMessage("RetroAchievements: this game has no achievements",
                          OSD::Duration::NORMAL, OSD::Color::YELLOW);
          return;
        }
        if (code != RC_OK)
        {
          OSD::AddMessage(fmt::format("RetroAchievements: {}",
                                      error_message ? error_message : "could not load the game"),
                          OSD::Duration::VERY_LONG, OSD::Color::RED);
          return;
        }
        GetInstance().m_game_loaded = true;
        const auto* game = rc_client_get_game_info(client);
        rc_client_user_game_summary_t summary;
        rc_client_get_user_game_summary(client, &summary);
        OSD::AddMessage(fmt::format("RetroAchievements{}: {} - {} of {} unlocked",
                                    rc_client_get_hardcore_enabled(client) ? " (hardcore)" : "",
                                    game->title, summary.num_unlocked_achievements,
                                    summary.num_core_achievements),
                        OSD::Duration::VERY_LONG, OSD::Color::CYAN);
      },
      nullptr);
}

void AchievementManager::CloseGame()
{
  std::lock_guard lg{m_lock};
  if (m_client && m_game_loaded)
    rc_client_unload_game(m_client);
  m_game_loaded = false;
}

void AchievementManager::DoFrame()
{
  std::lock_guard lg{m_lock};
  if (m_client && m_game_loaded)
    rc_client_do_frame(m_client);
}

bool AchievementManager::IsHardcoreModeActive() const
{
  std::lock_guard lg{m_lock};
  return m_client && m_game_loaded && rc_client_get_hardcore_enabled(m_client);
}

void AchievementManager::Shutdown()
{
  std::lock_guard lg{m_lock};
  if (!m_client)
    return;
  rc_client_destroy(m_client);
  m_client = nullptr;
  m_game_loaded = false;
}

u32 AchievementManager::MemoryPeeker(u32 address, u8* buffer, u32 num_bytes, rc_client_t*)
{
  for (u32 i = 0; i < num_bytes; i++)
  {
    const auto value =
        PowerPC::HostTryReadU8(address + i, PowerPC::RequestedAddressSpace::Physical);
    if (!value.success)
      return i;
    buffer[i] = value.value;
  }
  return num_bytes;
}

void AchievementManager::EventHandler(const rc_client_event_t* event, rc_client_t*)
{
  switch (event->type)
  {
  case RC_CLIENT_EVENT_ACHIEVEMENT_TRIGGERED:
    OSD::AddMessage(fmt::format("Achievement unlocked: {} ({} points)", event->achievement->title,
                                event->achievement->points),
                    OSD::Duration::VERY_LONG, OSD::Color::YELLOW);
    break;
  case RC_CLIENT_EVENT_GAME_COMPLETED:
    OSD::AddMessage("RetroAchievements: every achievement of this game unlocked",
                    OSD::Duration::VERY_LONG, OSD::Color::GREEN);
    break;
  case RC_CLIENT_EVENT_LEADERBOARD_SUBMITTED:
    OSD::AddMessage(fmt::format("Leaderboard {}: {}", event->leaderboard->title,
                                event->leaderboard->tracker_value),
                    OSD::Duration::NORMAL, OSD::Color::CYAN);
    break;
  case RC_CLIENT_EVENT_DISCONNECTED:
    OSD::AddMessage("RetroAchievements: offline, unlocks are kept until it reconnects",
                    OSD::Duration::NORMAL, OSD::Color::RED);
    break;
  case RC_CLIENT_EVENT_RECONNECTED:
    OSD::AddMessage("RetroAchievements: reconnected", OSD::Duration::SHORT, OSD::Color::GREEN);
    break;
  case RC_CLIENT_EVENT_SERVER_ERROR:
    ERROR_LOG_FMT(CORE, "RetroAchievements server error: {} {}", event->server_error->api,
                  event->server_error->error_message);
    break;
  default:
    break;
  }
}
