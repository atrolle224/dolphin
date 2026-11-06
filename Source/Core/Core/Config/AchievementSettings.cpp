// Copyright 2023 Dolphin Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

#include "Core/Config/AchievementSettings.h"

namespace Config
{
const Info<bool> RA_ENABLED{{System::Main, "Achievements", "Enabled"}, true};
const Info<std::string> RA_USERNAME{{System::Main, "Achievements", "Username"}, ""};
const Info<std::string> RA_API_TOKEN{{System::Main, "Achievements", "ApiToken"}, ""};
const Info<bool> RA_HARDCORE_ENABLED{{System::Main, "Achievements", "HardcoreEnabled"}, false};
}  // namespace Config
