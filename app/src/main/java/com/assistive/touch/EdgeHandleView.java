package com.assistive.touch;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Thin rounded bar drawn on the screen edge; the whole view is the touch area. */
public class EdgeHandleView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final boolean left;

    public EdgeHandleView(Context context, boolean left, float alpha) {
        super(context);
        this.left = left;
        int a = Math.round(Math.max(0f, Math.min(1f, alpha)) * 255);
        paint.setColor((a << 24) | 0x8E8E93);
        setContentDescription("Side bar");
    }

    @Override
    protected void onDraw(Canvas c) {
        float d = getResources().getDisplayMetrics().density;
        float barW = Math.min(getWidth(), 6 * d);
        float inset = 2 * d;
        if (left) rect.set(inset, 0, inset + barW, getHeight());
        else rect.set(getWidth() - inset - barW, 0, getWidth() - inset, getHeight());
        c.drawRoundRect(rect, barW / 2f, barW / 2f, paint);
    }
}
