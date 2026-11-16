package com.assistive.touch;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

public class MainActivity extends Activity {

    private SharedPreferences prefs;
    private TextView status;
    private Button btnEnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = Prefs.get(this);

        status = findViewById(R.id.status);
        btnEnable = findViewById(R.id.btn_enable);
        btnEnable.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        findViewById(R.id.btn_app_info).setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName()))));

        Switch show = findViewById(R.id.switch_show);
        show.setChecked(prefs.getBoolean(Prefs.KEY_ENABLED, true));
        show.setOnCheckedChangeListener((b, checked) ->
                prefs.edit().putBoolean(Prefs.KEY_ENABLED, checked).apply());

        // Size: 40..80 dp
        TextView sizeLabel = findViewById(R.id.size_label);
        SeekBar seekSize = findViewById(R.id.seek_size);
        int size = prefs.getInt(Prefs.KEY_SIZE, Prefs.DEFAULT_SIZE);
        seekSize.setProgress(size - 40);
        sizeLabel.setText(getString(R.string.size_label, size));
        seekSize.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                sizeLabel.setText(getString(R.string.size_label, 40 + p));
                if (user) prefs.edit().putInt(Prefs.KEY_SIZE, 40 + p).apply();
            }
        });

        // Idle opacity: 20..100 %
        TextView alphaLabel = findViewById(R.id.alpha_label);
        SeekBar seekAlpha = findViewById(R.id.seek_alpha);
        int alpha = prefs.getInt(Prefs.KEY_IDLE_ALPHA, Prefs.DEFAULT_IDLE_ALPHA);
        seekAlpha.setProgress(alpha - 20);
        alphaLabel.setText(getString(R.string.alpha_label, alpha));
        seekAlpha.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                alphaLabel.setText(getString(R.string.alpha_label, 20 + p));
                if (user) prefs.edit().putInt(Prefs.KEY_IDLE_ALPHA, 20 + p).apply();
            }
        });

        setupSpinner(findViewById(R.id.spin_double), Prefs.KEY_DOUBLE);
        setupSpinner(findViewById(R.id.spin_long), Prefs.KEY_LONG);
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean on = isServiceEnabled();
        status.setText(on ? R.string.status_on : R.string.status_off);
        status.setTextColor(on ? Color.rgb(52, 199, 89) : Color.rgb(255, 59, 48));
        btnEnable.setVisibility(on ? View.GONE : View.VISIBLE);
    }

    private void setupSpinner(Spinner spinner, String key) {
        Action[] choices = Action.GESTURE_CHOICES;
        String[] labels = new String[choices.length];
        int selected = 0;
        Action current = Action.fromId(prefs.getString(key, Action.NONE.id));
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
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                prefs.edit().putString(key, choices[pos].id).apply();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) { }
        });
    }

    private boolean isServiceEnabled() {
        if (AssistiveTouchService.instance != null) return true;
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        ComponentName cn = new ComponentName(this, AssistiveTouchService.class);
        return enabled.contains(cn.flattenToString()) || enabled.contains(cn.flattenToShortString());
    }

    private abstract static class SimpleSeek implements SeekBar.OnSeekBarChangeListener {
        @Override public void onStartTrackingTouch(SeekBar seekBar) { }
        @Override public void onStopTrackingTouch(SeekBar seekBar) { }
    }
}
