package com.flipphoneguy.applock;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Salted PBKDF2 hash in private prefs. The plain password is never stored. */
final class Password {

    private static final String PREFS = "password";
    private static final int ITERATIONS = 20000;

    private Password() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean isSet(Context c) {
        return prefs(c).contains("hash");
    }

    static void set(Context c, String pw) {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        prefs(c).edit()
                .putString("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
                .putString("hash", Base64.encodeToString(hash(pw, salt), Base64.NO_WRAP))
                .commit();
    }

    static boolean check(Context c, String pw) {
        SharedPreferences p = prefs(c);
        String salt = p.getString("salt", null);
        String stored = p.getString("hash", null);
        if (salt == null || stored == null) return false;
        byte[] actual = hash(pw, Base64.decode(salt, Base64.NO_WRAP));
        return MessageDigest.isEqual(actual, Base64.decode(stored, Base64.NO_WRAP));
    }

    /** In a RESET_PASSWORD build, clears the password once per version code. Returns true if it did. */
    static boolean applyResetBuild(Context c) {
        if (!BuildConfig.RESET_PASSWORD) return false;
        SharedPreferences p = prefs(c);
        if (p.getInt("reset_vc", 0) == BuildConfig.VERSION_CODE) return false;
        p.edit().remove("salt").remove("hash").putInt("reset_vc", BuildConfig.VERSION_CODE).commit();
        return true;
    }

    private static byte[] hash(String pw, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(pw.toCharArray(), salt, ITERATIONS, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
