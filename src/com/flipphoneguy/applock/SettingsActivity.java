package com.flipphoneguy.applock;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public class SettingsActivity extends Activity {

    private static final int REQUEST_PICK_APK = 1;
    private static final String PREFS = "settings";
    private static final String KEY_CHECK_ON_LAUNCH = "check_on_launch";

    private Button changePwBtn, removeBtn;
    private View[] focusOrder;

    static boolean checkOnLaunch(Context c) {
        return c.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_CHECK_ON_LAUNCH, true);
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(ThemeHelper.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        ((TextView) findViewById(R.id.version)).setText("v" + BuildConfig.VERSION_NAME);
        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });

        final int[] themeLabels = { R.string.theme_system, R.string.theme_light, R.string.theme_dark };
        Button theme = (Button) findViewById(R.id.btn_theme);
        theme.setText(themeLabels[ThemeHelper.getMode(this)]);
        theme.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                ThemeHelper.setMode(SettingsActivity.this, (ThemeHelper.getMode(SettingsActivity.this) + 1) % 3);
                recreate();
            }
        });

        Switch launchCheck = (Switch) findViewById(R.id.switch_check_on_launch);
        launchCheck.setChecked(checkOnLaunch(this));
        launchCheck.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean on) {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_CHECK_ON_LAUNCH, on).apply();
            }
        });

        final TextView status = (TextView) findViewById(R.id.update_status);
        findViewById(R.id.btn_check_update).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { Updater.checkOnline(SettingsActivity.this, false, status); }
        });
        findViewById(R.id.btn_update_file).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickApk(); }
        });

        findViewById(R.id.btn_github_profile).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openUrl(BuildConfig.GITHUB_PROFILE); }
        });
        findViewById(R.id.btn_app_repo).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openUrl("https://github.com/" + BuildConfig.REPO); }
        });

        changePwBtn = (Button) findViewById(R.id.btn_change_password);
        removeBtn = (Button) findViewById(R.id.btn_remove);
        changePwBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                PasswordDialogs.setNew(SettingsActivity.this, R.string.pw_title_change, new Runnable() {
                    @Override public void run() { refreshLockState(); }
                });
            }
        });
        removeBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { confirmRemove(); }
        });

        int[] order = { R.id.btn_back, R.id.btn_theme, R.id.switch_check_on_launch, R.id.btn_check_update,
                R.id.btn_update_file, R.id.btn_github_profile, R.id.btn_app_repo,
                R.id.btn_change_password, R.id.btn_remove };
        focusOrder = new View[order.length];
        for (int i = 0; i < order.length; i++) focusOrder[i] = findViewById(order[i]);
    }

    // fixed d-pad order that skips greyed out buttons, Android's own search can get stuck on them
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        boolean up = k == KeyEvent.KEYCODE_DPAD_UP, down = k == KeyEvent.KEYCODE_DPAD_DOWN;
        if (!up && !down && k != KeyEvent.KEYCODE_DPAD_LEFT && k != KeyEvent.KEYCODE_DPAD_RIGHT) {
            return super.dispatchKeyEvent(e);
        }
        if (e.getAction() != KeyEvent.ACTION_DOWN || (!up && !down)) return true;
        View f = getCurrentFocus();
        int at = -1;
        for (int i = 0; i < focusOrder.length; i++) if (focusOrder[i] == f) at = i;
        if (at < 0) { focusOrder[0].requestFocus(); return true; }
        for (int i = at + (down ? 1 : -1); i >= 0 && i < focusOrder.length; i += down ? 1 : -1) {
            if (focusOrder[i].isEnabled() && focusOrder[i].requestFocus()) {
                // show the card above the first button and the text below the last one
                ScrollView scroll = (ScrollView) findViewById(R.id.scroll);
                if (i <= 1) scroll.scrollTo(0, 0);
                else if (i == focusOrder.length - 1) scroll.fullScroll(View.FOCUS_DOWN);
                break;
            }
        }
        return true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshLockState();
    }

    private void refreshLockState() {
        changePwBtn.setEnabled(PasswordDialogs.unlocked);
        removeBtn.setEnabled(PasswordDialogs.unlocked && Policy.isOwner(this));
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ---- update from file ----

    private void pickApk() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/vnd.android.package-archive", "application/octet-stream" });
        try {
            startActivityForResult(i, REQUEST_PICK_APK);
        } catch (Exception e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PICK_APK || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri != null) Updater.installFromFile(this, uri);
    }

    // ---- removal ----

    private void confirmRemove() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.remove_confirm_title)
                .setMessage(R.string.remove_body)
                .setPositiveButton(R.string.btn_remove, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { remove(false); }
                })
                .setNegativeButton(R.string.btn_cancel, null)
                .show();
    }

    private void remove(boolean force) {
        String error;
        try {
            error = Policy.release(this, force);
        } catch (Policy.UnhideFailed e) {
            // the apps that did unhide need to show up as visible again
            MainActivity.sReloadApps = true;
            new AlertDialog.Builder(this)
                    .setTitle(R.string.remove_unhide_failed_title)
                    .setMessage(getString(R.string.remove_unhide_failed,
                            TextUtils.join("\n", e.packages)))
                    .setPositiveButton(R.string.btn_remove_anyway, new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) { remove(true); }
                    })
                    .setNegativeButton(R.string.btn_cancel, null)
                    .show();
            return;
        }
        if (error != null) {
            new AlertDialog.Builder(this).setMessage(error).setPositiveButton(R.string.btn_ok, null).show();
            return;
        }
        PasswordDialogs.unlocked = false;
        startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + getPackageName())));
        finishAffinity();
    }
}
