package com.assistive.touch;

import android.content.SharedPreferences;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Quick Settings tile: both side bars on / off. */
public class EdgeTileService extends TileService {

    @Override
    public void onStartListening() {
        update();
    }

    @Override
    public void onClick() {
        SharedPreferences p = Prefs.get(this);
        boolean on = isOn(p);
        SharedPreferences.Editor e = p.edit();
        if (on) {
            e.putBoolean(Prefs.KEY_EDGE_MASTER, false);
        } else {
            e.putBoolean(Prefs.KEY_EDGE_MASTER, true);
            // If no side bar was ever enabled, turn on the right one so the tile does something.
            boolean anySide = p.getBoolean(Prefs.edgeKey(Prefs.LEFT, "enabled"), false)
                    || p.getBoolean(Prefs.edgeKey(Prefs.RIGHT, "enabled"), false);
            if (!anySide) e.putBoolean(Prefs.edgeKey(Prefs.RIGHT, "enabled"), true);
        }
        e.apply();
        update();
    }

    private static boolean isOn(SharedPreferences p) {
        return Prefs.edgeEnabled(p, Prefs.LEFT) || Prefs.edgeEnabled(p, Prefs.RIGHT);
    }

    private void update() {
        Tile t = getQsTile();
        if (t == null) return;
        boolean on = isOn(Prefs.get(this));
        t.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.setLabel("Side bars");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) t.setSubtitle(on ? "On" : "Off");
        t.updateTile();
    }
}
