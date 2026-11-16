package com.assistive.touch;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.FileOutputStream;
import java.io.InputStream;

/** Crop the chosen photo: drag to move, pinch to zoom, Rotate button, Done saves it. */
public class CropActivity extends Activity {

    private CropView cropView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Crop photo");

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        cropView = new CropView(this,
                "circle".equals(Prefs.get(this).getString(Prefs.KEY_SHAPE, "rounded")));
        root.addView(cropView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        TextView hint = new TextView(this);
        hint.setText("Drag to move · Pinch to zoom");
        hint.setTextColor(0xCCFFFFFF);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        hint.setPadding(0, dp(16), 0, 0);
        root.addView(hint, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(dp(8), dp(8), dp(8), dp(16));
        bar.addView(button("Cancel", v -> finish()), weight());
        bar.addView(button("Rotate ↻", v -> cropView.rotate()), weight());
        bar.addView(button("Done", v -> save()), weight());
        root.addView(bar, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        setContentView(root);

        Uri uri = getIntent().getData();
        if (uri == null) { finish(); return; }
        new Thread(() -> {
            Bitmap b = load(uri);
            runOnUiThread(() -> {
                if (b == null) {
                    Toast.makeText(this, "Couldn't load that photo", Toast.LENGTH_SHORT).show();
                    finish();
                } else {
                    cropView.setBitmap(b);
                }
            });
        }).start();
    }

    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = dp(4);
        lp.rightMargin = dp(4);
        return lp;
    }

    private void save() {
        Bitmap out = cropView.crop(256);
        if (out == null) return;
        try (FileOutputStream fo = new FileOutputStream(FloatingButtonView.photoFile(this))) {
            out.compress(Bitmap.CompressFormat.PNG, 100, fo);
        } catch (Exception e) {
            Toast.makeText(this, "Couldn't save photo", Toast.LENGTH_SHORT).show();
            return;
        }
        Prefs.get(this).edit().putLong(Prefs.KEY_PHOTO, System.currentTimeMillis()).apply();
        finish();
    }

    /** Load at most ~2048 px and apply the EXIF rotation so the photo is upright. */
    private Bitmap load(Uri uri) {
        try {
            ContentResolver cr = getContentResolver();
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = cr.openInputStream(uri)) { BitmapFactory.decodeStream(in, null, o); }
            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / sample > 2048) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            Bitmap b;
            try (InputStream in = cr.openInputStream(uri)) { b = BitmapFactory.decodeStream(in, null, o2); }
            if (b == null) return null;

            int degrees = 0;
            boolean flip = false;
            try (InputStream in = cr.openInputStream(uri)) {
                ExifInterface exif = new ExifInterface(in);
                switch (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    case ExifInterface.ORIENTATION_ROTATE_90: degrees = 90; break;
                    case ExifInterface.ORIENTATION_ROTATE_180: degrees = 180; break;
                    case ExifInterface.ORIENTATION_ROTATE_270: degrees = 270; break;
                    case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: flip = true; break;
                    case ExifInterface.ORIENTATION_TRANSPOSE: degrees = 90; flip = true; break;
                    case ExifInterface.ORIENTATION_TRANSVERSE: degrees = 270; flip = true; break;
                    case ExifInterface.ORIENTATION_FLIP_VERTICAL: degrees = 180; flip = true; break;
                    default: break;
                }
            } catch (Exception ignored) { }
            if (degrees != 0 || flip) {
                Matrix m = new Matrix();
                if (flip) m.postScale(-1, 1);
                m.postRotate(degrees);
                b = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
            }
            return b;
        } catch (Throwable t) {
            return null;
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ------------------------------------------------------------------ crop view

    static class CropView extends View {
        private Bitmap bmp;
        private final Matrix m = new Matrix();
        private final RectF frame = new RectF();
        private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint shade = new Paint();
        private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final boolean circle;
        private final ScaleGestureDetector scaler;
        private float lastX, lastY;
        private final float[] values = new float[9];

        CropView(Context c, boolean circle) {
            super(c);
            this.circle = circle;
            shade.setColor(0xAA000000);
            border.setStyle(Paint.Style.STROKE);
            border.setColor(Color.WHITE);
            border.setStrokeWidth(2 * c.getResources().getDisplayMetrics().density);
            scaler = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScale(ScaleGestureDetector d) {
                    if (bmp == null) return true;
                    float cur = scale();
                    float target = Math.max(minScale(), Math.min(minScale() * 8f, cur * d.getScaleFactor()));
                    float f = target / cur;
                    m.postScale(f, f, d.getFocusX(), d.getFocusY());
                    clampToFrame();
                    invalidate();
                    return true;
                }
            });
        }

        void setBitmap(Bitmap b) {
            bmp = b;
            reset();
        }

        void rotate() {
            if (bmp == null) return;
            Matrix r = new Matrix();
            r.postRotate(90);
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), r, true);
            reset();
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            float side = Math.min(w, h) * 0.8f;
            frame.set((w - side) / 2f, (h - side) / 2f, (w + side) / 2f, (h + side) / 2f);
            reset();
        }

        private float minScale() {
            return frame.width() / Math.min(bmp.getWidth(), bmp.getHeight());
        }

        private float scale() {
            m.getValues(values);
            return values[Matrix.MSCALE_X];
        }

        private void reset() {
            if (bmp == null || frame.isEmpty()) return;
            float s = minScale();
            m.setScale(s, s);
            m.postTranslate(frame.centerX() - bmp.getWidth() * s / 2f,
                    frame.centerY() - bmp.getHeight() * s / 2f);
            invalidate();
        }

        /** Keep the photo covering the whole crop frame. */
        private void clampToFrame() {
            RectF r = new RectF(0, 0, bmp.getWidth(), bmp.getHeight());
            m.mapRect(r);
            float dx = 0, dy = 0;
            if (r.left > frame.left) dx = frame.left - r.left;
            else if (r.right < frame.right) dx = frame.right - r.right;
            if (r.top > frame.top) dy = frame.top - r.top;
            else if (r.bottom < frame.bottom) dy = frame.bottom - r.bottom;
            m.postTranslate(dx, dy);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (bmp == null) return true;
            scaler.onTouchEvent(e);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lastX = e.getX();
                    lastY = e.getY();
                    break;
                case MotionEvent.ACTION_POINTER_UP: {
                    int keep = e.getActionIndex() == 0 ? 1 : 0;
                    lastX = e.getX(keep);
                    lastY = e.getY(keep);
                    break;
                }
                case MotionEvent.ACTION_MOVE:
                    if (e.getPointerCount() == 1 && !scaler.isInProgress()) {
                        m.postTranslate(e.getX() - lastX, e.getY() - lastY);
                        clampToFrame();
                        invalidate();
                    }
                    lastX = e.getX();
                    lastY = e.getY();
                    break;
            }
            return true;
        }

        @Override
        protected void onDraw(Canvas c) {
            if (bmp == null) return;
            c.drawBitmap(bmp, m, bitmapPaint);

            path.reset();
            path.setFillType(Path.FillType.EVEN_ODD);
            path.addRect(0, 0, getWidth(), getHeight(), Path.Direction.CW);
            float r = circle ? frame.width() / 2f : frame.width() * 0.3f;
            path.addRoundRect(frame, r, r, Path.Direction.CW);
            c.drawPath(path, shade);
            c.drawRoundRect(frame, r, r, border);
        }

        Bitmap crop(int size) {
            if (bmp == null) return null;
            Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(out);
            Matrix mm = new Matrix(m);
            mm.postTranslate(-frame.left, -frame.top);
            float k = size / frame.width();
            mm.postScale(k, k);
            c.drawBitmap(bmp, mm, bitmapPaint);
            return out;
        }
    }
}
