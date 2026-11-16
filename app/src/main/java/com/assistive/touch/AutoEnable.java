package com.assistive.touch;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;

/**
 * Switches our accessibility service on directly. Only works if the user granted
 * WRITE_SECURE_SETTINGS once via ADB; otherwise canWrite() is false and nothing happens.
 */
public final class AutoEnable {
    private AutoEnable() {}

    public static boolean canWrite(Context c) {
        return c.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS")
                == PackageManager.PERMISSION_GRANTED;
    }

    public static boolean enable(Context c) {
        if (!canWrite(c)) return false;
        try {
            ContentResolver cr = c.getContentResolver();
            String me = new ComponentName(c, AssistiveTouchService.class).flattenToString();
            String cur = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            String next;
            if (cur == null || cur.trim().isEmpty()) next = me;
            else if (cur.contains(me)) next = cur;
            else next = cur + ":" + me;
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next);
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
