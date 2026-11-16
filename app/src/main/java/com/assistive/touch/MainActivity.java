package com.assistive.touch;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
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
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.Toast;
import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Settings screen, built in code. */
public class MainActivity extends Activity {

    private SharedPreferences prefs;
    private TextView status;
    private Button btnEnable;
    private TextView sidebarCount;
    private FloatingButtonView preview;
    private LinearLayout buttonSection;
    private final Button[] tabButtons = new Button[Prefs.BUTTON_COUNT];
    private int currentButton;
    private boolean askedNotif;
    private final List<Runnable> refreshers = new ArrayList<>();
    private static final int REQ_PHOTO = 41;

    private static final int[] PALETTE = {
            0xFF1E1E1E, 0xFF000000, 0xFFFFFFFF, 0xFF8E8E93, 0xFF007AFF, 0xFF5856D6,
            0xFFAF52DE, 0xFFFF2D55, 0xFFFF3B30, 0xFFFF9500, 0xFFFFCC00, 0xFF34C759, 0xFF30B0C7
    };

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

        // ---- keep running
        root.addView(header("Keep running in background"));
        LinearLayout kr = card(root);
        kr.addView(note("Xiaomi / Redmi / POCO phones close apps when you tap \"Clear all\". "
                + "Do these 3 steps once so AssistiveTouch never stops:"));
        kr.addView(actionButton("1. Allow Autostart", this::openAutostart));
        kr.addView(actionButton("2. Battery saver → No restrictions", this::openBatterySettings));
        kr.addView(note("3. Open Recents, then long-press (or pull down) the AssistiveTouch card and tap the 🔒 lock icon. "
                + "Locked apps are skipped by \"Clear all\"."));
        kr.addView(note("If the buttons ever disappear, open this app: if it says OFF, turn it on again in Accessibility."));
        Button exit = actionButton("Exit AssistiveTouch", () -> {
            KeepAliveService.exitEverything(this);
            Toast.makeText(this, "AssistiveTouch turned off", Toast.LENGTH_SHORT).show();
            status.postDelayed(this::onResume, 500);
        });
        exit.setTextColor(0xFFFF3B30);
        kr.addView(exit);

        // ---- floating buttons
        root.addView(header("Floating buttons"));
        LinearLayout gm = card(root);
        switchRow(gm, "Floating buttons on (master switch)", Prefs.KEY_BUTTONS_MASTER, true);
        switchRow(gm, "Hide buttons in Power + Volume Down screenshots", Prefs.KEY_HIDE_ON_VOLUME, true);
        gm.addView(note("The Screenshot action always hides all buttons and side bars first. "
                + "Hiding also happens for a moment whenever Volume Down is pressed."));

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setPadding(0, dp(12), 0, 0);
        for (int i = 0; i < Prefs.BUTTON_COUNT; i++) {
            final int idx = i;
            Button t = new Button(this);
            t.setText("Button " + (i + 1));
            t.setAllCaps(false);
            t.setOnClickListener(v -> selectButton(idx));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(44), 1f);
            lp.leftMargin = dp(3);
            lp.rightMargin = dp(3);
            tabs.addView(t, lp);
            tabButtons[i] = t;
        }
        root.addView(tabs);
        buttonSection = new LinearLayout(this);
        buttonSection.setOrientation(LinearLayout.VERTICAL);
        root.addView(buttonSection);
        selectButton(0);

        // ---- quick settings tiles
        root.addView(header("Quick Settings tiles"));
        LinearLayout qs = card(root);
        qs.addView(note("Pull down Quick Settings, tap the pencil / Edit icon and drag these tiles in: "
                + "\"Floating buttons\" (on/off), \"Side bars\" (on/off) and \"App Sidebar\" (opens your apps)."));

        // ---- edge bars
        root.addView(header("Side bars"));
        LinearLayout master = card(root);
        switchRow(master, "Side bars on (master switch)", Prefs.KEY_EDGE_MASTER, true);
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
        manage.setText("Add / edit / remove apps & actions");
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
        if (on) KeepAliveService.start(this);
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED && !askedNotif) {
            askedNotif = true;
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 5);
        }
        int n = Prefs.sidebarApps(prefs).size();
        sidebarCount.setText(n == 1 ? "1 item in sidebar" : n + " items in sidebar");
        for (Runnable r : refreshers) r.run();
        if (preview != null) preview.loadStyle();
    }

    // ------------------------------------------------------------ appearance

    /** Show the settings of one floating button (1, 2 or 3). */
    private void selectButton(int index) {
        currentButton = index;
        for (int i = 0; i < tabButtons.length; i++) {
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(10));
            g.setColor(i == index ? 0xFF3A5BA9 : 0xFFFFFFFF);
            tabButtons[i].setBackground(g);
            tabButtons[i].setTextColor(i == index ? Color.WHITE : 0xFF3A5BA9);
        }
        buttonSection.removeAllViews();
        String n = "Button " + (index + 1);

        buttonSection.addView(header(n + " — actions"));
        LinearLayout fb = card(buttonSection);
        switchRow(fb, "Show " + n, Prefs.bkey(index, Prefs.KEY_ENABLED), index == 0);
        seekRow(fb, "Button size", Prefs.bkey(index, Prefs.KEY_SIZE), 40, 80, Prefs.DEFAULT_SIZE, "%d dp");
        seekRow(fb, "Idle opacity", Prefs.bkey(index, Prefs.KEY_IDLE_ALPHA), 20, 100, Prefs.DEFAULT_IDLE_ALPHA, "%d%%");
        fb.addView(note("Swipe = a quick flick on the button. A slow drag moves the button."));
        actionRow(fb, "Single tap", Prefs.bkey(index, Prefs.KEY_TAP));
        actionRow(fb, "Double tap", Prefs.bkey(index, Prefs.KEY_DOUBLE));
        actionRow(fb, "Triple tap", Prefs.bkey(index, Prefs.KEY_TRIPLE));
        actionRow(fb, "Long press", Prefs.bkey(index, Prefs.KEY_LONG));
        actionRow(fb, "Swipe up", Prefs.bkey(index, Prefs.KEY_SWIPE_UP));
        actionRow(fb, "Swipe down", Prefs.bkey(index, Prefs.KEY_SWIPE_DOWN));
        actionRow(fb, "Swipe left", Prefs.bkey(index, Prefs.KEY_SWIPE_LEFT));
        actionRow(fb, "Swipe right", Prefs.bkey(index, Prefs.KEY_SWIPE_RIGHT));

        appearanceSection(buttonSection, index);
    }

    private void appearanceSection(LinearLayout root, int index) {
        root.addView(header("Button " + (index + 1) + " — appearance"));
        LinearLayout c = card(root);

        FrameLayout box = new FrameLayout(this);
        box.setPadding(0, dp(10), 0, dp(10));
        GradientDrawable boxBg = new GradientDrawable();
        boxBg.setColor(0xFFB0B4BA);
        boxBg.setCornerRadius(dp(12));
        box.setBackground(boxBg);
        preview = new FloatingButtonView(this, index);
        box.addView(preview, new FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER));
        c.addView(box, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(96)));

        LinearLayout photoRow = new LinearLayout(this);
        photoRow.setOrientation(LinearLayout.HORIZONTAL);
        Button pick = new Button(this);
        pick.setText("Choose photo");
        pick.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT).setType("image/*");
            try {
                startActivityForResult(Intent.createChooser(i, "Choose photo"), REQ_PHOTO);
            } catch (Exception e) {
                Toast.makeText(this, "No gallery app found", Toast.LENGTH_SHORT).show();
            }
        });
        Button clear = new Button(this);
        clear.setText("Remove photo");
        clear.setOnClickListener(v -> {
            FloatingButtonView.photoFile(this, index).delete();
            prefs.edit().putLong(Prefs.bkey(index, Prefs.KEY_PHOTO), 0).apply();
            preview.loadStyle();
        });
        photoRow.addView(pick, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        photoRow.addView(clear, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        c.addView(photoRow);

        colorRow(c, "Button colour", Prefs.bkey(index, Prefs.KEY_BG_COLOR), Prefs.DEFAULT_BG_COLOR);
        colorRow(c, "Icon colour", Prefs.bkey(index, Prefs.KEY_ICON_COLOR), Prefs.DEFAULT_ICON_COLOR);
        seekRow(c, "Button colour opacity", Prefs.bkey(index, Prefs.KEY_BG_ALPHA), 10, 100, Prefs.DEFAULT_BG_ALPHA, "%d%%");

        Switch round = new Switch(this);
        round.setText("Circle shape");
        round.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        round.setPadding(0, dp(10), 0, dp(10));
        round.setChecked("circle".equals(prefs.getString(Prefs.bkey(index, Prefs.KEY_SHAPE), "rounded")));
        round.setOnCheckedChangeListener((b, on) -> {
            prefs.edit().putString(Prefs.bkey(index, Prefs.KEY_SHAPE), on ? "circle" : "rounded").apply();
            preview.loadStyle();
        });
        c.addView(round);
        c.addView(note("Colours are used when no photo is set."));
    }

    private void colorRow(LinearLayout parent, String label, String key, int def) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setPadding(0, dp(10), 0, dp(6));
        parent.addView(t);

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        hs.addView(row);
        parent.addView(hs);

        List<View> swatches = new ArrayList<>();
        Runnable paint = () -> {
            int cur = prefs.getInt(key, def);
            for (int i = 0; i < swatches.size(); i++) {
                GradientDrawable g = new GradientDrawable();
                g.setShape(GradientDrawable.OVAL);
                g.setColor(PALETTE[i]);
                boolean sel = (PALETTE[i] & 0xFFFFFF) == (cur & 0xFFFFFF);
                g.setStroke(sel ? dp(3) : dp(1), sel ? 0xFF3A5BA9 : 0xFFC7C7CC);
                swatches.get(i).setBackground(g);
            }
        };
        for (int color : PALETTE) {
            View sw = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(34), dp(34));
            lp.rightMargin = dp(8);
            sw.setOnClickListener(v -> {
                prefs.edit().putInt(key, color).apply();
                paint.run();
                preview.loadStyle();
            });
            row.addView(sw, lp);
            swatches.add(sw);
        }
        paint.run();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PHOTO || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Intent crop = new Intent(this, CropActivity.class);
        crop.setData(data.getData());
        crop.putExtra("index", currentButton);
        crop.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(crop);
    }

    private void edgeSection(LinearLayout root, String side, String title) {
        root.addView(header(title));
        LinearLayout c = card(root);
        switchRow(c, "Enable " + title.toLowerCase(), Prefs.edgeKey(side, "enabled"), false);
        actionRow(c, "Single tap", Prefs.edgeKey(side, "tap"));
        actionRow(c, "Double tap", Prefs.edgeKey(side, "double"));
        actionRow(c, "Triple tap", Prefs.edgeKey(side, "triple"));
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
        s.setOnCheckedChangeListener((b, checked) -> {
            if (prefs.getBoolean(key, def) != checked) prefs.edit().putBoolean(key, checked).apply();
        });
        refreshers.add(() -> s.setChecked(prefs.getBoolean(key, def)));
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
                if (user) {
                    prefs.edit().putInt(key, min + p).apply();
                    if (preview != null) preview.loadStyle();
                }
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

    private Button actionButton(String text, Runnable r) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(v -> r.run());
        return b;
    }

    private boolean tryStart(Intent i) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void openAutostart() {
        Intent mi = new Intent().setComponent(new ComponentName("com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"));
        if (tryStart(mi)) return;
        Toast.makeText(this, "Turn on Autostart / Background activity for AssistiveTouch here", Toast.LENGTH_LONG).show();
        tryStart(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
    }

    private void openBatterySettings() {
        Intent mi = new Intent("miui.intent.action.HIDDEN_APPS_CONFIG_ACTIVITY");
        mi.putExtra("package_name", getPackageName());
        mi.putExtra("package_label", getString(R.string.app_name));
        if (tryStart(mi)) {
            Toast.makeText(this, "Choose \"No restrictions\"", Toast.LENGTH_LONG).show();
            return;
        }
        Intent std = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + getPackageName()));
        if (tryStart(std)) return;
        tryStart(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
