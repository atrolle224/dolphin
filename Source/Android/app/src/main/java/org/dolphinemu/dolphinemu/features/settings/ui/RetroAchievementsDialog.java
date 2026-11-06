package org.dolphinemu.dolphinemu.features.settings.ui;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import org.dolphinemu.dolphinemu.NativeLibrary;
import org.dolphinemu.dolphinemu.R;

public final class RetroAchievementsDialog
{
  public static void show(Context context)
  {
    String user = NativeLibrary.RetroAchievementsGetUser();
    if (!user.isEmpty())
    {
      new AlertDialog.Builder(context)
              .setTitle(R.string.ra_account)
              .setMessage(context.getString(R.string.ra_signed_in_as, user))
              .setPositiveButton(R.string.ra_sign_out, (dialog, which) ->
              {
                NativeLibrary.RetroAchievementsLogout();
                Toast.makeText(context, R.string.ra_signed_out, Toast.LENGTH_SHORT).show();
              })
              .setNegativeButton(R.string.cancel, null)
              .show();
      return;
    }

    int pad = (int) (20 * context.getResources().getDisplayMetrics().density);
    LinearLayout layout = new LinearLayout(context);
    layout.setOrientation(LinearLayout.VERTICAL);
    layout.setPadding(pad, pad / 2, pad, 0);
    EditText username = new EditText(context);
    username.setHint(R.string.ra_username);
    username.setSingleLine();
    EditText password = new EditText(context);
    password.setHint(R.string.ra_password);
    password.setSingleLine();
    password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
    layout.addView(username);
    layout.addView(password);

    new AlertDialog.Builder(context)
            .setTitle(R.string.ra_account)
            .setView(layout)
            .setPositiveButton(R.string.ra_sign_in, (dialog, which) ->
            {
              String name = username.getText().toString().trim();
              String pass = password.getText().toString();
              password.setText("");
              Handler main = new Handler(Looper.getMainLooper());
              new Thread(() ->
              {
                String error = NativeLibrary.RetroAchievementsLogin(name, pass);
                main.post(() -> Toast.makeText(context, error.isEmpty() ?
                        context.getString(R.string.ra_signed_in_as, name) : error,
                        Toast.LENGTH_LONG).show());
              }).start();
            })
            .setNegativeButton(R.string.cancel, null)
            .show();
  }
}
