package com.assistive.touch;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

import java.io.File;

/**
 * The floating button. Default look: translucent rounded square with three rings (iOS style).
 * Can be customised with colours, shape, or a photo.
 */
public class FloatingButtonView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint photoPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF rect = new RectF();
    private final Matrix matrix = new Matrix();

    private int bgColor = Prefs.DEFAULT_BG_COLOR;
    private int bgAlpha = Prefs.DEFAULT_BG_ALPHA;
    private int iconColor = Prefs.DEFAULT_ICON_COLOR;
    private boolean circle;
    private Bitmap photo;

    public FloatingButtonView(Context context) {
        super(context);
        setContentDescription(context.getString(R.string.app_name));
        loadStyle();
    }

    public static File photoFile(Context c) {
        return new File(c.getFilesDir(), Prefs.PHOTO_FILE);
    }

    /** Re-read colours, shape and photo from settings. */
    public void loadStyle() {
        SharedPreferences p = Prefs.get(getContext());
        bgColor = p.getInt(Prefs.KEY_BG_COLOR, Prefs.DEFAULT_BG_COLOR);
        bgAlpha = p.getInt(Prefs.KEY_BG_ALPHA, Prefs.DEFAULT_BG_ALPHA);
        iconColor = p.getInt(Prefs.KEY_ICON_COLOR, Prefs.DEFAULT_ICON_COLOR);
        circle = "circle".equals(p.getString(Prefs.KEY_SHAPE, "rounded"));
        photo = null;
        if (p.getLong(Prefs.KEY_PHOTO, 0) != 0) {
            File f = photoFile(getContext());
            if (f.exists()) {
                try { photo = BitmapFactory.decodeFile(f.getPath()); } catch (Throwable ignored) { }
            }
        }
        if (photo != null) {
            photoPaint.setShader(new BitmapShader(photo, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        } else {
            photoPaint.setShader(null);
        }
        invalidate();
    }

    private static int withAlpha(int color, int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (color & 0xFFFFFF);
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float s = Math.min(w, h);
        float cx = w / 2f, cy = h / 2f;
        float radius = circle ? s / 2f : s * 0.3f;
        rect.set(0, 0, w, h);

        if (photo != null) {
            float scale = Math.max(w / photo.getWidth(), h / photo.getHeight());
            matrix.setScale(scale, scale);
            matrix.postTranslate((w - photo.getWidth() * scale) / 2f, (h - photo.getHeight() * scale) / 2f);
            photoPaint.getShader().setLocalMatrix(matrix);
            c.drawRoundRect(rect, radius, radius, photoPaint);
            return;
        }

        paint.setColor(withAlpha(bgColor, Math.round(bgAlpha * 2.55f)));
        c.drawRoundRect(rect, radius, radius, paint);

        paint.setColor(withAlpha(iconColor, 0x40));
        c.drawCircle(cx, cy, s * 0.36f, paint);
        paint.setColor(withAlpha(iconColor, 0x66));
        c.drawCircle(cx, cy, s * 0.28f, paint);
        paint.setColor(withAlpha(iconColor, 0xF2));
        c.drawCircle(cx, cy, s * 0.19f, paint);
    }
}
