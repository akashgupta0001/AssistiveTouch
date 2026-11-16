package com.assistive.touch;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/**
 * Foreground service with a small ongoing notification. It keeps the app's process at high
 * priority so the system (and "clear all" on many phones) doesn't kill the floating buttons.
 * The notification has an "Exit" button that turns everything off.
 */
public class KeepAliveService extends Service {

    public static final String ACTION_EXIT = "com.assistive.touch.EXIT";
    private static final String CHANNEL = "running";
    private static final int NOTIF_ID = 7;

    public static void start(Context c) {
        try {
            Intent i = new Intent(c, KeepAliveService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.startForegroundService(i);
            else c.startService(i);
        } catch (Throwable ignored) {
            // Some Android versions block starting from the background; the app retries when opened.
        }
    }

    public static void stop(Context c) {
        try { c.stopService(new Intent(c, KeepAliveService.class)); } catch (Throwable ignored) { }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_EXIT.equals(intent.getAction())) {
            exitEverything(this);
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            Notification n = buildNotification();
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (Throwable t) {
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    /** Turn off the accessibility service and this keep-alive service. */
    public static void exitEverything(Context c) {
        AssistiveTouchService s = AssistiveTouchService.instance;
        if (s != null) s.exit();
        stop(c);
    }

    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Running in background",
                    NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        int piFlags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), piFlags);
        PendingIntent exit = PendingIntent.getService(this, 1,
                new Intent(this, KeepAliveService.class).setAction(ACTION_EXIT), piFlags);

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_tile_button)
                .setContentTitle("AssistiveTouch is running")
                .setContentText("Tap to open settings")
                .setOngoing(true)
                .setShowWhen(false)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "Exit", exit).build());
        return b.build();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
