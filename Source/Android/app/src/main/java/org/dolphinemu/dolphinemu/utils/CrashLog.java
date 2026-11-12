// SPDX-License-Identifier: GPL-2.0-or-later

package org.dolphinemu.dolphinemu.utils;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

/**
 * RG477M: keeps the last crash where a USB copy reaches it,
 * Android/data/org.dolphinemu.mmjr.rg477m/files/crash-log.txt: a Java exception as it happens,
 * a native crash from the crash log buffer at the next start.
 */
public final class CrashLog
{
  private CrashLog()
  {
  }

  public static void install(Context context)
  {
    final File dir = context.getExternalFilesDir(null);
    if (dir == null)
      return;
    saveCrashBuffer(new File(dir, "crash-log.txt"));

    final Thread.UncaughtExceptionHandler next = Thread.getDefaultUncaughtExceptionHandler();
    Thread.setDefaultUncaughtExceptionHandler((thread, error) ->
    {
      StringWriter text = new StringWriter();
      error.printStackTrace(new PrintWriter(text));
      write(new File(dir, "crash-log-java.txt"), ("Thread " + thread.getName() + "\n" + text)
              .getBytes(StandardCharsets.UTF_8));
      if (next != null)
        next.uncaughtException(thread, error);
    });
  }

  private static void saveCrashBuffer(File file)
  {
    try
    {
      Process logcat = new ProcessBuilder("logcat", "-d", "-b", "crash").redirectErrorStream(true)
              .start();
      byte[] out;
      try (InputStream in = logcat.getInputStream())
      {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) > 0)
          buffer.write(chunk, 0, n);
        out = buffer.toByteArray();
      }
      if (out.length > 64)
        write(file, out);
    }
    catch (Exception ignored)
    {
    }
  }

  private static void write(File file, byte[] data)
  {
    try (OutputStream out = new FileOutputStream(file))
    {
      out.write(data);
    }
    catch (Exception ignored)
    {
    }
  }
}
