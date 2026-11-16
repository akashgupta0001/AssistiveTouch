package com.assistive.touch;

public enum Action {
    NONE("none", R.string.act_none, 0),
    MENU("menu", R.string.act_menu, R.drawable.ic_menu),
    SIDEBAR("sidebar", R.string.act_sidebar, R.drawable.ic_apps),
    HOME("home", R.string.act_home, R.drawable.ic_home),
    BACK("back", R.string.act_back, R.drawable.ic_back),
    RECENTS("recents", R.string.act_recents, R.drawable.ic_recents),
    NOTIFICATIONS("notifications", R.string.act_notifications, R.drawable.ic_notifications),
    CONTROL_CENTRE("control", R.string.act_control, R.drawable.ic_control),
    SCREENSHOT("screenshot", R.string.act_screenshot, R.drawable.ic_screenshot),
    LOCK_SCREEN("lock", R.string.act_lock, R.drawable.ic_lock),
    POWER("power", R.string.act_power, R.drawable.ic_power),
    VOLUME_UP("vol_up", R.string.act_vol_up, R.drawable.ic_vol_up),
    VOLUME_DOWN("vol_down", R.string.act_vol_down, R.drawable.ic_vol_down),
    FLASHLIGHT("flash", R.string.act_flash, R.drawable.ic_flash),
    SPLIT_SCREEN("split", R.string.act_split, R.drawable.ic_split),
    SETTINGS("settings", R.string.act_settings, R.drawable.ic_settings);

    public final String id;
    public final int label;
    public final int icon;

    Action(String id, int label, int icon) {
        this.id = id;
        this.label = label;
        this.icon = icon;
    }

    /** Items shown in the pop-up menu, 3 per row like iOS. */
    public static final Action[] MENU_ITEMS = {
            NOTIFICATIONS, CONTROL_CENTRE, SCREENSHOT,
            BACK, HOME, RECENTS,
            VOLUME_DOWN, LOCK_SCREEN, VOLUME_UP,
            FLASHLIGHT, SIDEBAR, POWER,
            SPLIT_SCREEN, SETTINGS
    };

    /** Choices for every gesture setting. */
    public static final Action[] GESTURE_CHOICES = {
            NONE, MENU, SIDEBAR, HOME, BACK, RECENTS, NOTIFICATIONS, CONTROL_CENTRE,
            SCREENSHOT, LOCK_SCREEN, POWER, VOLUME_UP, VOLUME_DOWN, FLASHLIGHT,
            SPLIT_SCREEN, SETTINGS
    };

    public static Action fromId(String id) {
        if (id != null) {
            for (Action a : values()) if (a.id.equals(id)) return a;
        }
        return NONE;
    }
}
