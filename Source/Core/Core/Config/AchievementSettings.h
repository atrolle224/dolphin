// Copyright 2023 Dolphin Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

#pragma once

#include <string>

#include "Common/Config/Config.h"

namespace Config
{
// Dolphin.ini [Achievements], as in upstream Dolphin; the password is never stored, only the
// token the server returns for it.
extern const Info<bool> RA_ENABLED;
extern const Info<std::string> RA_USERNAME;
extern const Info<std::string> RA_API_TOKEN;
extern const Info<bool> RA_HARDCORE_ENABLED;
}  // namespace Config
