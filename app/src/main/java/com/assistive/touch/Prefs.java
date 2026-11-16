package com.assistive.touch;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;

public final class Prefs {
    public static final String NAME = "assistive_prefs";

    // Floating button
    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_SIZE = "size_dp";
    public static final String KEY_IDLE_ALPHA = "idle_alpha";
    public static final String KEY_TAP = "single_tap";
    public static final String KEY_DOUBLE = "double_tap";
    public static final String KEY_TRIPLE = "triple_tap";
    public static final String KEY_LONG = "long_press";
    public static final String KEY_SWIPE_UP = "swipe_up";
    public static final String KEY_SWIPE_DOWN = "swipe_down";
    public static final String KEY_SWIPE_LEFT = "swipe_left";
    public static final String KEY_SWIPE_RIGHT = "swipe_right";
    public static final String KEY_X = "pos_x";
    public static final String KEY_Y = "pos_y";

    // App sidebar
    public static final String KEY_SIDEBAR_APPS = "sidebar_apps";

    // Edge bars: keys look like edge_L_tap, edge_R_len ...
    public static final String EDGE_PREFIX = "edge_";
    public static final String LEFT = "L";
    public static final String RIGHT = "R";

    public static final int DEFAULT_SIZE = 56;       // dp
    public static final int DEFAULT_IDLE_ALPHA = 45; // percent

    private Prefs() {}

    public static SharedPreferences get(Context c) {
        return c.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static String edgeKey(String side, String name) {
        return EDGE_PREFIX + side + "_" + name;
    }

    public static boolean edgeEnabled(SharedPreferences p, String side) {
        return p.getBoolean(edgeKey(side, "enabled"), false);
    }

    public static int edgeInt(SharedPreferences p, String side, String name) {
        return p.getInt(edgeKey(side, name), edgeIntDefault(name));
    }

    public static int edgeIntDefault(String name) {
        switch (name) {
            case "len": return 160;   // dp
            case "thick": return 20;  // dp
            case "pos": return 40;    // % from top
            case "alpha": return 50;  // %
            default: return 0;
        }
    }

    public static String defaultActionId(String key) {
        if (KEY_TAP.equals(key)) return Action.MENU.id;
        if (key.startsWith(EDGE_PREFIX) && (key.endsWith("_tap") || key.endsWith("_in"))) {
            return Action.SIDEBAR.id;
        }
        return Action.NONE.id;
    }

    public static Action action(SharedPreferences p, String key) {
        return Action.fromId(p.getString(key, defaultActionId(key)));
    }

    public static List<String> sidebarApps(SharedPreferences p) {
        List<String> out = new ArrayList<>();
        String s = p.getString(KEY_SIDEBAR_APPS, "");
        for (String pkg : s.split(",")) {
            if (!pkg.trim().isEmpty()) out.add(pkg.trim());
        }
        return out;
    }

    public static void saveSidebarApps(SharedPreferences p, List<String> apps) {
        p.edit().putString(KEY_SIDEBAR_APPS, TextUtils.join(",", apps)).apply();
    }
}
