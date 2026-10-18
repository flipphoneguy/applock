package com.flipphoneguy.applock;

import android.content.Context;
import android.content.res.Configuration;

public class ThemeHelper {

    public static final int MODE_SYSTEM = 0;
    public static final int MODE_LIGHT = 1;
    public static final int MODE_DARK = 2;

    private static final String PREFS = "theme_prefs";
    private static final String KEY_MODE = "theme_mode";

    public static int getMode(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_MODE, MODE_SYSTEM);
    }

    public static void setMode(Context context, int mode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_MODE, mode).apply();
    }

    // Layouts are made for phones at least this wide; narrower screens get the whole app scaled down to fit.
    private static final int MIN_WIDTH_DP = 360;

    public static Context wrap(Context context) {
        int mode = getMode(context);
        Configuration config = new Configuration(context.getResources().getConfiguration());
        boolean changed = false;

        if (mode != MODE_SYSTEM) {
            int nightFlag = (mode == MODE_DARK)
                ? Configuration.UI_MODE_NIGHT_YES
                : Configuration.UI_MODE_NIGHT_NO;
            config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | nightFlag;
            changed = true;
        }

        int sw = config.smallestScreenWidthDp;
        if (sw > 0 && sw < MIN_WIDTH_DP && config.densityDpi > 0) {
            float scale = (float) sw / MIN_WIDTH_DP;
            config.densityDpi = Math.round(config.densityDpi * scale);
            config.screenWidthDp = Math.round(config.screenWidthDp / scale);
            config.screenHeightDp = Math.round(config.screenHeightDp / scale);
            config.smallestScreenWidthDp = MIN_WIDTH_DP;
            changed = true;
        }

        return changed ? context.createConfigurationContext(config) : context;
    }
}
