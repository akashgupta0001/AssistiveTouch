package com.assistive.touch;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * Edge side bar. The whole touch area (thickness x length) is shaded dark so you can
 * see how much of the screen it covers, with a lighter grip line on the screen edge.
 * When "highlight" is on (while changing settings) it is drawn strongly with an outline.
 */
public class EdgeHandleView extends View {
    private final Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final boolean left;
    private final boolean highlight;

    public EdgeHandleView(Context context, boolean left, float alpha, boolean highlight) {
        super(context);
        this.left = left;
        this.highlight = highlight;
        float a = Math.max(0f, Math.min(1f, alpha));
        if (highlight) a = Math.max(a, 0.8f);
        shade.setColor(argb(a * 0.55f, 0x000000));
        bar.setColor(argb(a, 0xC7C7CC));
        outline.setStyle(Paint.Style.STROKE);
        outline.setStrokeWidth(2 * context.getResources().getDisplayMetrics().density);
        outline.setColor(0xFFFFCC00);
        setContentDescription("Side bar");
    }

    private static int argb(float alpha, int rgb) {
        return (Math.round(alpha * 255) << 24) | rgb;
    }

    @Override
    protected void onDraw(Canvas c) {
        float d = getResources().getDisplayMetrics().density;
        float w = getWidth(), h = getHeight();
        float r = Math.min(w, 12 * d);

        // Full touch area, rounded on the inner side.
        if (left) rect.set(-r, 0, w, h);
        else rect.set(0, 0, w + r, h);
        c.drawRoundRect(rect, r, r, shade);
        if (highlight) {
            rect.inset(outline.getStrokeWidth() / 2, outline.getStrokeWidth() / 2);
            c.drawRoundRect(rect, r, r, outline);
        }

        // Grip line on the screen edge.
        float barW = Math.min(w / 2f, 4 * d);
        float inset = 3 * d;
        float vPad = Math.min(h * 0.1f, 12 * d);
        if (left) rect.set(inset, vPad, inset + barW, h - vPad);
        else rect.set(w - inset - barW, vPad, w - inset, h - vPad);
        c.drawRoundRect(rect, barW / 2f, barW / 2f, bar);
    }
}
