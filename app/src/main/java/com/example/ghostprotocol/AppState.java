package com.example.ghostprotocol;

import android.app.AppOpsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Process;
import android.provider.Settings;
import android.text.TextUtils;

/** Shared checks and controls for whether Ghost Protocol can run. */
final class AppState {
    private AppState() {}

    static final String PREFS = "GhostPrefs";
    static final String KEY_PAUSED = "manually_stopped";

    static boolean isPaused(Context ctx) {
        return GhostService.isPaused(ctx);
    }

    static void setPaused(Context ctx, boolean paused) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_PAUSED, paused).apply();
    }

    static boolean hasNotificationAccess(Context ctx) {
        String flat = Settings.Secure.getString(ctx.getContentResolver(),
                "enabled_notification_listeners");
        if (TextUtils.isEmpty(flat)) return false;
        for (String name : flat.split(":")) {
            ComponentName cn = ComponentName.unflattenFromString(name);
            if (cn != null && ctx.getPackageName().equals(cn.getPackageName())) return true;
        }
        return false;
    }

    /** Usage access lets the app see when WhatsApp is open and stay quiet. */
    static boolean hasUsageAccess(Context ctx) {
        try {
            AppOpsManager appOps = (AppOpsManager) ctx.getSystemService(Context.APP_OPS_SERVICE);
            if (appOps == null) return false;
            int mode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    ? appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                            Process.myUid(), ctx.getPackageName())
                    : appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                            Process.myUid(), ctx.getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    static void openNotificationAccessSettings(Context ctx) {
        ctx.startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
    }

    static void openUsageAccessSettings(Context ctx) {
        try {
            ctx.startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
        } catch (Exception e) {
            ctx.startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    static void startKeepAlive(Context ctx) {
        Intent intent = new Intent(ctx, KeepAliveService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(intent);
        } else {
            ctx.startService(intent);
        }
    }

    /** Restarts the background service and reconnects the notification listener. */
    static void restart(Context ctx) {
        ctx.stopService(new Intent(ctx, KeepAliveService.class));
        GhostService.reconnect(ctx);
        if (!isPaused(ctx)) startKeepAlive(ctx);
    }

    static String versionName(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }
}
