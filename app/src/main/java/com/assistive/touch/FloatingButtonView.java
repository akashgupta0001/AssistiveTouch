package com.assistive.touch;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** The iOS-style button: dark translucent rounded square with three concentric white rings. */
public class FloatingButtonView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    public FloatingButtonView(Context context) {
        super(context);
        setContentDescription(context.getString(R.string.app_name));
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float s = Math.min(w, h);
        float cx = w / 2f, cy = h / 2f;

        rect.set(0, 0, w, h);
        paint.setColor(0xCC1E1E1E);
        c.drawRoundRect(rect, s * 0.3f, s * 0.3f, paint);

        paint.setColor(0x40FFFFFF);
        c.drawCircle(cx, cy, s * 0.36f, paint);
        paint.setColor(0x66FFFFFF);
        c.drawCircle(cx, cy, s * 0.28f, paint);
        paint.setColor(0xF2FFFFFF);
        c.drawCircle(cx, cy, s * 0.19f, paint);
    }
}
