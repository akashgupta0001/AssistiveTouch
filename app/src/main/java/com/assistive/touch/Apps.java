package com.assistive.touch;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small helpers for reading installed apps. */
public final class Apps {
    private Apps() {}

    public static String label(PackageManager pm, String pkg) {
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    public static Drawable icon(PackageManager pm, String pkg) {
        try {
            return pm.getApplicationIcon(pkg);
        } catch (Exception e) {
            return null;
        }
    }

    /** All launchable apps as package -> label, sorted by label. */
    public static Map<String, String> launchable(PackageManager pm, String excludePkg) {
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> infos = pm.queryIntentActivities(main, 0);
        List<String[]> rows = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (ResolveInfo ri : infos) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(excludePkg) || seen.contains(pkg)) continue;
            seen.add(pkg);
            rows.add(new String[]{pkg, String.valueOf(ri.loadLabel(pm))});
        }
        Collections.sort(rows, (a, b) -> a[1].compareToIgnoreCase(b[1]));
        Map<String, String> out = new LinkedHashMap<>();
        for (String[] r : rows) out.put(r[0], r[1]);
        return out;
    }
}
