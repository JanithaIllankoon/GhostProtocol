package com.example.ghostprotocol;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
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

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        // 1. First, create the Visible Notification (The Badge)
        elevateToForeground();

        // 2. Then, create the Invisible Overlay (The Shield)
        addInvisibleOverlay();
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
        removeOverlay();
    }
}