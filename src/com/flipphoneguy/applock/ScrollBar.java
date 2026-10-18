package com.flipphoneguy.applock;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ListView;

/** Drag scrollbar for the app list. Counts header views and only takes touches while shown. */
public class ScrollBar extends View {

    private static final int HIDE_DELAY_MS = 1500;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float thumbW, thumbRight, minThumbH;
    private ListView list;
    private View[] headers;
    private float rowH;
    private float offset = -1, range;
    private float thumbTop, thumbH, grab;
    private boolean shown, dragging;

    private final Runnable hide = new Runnable() {
        @Override public void run() {
            shown = false;
            animate().alpha(0f).setDuration(200);
        }
    };

    public ScrollBar(Context context, AttributeSet attrs) {
        super(context, attrs);
        float d = context.getResources().getDisplayMetrics().density;
        thumbW = 6 * d;
        thumbRight = 3 * d;
        minThumbH = 40 * d;
        rowH = 68 * d;
        paint.setColor(context.getColor(R.color.primary));
        setAlpha(0f);
    }

    void attach(ListView list, View[] headers) {
        this.list = list;
        this.headers = headers;
    }

    /** Call from the list's scroll listener. */
    void update() {
        if (list == null || list.getChildCount() == 0) return;
        int first = list.getFirstVisiblePosition();
        int rows = rowCount();
        for (int i = 0; i < list.getChildCount(); i++) {
            int pos = first + i;
            if (pos >= headers.length && pos < headers.length + rows) {
                rowH = list.getChildAt(i).getHeight();
                break;
            }
        }
        // top() counts a divider after every item, the last one has none
        float content = top(headers.length + rows) - list.getDividerHeight();
        range = content + list.getPaddingTop() + list.getPaddingBottom() - list.getHeight();
        if (range <= 0) {
            removeCallbacks(hide);
            if (shown) hide.run();
            offset = 0;
            return;
        }
        float now = top(first) + list.getPaddingTop() - list.getChildAt(0).getTop();
        boolean moved = offset >= 0 && Math.abs(now - offset) >= 1;
        offset = Math.max(0, Math.min(range, now));
        layoutThumb();
        if (moved) show();
    }

    private int rowCount() {
        return list.getCount() - list.getHeaderViewsCount();
    }

    /** Distance from the top of the content to the top of item pos. Every item is followed by a divider. */
    private float top(int pos) {
        float div = list.getDividerHeight();
        float y = 0;
        for (int i = 0; i < headers.length && i < pos; i++) y += height(headers[i]) + div;
        if (pos > headers.length) y += Math.min(pos - headers.length, rowCount()) * (rowH + div);
        return y;
    }

    private int height(View v) {
        if (v.isLaidOut() && !v.isLayoutRequested()) return v.getHeight();
        int w = list.getWidth() - list.getPaddingLeft() - list.getPaddingRight();
        v.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        return v.getMeasuredHeight();
    }

    private void layoutThumb() {
        float track = getHeight();
        thumbH = Math.min(track, Math.max(minThumbH, track * list.getHeight() / (range + list.getHeight())));
        if (!dragging) thumbTop = (track - thumbH) * offset / range;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        if (list != null && range > 0) layoutThumb();
    }

    private void show() {
        removeCallbacks(hide);
        if (!shown) {
            shown = true;
            animate().cancel();
            animate().alpha(1f).setDuration(100);
        }
        if (!dragging) postDelayed(hide, HIDE_DELAY_MS);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // hidden: let the touch fall through to the list
                if (!shown || range <= 0) return false;
                dragging = true;
                removeCallbacks(hide);
                getParent().requestDisallowInterceptTouchEvent(true);
                list.smoothScrollBy(0, 0);
                float y = e.getY();
                grab = y >= thumbTop && y <= thumbTop + thumbH ? y - thumbTop : thumbH / 2;
                dragTo(y);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (dragging) dragTo(e.getY());
                return dragging;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (!dragging) return false;
                dragging = false;
                postDelayed(hide, HIDE_DELAY_MS);
                return true;
        }
        return dragging;
    }

    private void dragTo(float y) {
        float span = getHeight() - thumbH;
        thumbTop = Math.max(0, Math.min(span, y - grab));
        invalidate();
        float target = span > 0 ? thumbTop / span * range : 0;

        // find the item at that offset, then place it so the content lands exactly there
        int rows = rowCount();
        int pos = 0;
        while (pos < headers.length && top(pos + 1) <= target) pos++;
        if (pos == headers.length && rows > 0) {
            float div = list.getDividerHeight();
            int row = (int) ((target - top(pos)) / (rowH + div));
            pos += Math.max(0, Math.min(rows - 1, row));
        }
        list.setSelectionFromTop(pos, Math.round(top(pos) - target));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (range <= 0) return;
        float right = getWidth() - thumbRight;
        rect.set(right - thumbW, thumbTop, right, thumbTop + thumbH);
        canvas.drawRoundRect(rect, thumbW / 2, thumbW / 2, paint);
    }
}
