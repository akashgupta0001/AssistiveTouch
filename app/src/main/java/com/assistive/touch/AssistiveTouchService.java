package com.assistive.touch;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
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
import android.view.KeyEvent;
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

    private final ButtonHolder[] buttons = new ButtonHolder[Prefs.BUTTON_COUNT];
    private View menuRoot;
    private View sidebarRoot;
    private int menuSide = Gravity.RIGHT;
    private boolean capturing;
    private final View[] edgeViews = new View[2]; // 0 = left, 1 = right
    private long highlightUntil;
    private final Runnable endHighlight = this::rebuildEdges;

    private CameraManager cameraManager;
    private String torchId;
    private boolean torchOn;

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
        syncButtons();
        rebuildEdges();
        KeepAliveService.start(this);
    }

    /** Exit: remove everything and switch this accessibility service off. */
    public void exit() {
        cleanup();
        KeepAliveService.stop(this);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) disableSelf();
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
        for (int i = 0; i < buttons.length; i++) removeButton(i);
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
            for (ButtonHolder h : buttons) if (h != null) h.snapToEdge();
            rebuildEdges();
        }, 300);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
        if (key == null) return;
        if (key.startsWith(Prefs.EDGE_PREFIX)) {
            if (key.endsWith("_len") || key.endsWith("_thick") || key.endsWith("_pos")
                    || key.endsWith("_alpha") || key.endsWith("_enabled") || key.equals(Prefs.KEY_EDGE_MASTER)) {
                // Show the bar strongly for a moment so its size and position are easy to see.
                highlightUntil = System.currentTimeMillis() + 2500;
                handler.removeCallbacks(endHighlight);
                handler.postDelayed(endHighlight, 2600);
            }
            rebuildEdges();
            return;
        }
        if (key.equals(Prefs.KEY_BUTTONS_MASTER)) {
            syncButtons();
            return;
        }
        int index = Prefs.buttonIndexOfKey(key);
        String base = Prefs.baseKey(key);
        ButtonHolder h = buttons[index];
        switch (base) {
            case Prefs.KEY_ENABLED:
                syncButtons();
                break;
            case Prefs.KEY_SIZE:
                if (h != null) h.resize();
                break;
            case Prefs.KEY_IDLE_ALPHA:
                if (h != null) h.wake();
                break;
            case Prefs.KEY_BG_COLOR:
            case Prefs.KEY_BG_ALPHA:
            case Prefs.KEY_ICON_COLOR:
            case Prefs.KEY_SHAPE:
            case Prefs.KEY_PHOTO:
                if (h != null) {
                    h.view.loadStyle();
                    h.wake();
                }
                break;
            default:
                break;
        }
    }

    /** Called by the Quick Settings tile: close the panel, then show the app sidebar. */
    public void openSidebarFromTile() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE);
        }
        handler.postDelayed(() -> showSidebar(Gravity.RIGHT), 400);
    }

    // ================================================================ floating buttons

    private void syncButtons() {
        if (wm == null || prefs == null) return;
        for (int i = 0; i < buttons.length; i++) {
            boolean want = Prefs.buttonEnabled(prefs, i);
            if (want && buttons[i] == null) addButton(i);
            else if (!want && buttons[i] != null) removeButton(i);
        }
        if (menuRoot == null && sidebarRoot == null) return;
        setButtonsVisible(false);
    }

    private void addButton(int index) {
        ButtonHolder h = new ButtonHolder(index);
        if (h.attach()) buttons[index] = h;
    }

    private void removeButton(int index) {
        if (buttons[index] != null) {
            buttons[index].detach();
            buttons[index] = null;
        }
    }

    private void setButtonsVisible(boolean visible) {
        for (ButtonHolder h : buttons) {
            if (h != null) h.view.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
        }
    }

    /** One floating button with its own position, look and actions. */
    private class ButtonHolder implements View.OnTouchListener {
        final int index;
        final FloatingButtonView view;
        final WindowManager.LayoutParams lp;
        private ValueAnimator snapAnim;

        private float downX, downY;
        private int startX, startY;
        private long downTime;
        private boolean dragging, longFired;
        private int tapCount;
        private int slop;

        private final Runnable goIdle = new Runnable() {
            @Override
            public void run() {
                view.animate().alpha(idleAlpha()).setDuration(400).start();
            }
        };

        private final Runnable longPress = new Runnable() {
            @Override
            public void run() {
                longFired = true;
                Action a = action(Prefs.KEY_LONG);
                if (a != Action.NONE) {
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    view.animate().scaleX(1f).scaleY(1f).setDuration(100).start();
                    ButtonHolder.this.run(a);
                }
            }
        };

        private final Runnable resolveTaps = new Runnable() {
            @Override
            public void run() {
                int n = tapCount;
                tapCount = 0;
                fireTaps(n);
            }
        };

        ButtonHolder(int index) {
            this.index = index;
            this.view = new FloatingButtonView(AssistiveTouchService.this, index);
            int size = dp(prefs.getInt(key(Prefs.KEY_SIZE), Prefs.DEFAULT_SIZE));
            Point screen = screenSize();
            lp = overlayParams(size, size, false);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = prefs.getInt(key(Prefs.KEY_X), screen.x - size - dp(4));
            lp.y = prefs.getInt(key(Prefs.KEY_Y), screen.y / 3 + index * (size + dp(16)));
            clamp(screen);
            view.setOnTouchListener(this);
        }

        String key(String base) {
            return Prefs.bkey(index, base);
        }

        Action action(String base) {
            return Prefs.action(prefs, key(base));
        }

        boolean attach() {
            try {
                wm.addView(view, lp);
            } catch (Exception e) {
                return false;
            }
            view.setAlpha(0f);
            view.animate().alpha(1f).setDuration(200).start();
            scheduleIdle();
            return true;
        }

        void detach() {
            if (snapAnim != null) snapAnim.cancel();
            handler.removeCallbacks(goIdle);
            handler.removeCallbacks(longPress);
            handler.removeCallbacks(resolveTaps);
            try { wm.removeView(view); } catch (Exception ignored) { }
        }

        void update() {
            if (view.isAttachedToWindow()) {
                try { wm.updateViewLayout(view, lp); } catch (Exception ignored) { }
            }
        }

        void resize() {
            int s = dp(prefs.getInt(key(Prefs.KEY_SIZE), Prefs.DEFAULT_SIZE));
            lp.width = s;
            lp.height = s;
            update();
            snapToEdge();
            wake();
        }

        float idleAlpha() {
            return prefs.getInt(key(Prefs.KEY_IDLE_ALPHA), Prefs.DEFAULT_IDLE_ALPHA) / 100f;
        }

        void scheduleIdle() {
            handler.removeCallbacks(goIdle);
            handler.postDelayed(goIdle, IDLE_DELAY_MS);
        }

        void wake() {
            view.animate().cancel();
            view.setAlpha(1f);
            scheduleIdle();
        }

        void clamp(Point screen) {
            int m = dp(4);
            lp.x = Math.max(m, Math.min(lp.x, screen.x - lp.width - m));
            lp.y = Math.max(m, Math.min(lp.y, screen.y - lp.height - m));
        }

        void snapToEdge() {
            Point screen = screenSize();
            clamp(screen);
            int m = dp(4);
            int target = (lp.x + lp.width / 2 < screen.x / 2) ? m : screen.x - lp.width - m;
            if (snapAnim != null) snapAnim.cancel();
            snapAnim = ValueAnimator.ofInt(lp.x, target);
            snapAnim.setDuration(260);
            snapAnim.setInterpolator(new DecelerateInterpolator());
            snapAnim.addUpdateListener(a -> {
                lp.x = (int) a.getAnimatedValue();
                update();
            });
            snapAnim.start();
            prefs.edit().putInt(key(Prefs.KEY_X), target).putInt(key(Prefs.KEY_Y), lp.y).apply();
        }

        int side() {
            return (lp.x + lp.width / 2 < screenSize().x / 2) ? Gravity.LEFT : Gravity.RIGHT;
        }

        void run(Action a) {
            menuSide = side();
            perform(a, menuSide);
        }

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
                    startX = lp.x;
                    startY = lp.y;
                    downTime = e.getEventTime();
                    dragging = false;
                    longFired = false;
                    if (action(Prefs.KEY_LONG) != Action.NONE) {
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
                        lp.x = startX + (int) dx;
                        lp.y = startY + (int) dy;
                        update();
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
                        dragEnd(e);
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

        private void dragEnd(MotionEvent e) {
            float dx = e.getRawX() - downX;
            float dy = e.getRawY() - downY;
            long dt = e.getEventTime() - downTime;
            String base;
            if (Math.abs(dx) > Math.abs(dy)) base = dx > 0 ? Prefs.KEY_SWIPE_RIGHT : Prefs.KEY_SWIPE_LEFT;
            else base = dy > 0 ? Prefs.KEY_SWIPE_DOWN : Prefs.KEY_SWIPE_UP;
            Action a = action(base);
            if (dt <= FLICK_MAX_MS && Math.hypot(dx, dy) > dp(36) && a != Action.NONE) {
                lp.x = startX;
                lp.y = startY;
                update();
                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                run(a);
            } else {
                snapToEdge();
            }
        }

        private int maxTaps() {
            if (action(Prefs.KEY_TRIPLE) != Action.NONE) return 3;
            if (action(Prefs.KEY_DOUBLE) != Action.NONE) return 2;
            return 1;
        }

        private void fireTaps(int n) {
            String base = n <= 1 ? Prefs.KEY_TAP : (n == 2 ? Prefs.KEY_DOUBLE : Prefs.KEY_TRIPLE);
            Action a = action(base);
            if (a != Action.NONE) run(a);
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
        setButtonsVisible(false);

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
            int side = menuSide;
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
        setButtonsVisible(false);

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
        if (menuRoot == null && sidebarRoot == null && !capturing) {
            setButtonsVisible(true);
            for (ButtonHolder h : buttons) if (h != null) h.wake();
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
        menuSide = side;
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
                openControlCentre();
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
            case EXIT:
                KeepAliveService.exitEverything(this);
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
        hideForCapture(1800);
        handler.postDelayed(() -> performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT), 350);
    }

    /** Hide all floating buttons and side bars for a moment so they are not in a screenshot. */
    private void hideForCapture(long ms) {
        capturing = true;
        setButtonsVisible(false);
        setEdgesVisible(false);
        handler.removeCallbacks(endCapture);
        handler.postDelayed(endCapture, ms);
    }

    private final Runnable endCapture = () -> {
        capturing = false;
        setEdgesVisible(true);
        restoreButton();
    };

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        // Power + Volume Down screenshot: hide as soon as Volume Down is pressed.
        if (event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_DOWN
                && event.getAction() == KeyEvent.ACTION_DOWN
                && prefs != null && prefs.getBoolean(Prefs.KEY_HIDE_ON_VOLUME, true)) {
            hideForCapture(2000);
        }
        return false; // never block the key
    }

    private static boolean isXiaomi() {
        String m = (Build.MANUFACTURER + " " + Build.BRAND).toLowerCase();
        return m.contains("xiaomi") || m.contains("redmi") || m.contains("poco");
    }

    /**
     * Xiaomi HyperOS / MIUI keep the Control Centre separate from notifications, and the
     * standard "quick settings" action opens notifications there. So on Xiaomi we perform
     * the same swipe a finger would: down from the top-right corner.
     */
    private void openControlCentre() {
        if (!isXiaomi()) {
            performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS);
            return;
        }
        Point screen = screenSize();
        float x = screen.x * 0.92f;
        Path path = new Path();
        path.moveTo(x, 2);
        path.lineTo(x, screen.y * 0.55f);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 280))
                .build();
        // small delay so a closing menu / sidebar doesn't get in the way
        handler.postDelayed(() -> {
            boolean sent = dispatchGesture(g, new GestureResultCallback() {
                @Override
                public void onCancelled(GestureDescription d) {
                    performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS);
                }
            }, null);
            if (!sent) performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS);
        }, 120);
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
