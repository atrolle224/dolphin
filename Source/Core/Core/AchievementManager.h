// Copyright 2023 Dolphin Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

// RetroAchievements for Dolphin MMJR2 477M: a smaller take on upstream Dolphin's
// AchievementManager (2024), on the same rcheevos client, for this older core. It logs in,
// identifies the GameCube or Wii disc, checks achievements every field, and shows unlocks
// on screen. Hardcore mode blocks loading states and cheats.

#pragma once

#include <mutex>
#include <string>

#include "Common/CommonTypes.h"
#include "Common/WorkQueueThread.h"

struct rc_client_t;
struct rc_client_event_t;

class AchievementManager
{
public:
  static AchievementManager& GetInstance();

  // Synchronous: returns "" on success or the server's error. Only the token is kept.
  std::string Login(const std::string& username, const std::string& password);
  void Logout();
  std::string GetUsername() const;

  void LoadGame(const std::string& disc_path, bool wii);
  void CloseGame();
  // Every emulated field, on the CPU thread.
  void DoFrame();

  bool IsHardcoreModeActive() const;
  void Shutdown();

private:
  AchievementManager() = default;
  bool EnsureClient();
  void LoginWithToken();

  static u32 MemoryPeeker(u32 address, u8* buffer, u32 num_bytes, rc_client_t* client);
  static void EventHandler(const rc_client_event_t* event, rc_client_t* client);

  rc_client_t* m_client = nullptr;
  mutable std::recursive_mutex m_lock;
  Common::WorkQueueThread<std::function<void()>> m_queue;
  bool m_game_loaded = false;
};
