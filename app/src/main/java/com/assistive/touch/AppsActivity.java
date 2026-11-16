package com.assistive.touch;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Add, reorder and remove the apps shown in the sidebar. */
public class AppsActivity extends Activity {

    private SharedPreferences prefs;
    private LinearLayout list;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Sidebar apps");
        prefs = Prefs.get(this);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFFF2F2F7);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(32));
        scroll.addView(root);
        setContentView(scroll);

        Button add = new Button(this);
        add.setText("+ Add apps");
        add.setOnClickListener(v -> showPicker());
        root.addView(add);

        TextView help = new TextView(this);
        help.setText("Use ▲ ▼ to change the order and ✕ to remove an app.");
        help.setTextColor(0xFF6C6C70);
        help.setPadding(dp(4), dp(8), 0, dp(8));
        root.addView(help);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(14));
        list.setBackground(bg);
        list.setPadding(dp(8), dp(4), dp(4), dp(4));
        root.addView(list);

        render();
    }

    private void render() {
        list.removeAllViews();
        PackageManager pm = getPackageManager();
        List<String> apps = Prefs.sidebarApps(prefs);
        if (apps.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No apps yet. Tap \"+ Add apps\".");
            empty.setPadding(dp(8), dp(16), dp(8), dp(16));
            list.addView(empty);
            return;
        }
        for (int i = 0; i < apps.size(); i++) {
            final int index = i;
            String pkg = apps.get(i);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(4), 0, dp(4));

            ImageView icon = new ImageView(this);
            Drawable d = Apps.icon(pm, pkg);
            if (d != null) icon.setImageDrawable(d);
            row.addView(icon, new LinearLayout.LayoutParams(dp(40), dp(40)));

            TextView label = new TextView(this);
            label.setText(Apps.label(pm, pkg));
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            label.setPadding(dp(12), 0, dp(4), 0);
            label.setSingleLine(true);
            row.addView(label, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            row.addView(smallButton("▲", () -> move(index, -1)));
            row.addView(smallButton("▼", () -> move(index, 1)));
            row.addView(smallButton("✕", () -> remove(index)));
            list.addView(row);
        }
    }

    private Button smallButton(String text, Runnable action) {
        Button b = new Button(this, null, android.R.attr.borderlessButtonStyle);
        b.setText(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(10), 0, dp(10), 0);
        b.setOnClickListener(v -> action.run());
        return b;
    }

    private void move(int index, int delta) {
        List<String> apps = Prefs.sidebarApps(prefs);
        int to = index + delta;
        if (to < 0 || to >= apps.size()) return;
        String item = apps.remove(index);
        apps.add(to, item);
        Prefs.saveSidebarApps(prefs, apps);
        render();
    }

    private void remove(int index) {
        List<String> apps = Prefs.sidebarApps(prefs);
        if (index < apps.size()) apps.remove(index);
        Prefs.saveSidebarApps(prefs, apps);
        render();
    }

    private void showPicker() {
        Map<String, String> all = Apps.launchable(getPackageManager(), getPackageName());
        List<String> pkgs = new ArrayList<>(all.keySet());
        String[] labels = all.values().toArray(new String[0]);
        List<String> current = Prefs.sidebarApps(prefs);
        boolean[] checked = new boolean[pkgs.size()];
        for (int i = 0; i < pkgs.size(); i++) checked[i] = current.contains(pkgs.get(i));

        new AlertDialog.Builder(this)
                .setTitle("Choose apps")
                .setMultiChoiceItems(labels, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("Save", (d, w) -> {
                    List<String> result = new ArrayList<>();
                    // keep existing order for apps that stay selected
                    for (String pkg : current) {
                        int idx = pkgs.indexOf(pkg);
                        if (idx < 0 || checked[idx]) result.add(pkg);
                    }
                    // add newly selected apps at the end
                    for (int i = 0; i < pkgs.size(); i++) {
                        if (checked[i] && !result.contains(pkgs.get(i))) result.add(pkgs.get(i));
                    }
                    Prefs.saveSidebarApps(prefs, result);
                    render();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
