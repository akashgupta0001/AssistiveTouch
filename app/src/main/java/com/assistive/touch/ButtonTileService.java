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
        boolean on = p.getBoolean(Prefs.KEY_BUTTONS_MASTER, true);
        p.edit().putBoolean(Prefs.KEY_BUTTONS_MASTER, !on).apply();
        update();
    }

    private void update() {
        Tile t = getQsTile();
        if (t == null) return;
        boolean on = Prefs.get(this).getBoolean(Prefs.KEY_BUTTONS_MASTER, true);
        t.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.setLabel("Floating buttons");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) t.setSubtitle(on ? "On" : "Off");
        t.updateTile();
    }
}
