package com.example.ghostprotocol;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

public class KeepAliveService extends Service {

    // Static flag so MainActivity can poll whether this service is actually alive.
    // Set to true in onCreate, false in onDestroy — reliable within the same process.
    // NOTE: onDestroy is NOT guaranteed to be called on force-stop; the flag may
    // be stale if Android kills the process. Acceptable for a UI health indicator.
    private static boolean running = false;
    public static boolean isRunning() { return running; }

    // Overlay references — stored so we can clean up in onDestroy to prevent
    // WindowManager view leaks on service restart.
    private WindowManager windowManager;
    private View overlayView;

    // -------------------------------------------------------------------------
    // LISTENER WATCHDOG
    //
    // On aggressive OEM ROMs the NotificationListenerService binding gets
    // silently severed after many hours — onListenerDisconnected() is not
    // reliably called, so requestRebind() from there never fires. The symptom
    // is exactly what was reported: the app keeps "running" (this anchor stays
    // alive) but stops auto-replying until a manual restart, and previously
    // queued notifications sometimes flush late once the binding recovers.
    //
    // This watchdog independently pokes the binding on a timer. requestRebind()
    // is a safe no-op when the listener is already healthy, and forces a fresh
    // bind when it has been dropped. If the heartbeat has been stale for a long
    // stretch, we also hard-toggle the component to recover a wedged binding.
    // -------------------------------------------------------------------------
    private static final long WATCHDOG_INTERVAL_MS = 10 * 60 * 1000L; // poke every 10 min
    private static final long HEARTBEAT_STALE_MS   = 30 * 60 * 1000L; // hard-recover after 30 min silent

    private final Handler watchdog = new Handler(Looper.getMainLooper());
    private final Runnable watchdogTask = new Runnable() {
        @Override
        public void run() {
            try {
                ComponentName ghost = new ComponentName(
                        KeepAliveService.this, GhostService.class);
                long silentFor = System.currentTimeMillis()
                        - GhostService.lastListenerHeartbeatMs;

                if (GhostService.lastListenerHeartbeatMs > 0
                        && silentFor > HEARTBEAT_STALE_MS) {
                    // Binding looks wedged — force the OS to tear it down and
                    // rebind by toggling the component off then on.
                    Log.w("GhostProtocol", "Watchdog: listener silent for "
                            + (silentFor / 60000) + " min — forcing rebind.");
                    getPackageManager().setComponentEnabledSetting(ghost,
                            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                            PackageManager.DONT_KILL_APP);
                    watchdog.postDelayed(() -> {
                        getPackageManager().setComponentEnabledSetting(ghost,
                                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                                PackageManager.DONT_KILL_APP);
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            NotificationListenerService.requestRebind(ghost);
                        }
                    }, 800);
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    // Routine nudge — recovers a silently-unbound listener.
                    NotificationListenerService.requestRebind(ghost);
                }
            } catch (Exception e) {
                Log.e("GhostProtocol", "Watchdog rebind failed: " + e.getMessage());
            }
            watchdog.postDelayed(this, WATCHDOG_INTERVAL_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        // 1. First, create the Visible Notification (The Badge)
        elevateToForeground();

        // 2. Then, create the Invisible Overlay (The Shield)
        addInvisibleOverlay();

        // 3. Start the listener watchdog (self-heals a dropped notification bind)
        watchdog.postDelayed(watchdogTask, WATCHDOG_INTERVAL_MS);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // If the system kills this service, restart it immediately.
        return START_STICKY;
    }

    private void elevateToForeground() {
        // Changed channel ID and priority: IMPORTANCE_LOW shows in the status bar
        // quietly without popping up a heads-up banner on every update.
        // Old code used IMPORTANCE_HIGH which was intrusive.
        String channelId = "GhostSentinel_Low_Priority";
        NotificationManager notificationManager =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    channelId,
                    "Ghost Protocol Status",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Shows that the Ghost Protocol is active");
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            notificationManager.createNotificationChannel(channel);
        }

        Notification notification = new NotificationCompat.Builder(this, channelId)
                .setContentTitle("Ghost Protocol Active")
                .setContentText("System Overlay Active. Protected.")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();

        startForeground(1, notification);
    }

    private void addInvisibleOverlay() {
        // Verify overlay permission BEFORE attempting to add the view.
        // Old code skipped this check — if permission was revoked after
        // initial grant, windowManager.addView() would throw a
        // BadTokenException (caught but silently failed).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Log.w("GhostProtocol", "Overlay permission not granted — skipping overlay shield.");
            return;
        }

        try {
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

            int layoutType;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                layoutType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
            } else {
                @SuppressWarnings("deprecation")
                int legacyType = WindowManager.LayoutParams.TYPE_PHONE;
                layoutType = legacyType;
            }

            // 1x1 Pixel, Top-Left Corner
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    1, 1,
                    layoutType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
                            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                    PixelFormat.TRANSLUCENT
            );

            params.gravity = Gravity.TOP | Gravity.START;
            params.x = 0;
            params.y = 0;

            overlayView = new View(this);
            windowManager.addView(overlayView, params);

        } catch (Exception e) {
            Log.e("GhostProtocol", "Overlay creation failed: " + e.getMessage());
        }
    }

    /** Remove the overlay view to prevent WindowManager leaks on service restart. */
    private void removeOverlay() {
        if (windowManager != null && overlayView != null) {
            try {
                windowManager.removeView(overlayView);
            } catch (Exception e) {
                Log.e("GhostProtocol", "Overlay removal failed: " + e.getMessage());
            }
            overlayView = null;
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        running = false;
        watchdog.removeCallbacks(watchdogTask);
        removeOverlay();
    }
}