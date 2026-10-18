package com.flipphoneguy.applock;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** Unlock and set-password dialogs, shared by the main and settings screens. */
final class PasswordDialogs {

    // In memory only, so the app locks again whenever its process dies.
    static boolean unlocked;

    // ignores the system "Show passwords" setting so typed characters never flash
    private static final PasswordTransformationMethod DOTS = new PasswordTransformationMethod() {
        @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
    };

    private PasswordDialogs() {}

    /** Asks for the password (or to set one if there is none) and runs onUnlocked on success. */
    static void unlock(final Activity a, final Runnable onUnlocked) {
        if (!Password.isSet(a)) {
            setNew(a, R.string.pw_title_set, onUnlocked);
            return;
        }
        final EditText pw = passwordField(a, R.string.pw_hint);
        final AlertDialog d = new AlertDialog.Builder(a)
                .setTitle(R.string.pw_title_unlock)
                .setView(dialogBody(a, pw, showToggle(a, pw)))
                .setPositiveButton(R.string.btn_ok, null)
                .setNegativeButton(R.string.btn_cancel, null)
                .create();
        final Runnable submit = new Runnable() {
            @Override public void run() {
                if (Password.check(a, pw.getText().toString())) {
                    unlocked = true;
                    onUnlocked.run();
                    d.dismiss();
                } else {
                    pw.setText("");
                    pw.setError(a.getString(R.string.pw_wrong));
                }
            }
        };
        pw.setImeOptions(EditorInfo.IME_ACTION_DONE);
        pw.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int action, KeyEvent e) {
                submit.run();
                return true;
            }
        });
        showKeepingOpen(d, submit);
    }

    static void setNew(final Activity a, int title, final Runnable onSaved) {
        final EditText pw = passwordField(a, R.string.pw_hint);
        final EditText confirm = passwordField(a, R.string.pw_hint_confirm);
        final AlertDialog d = new AlertDialog.Builder(a)
                .setTitle(title)
                .setView(dialogBody(a, pw, confirm, showToggle(a, pw, confirm)))
                .setPositiveButton(R.string.btn_ok, null)
                .setNegativeButton(R.string.btn_cancel, null)
                .create();
        showKeepingOpen(d, new Runnable() {
            @Override public void run() {
                String s = pw.getText().toString();
                if (s.length() < 4) { pw.setError(a.getString(R.string.pw_too_short)); return; }
                if (!s.equals(confirm.getText().toString())) {
                    confirm.setError(a.getString(R.string.pw_mismatch));
                    return;
                }
                Password.set(a, s);
                unlocked = true;
                onSaved.run();
                Toast.makeText(a, R.string.pw_saved, Toast.LENGTH_SHORT).show();
                d.dismiss();
            }
        });
    }

    // no setSingleLine here, it replaces the password dots with plain text
    private static EditText passwordField(Activity a, int hint) {
        EditText e = new EditText(a);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        e.setTransformationMethod(DOTS);
        e.setHint(hint);
        return e;
    }

    private static CheckBox showToggle(Activity a, final EditText... fields) {
        CheckBox cb = new CheckBox(a);
        cb.setText(R.string.pw_show);
        cb.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean show) {
                for (EditText f : fields) {
                    f.setTransformationMethod(show ? null : DOTS);
                    f.setSelection(f.getText().length());
                }
            }
        });
        return cb;
    }

    private static View dialogBody(Activity a, View... fields) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(20 * a.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        for (View f : fields) box.addView(f);
        return box;
    }

    /** Shows the dialog with an OK button that only closes when the action does. */
    private static void showKeepingOpen(final AlertDialog d, final Runnable onOk) {
        d.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override public void onShow(DialogInterface di) {
                d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { onOk.run(); }
                });
            }
        });
        d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        d.show();
    }
}
