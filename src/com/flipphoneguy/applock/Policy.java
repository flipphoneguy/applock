package com.flipphoneguy.applock;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.UserManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** All DevicePolicyManager calls live here. */
final class Policy {

    private static final String PREFS = "policy";
    private static final String KEY_HIDDEN = "hidden";
    private static final String KEY_REBLOCK = "reblock_installs";

    // Includes "disabled until used" apps so they aren't mistaken for hidden ones.
    static final int MATCH_ALL = PackageManager.MATCH_UNINSTALLED_PACKAGES
            | PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS;

    private Policy() {}

    static DevicePolicyManager dpm(Context c) {
        return (DevicePolicyManager) c.getSystemService(Context.DEVICE_POLICY_SERVICE);
    }

    static ComponentName admin(Context c) {
        return new ComponentName(c, AdminReceiver.class);
    }

    static boolean isOwner(Context c) {
        return dpm(c).isDeviceOwnerApp(c.getPackageName());
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // Policies only cover the main user, so other users and profiles would bypass everything.
    private static final String[] BASELINE = {
            UserManager.DISALLOW_FACTORY_RESET,
            UserManager.DISALLOW_ADD_USER,
            UserManager.DISALLOW_USER_SWITCH,
            UserManager.DISALLOW_ADD_MANAGED_PROFILE,
    };

    /** Always-on protections. Safe to call repeatedly. */
    static void applyBaseline(Context c) {
        if (!isOwner(c)) return;
        DevicePolicyManager dpm = dpm(c);
        ComponentName admin = admin(c);
        for (String r : BASELINE) dpm.addUserRestriction(admin, r);
        if (Build.VERSION.SDK_INT >= 30) {
            // greys out Force stop and Clear storage for AppLock in Settings
            dpm.setUserControlDisabledPackages(admin,
                    Collections.singletonList(c.getPackageName()));
        }
    }

    // ---- installs ----

    private static List<String> installRestrictions() {
        List<String> r = new ArrayList<>();
        r.add(UserManager.DISALLOW_INSTALL_APPS);
        r.add(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES);
        if (Build.VERSION.SDK_INT >= 29) r.add(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY);
        return r;
    }

    static boolean installsAllowed(Context c) {
        if (!isOwner(c)) return true;
        return !dpm(c).getUserRestrictions(admin(c)).getBoolean(UserManager.DISALLOW_INSTALL_APPS);
    }

    static void setInstallsAllowed(Context c, boolean allowed) {
        DevicePolicyManager dpm = dpm(c);
        ComponentName admin = admin(c);
        for (String r : installRestrictions()) {
            if (allowed) dpm.clearUserRestriction(admin, r);
            else dpm.addUserRestriction(admin, r);
        }
    }

    /** Lifts the install block for a self-update and remembers to put it back. */
    static void liftForUpdate(Context c) {
        if (!isOwner(c) || installsAllowed(c)) return;
        prefs(c).edit().putBoolean(KEY_REBLOCK, true).commit();
        setInstallsAllowed(c, true);
    }

    static void reblockIfPending(Context c) {
        SharedPreferences p = prefs(c);
        if (!p.getBoolean(KEY_REBLOCK, false)) return;
        if (isOwner(c)) setInstallsAllowed(c, false);
        p.edit().remove(KEY_REBLOCK).commit();
    }

    // ---- hide / suspend ----

    static boolean setHidden(Context c, String pkg, boolean hidden) {
        DevicePolicyManager dpm = dpm(c);
        ComponentName admin = admin(c);
        try {
            dpm.setApplicationHidden(admin, pkg, hidden);
            if (dpm.isApplicationHidden(admin, pkg) != hidden) return false;
        } catch (Exception e) {
            return false;
        }
        Set<String> set = new HashSet<>(rememberedHidden(c));
        if (hidden) set.add(pkg); else set.remove(pkg);
        prefs(c).edit().putStringSet(KEY_HIDDEN, set).commit();
        return true;
    }

    static boolean setSuspended(Context c, String pkg, boolean suspended) {
        try {
            String[] failed = dpm(c).setPackagesSuspended(admin(c), new String[]{pkg}, suspended);
            return failed.length == 0 && dpm(c).isPackageSuspended(admin(c), pkg) == suspended;
        } catch (Exception e) {
            return false;
        }
    }

    static Set<String> rememberedHidden(Context c) {
        return prefs(c).getStringSet(KEY_HIDDEN, Collections.<String>emptySet());
    }

    /**
     * Hidden apps don't show up in a normal package query, so anything installed that only
     * appears with MATCH_UNINSTALLED_PACKAGES is a candidate. The stored list is merged in too.
     */
    static Set<String> findHidden(Context c, List<ApplicationInfo> installed) {
        PackageManager pm = c.getPackageManager();
        Set<String> visible = new HashSet<>();
        for (ApplicationInfo ai : pm.getInstalledApplications(
                PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS)) {
            visible.add(ai.packageName);
        }
        Set<String> installedPkgs = new HashSet<>();
        for (ApplicationInfo ai : installed) installedPkgs.add(ai.packageName);

        Set<String> candidates = new HashSet<>(rememberedHidden(c));
        for (String pkg : installedPkgs) {
            if (!visible.contains(pkg)) candidates.add(pkg);
        }

        boolean owner = isOwner(c);
        Set<String> hidden = new HashSet<>();
        for (String pkg : candidates) {
            if (!installedPkgs.contains(pkg)) continue;
            if (owner) {
                try {
                    if (!dpm(c).isApplicationHidden(admin(c), pkg)) continue;
                } catch (Exception e) {
                    continue;
                }
            } else if (visible.contains(pkg)) {
                continue;
            }
            hidden.add(pkg);
        }
        return hidden;
    }

    static List<ApplicationInfo> installedApps(Context c) {
        List<ApplicationInfo> out = new ArrayList<>();
        for (ApplicationInfo ai : c.getPackageManager().getInstalledApplications(MATCH_ALL)) {
            if ((ai.flags & ApplicationInfo.FLAG_INSTALLED) == 0) continue;
            if (ai.packageName.equals(c.getPackageName())) continue;
            out.add(ai);
        }
        return out;
    }

    // ---- removal ----

    static class UnhideFailed extends Exception {
        final List<String> packages;
        UnhideFailed(List<String> packages) { this.packages = packages; }
    }

    /**
     * Undoes everything and gives up device owner. Returns null on success, otherwise a
     * message. Unless force is set, throws before clearing device owner if any app can't
     * be unhidden, since nothing could unhide it afterwards.
     */
    static String release(Context c, boolean force) throws UnhideFailed {
        DevicePolicyManager dpm = dpm(c);
        ComponentName admin = admin(c);
        List<ApplicationInfo> installed = installedApps(c);

        List<String> stuck = new ArrayList<>();
        for (String pkg : findHidden(c, installed)) {
            if (!setHidden(c, pkg, false)) stuck.add(pkg);
        }
        if (!stuck.isEmpty() && !force) throw new UnhideFailed(stuck);

        List<String> suspended = new ArrayList<>();
        for (ApplicationInfo ai : installed) {
            if ((ai.flags & ApplicationInfo.FLAG_SUSPENDED) != 0) suspended.add(ai.packageName);
        }
        if (!suspended.isEmpty()) {
            try {
                dpm.setPackagesSuspended(admin, suspended.toArray(new String[0]), false);
            } catch (Exception ignored) {}
        }

        setInstallsAllowed(c, true);
        for (String r : BASELINE) dpm.clearUserRestriction(admin, r);
        if (Build.VERSION.SDK_INT >= 30) {
            dpm.setUserControlDisabledPackages(admin, Collections.<String>emptyList());
        }

        try {
            dpm.clearDeviceOwnerApp(c.getPackageName());
        } catch (Exception ignored) {}
        if (isOwner(c)) return c.getString(R.string.remove_still_owner);

        prefs(c).edit().clear().commit();
        return null;
    }
}
