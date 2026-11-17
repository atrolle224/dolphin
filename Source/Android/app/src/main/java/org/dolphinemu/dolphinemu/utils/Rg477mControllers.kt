// SPDX-License-Identifier: GPL-2.0-or-later

package org.dolphinemu.dolphinemu.utils

import android.content.Context
import android.os.Build
import android.view.InputDevice
import android.view.KeyEvent
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import org.dolphinemu.dolphinemu.features.input.model.ControllerInterface
import org.dolphinemu.dolphinemu.features.input.model.MappingCommon
import org.dolphinemu.dolphinemu.features.input.model.controlleremu.EmulatedController
import org.dolphinemu.dolphinemu.features.settings.model.BooleanSetting
import org.dolphinemu.dolphinemu.features.settings.model.Settings
import java.io.File

/**
 * RG477M build: GameCube port 1 starts on the handheld's own controls (the built-in gamepad,
 * else the first connected one) with the "Android Gamepad" profile (Xbox-style layout: A/B/X/Y,
 * R1 = Z, triggers = L/R, right stick = C-stick), instead of the touchscreen. Dolphin's own
 * Default maps no buttons on Android. The profile is loaded once, or again while port 1 still
 * has no buttons; at every start a port 1 whose device is gone or isn't a gamepad (a controller
 * number that changed, the touchscreen, the sensors) is put back on the gamepad, keeping its
 * mappings. With CombineControllers on, a Bluetooth controller then drives the same port.
 */
object Rg477mControllers {
    private const val KEY_GC = "rg477m_automap_gc1_v3"
    private const val PROFILE = "Android Gamepad.ini"

    fun apply(context: Context, settings: Settings) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val pad = EmulatedController.getGcPad(0)
        val device = gamepadDeviceString() ?: return
        val current = pad.getDefaultDevice()
        val connected = ControllerInterface.getAllDeviceStrings().toSet()
        var rebound = true
        if (!prefs.getBoolean(KEY_GC, false) || !hasButtons(pad)) {
            val profile = File(pad.getSysProfileDirectoryPath(), PROFILE)
            if (profile.exists()) pad.loadProfile(profile.path) else pad.loadDefaultSettings()
            pad.setDefaultDevice(device)
        } else if (current != device && !(current in connected && isGamepadString(current))) {
            pad.setDefaultDevice(device)
        } else {
            rebound = false
        }
        if (rebound) {
            MappingCommon.save()
            // the touch controls are hidden once real controls are mapped (Overlay controls brings them back)
            BooleanSetting.MAIN_SHOW_INPUT_OVERLAY.setBoolean(settings, false)
            Log.info("[Rg477mControllers] GameCube port 1 mapped to ${pad.getDefaultDevice()}")
        }
        prefs.edit { putBoolean(KEY_GC, true) }
    }

    /** Whether the A button has a mapping (an empty port 1 maps nothing). */
    private fun hasButtons(pad: EmulatedController): Boolean =
        pad.getGroupCount() > 0 && pad.getGroup(0).getControlCount() > 0 &&
            pad.getGroup(0).getControl(0).getControlReference().getExpression().isNotBlank()

    /** The connected gamepads with buttons: the built-in one first. */
    private fun gamepads(): List<InputDevice> {
        val pads = InputDevice.getDeviceIds().toList().mapNotNull { id -> InputDevice.getDevice(id) }
            .filter { d ->
                !d.isVirtual &&
                    ((d.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                        (d.sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK)
            }
            // a handheld can split its controls over two devices of one name: the one with the
            // face buttons first
            .sortedByDescending { it.hasKeys(KeyEvent.KEYCODE_BUTTON_A)[0] }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            pads.sortedBy { it.isExternal }
        } else {
            pads
        }
    }

    /** Dolphin's device string for the built-in gamepad, else the first connected one. */
    private fun gamepadDeviceString(): String? {
        val strings = ControllerInterface.getAllDeviceStrings()
        for (pad in gamepads()) {
            val named = strings.filter { s -> s.startsWith("Android/") && s.endsWith("/" + pad.name) }
            // Dolphin numbers an Android controller by its controller number
            val match = named.firstOrNull { it == "Android/${pad.controllerNumber}/${pad.name}" }
                ?: named.firstOrNull()
            if (match != null) return match
        }
        return null
    }

    private fun isGamepadString(device: String): Boolean =
        gamepads().any { device.startsWith("Android/") && device.endsWith("/" + it.name) }
}
