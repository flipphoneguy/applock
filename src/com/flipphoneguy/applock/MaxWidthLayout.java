package com.flipphoneguy.applock;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;

/** LinearLayout that stops growing past MAX_DP so tablet layouts don't stretch edge to edge. */
public class MaxWidthLayout extends LinearLayout {

    private static final int MAX_DP = 760;
    private final int maxPx;

    public MaxWidthLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        maxPx = Math.round(MAX_DP * context.getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        if (View.MeasureSpec.getSize(widthSpec) > maxPx) {
            widthSpec = View.MeasureSpec.makeMeasureSpec(maxPx, View.MeasureSpec.EXACTLY);
        }
        super.onMeasure(widthSpec, heightSpec);
    }
}
