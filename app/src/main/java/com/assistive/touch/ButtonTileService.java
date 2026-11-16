package com.assistive.touch;

import android.content.SharedPreferences;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Quick Settings tile: floating button on / off. */
public class ButtonTileService extends TileService {

    @Override
    public void onStartListening() {
        update();
    }

    @Override
    public void onClick() {
        SharedPreferences p = Prefs.get(this);
        boolean on = p.getBoolean(Prefs.KEY_ENABLED, true);
        p.edit().putBoolean(Prefs.KEY_ENABLED, !on).apply();
        update();
    }

    private void update() {
        Tile t = getQsTile();
        if (t == null) return;
        boolean on = Prefs.get(this).getBoolean(Prefs.KEY_ENABLED, true);
        t.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.setLabel("Floating button");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) t.setSubtitle(on ? "On" : "Off");
        t.updateTile();
    }
}
