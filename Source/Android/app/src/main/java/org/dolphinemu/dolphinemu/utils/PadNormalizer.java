// SPDX-License-Identifier: GPL-2.0-or-later

package org.dolphinemu.dolphinemu.utils;

import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

import org.dolphinemu.dolphinemu.NativeLibrary;

/**
 * RG477M: the built-in profile reads triggers as AXIS_LTRIGGER/RTRIGGER, the right stick as
 * AXIS_Z/RZ and the D-pad as the hat axes. Pads that report them otherwise (BRAKE/GAS, RX/RY,
 * D-pad or L2/R2 keys, as 8BitDo pads do in some modes) get those axes sent as well.
 */
public final class PadNormalizer
{
  private PadNormalizer()
  {
  }

  private static boolean has(InputDevice device, int axis)
  {
    return device.getMotionRange(axis) != null;
  }

  public static void onKey(InputDevice device, int keyCode, boolean down)
  {
    if (device == null)
      return;
    String dev = device.getDescriptor();
    float value = down ? 1.0f : 0.0f;
    switch (keyCode)
    {
      case KeyEvent.KEYCODE_DPAD_UP:
      case KeyEvent.KEYCODE_DPAD_DOWN:
        if (!has(device, MotionEvent.AXIS_HAT_Y))
          NativeLibrary.onGamePadMoveEvent(dev, MotionEvent.AXIS_HAT_Y,
                  keyCode == KeyEvent.KEYCODE_DPAD_UP ? -value : value);
        break;
      case KeyEvent.KEYCODE_DPAD_LEFT:
      case KeyEvent.KEYCODE_DPAD_RIGHT:
        if (!has(device, MotionEvent.AXIS_HAT_X))
          NativeLibrary.onGamePadMoveEvent(dev, MotionEvent.AXIS_HAT_X,
                  keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -value : value);
        break;
      case KeyEvent.KEYCODE_BUTTON_L2:
        if (!has(device, MotionEvent.AXIS_LTRIGGER) && !has(device, MotionEvent.AXIS_BRAKE))
          NativeLibrary.onGamePadMoveEvent(dev, MotionEvent.AXIS_LTRIGGER, value);
        break;
      case KeyEvent.KEYCODE_BUTTON_R2:
        if (!has(device, MotionEvent.AXIS_RTRIGGER) && !has(device, MotionEvent.AXIS_GAS))
          NativeLibrary.onGamePadMoveEvent(dev, MotionEvent.AXIS_RTRIGGER, value);
        break;
      default:
        break;
    }
  }

  /** Called with each axis' scaled value after it was sent as it is. */
  public static void onAxis(InputDevice device, int axis, float value)
  {
    String dev = device.getDescriptor();
    switch (axis)
    {
      case MotionEvent.AXIS_BRAKE:
        if (!has(device, MotionEvent.AXIS_LTRIGGER))
          NativeLibrary.onGamePadMoveEvent(dev, MotionEvent.AXIS_LTRIGGER, value);
        break;
      case MotionEvent.AXIS_GAS:
        if (!has(device, MotionEvent.AXIS_RTRIGGER))
          NativeLibrary.onGamePadMoveEvent(dev, MotionEvent.AXIS_RTRIGGER, value);
        break;
      case MotionEvent.AXIS_RX:
        if (!has(device, MotionEvent.AXIS_Z))
          NativeLibrary.onGamePadMoveEvent(dev, MotionEvent.AXIS_Z, value);
        break;
      case MotionEvent.AXIS_RY:
        if (!has(device, MotionEvent.AXIS_RZ))
          NativeLibrary.onGamePadMoveEvent(dev, MotionEvent.AXIS_RZ, value);
        break;
      default:
        break;
    }
  }
}
