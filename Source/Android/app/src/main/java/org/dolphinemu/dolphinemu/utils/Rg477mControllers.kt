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
import java.io.File

/**
 * RG477M build: GameCube port 1 starts on the handheld's own controls (the built-in gamepad,
 * else the first connected one) with the "Android Gamepad" profile (Xbox-style layout: A/B/X/Y,
 * R1 = Z, triggers = L/R, right stick = C-stick), instead of the touchscreen. Dolphin's own
 * Default maps no buttons on Android. Done once, or again while port 1 still has no buttons;
 * a device or mapping chosen later stays. With CombineControllers on, a Bluetooth controller
 * then drives the same port.
 */
object Rg477mControllers {
    private const val KEY_GC = "rg477m_automap_gc1_v2"
    private const val PROFILE = "Android Gamepad.ini"

    fun apply(context: Context, settings: Settings) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val pad = EmulatedController.getGcPad(0)
        if (prefs.getBoolean(KEY_GC, false) && hasButtons(pad)) return
        val device = gamepadDeviceString() ?: return
        val current = pad.getDefaultDevice()
        val onTouch = current.isEmpty() || current.endsWith("/Touchscreen")
        if (onTouch || !hasButtons(pad)) {
            val profile = File(pad.getSysProfileDirectoryPath(), PROFILE)
            if (profile.exists()) pad.loadProfile(profile.path) else pad.loadDefaultSettings()
            pad.setDefaultDevice(if (onTouch) device else current)
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
