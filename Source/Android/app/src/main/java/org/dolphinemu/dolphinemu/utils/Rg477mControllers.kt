// SPDX-License-Identifier: GPL-2.0-or-later

package org.dolphinemu.dolphinemu.utils

import android.content.Context
import android.os.Build
import android.view.InputDevice
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import org.dolphinemu.dolphinemu.features.input.model.ControllerInterface
import org.dolphinemu.dolphinemu.features.input.model.MappingCommon
import org.dolphinemu.dolphinemu.features.input.model.controlleremu.EmulatedController
import org.dolphinemu.dolphinemu.features.settings.model.BooleanSetting
import org.dolphinemu.dolphinemu.features.settings.model.Settings

/**
 * RG477M build: GameCube port 1 and Wii Remote 1 start on the handheld's own controls (the
 * built-in gamepad, else the first connected one) with Dolphin's default mapping, instead of
 * the touchscreen. Done once per controller; a device picked later in the settings stays.
 * With CombineControllers on, a Bluetooth controller then drives the same port.
 */
object Rg477mControllers {
    private const val KEY_GC = "rg477m_automap_gc1"
    private const val KEY_WII = "rg477m_automap_wii1"

    fun apply(context: Context, settings: Settings) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (prefs.getBoolean(KEY_GC, false) && prefs.getBoolean(KEY_WII, false)) return
        val device = gamepadDeviceString() ?: return
        var changed = false
        if (!prefs.getBoolean(KEY_GC, false)) {
            changed = map(EmulatedController.getGcPad(0), device) or changed
            prefs.edit { putBoolean(KEY_GC, true) }
        }
        if (!prefs.getBoolean(KEY_WII, false)) {
            changed = map(EmulatedController.getWiimote(0), device) or changed
            prefs.edit { putBoolean(KEY_WII, true) }
        }
        if (changed) {
            MappingCommon.save()
            // the touch controls are hidden once real controls are mapped (Overlay controls brings them back)
            BooleanSetting.MAIN_SHOW_INPUT_OVERLAY.setBoolean(settings, false)
            Log.info("[Rg477mControllers] port 1 and Wii Remote 1 mapped to $device")
        }
    }

    private fun map(controller: EmulatedController, device: String): Boolean {
        val current = controller.getDefaultDevice()
        if (current.isNotEmpty() && !current.endsWith("/Touchscreen")) return false
        controller.loadDefaultSettings()
        controller.setDefaultDevice(device)
        return true
    }

    /** Dolphin's device string for the built-in gamepad, else the first connected one. */
    private fun gamepadDeviceString(): String? {
        val pads = InputDevice.getDeviceIds().mapNotNull { InputDevice.getDevice(it) }.filter {
            !it.isVirtual &&
                (it.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
                    it.sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK)
        }
        val ordered = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            pads.sortedBy { it.isExternal }
        } else {
            pads
        }
        val strings = ControllerInterface.getAllDeviceStrings()
        for (pad in ordered) {
            strings.firstOrNull { it.startsWith("Android/") && it.endsWith("/" + pad.name) }
                ?.let { return it }
        }
        return null
    }
}
