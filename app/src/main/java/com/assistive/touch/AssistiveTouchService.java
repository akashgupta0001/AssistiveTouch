package com.assistive.touch;

import android.accessibilityservice.AccessibilityService;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.display.DisplayManager;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Display;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class AssistiveTouchService extends AccessibilityService
        implements SharedPreferences.OnSharedPreferenceChangeListener {

    static AssistiveTouchService instance;

    private static final long IDLE_DELAY_MS = 3000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private SharedPreferences prefs;

    private FloatingButtonView button;
    private WindowManager.LayoutParams bp;
    private View menuRoot;
    private ValueAnimator snapAnim;
    private GestureDetector gestures;

    private CameraManager cameraManager;
    private String torchId;
    private boolean torchOn;

    private final Runnable goIdle = () -> {
        if (button != null) {
            button.animate().alpha(idleAlpha()).setDuration(400).start();
        }
    };

    private final CameraManager.TorchCallback torchCallback = new CameraManager.TorchCallback() {
        @Override
        public void onTorchModeChanged(String cameraId, boolean enabled) {
            if (cameraId.equals(torchId)) torchOn = enabled;
        }
    };

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        prefs = Prefs.get(this);
        prefs.registerOnSharedPreferenceChangeListener(this);
        setupGestures();
        setupTorch();
        if (prefs.getBoolean(Prefs.KEY_ENABLED, true)) showButton();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override
    public void onInterrupt() { }

    @Override
    public void onDestroy() {
        cleanup();
        super.onDestroy();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        cleanup();
        return super.onUnbind(intent);
    }

    private void cleanup() {
        instance = null;
        handler.removeCallbacksAndMessages(null);
        hideMenu();
        removeButton();
        if (prefs != null) prefs.unregisterOnSharedPreferenceChangeListener(this);
        if (cameraManager != null && torchId != null) {
            try { cameraManager.unregisterTorchCallback(torchCallback); } catch (Exception ignored) { }
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        hideMenu();
        if (button != null) handler.postDelayed(this::snapToEdge, 300);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
        if (key == null) return;
        switch (key) {
            case Prefs.KEY_ENABLED:
                if (sp.getBoolean(Prefs.KEY_ENABLED, true)) showButton();
                else { hideMenu(); removeButton(); }
                break;
            case Prefs.KEY_SIZE:
                if (button != null) {
                    int s = dp(sp.getInt(Prefs.KEY_SIZE, Prefs.DEFAULT_SIZE));
                    bp.width = s;
                    bp.height = s;
                    safeUpdate();
                    snapToEdge();
                    wake();
                }
                break;
            case Prefs.KEY_IDLE_ALPHA:
                wake();
                break;
        }
    }

    // ---------------------------------------------------------------- floating button

    private void showButton() {
        if (button != null || wm == null) return;
        int size = dp(prefs.getInt(Prefs.KEY_SIZE, Prefs.DEFAULT_SIZE));
        Point screen = screenSize();

        bp = new WindowManager.LayoutParams(
                size, size,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        bp.gravity = Gravity.TOP | Gravity.START;
        bp.x = prefs.getInt(Prefs.KEY_X, screen.x - size - dp(4));
        bp.y = prefs.getInt(Prefs.KEY_Y, screen.y / 3);
        clampPosition(screen);

        button = new FloatingButtonView(this);
        button.setOnTouchListener(touchListener);
        try {
            wm.addView(button, bp);
        } catch (Exception e) {
            button = null;
            return;
        }
        button.setAlpha(0f);
        button.animate().alpha(1f).setDuration(200).start();
        scheduleIdle();
    }

    private void removeButton() {
        if (snapAnim != null) snapAnim.cancel();
        if (button != null) {
            try { wm.removeView(button); } catch (Exception ignored) { }
            button = null;
        }
    }

    private void safeUpdate() {
        if (button != null && button.isAttachedToWindow()) {
            try { wm.updateViewLayout(button, bp); } catch (Exception ignored) { }
        }
    }

    private void scheduleIdle() {
        handler.removeCallbacks(goIdle);
        handler.postDelayed(goIdle, IDLE_DELAY_MS);
    }

    private void wake() {
        if (button == null) return;
        button.animate().cancel();
        button.setAlpha(1f);
        scheduleIdle();
    }

    private float idleAlpha() {
        return prefs.getInt(Prefs.KEY_IDLE_ALPHA, Prefs.DEFAULT_IDLE_ALPHA) / 100f;
    }

    private void clampPosition(Point screen) {
        int m = dp(4);
        bp.x = Math.max(m, Math.min(bp.x, screen.x - bp.width - m));
        bp.y = Math.max(m, Math.min(bp.y, screen.y - bp.height - m));
    }

    private void snapToEdge() {
        if (button == null) return;
        Point screen = screenSize();
        clampPosition(screen);
        int m = dp(4);
        int target = (bp.x + bp.width / 2 < screen.x / 2) ? m : screen.x - bp.width - m;

        if (snapAnim != null) snapAnim.cancel();
        snapAnim = ValueAnimator.ofInt(bp.x, target);
        snapAnim.setDuration(260);
        snapAnim.setInterpolator(new DecelerateInterpolator());
        snapAnim.addUpdateListener(a -> {
            bp.x = (int) a.getAnimatedValue();
            safeUpdate();
        });
        snapAnim.start();
        prefs.edit().putInt(Prefs.KEY_X, target).putInt(Prefs.KEY_Y, bp.y).apply();
    }

    private final View.OnTouchListener touchListener = new View.OnTouchListener() {
        private float downX, downY;
        private int startX, startY;
        private boolean dragging;
        private int slop;

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            if (slop == 0) slop = ViewConfiguration.get(AssistiveTouchService.this).getScaledTouchSlop();
            gestures.onTouchEvent(e);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    handler.removeCallbacks(goIdle);
                    if (snapAnim != null) snapAnim.cancel();
                    v.animate().cancel();
                    v.setAlpha(1f);
                    v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(80).start();
                    downX = e.getRawX();
                    downY = e.getRawY();
                    startX = bp.x;
                    startY = bp.y;
                    dragging = false;
                    return true;

                case MotionEvent.ACTION_MOVE:
                    float dx = e.getRawX() - downX;
                    float dy = e.getRawY() - downY;
                    if (!dragging && Math.hypot(dx, dy) > slop) dragging = true;
                    if (dragging) {
                        bp.x = startX + (int) dx;
                        bp.y = startY + (int) dy;
                        safeUpdate();
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                    if (dragging) snapToEdge();
                    scheduleIdle();
                    return true;
            }
            return false;
        }
    };

    private void setupGestures() {
        gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) { return true; }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                // Open instantly when no double-tap action is configured.
                if (gestureAction(Prefs.KEY_DOUBLE) == Action.NONE) {
                    perform(Action.MENU);
                    return true;
                }
                return false;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (gestureAction(Prefs.KEY_DOUBLE) != Action.NONE) perform(Action.MENU);
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                Action a = gestureAction(Prefs.KEY_DOUBLE);
                if (a != Action.NONE) perform(a);
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                Action a = gestureAction(Prefs.KEY_LONG);
                if (a != Action.NONE) {
                    if (button != null) button.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    perform(a);
                }
            }
        }, handler);
    }

    private Action gestureAction(String key) {
        return Action.fromId(prefs.getString(key, Action.NONE.id));
    }

    // ---------------------------------------------------------------- menu

    private void showMenu() {
        if (menuRoot != null || wm == null) return;
        handler.removeCallbacks(goIdle);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0x33000000);
        root.setOnClickListener(v -> hideMenu());

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(10);
        panel.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xE61C1C1E);
        bg.setCornerRadius(dp(32));
        panel.setBackground(bg);
        panel.setClickable(true); // taps on the panel itself don't close it

        LinearLayout row = null;
        Action[] items = Action.MENU_ITEMS;
        for (int i = 0; i < items.length; i++) {
            if (i % 3 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                panel.addView(row);
            }
            row.addView(makeCell(items[i]));
        }

        root.addView(panel, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        WindowManager.LayoutParams mp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        try {
            wm.addView(root, mp);
        } catch (Exception e) {
            return;
        }
        menuRoot = root;
        if (button != null) button.setVisibility(View.INVISIBLE);

        root.setAlpha(0f);
        root.animate().alpha(1f).setDuration(150).start();
        panel.setScaleX(0.5f);
        panel.setScaleY(0.5f);
        panel.animate().scaleX(1f).scaleY(1f)
                .setInterpolator(new OvershootInterpolator(1.1f))
                .setDuration(240).start();
    }

    private View makeCell(Action a) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER);
        cell.setLayoutParams(new LinearLayout.LayoutParams(dp(86), dp(86)));

        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(dp(20));
        cell.setBackground(new RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), null, mask));

        ImageView icon = new ImageView(this);
        icon.setImageResource(a.icon);
        cell.addView(icon, new LinearLayout.LayoutParams(dp(30), dp(30)));

        TextView label = new TextView(this);
        label.setText(a.label);
        label.setTextColor(Color.WHITE);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        label.setGravity(Gravity.CENTER);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        cell.addView(label, lp);

        cell.setContentDescription(getString(a.label));
        cell.setOnClickListener(v -> {
            hideMenu();
            perform(a);
        });
        return cell;
    }

    private void hideMenu() {
        if (menuRoot == null) return;
        View r = menuRoot;
        menuRoot = null;
        try { wm.removeView(r); } catch (Exception ignored) { }
        if (button != null) {
            button.setVisibility(View.VISIBLE);
            wake();
        }
    }

    // ---------------------------------------------------------------- actions

    private void perform(Action a) {
        switch (a) {
            case MENU:
                showMenu();
                break;
            case HOME:
                performGlobalAction(GLOBAL_ACTION_HOME);
                break;
            case BACK:
                performGlobalAction(GLOBAL_ACTION_BACK);
                break;
            case RECENTS:
                performGlobalAction(GLOBAL_ACTION_RECENTS);
                break;
            case NOTIFICATIONS:
                performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS);
                break;
            case CONTROL_CENTRE:
                performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS);
                break;
            case POWER:
                performGlobalAction(GLOBAL_ACTION_POWER_DIALOG);
                break;
            case SPLIT_SCREEN:
                performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN);
                break;
            case LOCK_SCREEN:
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
                } else toast(R.string.needs_android9);
                break;
            case SCREENSHOT:
                takeScreenshot();
                break;
            case VOLUME_UP:
                adjustVolume(AudioManager.ADJUST_RAISE);
                break;
            case VOLUME_DOWN:
                adjustVolume(AudioManager.ADJUST_LOWER);
                break;
            case FLASHLIGHT:
                toggleTorch();
                break;
            case SETTINGS:
                Intent i = new Intent(this, MainActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                break;
            case NONE:
            default:
                break;
        }
    }

    private void takeScreenshot() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            toast(R.string.needs_android9);
            return;
        }
        // Hide the button so it isn't in the screenshot.
        if (button != null) button.setVisibility(View.INVISIBLE);
        handler.postDelayed(() -> {
            performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT);
            handler.postDelayed(() -> {
                if (button != null && menuRoot == null) {
                    button.setVisibility(View.VISIBLE);
                    wake();
                }
            }, 1500);
        }, 350);
    }

    private void adjustVolume(int direction) {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am != null) {
            am.adjustSuggestedStreamVolume(direction,
                    AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI);
        }
    }

    private void setupTorch() {
        try {
            cameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);
            if (cameraManager == null) return;
            for (String id : cameraManager.getCameraIdList()) {
                CameraCharacteristics ch = cameraManager.getCameraCharacteristics(id);
                Boolean flash = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
                if (Boolean.TRUE.equals(flash) && facing != null
                        && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    torchId = id;
                    break;
                }
            }
            if (torchId != null) cameraManager.registerTorchCallback(torchCallback, handler);
        } catch (Exception ignored) {
            torchId = null;
        }
    }

    private void toggleTorch() {
        if (cameraManager == null || torchId == null) {
            toast(R.string.no_flash);
            return;
        }
        try {
            cameraManager.setTorchMode(torchId, !torchOn);
        } catch (Exception e) {
            toast(R.string.no_flash);
        }
    }

    // ---------------------------------------------------------------- helpers

    private Point screenSize() {
        DisplayMetrics dm = new DisplayMetrics();
        try {
            DisplayManager dmgr = (DisplayManager) getSystemService(DISPLAY_SERVICE);
            Display d = dmgr.getDisplay(Display.DEFAULT_DISPLAY);
            d.getRealMetrics(dm);
        } catch (Exception e) {
            dm = getResources().getDisplayMetrics();
        }
        return new Point(dm.widthPixels, dm.heightPixels);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(int res) {
        Toast.makeText(this, res, Toast.LENGTH_SHORT).show();
    }
}
