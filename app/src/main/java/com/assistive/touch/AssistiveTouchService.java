package com.assistive.touch;

import android.accessibilityservice.AccessibilityService;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.drawable.Drawable;
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
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

public class AssistiveTouchService extends AccessibilityService
        implements SharedPreferences.OnSharedPreferenceChangeListener {

    static AssistiveTouchService instance;

    private static final long IDLE_DELAY_MS = 3000;
    private static final long FLICK_MAX_MS = 350;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private SharedPreferences prefs;

    private FloatingButtonView button;
    private WindowManager.LayoutParams bp;
    private View menuRoot;
    private View sidebarRoot;
    private ValueAnimator snapAnim;
    private final View[] edgeViews = new View[2]; // 0 = left, 1 = right
    private long highlightUntil;
    private final Runnable endHighlight = this::rebuildEdges;

    private CameraManager cameraManager;
    private String torchId;
    private boolean torchOn;

    private final Runnable goIdle = () -> {
        if (button != null) button.animate().alpha(idleAlpha()).setDuration(400).start();
    };

    private final CameraManager.TorchCallback torchCallback = new CameraManager.TorchCallback() {
        @Override
        public void onTorchModeChanged(String cameraId, boolean enabled) {
            if (cameraId.equals(torchId)) torchOn = enabled;
        }
    };

    // ================================================================ lifecycle

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        prefs = Prefs.get(this);
        prefs.registerOnSharedPreferenceChangeListener(this);
        setupTorch();
        if (prefs.getBoolean(Prefs.KEY_ENABLED, true)) showButton();
        rebuildEdges();
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
        hideSidebar();
        removeButton();
        removeEdges();
        if (prefs != null) prefs.unregisterOnSharedPreferenceChangeListener(this);
        if (cameraManager != null && torchId != null) {
            try { cameraManager.unregisterTorchCallback(torchCallback); } catch (Exception ignored) { }
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        hideMenu();
        hideSidebar();
        handler.postDelayed(() -> {
            if (button != null) snapToEdge();
            rebuildEdges();
        }, 300);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
        if (key == null) return;
        if (key.startsWith(Prefs.EDGE_PREFIX)) {
            if (key.endsWith("_len") || key.endsWith("_thick") || key.endsWith("_pos")
                    || key.endsWith("_alpha") || key.endsWith("_enabled")) {
                // Show the bar strongly for a moment so its size and position are easy to see.
                highlightUntil = System.currentTimeMillis() + 2500;
                handler.removeCallbacks(endHighlight);
                handler.postDelayed(endHighlight, 2600);
            }
            rebuildEdges();
            return;
        }
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
            case Prefs.KEY_BG_COLOR:
            case Prefs.KEY_BG_ALPHA:
            case Prefs.KEY_ICON_COLOR:
            case Prefs.KEY_SHAPE:
            case Prefs.KEY_PHOTO:
                if (button != null) {
                    button.loadStyle();
                    wake();
                }
                break;
        }
    }

    /** Called by the Quick Settings tile: close the panel, then show the app sidebar. */
    public void openSidebarFromTile() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE);
        }
        handler.postDelayed(() -> showSidebar(buttonSide()), 400);
    }

    // ================================================================ floating button

    private void showButton() {
        if (button != null || wm == null) return;
        int size = dp(prefs.getInt(Prefs.KEY_SIZE, Prefs.DEFAULT_SIZE));
        Point screen = screenSize();

        bp = overlayParams(size, size, false);
        bp.gravity = Gravity.TOP | Gravity.START;
        bp.x = prefs.getInt(Prefs.KEY_X, screen.x - size - dp(4));
        bp.y = prefs.getInt(Prefs.KEY_Y, screen.y / 3);
        clampPosition(screen);

        button = new FloatingButtonView(this);
        button.setOnTouchListener(new ButtonTouch());
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

    /** Which side of the screen the floating button is on. */
    private int buttonSide() {
        if (button == null || bp == null) return Gravity.RIGHT;
        return (bp.x + bp.width / 2 < screenSize().x / 2) ? Gravity.LEFT : Gravity.RIGHT;
    }

    /**
     * Gestures on the floating button:
     * tap / double / triple tap, long press, quick flick up/down/left/right.
     * A slow drag (or a flick with no action set) moves the button.
     */
    private class ButtonTouch implements View.OnTouchListener {
        private float downX, downY;
        private int startX, startY;
        private long downTime;
        private boolean dragging, longFired;
        private int tapCount;
        private int slop;

        private final Runnable longPress = () -> {
            longFired = true;
            Action a = Prefs.action(prefs, Prefs.KEY_LONG);
            if (a != Action.NONE && button != null) {
                button.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                button.animate().scaleX(1f).scaleY(1f).setDuration(100).start();
                perform(a, buttonSide());
            }
        };

        private final Runnable resolveTaps = () -> {
            int n = tapCount;
            tapCount = 0;
            fireTaps(n);
        };

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            if (slop == 0) slop = ViewConfiguration.get(AssistiveTouchService.this).getScaledTouchSlop();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    handler.removeCallbacks(goIdle);
                    handler.removeCallbacks(resolveTaps);
                    if (snapAnim != null) snapAnim.cancel();
                    v.animate().cancel();
                    v.setAlpha(1f);
                    v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(80).start();
                    downX = e.getRawX();
                    downY = e.getRawY();
                    startX = bp.x;
                    startY = bp.y;
                    downTime = e.getEventTime();
                    dragging = false;
                    longFired = false;
                    if (Prefs.action(prefs, Prefs.KEY_LONG) != Action.NONE) {
                        handler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
                    }
                    return true;

                case MotionEvent.ACTION_MOVE:
                    if (longFired) return true;
                    float dx = e.getRawX() - downX;
                    float dy = e.getRawY() - downY;
                    if (!dragging && Math.hypot(dx, dy) > slop) {
                        dragging = true;
                        tapCount = 0;
                        handler.removeCallbacks(longPress);
                    }
                    if (dragging) {
                        bp.x = startX + (int) dx;
                        bp.y = startY + (int) dy;
                        safeUpdate();
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                    handler.removeCallbacks(longPress);
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                    scheduleIdle();
                    if (longFired) {
                        tapCount = 0;
                        return true;
                    }
                    if (dragging) {
                        handleDragEnd(e);
                    } else {
                        tapCount++;
                        if (tapCount >= maxTaps()) {
                            int n = tapCount;
                            tapCount = 0;
                            fireTaps(n);
                        } else {
                            handler.postDelayed(resolveTaps, ViewConfiguration.getDoubleTapTimeout());
                        }
                    }
                    return true;

                case MotionEvent.ACTION_CANCEL:
                    handler.removeCallbacks(longPress);
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                    if (dragging) snapToEdge();
                    tapCount = 0;
                    scheduleIdle();
                    return true;
            }
            return false;
        }

        private void handleDragEnd(MotionEvent e) {
            float dx = e.getRawX() - downX;
            float dy = e.getRawY() - downY;
            long dt = e.getEventTime() - downTime;
            String key;
            if (Math.abs(dx) > Math.abs(dy)) key = dx > 0 ? Prefs.KEY_SWIPE_RIGHT : Prefs.KEY_SWIPE_LEFT;
            else key = dy > 0 ? Prefs.KEY_SWIPE_DOWN : Prefs.KEY_SWIPE_UP;
            Action a = Prefs.action(prefs, key);

            if (dt <= FLICK_MAX_MS && Math.hypot(dx, dy) > dp(36) && a != Action.NONE) {
                // Quick flick: run the action and put the button back where it was.
                bp.x = startX;
                bp.y = startY;
                safeUpdate();
                if (button != null) button.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                perform(a, buttonSide());
            } else {
                snapToEdge();
            }
        }

        private int maxTaps() {
            if (Prefs.action(prefs, Prefs.KEY_TRIPLE) != Action.NONE) return 3;
            if (Prefs.action(prefs, Prefs.KEY_DOUBLE) != Action.NONE) return 2;
            return 1;
        }

        private void fireTaps(int n) {
            String key = n <= 1 ? Prefs.KEY_TAP : (n == 2 ? Prefs.KEY_DOUBLE : Prefs.KEY_TRIPLE);
            Action a = Prefs.action(prefs, key);
            if (a != Action.NONE) perform(a, buttonSide());
        }
    }

    // ================================================================ edge side bars

    private void rebuildEdges() {
        removeEdges();
        if (wm == null || prefs == null) return;
        if (Prefs.edgeEnabled(prefs, Prefs.LEFT)) addEdge(Prefs.LEFT, 0);
        if (Prefs.edgeEnabled(prefs, Prefs.RIGHT)) addEdge(Prefs.RIGHT, 1);
    }

    private void removeEdges() {
        for (int i = 0; i < edgeViews.length; i++) {
            if (edgeViews[i] != null) {
                try { wm.removeView(edgeViews[i]); } catch (Exception ignored) { }
                edgeViews[i] = null;
            }
        }
    }

    private void addEdge(String side, int index) {
        boolean left = Prefs.LEFT.equals(side);
        Point screen = screenSize();
        int len = Math.min(dp(Prefs.edgeInt(prefs, side, "len")), screen.y);
        int thick = dp(Prefs.edgeInt(prefs, side, "thick"));
        int pos = Prefs.edgeInt(prefs, side, "pos");
        float alpha = Prefs.edgeInt(prefs, side, "alpha") / 100f;

        boolean highlight = System.currentTimeMillis() < highlightUntil;
        EdgeHandleView v = new EdgeHandleView(this, left, alpha, highlight);
        WindowManager.LayoutParams lp = overlayParams(thick, len, false);
        lp.gravity = Gravity.TOP | (left ? Gravity.LEFT : Gravity.RIGHT);
        lp.x = 0;
        lp.y = Math.max(0, (screen.y - len) * pos / 100);
        v.setOnTouchListener(new EdgeTouch(side, left));
        try {
            wm.addView(v, lp);
            edgeViews[index] = v;
        } catch (Exception ignored) { }
    }

    private void setEdgesVisible(boolean visible) {
        for (View v : edgeViews) if (v != null) v.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
    }

    /** Tap, long press, swipe inward / up / down on an edge bar. */
    private class EdgeTouch implements View.OnTouchListener {
        private final String side;
        private final boolean left;
        private float x0, y0;
        private boolean moved, longFired;
        private View view;
        private int slop;

        private int tapCount;

        private final Runnable longPress = () -> {
            longFired = true;
            tapCount = 0;
            fire("long");
        };

        private final Runnable resolveTaps = () -> {
            int n = tapCount;
            tapCount = 0;
            fireTaps(n);
        };

        private int maxTaps() {
            if (Prefs.action(prefs, Prefs.edgeKey(side, "triple")) != Action.NONE) return 3;
            if (Prefs.action(prefs, Prefs.edgeKey(side, "double")) != Action.NONE) return 2;
            return 1;
        }

        private void fireTaps(int n) {
            fire(n <= 1 ? "tap" : (n == 2 ? "double" : "triple"));
        }

        EdgeTouch(String side, boolean left) {
            this.side = side;
            this.left = left;
        }

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            view = v;
            if (slop == 0) slop = ViewConfiguration.get(AssistiveTouchService.this).getScaledTouchSlop();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    handler.removeCallbacks(resolveTaps);
                    x0 = e.getRawX();
                    y0 = e.getRawY();
                    moved = false;
                    longFired = false;
                    if (Prefs.action(prefs, Prefs.edgeKey(side, "long")) != Action.NONE) {
                        handler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
                    }
                    return true;

                case MotionEvent.ACTION_MOVE:
                    if (!moved && Math.hypot(e.getRawX() - x0, e.getRawY() - y0) > slop) {
                        moved = true;
                        tapCount = 0;
                        handler.removeCallbacks(longPress);
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                    handler.removeCallbacks(longPress);
                    if (longFired) return true;
                    float inward = left ? e.getRawX() - x0 : x0 - e.getRawX();
                    float dy = e.getRawY() - y0;
                    int th = dp(30);
                    if (!moved) {
                        tapCount++;
                        if (tapCount >= maxTaps()) {
                            int n = tapCount;
                            tapCount = 0;
                            fireTaps(n);
                        } else {
                            handler.postDelayed(resolveTaps, ViewConfiguration.getDoubleTapTimeout());
                        }
                    } else if (inward > th && inward >= Math.abs(dy)) fire("in");
                    else if (Math.abs(dy) > th) fire(dy < 0 ? "up" : "down");
                    return true;

                case MotionEvent.ACTION_CANCEL:
                    handler.removeCallbacks(longPress);
                    return true;
            }
            return false;
        }

        private void fire(String name) {
            Action a = Prefs.action(prefs, Prefs.edgeKey(side, name));
            if (a == Action.NONE) return;
            if (view != null) view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            perform(a, left ? Gravity.LEFT : Gravity.RIGHT);
        }
    }

    // ================================================================ menu

    private void showMenu() {
        if (wm == null) return;
        hideSidebar();
        if (menuRoot != null) return;
        handler.removeCallbacks(goIdle);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0x33000000);
        root.setOnClickListener(v -> hideMenu());

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(10);
        panel.setPadding(pad, pad, pad, pad);
        panel.setBackground(roundRect(0xE61C1C1E, dp(32)));
        panel.setClickable(true);

        LinearLayout row = null;
        Action[] items = Action.MENU_ITEMS;
        for (int i = 0; i < items.length; i++) {
            if (i % 3 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_HORIZONTAL);
                panel.addView(row, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            }
            row.addView(makeMenuCell(items[i]));
        }

        root.addView(panel, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        try {
            wm.addView(root, overlayParams(WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT, true));
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

    private View makeMenuCell(Action a) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER);
        cell.setLayoutParams(new LinearLayout.LayoutParams(dp(86), dp(86)));
        cell.setBackground(ripple(dp(20)));

        ImageView icon = new ImageView(this);
        icon.setImageResource(a.icon);
        cell.addView(icon, new LinearLayout.LayoutParams(dp(30), dp(30)));
        cell.addView(cellLabel(getString(a.label)));

        cell.setContentDescription(getString(a.label));
        cell.setOnClickListener(v -> {
            int side = buttonSide();
            hideMenu();
            perform(a, side);
        });
        return cell;
    }

    private void hideMenu() {
        if (menuRoot == null) return;
        View r = menuRoot;
        menuRoot = null;
        try { wm.removeView(r); } catch (Exception ignored) { }
        restoreButton();
    }

    // ================================================================ app sidebar

    private void showSidebar(int gravity) {
        if (wm == null) return;
        hideMenu();
        if (sidebarRoot != null) {
            hideSidebar();
            return;
        }
        boolean left = gravity == Gravity.LEFT;

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0x55000000);
        root.setOnClickListener(v -> hideSidebar());

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setBackground(roundRect(0xF01C1C1E, dp(26)));
        scroll.setClickable(true);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        panel.setPadding(dp(6), dp(12), dp(6), dp(12));

        PackageManager pm = getPackageManager();
        List<String> apps = Prefs.sidebarApps(prefs);
        for (String entry : apps) {
            if (Prefs.isAction(entry)) {
                Action a = Prefs.actionOf(entry);
                if (a == Action.NONE || a.icon == 0) continue;
                panel.addView(makeSideCell(getDrawable(a.icon), getString(a.label), true, v -> {
                    hideSidebar();
                    perform(a, gravity);
                }));
                continue;
            }
            Drawable icon = Apps.icon(pm, entry);
            if (icon == null) continue;
            panel.addView(makeSideCell(icon, Apps.label(pm, entry), false, v -> {
                hideSidebar();
                launchApp(entry);
            }));
        }
        Drawable edit = getDrawable(apps.isEmpty() ? R.drawable.ic_add : R.drawable.ic_edit);
        panel.addView(makeSideCell(edit, apps.isEmpty() ? "Add apps" : "Edit", true, v -> {
            hideSidebar();
            openActivity(AppsActivity.class);
        }));
        scroll.addView(panel);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                dp(92), FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL | (left ? Gravity.LEFT : Gravity.RIGHT));
        lp.leftMargin = dp(8);
        lp.rightMargin = dp(8);
        lp.topMargin = dp(48);
        lp.bottomMargin = dp(48);
        root.addView(scroll, lp);

        try {
            wm.addView(root, overlayParams(WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT, true));
        } catch (Exception e) {
            return;
        }
        sidebarRoot = root;
        if (button != null) button.setVisibility(View.INVISIBLE);

        root.setAlpha(0f);
        root.animate().alpha(1f).setDuration(150).start();
        scroll.setTranslationX(left ? -dp(120) : dp(120));
        scroll.animate().translationX(0).setDuration(220)
                .setInterpolator(new DecelerateInterpolator()).start();
    }

    private View makeSideCell(Drawable icon, String label, boolean small, View.OnClickListener click) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER);
        cell.setPadding(dp(4), dp(8), dp(4), dp(8));
        cell.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        cell.setBackground(ripple(dp(16)));

        ImageView iv = new ImageView(this);
        iv.setImageDrawable(icon);
        int s = small ? dp(30) : dp(46);
        if (small) {
            // system action: white icon on a soft round background, same size as app icons
            iv.setPadding(dp(8), dp(8), dp(8), dp(8));
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(0x33FFFFFF);
            iv.setBackground(g);
            s = dp(46);
        }
        cell.addView(iv, new LinearLayout.LayoutParams(s, s));
        cell.addView(cellLabel(label));

        cell.setContentDescription(label);
        cell.setOnClickListener(click);
        return cell;
    }

    private void hideSidebar() {
        if (sidebarRoot == null) return;
        View r = sidebarRoot;
        sidebarRoot = null;
        try { wm.removeView(r); } catch (Exception ignored) { }
        restoreButton();
    }

    private void restoreButton() {
        if (button != null && menuRoot == null && sidebarRoot == null) {
            button.setVisibility(View.VISIBLE);
            wake();
        }
    }

    private void launchApp(String pkg) {
        Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) {
            Toast.makeText(this, "App not found", Toast.LENGTH_SHORT).show();
            return;
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "Can't open app", Toast.LENGTH_SHORT).show();
        }
    }

    private void openActivity(Class<?> cls) {
        Intent i = new Intent(this, cls);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
    }

    // ================================================================ actions

    private void perform(Action a, int side) {
        switch (a) {
            case MENU:
                showMenu();
                break;
            case SIDEBAR:
                showSidebar(side);
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
                openActivity(MainActivity.class);
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
        if (button != null) button.setVisibility(View.INVISIBLE);
        setEdgesVisible(false);
        handler.postDelayed(() -> {
            performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT);
            handler.postDelayed(() -> {
                setEdgesVisible(true);
                restoreButton();
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

    // ================================================================ helpers

    private WindowManager.LayoutParams overlayParams(int w, int h, boolean fullScreen) {
        int flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        if (!fullScreen) flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        return new WindowManager.LayoutParams(w, h,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, flags, PixelFormat.TRANSLUCENT);
    }

    private GradientDrawable roundRect(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    private RippleDrawable ripple(float radius) {
        return new RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), null, roundRect(Color.WHITE, radius));
    }

    private TextView cellLabel(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextColor(Color.WHITE);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        label.setGravity(Gravity.CENTER);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(5);
        label.setLayoutParams(lp);
        return label;
    }

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
