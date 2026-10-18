package com.flipphoneguy.applock;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.HashSet;

/** Self-update from GitHub releases or a picked APK file. Every APK is checked before installing. */
final class Updater {

    // one GitHub update at a time, a second download would overwrite the APK mid-install
    private static volatile boolean downloading;

    private Updater() {}

    // ---- GitHub ----

    /**
     * Checks the latest release. quiet is for the launch check: it stays silent unless an
     * update is found. status, if not null, shows progress in the settings screen.
     */
    static void checkOnline(final Activity a, final boolean quiet, final TextView status) {
        setStatus(status, a.getString(R.string.update_checking));
        new Thread(new Runnable() {
            @Override public void run() {
                String version = null, apkUrl = null, error = null;
                try {
                    JSONObject release = new JSONObject(fetch(
                            "https://api.github.com/repos/" + BuildConfig.REPO + "/releases/latest"));
                    version = release.optString("tag_name", "").replaceFirst("^[vV]", "");
                    JSONArray assets = release.optJSONArray("assets");
                    for (int i = 0; assets != null && i < assets.length(); i++) {
                        String url = assets.getJSONObject(i).optString("browser_download_url", "");
                        if (url.endsWith(".apk")) { apkUrl = url; break; }
                    }
                } catch (Exception e) {
                    error = message(e);
                }
                final String v = version, url = apkUrl, err = error;
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (a.isDestroyed()) return;
                        if (err != null) {
                            if (!quiet) setStatus(status, a.getString(R.string.update_check_failed, err));
                        } else if (url == null || v.isEmpty()) {
                            if (!quiet) setStatus(status, a.getString(R.string.update_no_release));
                        } else if (compareVersions(v, BuildConfig.VERSION_NAME) <= 0) {
                            if (!quiet) setStatus(status, a.getString(R.string.update_up_to_date, BuildConfig.VERSION_NAME));
                        } else {
                            setStatus(status, null);
                            offer(a, v, url, status);
                        }
                    }
                });
            }
        }).start();
    }

    private static void offer(final Activity a, final String version, final String url, final TextView status) {
        new AlertDialog.Builder(a)
                .setTitle(R.string.update_available_title)
                .setMessage(a.getString(R.string.update_available, version, BuildConfig.VERSION_NAME))
                .setPositiveButton(R.string.btn_update_now, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { downloadAndInstall(a, version, url, status); }
                })
                .setNegativeButton(R.string.btn_later, null)
                .show();
    }

    private static void downloadAndInstall(final Activity a, String version, final String url, final TextView status) {
        if (downloading) {
            Toast.makeText(a, R.string.update_busy, Toast.LENGTH_SHORT).show();
            return;
        }
        downloading = true;
        String msg = a.getString(R.string.update_downloading, version);
        setStatus(status, msg);
        if (status == null) Toast.makeText(a, msg, Toast.LENGTH_SHORT).show();
        final Context app = a.getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    File apk = download(app, url);
                    verify(app, apk);
                    a.runOnUiThread(new Runnable() {
                        @Override public void run() {
                            Toast.makeText(app, R.string.update_installing, Toast.LENGTH_SHORT).show();
                        }
                    });
                    UpdateReceiver.install(app, apk);
                } catch (final Exception e) {
                    a.runOnUiThread(new Runnable() {
                        @Override public void run() {
                            String err = app.getString(R.string.update_failed, message(e));
                            setStatus(status, err);
                            Toast.makeText(app, err, Toast.LENGTH_LONG).show();
                        }
                    });
                } finally {
                    downloading = false;
                }
            }
        }).start();
    }

    // ---- APK file ----

    /** Copies a picked APK, checks it and asks before installing. */
    static void installFromFile(final Activity a, Uri uri) {
        final Context app = a.getApplicationContext();
        // its own file, so it can't clash with a GitHub download running at the same time
        final File apk = new File(app.getCacheDir(), "picked.apk");
        PackageInfo info;
        try {
            try (InputStream in = a.getContentResolver().openInputStream(uri);
                 OutputStream out = new FileOutputStream(apk)) {
                copy(in, out);
            }
            info = verify(app, apk);
        } catch (Exception e) {
            apk.delete();
            Toast.makeText(a, message(e), Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(a)
                .setMessage(a.getString(R.string.update_confirm, info.versionName))
                .setPositiveButton(R.string.btn_ok, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { install(a, apk); }
                })
                .setNegativeButton(R.string.btn_cancel, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { apk.delete(); }
                })
                .show();
    }

    private static void install(final Activity a, final File apk) {
        final Context app = a.getApplicationContext();
        Toast.makeText(app, R.string.update_installing, Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    UpdateReceiver.install(app, apk);
                } catch (final Exception e) {
                    a.runOnUiThread(new Runnable() {
                        @Override public void run() {
                            Toast.makeText(app, app.getString(R.string.update_failed, message(e)),
                                    Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }).start();
    }

    // ---- checks ----

    /**
     * Throws with a readable message unless the APK is AppLock, signed with the same key and
     * newer. Runs before the install block is lifted, so a fake APK never opens an install window.
     */
    static PackageInfo verify(Context c, File apk) throws Exception {
        PackageManager pm = c.getPackageManager();
        PackageInfo info = pm.getPackageArchiveInfo(apk.getPath(), PackageManager.GET_SIGNING_CERTIFICATES);
        if (info == null || !c.getPackageName().equals(info.packageName)) {
            apk.delete();
            throw new Exception(c.getString(R.string.update_picked_wrong));
        }
        if (!sameSigner(c, info)) {
            apk.delete();
            throw new Exception(c.getString(R.string.update_wrong_key));
        }
        if (info.getLongVersionCode() <= BuildConfig.VERSION_CODE) {
            apk.delete();
            throw new Exception(c.getString(R.string.update_older, info.versionName, BuildConfig.VERSION_NAME));
        }
        return info;
    }

    private static boolean sameSigner(Context c, PackageInfo apk) {
        try {
            PackageInfo self = c.getPackageManager().getPackageInfo(c.getPackageName(),
                    PackageManager.GET_SIGNING_CERTIFICATES);
            if (apk.signingInfo == null || self.signingInfo == null) return false;
            Signature[] theirs = apk.signingInfo.getApkContentsSigners();
            Signature[] ours = self.signingInfo.getApkContentsSigners();
            return theirs != null && new HashSet<>(Arrays.asList(theirs)).equals(new HashSet<>(Arrays.asList(ours)));
        } catch (Exception e) {
            return false;
        }
    }

    static int compareVersions(String a, String b) {
        String[] pa = a.split("\\."), pb = b.split("\\.");
        for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
            int va = i < pa.length ? parseInt(pa[i]) : 0;
            int vb = i < pb.length ? parseInt(pb[i]) : 0;
            if (va != vb) return Integer.compare(va, vb);
        }
        return 0;
    }

    // ---- helpers ----

    private static String message(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.toString();
    }

    private static void setStatus(TextView status, String text) {
        if (status == null) return;
        status.setText(text);
        status.setVisibility(text == null ? android.view.View.GONE : android.view.View.VISIBLE);
    }

    private static String fetch(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestProperty("User-Agent", BuildConfig.APP_NAME + "/" + BuildConfig.VERSION_NAME);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("GitHub HTTP " + code);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) { copy(in, out); }
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    private static File download(Context c, String url) throws IOException {
        File out = new File(c.getCacheDir(), "update.apk");
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestProperty("User-Agent", BuildConfig.APP_NAME + "/" + BuildConfig.VERSION_NAME);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);
        try (InputStream in = conn.getInputStream(); OutputStream o = new FileOutputStream(out)) {
            copy(in, o);
        } finally {
            conn.disconnect();
        }
        return out;
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return 0; }
    }
}
