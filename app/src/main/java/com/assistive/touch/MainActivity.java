package com.assistive.touch;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

/** Settings screen, built in code. */
public class MainActivity extends Activity {

    private SharedPreferences prefs;
    private TextView status;
    private Button btnEnable;
    private TextView sidebarCount;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = Prefs.get(this);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFFF2F2F7);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(32));
        scroll.addView(root);
        setContentView(scroll);

        // ---- status
        status = new TextView(this);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        status.setTypeface(Typeface.DEFAULT_BOLD);
        status.setPadding(0, 0, 0, dp(8));
        root.addView(status);

        btnEnable = new Button(this);
        btnEnable.setText(R.string.btn_enable);
        btnEnable.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(btnEnable);

        TextView hint = note(getString(R.string.restricted_hint));
        root.addView(hint);
        Button appInfo = new Button(this);
        appInfo.setText(R.string.btn_app_info);
        appInfo.setOnClickListener(v -> startActivity(new Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))));
        root.addView(appInfo);

        // ---- floating button
        root.addView(header("Floating button"));
        LinearLayout fb = card(root);
        switchRow(fb, "Show floating button", Prefs.KEY_ENABLED, true);
        seekRow(fb, "Button size", Prefs.KEY_SIZE, 40, 80, Prefs.DEFAULT_SIZE, "%d dp");
        seekRow(fb, "Idle opacity", Prefs.KEY_IDLE_ALPHA, 20, 100, Prefs.DEFAULT_IDLE_ALPHA, "%d%%");
        fb.addView(note("Swipe = a quick flick on the button. A slow drag moves the button."));
        actionRow(fb, "Single tap", Prefs.KEY_TAP);
        actionRow(fb, "Double tap", Prefs.KEY_DOUBLE);
        actionRow(fb, "Triple tap", Prefs.KEY_TRIPLE);
        actionRow(fb, "Long press", Prefs.KEY_LONG);
        actionRow(fb, "Swipe up", Prefs.KEY_SWIPE_UP);
        actionRow(fb, "Swipe down", Prefs.KEY_SWIPE_DOWN);
        actionRow(fb, "Swipe left", Prefs.KEY_SWIPE_LEFT);
        actionRow(fb, "Swipe right", Prefs.KEY_SWIPE_RIGHT);

        // ---- edge bars
        edgeSection(root, Prefs.LEFT, "Left side bar");
        edgeSection(root, Prefs.RIGHT, "Right side bar");

        // ---- app sidebar
        root.addView(header("App sidebar (shortcuts)"));
        LinearLayout sb = card(root);
        sidebarCount = new TextView(this);
        sidebarCount.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        sb.addView(sidebarCount);
        sb.addView(note("Choose \"App Sidebar\" as the action for any tap, swipe or side bar to open it."));
        Button manage = new Button(this);
        manage.setText("Add / edit / remove apps");
        manage.setOnClickListener(v -> startActivity(new Intent(this, AppsActivity.class)));
        sb.addView(manage);
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean on = isServiceEnabled();
        status.setText(on ? R.string.status_on : R.string.status_off);
        status.setTextColor(on ? Color.rgb(52, 199, 89) : Color.rgb(255, 59, 48));
        btnEnable.setVisibility(on ? View.GONE : View.VISIBLE);
        int n = Prefs.sidebarApps(prefs).size();
        sidebarCount.setText(n == 1 ? "1 app in sidebar" : n + " apps in sidebar");
    }

    private void edgeSection(LinearLayout root, String side, String title) {
        root.addView(header(title));
        LinearLayout c = card(root);
        switchRow(c, "Enable " + title.toLowerCase(), Prefs.edgeKey(side, "enabled"), false);
        actionRow(c, "Tap", Prefs.edgeKey(side, "tap"));
        actionRow(c, "Long press", Prefs.edgeKey(side, "long"));
        actionRow(c, "Swipe inward", Prefs.edgeKey(side, "in"));
        actionRow(c, "Swipe up", Prefs.edgeKey(side, "up"));
        actionRow(c, "Swipe down", Prefs.edgeKey(side, "down"));
        seekRow(c, "Length", Prefs.edgeKey(side, "len"), 60, 400, Prefs.edgeIntDefault("len"), "%d dp");
        seekRow(c, "Thickness (touch area)", Prefs.edgeKey(side, "thick"), 8, 60, Prefs.edgeIntDefault("thick"), "%d dp");
        seekRow(c, "Vertical position", Prefs.edgeKey(side, "pos"), 0, 100, Prefs.edgeIntDefault("pos"), "%d%% from top");
        seekRow(c, "Bar opacity", Prefs.edgeKey(side, "alpha"), 0, 100, Prefs.edgeIntDefault("alpha"), "%d%%");
    }

    // ------------------------------------------------------------ row builders

    private TextView header(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(0xFF3A5BA9);
        t.setPadding(dp(4), dp(20), 0, dp(6));
        return t;
    }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14), dp(8), dp(14), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(14));
        c.setBackground(bg);
        parent.addView(c);
        return c;
    }

    private TextView note(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(0xFF6C6C70);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setPadding(0, dp(8), 0, dp(4));
        return t;
    }

    private void switchRow(LinearLayout parent, String label, String key, boolean def) {
        Switch s = new Switch(this);
        s.setText(label);
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        s.setPadding(0, dp(10), 0, dp(10));
        s.setChecked(prefs.getBoolean(key, def));
        s.setOnCheckedChangeListener((b, checked) -> prefs.edit().putBoolean(key, checked).apply());
        parent.addView(s);
    }

    private void seekRow(LinearLayout parent, String label, String key, int min, int max, int def, String fmt) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setPadding(0, dp(10), 0, 0);
        SeekBar bar = new SeekBar(this);
        bar.setMax(max - min);
        int value = Math.max(min, Math.min(max, prefs.getInt(key, def)));
        bar.setProgress(value - min);
        t.setText(label + ": " + String.format(fmt, value));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                t.setText(label + ": " + String.format(fmt, min + p));
                if (user) prefs.edit().putInt(key, min + p).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        parent.addView(t);
        parent.addView(bar);
    }

    private void actionRow(LinearLayout parent, String label, String key) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(4));

        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        row.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Spinner spinner = new Spinner(this);
        Action[] choices = Action.GESTURE_CHOICES;
        String[] labels = new String[choices.length];
        Action current = Prefs.action(prefs, key);
        int selected = 0;
        for (int i = 0; i < choices.length; i++) {
            labels[i] = getString(choices[i].label);
            if (choices[i] == current) selected = i;
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(selected);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (Prefs.action(prefs, key) != choices[pos]) {
                    prefs.edit().putString(key, choices[pos].id).apply();
                }
            }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        });
        row.addView(spinner, new LinearLayout.LayoutParams(dp(180), LinearLayout.LayoutParams.WRAP_CONTENT));
        parent.addView(row);
    }

    // ------------------------------------------------------------ helpers

    private boolean isServiceEnabled() {
        if (AssistiveTouchService.instance != null) return true;
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        ComponentName cn = new ComponentName(this, AssistiveTouchService.class);
        return enabled.contains(cn.flattenToString()) || enabled.contains(cn.flattenToShortString());
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
