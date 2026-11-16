package com.assistive.touch;

import android.content.Context;
import android.content.SharedPreferences;

public final class Prefs {
    public static final String NAME = "assistive_prefs";
    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_SIZE = "size_dp";
    public static final String KEY_IDLE_ALPHA = "idle_alpha";
    public static final String KEY_DOUBLE = "double_tap";
    public static final String KEY_LONG = "long_press";
    public static final String KEY_X = "pos_x";
    public static final String KEY_Y = "pos_y";

    public static final int DEFAULT_SIZE = 56;      // dp
    public static final int DEFAULT_IDLE_ALPHA = 45; // percent

    private Prefs() {}

    public static SharedPreferences get(Context c) {
        return c.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }
}
