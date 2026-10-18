package com.flipphoneguy.applock;

import android.content.pm.ApplicationInfo;

final class AppItem {
    final ApplicationInfo info;
    final String pkg;
    final String label;
    final String searchText;
    final boolean system;
    final boolean launchable;
    boolean hidden;
    boolean suspended;

    AppItem(ApplicationInfo info, String label, boolean launchable, boolean hidden) {
        this.info = info;
        this.pkg = info.packageName;
        this.label = label;
        this.searchText = (label + "\n" + pkg).toLowerCase();
        this.system = (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
        this.launchable = launchable;
        this.hidden = hidden;
        this.suspended = (info.flags & ApplicationInfo.FLAG_SUSPENDED) != 0;
    }
}
