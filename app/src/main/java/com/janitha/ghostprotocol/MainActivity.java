package com.janitha.ghostprotocol;

import android.Manifest;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    private Button statusButton;
    private Button restartButton;
    private Button stopButton;
    private TextView serviceHealthText;

    // When the user manually stops the protocol, we set this flag so onResume
    // does not auto-restart the service when they come back to the activity.
    private boolean manuallyStopped = false;

    // Polls every 3 seconds to keep the health indicator live
    private final Handler healthPoller = new Handler(Looper.getMainLooper());
    private final Runnable healthCheck = new Runnable() {
        @Override
        public void run() {
            updateStatus();
            healthPoller.postDelayed(this, 3000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 1. OVERLAY PERMISSION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, 1234);
                return;
            }
        }

        // 2. NOTIFICATION RUNTIME PERMISSION (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                        this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, 101);
            }
        }

        // 3. BUILD UI PROGRAMMATICALLY
        buildUI();

        // 4. START THE ANCHOR SERVICE (only if not manually stopped)
        if (!manuallyStopped) {
            startKeepAliveService();
        }
    }

    private void buildUI() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.parseColor("#0A0A0A"));
        root.setPadding(60, 60, 60, 60);

        // --- Title label ---
        TextView titleText = new TextView(this);
        titleText.setText("GHOST PROTOCOL");
        titleText.setTextColor(Color.parseColor("#00FF41"));
        titleText.setTextSize(22);
        titleText.setTypeface(null, Typeface.BOLD);
        titleText.setGravity(Gravity.CENTER);
        titleText.setPadding(0, 0, 0, 8);

        // --- Live health indicator ---
        serviceHealthText = new TextView(this);
        serviceHealthText.setText("Checking...");
        serviceHealthText.setTextColor(Color.GRAY);
        serviceHealthText.setTextSize(12);
        serviceHealthText.setGravity(Gravity.CENTER);
        serviceHealthText.setPadding(0, 0, 0, 40);

        // --- Main status button ---
        statusButton = new Button(this);
        statusButton.setAllCaps(false);
        statusButton.setTextSize(16);
        statusButton.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 160);
        statusParams.setMargins(0, 0, 0, 24);
        statusButton.setLayoutParams(statusParams);
        statusButton.setOnClickListener(v -> {
            if (!isNotificationServiceEnabled()) {
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
            }
        });

        // --- Force Restart button ---
        restartButton = new Button(this);
        restartButton.setText("⟳  FORCE RESTART SERVICE");
        restartButton.setAllCaps(false);
        restartButton.setTextSize(14);
        restartButton.setTextColor(Color.parseColor("#FFCC00"));
        restartButton.setBackgroundColor(Color.parseColor("#1A1A1A"));
        restartButton.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams restartParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 130);
        restartParams.setMargins(0, 0, 0, 16);
        restartButton.setLayoutParams(restartParams);
        restartButton.setOnClickListener(v -> forceRestartService());

        // --- Stop / Start toggle button ---
        stopButton = new Button(this);
        stopButton.setAllCaps(false);
        stopButton.setTextSize(14);
        stopButton.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams stopParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 130);
        stopParams.setMargins(0, 0, 0, 0);
        stopButton.setLayoutParams(stopParams);
        stopButton.setOnClickListener(v -> {
            if (manuallyStopped) {
                startProtocol();
            } else {
                stopProtocol();
            }
        });

        root.addView(titleText);
        root.addView(serviceHealthText);
        root.addView(statusButton);
        root.addView(restartButton);
        root.addView(stopButton);

        setContentView(root);
    }

    // -------------------------------------------------------------------------
    // STOP — kills both services and disables the GhostService component so
    // Android stops routing notifications to it until explicitly re-enabled.
    // -------------------------------------------------------------------------
    private void stopProtocol() {
        manuallyStopped = true;

        // Stop the anchor
        stopService(new Intent(this, KeepAliveService.class));

        // Disable GhostService so Android unbinds the notification listener
        ComponentName ghostComponent = new ComponentName(this, GhostService.class);
        getPackageManager().setComponentEnabledSetting(
                ghostComponent,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);

        Log.i("GhostProtocol", "Protocol manually stopped.");
        updateStatus();
    }

    // -------------------------------------------------------------------------
    // START — re-enables GhostService component and restarts KeepAliveService.
    // Same toggle used by Force Restart, but initiated from the stop button.
    // -------------------------------------------------------------------------
    private void startProtocol() {
        manuallyStopped = false;

        ComponentName ghostComponent = new ComponentName(this, GhostService.class);
        PackageManager pm = getPackageManager();

        pm.setComponentEnabledSetting(
                ghostComponent,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP);

        startKeepAliveService();

        Log.i("GhostProtocol", "Protocol manually started.");

        // Small delay for the OS to rebind before refreshing the UI
        healthPoller.postDelayed(this::updateStatus, 1000);
    }

    // -------------------------------------------------------------------------
    // FORCE RESTART
    // The only reliable way to revive a dead NotificationListenerService without
    // reinstalling is to toggle its component enabled state. Android treats the
    // disable→enable transition as a fresh install event and rebinds the service.
    // -------------------------------------------------------------------------
    private void forceRestartService() {
        restartButton.setText("Restarting...");
        restartButton.setEnabled(false);

        // Step 1: Kill KeepAliveService cleanly
        stopService(new Intent(this, KeepAliveService.class));

        // Step 2: Toggle GhostService component — forces a full OS rebind
        ComponentName ghostComponent = new ComponentName(this, GhostService.class);
        PackageManager pm = getPackageManager();
        pm.setComponentEnabledSetting(
                ghostComponent,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);

        // Small delay so the OS actually processes the disable before re-enabling
        healthPoller.postDelayed(() -> {
            pm.setComponentEnabledSetting(
                    ghostComponent,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP);

            // Step 3: Restart KeepAliveService
            startKeepAliveService();

            // Step 4: Restore button after a moment and refresh status
            healthPoller.postDelayed(() -> {
                restartButton.setText("⟳  FORCE RESTART SERVICE");
                restartButton.setEnabled(true);
                updateStatus();
            }, 1500);

        }, 500);
    }

    private void startKeepAliveService() {
        Intent intent = new Intent(this, KeepAliveService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Only auto-start if the user hasn't deliberately stopped the protocol
        if (!manuallyStopped) {
            startKeepAliveService();
        }
        updateStatus();
        healthPoller.post(healthCheck);
    }

    @Override
    protected void onPause() {
        super.onPause();
        healthPoller.removeCallbacks(healthCheck); // Stop polling when app is backgrounded
    }

    private void updateStatus() {
        boolean listenerEnabled = isNotificationServiceEnabled();
        boolean keepAliveRunning = KeepAliveService.isRunning();
        boolean fullyHealthy = listenerEnabled && keepAliveRunning;

        if (manuallyStopped) {
            statusButton.setText("GHOST PROTOCOL: STOPPED\n(Tap the button below to resume)");
            statusButton.setBackgroundColor(Color.parseColor("#1A1A1A"));
            statusButton.setTextColor(Color.GRAY);
            serviceHealthText.setText("○ Manually stopped");
            serviceHealthText.setTextColor(Color.GRAY);
            stopButton.setText("▶  START PROTOCOL");
            stopButton.setTextColor(Color.parseColor("#00FF41"));
            stopButton.setBackgroundColor(Color.parseColor("#003300"));
            restartButton.setEnabled(false);
            restartButton.setAlpha(0.4f);

        } else if (fullyHealthy) {
            statusButton.setText("GHOST PROTOCOL: ACTIVE\n(System Locked)");
            statusButton.setBackgroundColor(Color.parseColor("#003300"));
            statusButton.setTextColor(Color.parseColor("#00FF41"));
            serviceHealthText.setText("● Listener bound  ● Anchor alive");
            serviceHealthText.setTextColor(Color.parseColor("#00FF41"));
            stopButton.setText("■  STOP PROTOCOL");
            stopButton.setTextColor(Color.parseColor("#FF4444"));
            stopButton.setBackgroundColor(Color.parseColor("#1A1A1A"));
            restartButton.setEnabled(true);
            restartButton.setAlpha(1f);

        } else if (listenerEnabled && !keepAliveRunning) {
            statusButton.setText("GHOST PROTOCOL: DEGRADED\n(Anchor service dead — tap Restart)");
            statusButton.setBackgroundColor(Color.parseColor("#332200"));
            statusButton.setTextColor(Color.parseColor("#FFCC00"));
            serviceHealthText.setText("● Listener bound  ✕ Anchor dead");
            serviceHealthText.setTextColor(Color.parseColor("#FFCC00"));
            stopButton.setText("■  STOP PROTOCOL");
            stopButton.setTextColor(Color.parseColor("#FF4444"));
            stopButton.setBackgroundColor(Color.parseColor("#1A1A1A"));
            restartButton.setEnabled(true);
            restartButton.setAlpha(1f);

        } else {
            statusButton.setText("ENABLE GHOST PROTOCOL\n(Tap to allow notification access)");
            statusButton.setBackgroundColor(Color.parseColor("#330000"));
            statusButton.setTextColor(Color.WHITE);
            serviceHealthText.setText("✕ Notification listener not enabled");
            serviceHealthText.setTextColor(Color.parseColor("#FF4444"));
            stopButton.setText("■  STOP PROTOCOL");
            stopButton.setTextColor(Color.parseColor("#FF4444"));
            stopButton.setBackgroundColor(Color.parseColor("#1A1A1A"));
            restartButton.setEnabled(true);
            restartButton.setAlpha(1f);
        }
    }

    private boolean isNotificationServiceEnabled() {
        String pkgName = getPackageName();
        final String flat = Settings.Secure.getString(
                getContentResolver(), "enabled_notification_listeners");
        if (!TextUtils.isEmpty(flat)) {
            for (String name : flat.split(":")) {
                final ComponentName cn = ComponentName.unflattenFromString(name);
                if (cn != null && TextUtils.equals(pkgName, cn.getPackageName())) {
                    return true;
                }
            }
        }
        return false;
    }
}