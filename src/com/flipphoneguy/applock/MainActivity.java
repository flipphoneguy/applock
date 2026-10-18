package com.flipphoneguy.applock;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.ChangedPackages;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.View;
import android.widget.AbsListView;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity implements AppAdapter.Toggler {

    private static final int F_LAUNCHABLE = 0, F_USER = 1, F_SYSTEM = 2,
            F_HIDDEN = 3, F_SUSPENDED = 4, F_ALL = 5;

    // once per process, so only a fresh launch checks for updates
    private static boolean sLaunchChecked;
    // set when apps changed hidden state outside this screen, which getChangedPackages doesn't report
    static boolean sReloadApps;

    private PackageManager pm;
    private AppAdapter adapter;
    private ListView list;
    private boolean phone;
    private Button settingsBtn, lockBtn;
    private EditText searchBox;
    private Switch installSwitch;
    private View notOwnerCard;
    private TextView listStatus;
    private final Button[] filterBtns = new Button[6];

    private final List<AppItem> allApps = new ArrayList<>();
    private int filter = F_LAUNCHABLE;
    private String query = "";
    private boolean owner;
    private int pkgSeq = -1;
    private boolean loading;
    private int appliedTheme;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(ThemeHelper.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        pm = getPackageManager();
        appliedTheme = ThemeHelper.getMode(this);

        if (Password.applyResetBuild(this)) {
            PasswordDialogs.unlocked = false;
            Toast.makeText(this, R.string.pw_was_reset, Toast.LENGTH_LONG).show();
        }

        list = (ListView) findViewById(R.id.app_list);
        View controls = findViewById(R.id.controls);
        phone = controls == null;
        if (phone) {
            // phone layout: the controls scroll away with the list
            controls = getLayoutInflater().inflate(R.layout.controls, list, false);
            list.addHeaderView(controls, null, false);
            // resizing the list for the keyboard steals focus from the search box
            getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
                    | android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
        }

        final View topBtn = findViewById(R.id.btn_top);
        final ScrollBar scrollBar = (ScrollBar) findViewById(R.id.scroll_bar);
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView v, int state) {}
            @Override public void onScroll(AbsListView v, int first, int visible, int total) {
                if (topBtn != null) topBtn.setVisibility(first > 0 ? View.VISIBLE : View.GONE);
                scrollBar.update();
            }
        });
        if (topBtn != null) {
            topBtn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { list.setSelection(0); }
            });
        }

        settingsBtn = (Button) findViewById(R.id.btn_settings);
        lockBtn = (Button) findViewById(R.id.btn_lock);
        installSwitch = (Switch) controls.findViewById(R.id.switch_install);
        notOwnerCard = controls.findViewById(R.id.not_owner_card);

        lockBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (PasswordDialogs.unlocked) {
                    PasswordDialogs.unlocked = false;
                    refreshLockState();
                } else {
                    PasswordDialogs.unlock(MainActivity.this, new Runnable() {
                        @Override public void run() { refreshLockState(); }
                    });
                }
            }
        });
        settingsBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });

        listStatus = new TextView(this);
        int pad = Math.round(24 * getResources().getDisplayMetrics().density);
        listStatus.setPadding(pad, pad, pad, pad);
        listStatus.setGravity(android.view.Gravity.CENTER);
        listStatus.setTextColor(getColor(R.color.text_secondary));
        listStatus.setText(R.string.apps_loading);
        LinearLayout statusWrap = new LinearLayout(this);
        statusWrap.addView(listStatus, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        list.addHeaderView(statusWrap, null, false);
        scrollBar.attach(list, phone ? new View[]{ controls, statusWrap } : new View[]{ statusWrap });

        adapter = new AppAdapter(this, this);
        list.setAdapter(adapter);
        // lets the d-pad reach the switches inside each row
        list.setItemsCanFocus(true);

        searchBox = (EditText) controls.findViewById(R.id.search_box);
        searchBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                query = s.toString().trim().toLowerCase(Locale.ROOT);
                applyFilter();
            }
        });

        int[] ids = { R.id.filter_launchable, R.id.filter_user, R.id.filter_system,
                R.id.filter_hidden, R.id.filter_suspended, R.id.filter_all };
        for (int i = 0; i < ids.length; i++) {
            final int f = i;
            filterBtns[i] = (Button) controls.findViewById(ids[i]);
            filterBtns[i].setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { filter = f; styleFilters(); applyFilter(); }
            });
        }
        styleFilters();

        if (!sLaunchChecked) {
            sLaunchChecked = true;
            if (SettingsActivity.checkOnLaunch(this)) Updater.checkOnline(this, true, null);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (appliedTheme != ThemeHelper.getMode(this)) { recreate(); return; }
        owner = Policy.isOwner(this);
        if (owner) Policy.applyBaseline(this);
        notOwnerCard.setVisibility(owner ? View.GONE : View.VISIBLE);
        refreshLockState();
        if (sReloadApps || needsReload()) {
            sReloadApps = false;
            loadApps();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        adapter.shutdown();
    }

    // ---- lock state ----

    private void refreshLockState() {
        boolean editable = owner && PasswordDialogs.unlocked;
        lockBtn.setText(PasswordDialogs.unlocked ? R.string.btn_lock : R.string.btn_unlock);

        installSwitch.setOnCheckedChangeListener(null);
        installSwitch.setChecked(Policy.installsAllowed(this));
        installSwitch.setEnabled(editable);
        installSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean allowed) {
                try {
                    Policy.setInstallsAllowed(MainActivity.this, allowed);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, e.getMessage(), Toast.LENGTH_LONG).show();
                }
                refreshLockState();
            }
        });

        adapter.setEditable(editable);
    }

    @Override
    public boolean toggle(AppItem item, boolean hide, boolean value) {
        if (!owner || !PasswordDialogs.unlocked) return false;
        boolean ok = hide ? Policy.setHidden(this, item.pkg, value)
                          : Policy.setSuspended(this, item.pkg, value);
        if (!ok) {
            Toast.makeText(this, getString(R.string.toggle_failed, item.label), Toast.LENGTH_SHORT).show();
        }
        return ok;
    }

    // ---- d-pad ----

    // Android's own focus search gets lost in the list (its rows aren't selectable), so arrow
    // keys follow a fixed order: header, install switch, search, filter chips, then the rows.
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        boolean vertical = k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN;
        boolean horizontal = k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT;
        if (!vertical && !horizontal) return super.dispatchKeyEvent(e);
        View f = getCurrentFocus();
        // the search box keeps left/right for its cursor
        if (horizontal && f == searchBox) return super.dispatchKeyEvent(e);
        if (e.getAction() == KeyEvent.ACTION_DOWN) moveFocus(f, k);
        return true;
    }

    private void moveFocus(View f, int key) {
        View[][] lines = { { settingsBtn, lockBtn }, { installSwitch }, { searchBox }, filterBtns };
        boolean down = key == KeyEvent.KEYCODE_DPAD_DOWN, up = key == KeyEvent.KEYCODE_DPAD_UP;
        boolean right = key == KeyEvent.KEYCODE_DPAD_RIGHT;

        int row = rowOf(f);
        if (row >= 0) {
            int col = f.getId() == R.id.switch_hide ? 1 : 0;
            if (down) { if (row + 1 < adapter.getCount()) focusRow(row + 1, col); }
            else if (up) { if (row > 0) focusRow(row - 1, col); else focusLine(lines, lines.length - 1, -1); }
            else focusRow(row, right ? 1 : 0);
            return;
        }

        int li = -1, idx = -1;
        for (int i = 0; i < lines.length && li < 0; i++) {
            for (int j = 0; j < lines[i].length; j++) {
                if (lines[i][j] == f) { li = i; idx = j; break; }
            }
        }
        if (li < 0) {
            lockBtn.requestFocus();
            return;
        }
        if (down) {
            if (!focusLine(lines, li + 1, 1) && adapter.getCount() > 0) focusRow(firstVisibleRow(), 0);
        } else if (up) {
            focusLine(lines, li - 1, -1);
        } else {
            int step = right ? 1 : -1;
            for (int j = idx + step; j >= 0 && j < lines[li].length; j += step) {
                if (usable(lines[li][j])) { lines[li][j].requestFocus(); break; }
            }
        }
    }

    /** Focuses the first usable line from start in direction dir. Returns false if there is none. */
    private boolean focusLine(View[][] lines, int start, int dir) {
        for (int i = start; i >= 0 && i < lines.length; i += dir) {
            View pick = null;
            if (lines[i] == filterBtns) pick = filterBtns[filter];
            else if (i == 0) pick = lockBtn;
            else for (View v : lines[i]) if (usable(v)) { pick = v; break; }
            if (pick == null || !usable(pick)) continue;
            // on phones everything below the header lives in the list's first item
            if (phone && i > 0) reveal(0);
            pick.requestFocus();
            return true;
        }
        return false;
    }

    private static boolean usable(View v) {
        return v.getVisibility() == View.VISIBLE && v.isEnabled() && v.isFocusable();
    }

    private void focusRow(int row, int col) {
        View child = reveal(row + list.getHeaderViewsCount());
        if (child == null) return;
        if (!adapter.isEditable()) child.requestFocus();
        else child.findViewById(col == 1 ? R.id.switch_hide : R.id.switch_suspend).requestFocus();
    }

    /** Scrolls the list just enough to fully show item pos and returns its view. */
    private View reveal(int pos) {
        int top = list.getPaddingTop(), bottom = list.getHeight() - list.getPaddingBottom();
        int step = Math.round(68 * getResources().getDisplayMetrics().density);
        for (int i = 0; i < 200; i++) {
            int first = list.getFirstVisiblePosition();
            View c = list.getChildAt(pos - first);
            if (c == null) {
                int dir = pos < first ? -1 : 1;
                if (!list.canScrollList(dir)) return null;
                list.scrollListBy(dir * step);
                continue;
            }
            if (c.getTop() < top) list.scrollListBy(c.getTop() - top);
            else if (c.getBottom() > bottom) list.scrollListBy(c.getBottom() - bottom);
            return list.getChildAt(pos - list.getFirstVisiblePosition());
        }
        return null;
    }

    /** Index of the app row containing v, or -1. */
    private int rowOf(View v) {
        while (v != null && v.getParent() != list) {
            v = v.getParent() instanceof View ? (View) v.getParent() : null;
        }
        if (v == null) return -1;
        int row = list.getPositionForView(v) - list.getHeaderViewsCount();
        return row >= 0 && row < adapter.getCount() ? row : -1;
    }

    private int firstVisibleRow() {
        int headers = list.getHeaderViewsCount();
        for (int i = 0; i < list.getChildCount(); i++) {
            int pos = list.getFirstVisiblePosition() + i;
            if (pos >= headers && list.getChildAt(i).getTop() >= list.getPaddingTop()) return pos - headers;
        }
        return Math.max(0, list.getFirstVisiblePosition() - headers);
    }

    // ---- app list ----

    private boolean needsReload() {
        if (pkgSeq < 0) return true;
        return pm.getChangedPackages(pkgSeq) != null;
    }

    private void loadApps() {
        if (loading) return;
        loading = true;
        ChangedPackages seq = pm.getChangedPackages(0);
        final int startSeq = seq == null ? 0 : seq.getSequenceNumber();
        new Thread(new Runnable() {
            @Override public void run() {
                List<AppItem> result = null;
                String error = null;
                try {
                    result = buildList();
                } catch (Exception e) {
                    error = e.toString();
                }
                final List<AppItem> r = result;
                final String err = error;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        loading = false;
                        if (isDestroyed()) return;
                        if (r == null) {
                            listStatus.setText(getString(R.string.apps_load_failed, err));
                            listStatus.setVisibility(View.VISIBLE);
                            return;
                        }
                        pkgSeq = startSeq;
                        allApps.clear();
                        allApps.addAll(r);
                        applyFilter();
                    }
                });
            }
        }).start();
    }

    private List<AppItem> buildList() {
        List<ApplicationInfo> installed = Policy.installedApps(this);
        Set<String> hidden = Policy.findHidden(this, installed);

        Set<String> launchable = new HashSet<>();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(main, PackageManager.MATCH_DISABLED_COMPONENTS)) {
            launchable.add(ri.activityInfo.packageName);
        }

        List<AppItem> out = new ArrayList<>(installed.size());
        for (ApplicationInfo ai : installed) {
            String label;
            try { label = ai.loadLabel(pm).toString(); } catch (Exception e) { label = ai.packageName; }
            out.add(new AppItem(ai, label, launchable.contains(ai.packageName), hidden.contains(ai.packageName)));
        }
        Collections.sort(out, new Comparator<AppItem>() {
            @Override public int compare(AppItem a, AppItem b) {
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        return out;
    }

    private void applyFilter() {
        List<AppItem> shown = new ArrayList<>();
        for (AppItem a : allApps) {
            boolean match;
            switch (filter) {
                // hidden apps can't be detected as launchable, so they always show here
                case F_LAUNCHABLE: match = a.launchable || a.hidden; break;
                case F_USER: match = !a.system; break;
                case F_SYSTEM: match = a.system; break;
                case F_HIDDEN: match = a.hidden; break;
                case F_SUSPENDED: match = a.suspended; break;
                default: match = true;
            }
            if (match && !query.isEmpty() && !a.searchText.contains(query)) match = false;
            if (match) shown.add(a);
        }
        adapter.setItems(shown);
        if (shown.isEmpty()) {
            listStatus.setText(R.string.apps_empty);
            listStatus.setVisibility(View.VISIBLE);
        } else {
            listStatus.setVisibility(View.GONE);
        }
    }

    private void styleFilters() {
        for (int i = 0; i < filterBtns.length; i++) {
            boolean on = i == filter;
            filterBtns[i].setBackgroundResource(on ? R.drawable.btn_filled : R.drawable.btn_outline);
            filterBtns[i].setTextColor(getColor(on ? R.color.text_on_primary : R.color.btn_outline_text));
        }
    }
}
