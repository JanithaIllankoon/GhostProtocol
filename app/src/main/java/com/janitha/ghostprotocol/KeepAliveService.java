package com.janitha.ghostprotocol;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

public class KeepAliveService extends Service {

    // Static flag so MainActivity can poll whether this service is actually alive.
    // Set to true in onCreate, false in onDestroy — reliable within the same process.
    private static boolean running = false;
    public static boolean isRunning() { return running; }

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
        // CHANGED ID: Force a fresh channel again
        String channelId = "GhostSentinel_High_Priority";
        NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    channelId,
                    "Ghost Protocol Status",
                    NotificationManager.IMPORTANCE_HIGH // CHANGED: FORCE VISIBILITY
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
                .setPriority(NotificationCompat.PRIORITY_MAX) // CHANGED: MAX PRIORITY
                .build();

        startForeground(1, notification);
    }

    private void addInvisibleOverlay() {
        try {
            WindowManager windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

            int layoutType;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                layoutType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
            } else {
                layoutType = WindowManager.LayoutParams.TYPE_PHONE;
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

            View dummyView = new View(this);
            windowManager.addView(dummyView, params);

        } catch (Exception e) {
            e.printStackTrace();
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
    }
}