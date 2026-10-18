package com.flipphoneguy.applock;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class AppAdapter extends BaseAdapter {

    interface Toggler {
        /** Returns false if Android refused; the switch is then flipped back. */
        boolean toggle(AppItem item, boolean hide, boolean value);
    }

    private final LayoutInflater inflater;
    private final PackageManager pm;
    private final Toggler toggler;
    private final List<AppItem> items = new ArrayList<>();
    private final LruCache<String, Drawable> icons = new LruCache<>(150);
    private final ExecutorService iconLoader = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean editable;

    AppAdapter(Context c, Toggler toggler) {
        this.inflater = LayoutInflater.from(c);
        this.pm = c.getPackageManager();
        this.toggler = toggler;
    }

    void setItems(List<AppItem> list) {
        items.clear();
        items.addAll(list);
        notifyDataSetChanged();
    }

    void setEditable(boolean editable) {
        this.editable = editable;
        notifyDataSetChanged();
    }

    boolean isEditable() {
        return editable;
    }

    void shutdown() {
        iconLoader.shutdownNow();
    }

    @Override public int getCount() { return items.size(); }
    @Override public AppItem getItem(int i) { return items.get(i); }
    @Override public long getItemId(int i) { return i; }
    @Override public boolean isEnabled(int i) { return false; }

    private static final class Holder {
        View row;
        ImageView icon;
        TextView label, pkg;
        Switch suspend, hide;
        AppItem item;
    }

    @Override
    public View getView(int position, View view, ViewGroup parent) {
        final Holder h;
        if (view == null) {
            view = inflater.inflate(R.layout.item_app, parent, false);
            h = new Holder();
            h.row = view;
            h.icon = (ImageView) view.findViewById(R.id.icon);
            h.label = (TextView) view.findViewById(R.id.label);
            h.pkg = (TextView) view.findViewById(R.id.pkg);
            h.suspend = (Switch) view.findViewById(R.id.switch_suspend);
            h.hide = (Switch) view.findViewById(R.id.switch_hide);
            view.setTag(h);
        } else {
            h = (Holder) view.getTag();
        }
        final AppItem item = items.get(position);
        h.item = item;

        h.label.setText(item.label);
        h.pkg.setText(item.pkg);
        bindIcon(h, item);
        bindState(h);
        return view;
    }

    private void bindState(Holder h) {
        float alpha = h.item.hidden || h.item.suspended ? 0.5f : 1f;
        h.label.setAlpha(alpha);
        h.icon.setAlpha(alpha);
        // while locked the switches can't take focus, so the d-pad lands on the row instead
        h.row.setFocusable(!editable);
        bindSwitch(h, h.suspend, h.item.suspended, false);
        bindSwitch(h, h.hide, h.item.hidden, true);
    }

    private void bindSwitch(final Holder h, final Switch sw, boolean checked, final boolean hide) {
        // detach first so setChecked on a recycled row can't fire for the wrong app
        sw.setOnCheckedChangeListener(null);
        sw.setChecked(checked);
        sw.setEnabled(editable);
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean value) {
                AppItem item = h.item;
                if (toggler.toggle(item, hide, value)) {
                    if (hide) item.hidden = value; else item.suspended = value;
                }
                // rebind just this row, a full redraw would knock d-pad focus off the switch
                bindState(h);
            }
        });
    }

    private void bindIcon(final Holder h, final AppItem item) {
        Drawable cached = icons.get(item.pkg);
        h.icon.setImageDrawable(cached);
        if (cached != null) return;
        iconLoader.execute(new Runnable() {
            @Override public void run() {
                final Drawable d;
                try { d = item.info.loadIcon(pm); } catch (Exception e) { return; }
                main.post(new Runnable() {
                    @Override public void run() {
                        icons.put(item.pkg, d);
                        if (h.item == item) h.icon.setImageDrawable(d);
                    }
                });
            }
        });
    }
}
