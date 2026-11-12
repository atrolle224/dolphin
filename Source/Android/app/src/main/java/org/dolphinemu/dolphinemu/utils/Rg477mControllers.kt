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
 * RG477M build: GameCube port 1 and Wii Remote 1 start on the handheld's own controls (the
 * built-in gamepad, else the first connected one) with the PrimeHack 477M profile, instead of
 * the touchscreen. Dolphin's own Default maps no buttons on Android. Done once per controller,
 * or again while it still has no buttons;
 * a device or mapping chosen later stays. With CombineControllers on, a Bluetooth controller
 * then drives the same port.
 */
object Rg477mControllers {
    private const val KEY_GC = "rg477m_primehack_gc1_v1"
    private const val KEY_WII = "rg477m_primehack_wii1_v1"

    // PrimeHack 477M: the right stick aims (Camera Control), R2 fires, the d-pad picks beams,
    // L1 + d-pad visors (Data/Sys/Profiles/*/PrimeHack 477M.ini)
    private const val PROFILE = "PrimeHack 477M.ini"

    fun apply(context: Context, settings: Settings) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val device = gamepadDeviceString() ?: return
        var changed = false
        for ((key, pad) in listOf(
            KEY_GC to EmulatedController.getGcPad(0),
            KEY_WII to EmulatedController.getWiimote(0)
        )) {
            if (prefs.getBoolean(key, false) && hasButtons(pad)) continue
            val current = pad.getDefaultDevice()
            val onTouch = current.isEmpty() || current.endsWith("/Touchscreen")
            if (onTouch || !hasButtons(pad)) {
                val profile = File(pad.getSysProfileDirectoryPath(), PROFILE)
                if (profile.exists()) pad.loadProfile(profile.path) else pad.loadDefaultSettings()
                pad.setDefaultDevice(if (onTouch) device else current)
                changed = true
                Log.info("[Rg477mControllers] $key mapped to ${pad.getDefaultDevice()}")
            }
            prefs.edit { putBoolean(key, true) }
        }
        if (changed) {
            MappingCommon.save()
            // the touch controls are hidden once real controls are mapped (Overlay controls brings them back)
            BooleanSetting.MAIN_SHOW_INPUT_OVERLAY.setBoolean(settings, false)
        }
    }

    /** Whether the A button has a mapping (an empty port 1 maps nothing). */
    private fun hasButtons(pad: EmulatedController): Boolean =
        pad.getGroupCount() > 0 && pad.getGroup(0).getControlCount() > 0 &&
            pad.getGroup(0).getControl(0).getControlReference().getExpression().isNotBlank()

    /** Dolphin's device string for the built-in gamepad, else the first connected one. */
    private fun gamepadDeviceString(): String? {
        val pads = InputDevice.getDeviceIds().toList().mapNotNull { id -> InputDevice.getDevice(id) }
            .filter { d ->
                !d.isVirtual &&
                    ((d.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                        (d.sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK)
            }
        val ordered = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            pads.sortedBy { it.isExternal }
        } else {
            pads
        }
        val strings = ControllerInterface.getAllDeviceStrings()
        for (pad in ordered) {
            val match = strings.firstOrNull { s -> s.startsWith("Android/") && s.endsWith("/" + pad.name) }
            if (match != null) return match
        }
        return null
    }
}
