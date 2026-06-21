package com.example.ghostprotocol;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
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
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;

public class MainActivity extends AppCompatActivity {

    private Button statusButton;
    private Button restartButton;
    private Button stopButton;
    private TextView serviceHealthText;
    private TextView footerCounts;

    private boolean manuallyStopped = false;

    private final Handler healthPoller = new Handler(Looper.getMainLooper());
    private final Runnable healthCheck = new Runnable() {
        @Override
        public void run() {
            updateStatus();
            healthPoller.postDelayed(this, 10000); // 10s — was 3s, reduces battery drain
        }
    };

    // Replaces the deprecated startActivityForResult for overlay permission.
    // registerForActivityResult must be called before onStart(), which is
    // satisfied by field initialisation in the constructor.
    private final ActivityResultLauncher<Intent> overlayLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        // User returns from overlay permission settings
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                                && Settings.canDrawOverlays(this)) {
                            proceedWithSetup();
                        }
                        // If still denied, user can re-open the app to try again
                    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Load persisted stop state — survives process death.
        // Old code used a non-persisted boolean, so killing the process
        // would auto-resume the protocol even after the user stopped it.
        manuallyStopped = loadManuallyStopped();

        // 1. OVERLAY PERMISSION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                overlayLauncher.launch(intent);
                return;
            }
        }

        proceedWithSetup();
    }

    /**
     * Called after overlay permission is confirmed (either already granted or
     * just granted via the launcher callback). Requests remaining permissions,
     * builds the UI, and starts the anchor service.
     */
    private void proceedWithSetup() {
        // 2. NOTIFICATION RUNTIME PERMISSION (Android 13+)
        // We chain permission requests one at a time — Android can silently
        // drop the second of two requestPermissions() calls fired in the same
        // tick. POST_NOTIFICATIONS goes first; READ_CONTACTS is requested in
        // onRequestPermissionsResult once this one resolves (granted or not).
        boolean willPromptNotifications = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                        this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, 101);
                willPromptNotifications = true;
            }
        }

        // If POST_NOTIFICATIONS isn't going to prompt (already granted or
        // pre-Android-13), request READ_CONTACTS now. Otherwise the result
        // handler will request it after the notification dialog closes.
        if (!willPromptNotifications) {
            requestContactsIfNeeded();
        }

        // 3. BUILD UI
        buildUI();

        // 4. START THE ANCHOR SERVICE
        if (!manuallyStopped) {
            startKeepAliveService();
        }

        // 5. Kick off initial status + health polling
        //    (needed when called from the overlay launcher callback,
        //    since onResume already ran with null UI and returned early)
        updateStatus();
        updateFooterCounts();
        healthPoller.post(healthCheck);
    }

    /**
     * Request READ_CONTACTS only if not already granted. Used as a chainable
     * step after POST_NOTIFICATIONS resolves.
     */
    private void requestContactsIfNeeded() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                    this, new String[]{Manifest.permission.READ_CONTACTS}, 102);
        }
    }

    /**
     * Handle permission results.
     * - 101 (POST_NOTIFICATIONS): chain READ_CONTACTS request after this resolves.
     * - 102 (READ_CONTACTS): show fallback explanation if denied.
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == 101) {
            // Notifications dialog closed (granted OR denied) — now ask for contacts
            requestContactsIfNeeded();
            return;
        }

        if (requestCode == 102) {
            if (grantResults.length > 0
                    && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                new AlertDialog.Builder(this)
                        .setTitle("Contacts Permission")
                        .setMessage("Without contact access, GhostProtocol cannot distinguish "
                                + "saved contacts from strangers. It will use a basic heuristic "
                                + "instead (phone-number-only titles treated as unknown).\n\n"
                                + "To grant later: Settings → Apps → Ghost Protocol → Permissions.")
                        .setPositiveButton("OK", null)
                        .show();
            }
        }
    }

    // -------------------------------------------------------------------------
    // MANUALLY STOPPED — persisted across process death
    // -------------------------------------------------------------------------
    private boolean loadManuallyStopped() {
        return getSharedPreferences("GhostPrefs", MODE_PRIVATE)
                .getBoolean("manually_stopped", false);
    }

    private void saveManuallyStopped(boolean stopped) {
        getSharedPreferences("GhostPrefs", MODE_PRIVATE)
                .edit().putBoolean("manually_stopped", stopped).apply();
    }

    // -------------------------------------------------------------------------
    // UI BUILDER
    // -------------------------------------------------------------------------
    private void buildUI() {
        ScrollView scroll = new ScrollView(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setBackgroundColor(Theme.BG);
        root.setPadding(60, 80, 60, 80);

        // --- Title ---
        TextView titleText = new TextView(this);
        titleText.setText("GHOST PROTOCOL");
        titleText.setTextColor(Theme.PRIMARY);
        titleText.setTextSize(22);
        titleText.setTypeface(null, Typeface.BOLD);
        titleText.setGravity(Gravity.CENTER);
        titleText.setPadding(0, 0, 0, 8);

        // --- Live health indicator ---
        serviceHealthText = new TextView(this);
        serviceHealthText.setText("Checking...");
        serviceHealthText.setTextColor(Theme.TEXT_MUTED);
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

        // --- Force Restart ---
        restartButton = new Button(this);
        restartButton.setText("⟳  FORCE RESTART SERVICE");
        restartButton.setAllCaps(false);
        restartButton.setTextSize(14);
        restartButton.setTextColor(Theme.WARN);
        restartButton.setBackgroundColor(Theme.INPUT_BG);
        restartButton.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams restartParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 130);
        restartParams.setMargins(0, 0, 0, 16);
        restartButton.setLayoutParams(restartParams);
        restartButton.setOnClickListener(v -> forceRestartService());

        // --- Stop / Start toggle ---
        stopButton = new Button(this);
        stopButton.setAllCaps(false);
        stopButton.setTextSize(14);
        stopButton.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams stopParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 130);
        stopParams.setMargins(0, 0, 0, 36);
        stopButton.setLayoutParams(stopParams);
        stopButton.setOnClickListener(v -> {
            if (manuallyStopped) startProtocol(); else stopProtocol();
        });

        // --- Section divider: Customise ---
        root.addView(titleText);
        root.addView(serviceHealthText);
        root.addView(statusButton);
        root.addView(restartButton);
        root.addView(stopButton);

        root.addView(sectionDivider("CUSTOMISE"));

        // --- Edit Messages ---
        root.addView(navButton("✉  Edit Auto-Reply Messages", EditMessagesActivity.class));

        // --- Keyword Rules ---
        root.addView(navButton("🔑  Manage Keyword Rules", KeywordRulesActivity.class));

        // --- Whitelist ---
        root.addView(navButton("✓  Manage Whitelist", WhitelistActivity.class));

        // --- Section divider: Monitor ---
        root.addView(sectionDivider("MONITOR"));

        // --- Reply History ---
        root.addView(navButton("📜  View Reply History", HistoryActivity.class));

        // --- Footer counts ---
        footerCounts = new TextView(this);
        footerCounts.setTextColor(Theme.TEXT_QUATERNARY);
        footerCounts.setTextSize(10);
        footerCounts.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams footerParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        footerParams.setMargins(0, 40, 0, 0);
        footerCounts.setLayoutParams(footerParams);
        root.addView(footerCounts);

        scroll.addView(root);
        setContentView(scroll);
    }

    // Helper: section divider label
    private TextView sectionDivider(String label) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(Theme.TEXT_QUATERNARY);
        tv.setTextSize(11);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 10, 0, 14);
        tv.setLayoutParams(params);
        return tv;
    }

    // Helper: standard navigation button
    private Button navButton(String label, final Class<?> target) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Theme.TEXT_SECONDARY);
        b.setBackgroundColor(Theme.BUTTON_BG);
        b.setTextSize(13);
        b.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 120);
        params.setMargins(0, 0, 0, 12);
        b.setLayoutParams(params);
        b.setOnClickListener(v -> startActivity(new Intent(this, target)));
        return b;
    }

    // -------------------------------------------------------------------------
    // STOP / START / RESTART
    // -------------------------------------------------------------------------
    private void stopProtocol() {
        manuallyStopped = true;
        saveManuallyStopped(true);
        stopService(new Intent(this, KeepAliveService.class));
        ComponentName ghostComponent = new ComponentName(this, GhostService.class);
        getPackageManager().setComponentEnabledSetting(
                ghostComponent,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
        Log.i("GhostProtocol", "Protocol manually stopped.");
        updateStatus();
    }

    private void startProtocol() {
        manuallyStopped = false;
        saveManuallyStopped(false);
        ComponentName ghostComponent = new ComponentName(this, GhostService.class);
        getPackageManager().setComponentEnabledSetting(
                ghostComponent,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP);
        startKeepAliveService();
        Log.i("GhostProtocol", "Protocol manually started.");
        healthPoller.postDelayed(this::updateStatus, 1000);
    }

    private void forceRestartService() {
        restartButton.setText("Restarting...");
        restartButton.setEnabled(false);

        stopService(new Intent(this, KeepAliveService.class));

        ComponentName ghostComponent = new ComponentName(this, GhostService.class);
        PackageManager pm = getPackageManager();
        pm.setComponentEnabledSetting(
                ghostComponent,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);

        healthPoller.postDelayed(() -> {
            pm.setComponentEnabledSetting(
                    ghostComponent,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP);
            startKeepAliveService();
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
        if (statusButton == null) return; // UI not yet built (overlay permission pending)
        if (!manuallyStopped) {
            startKeepAliveService();
        }
        updateStatus();
        updateFooterCounts();
        healthPoller.post(healthCheck);
    }

    @Override
    protected void onPause() {
        super.onPause();
        healthPoller.removeCallbacks(healthCheck);
    }

    private void updateStatus() {
        if (statusButton == null) return; // UI not yet built
        boolean listenerEnabled = isNotificationServiceEnabled();
        boolean keepAliveRunning = KeepAliveService.isRunning();
        boolean fullyHealthy = listenerEnabled && keepAliveRunning;

        if (manuallyStopped) {
            statusButton.setText("GHOST PROTOCOL: STOPPED\n(Tap the button below to resume)");
            statusButton.setBackgroundColor(Theme.INPUT_BG);
            statusButton.setTextColor(Theme.TEXT_MUTED);
            serviceHealthText.setText("○ Manually stopped");
            serviceHealthText.setTextColor(Theme.TEXT_MUTED);
            stopButton.setText("▶  START PROTOCOL");
            stopButton.setTextColor(Theme.PRIMARY);
            stopButton.setBackgroundColor(Theme.PRIMARY_BG);
            restartButton.setEnabled(false);
            restartButton.setAlpha(0.4f);

        } else if (fullyHealthy) {
            statusButton.setText("GHOST PROTOCOL: ACTIVE\n(System Locked)");
            statusButton.setBackgroundColor(Theme.PRIMARY_BG);
            statusButton.setTextColor(Theme.PRIMARY);
            serviceHealthText.setText("● Listener bound  ● Anchor alive");
            serviceHealthText.setTextColor(Theme.PRIMARY);
            stopButton.setText("■  STOP PROTOCOL");
            stopButton.setTextColor(Theme.DANGER);
            stopButton.setBackgroundColor(Theme.INPUT_BG);
            restartButton.setEnabled(true);
            restartButton.setAlpha(1f);

        } else if (listenerEnabled && !keepAliveRunning) {
            statusButton.setText("GHOST PROTOCOL: DEGRADED\n(Anchor service dead — tap Restart)");
            statusButton.setBackgroundColor(Theme.WARN_BG);
            statusButton.setTextColor(Theme.WARN);
            serviceHealthText.setText("● Listener bound  ✕ Anchor dead");
            serviceHealthText.setTextColor(Theme.WARN);
            stopButton.setText("■  STOP PROTOCOL");
            stopButton.setTextColor(Theme.DANGER);
            stopButton.setBackgroundColor(Theme.INPUT_BG);
            restartButton.setEnabled(true);
            restartButton.setAlpha(1f);

        } else {
            statusButton.setText("ENABLE GHOST PROTOCOL\n(Tap to allow notification access)");
            statusButton.setBackgroundColor(Theme.ERROR_BG);
            statusButton.setTextColor(Theme.TEXT_PRIMARY);
            serviceHealthText.setText("✕ Notification listener not enabled");
            serviceHealthText.setTextColor(Theme.DANGER);
            stopButton.setText("■  STOP PROTOCOL");
            stopButton.setTextColor(Theme.DANGER);
            stopButton.setBackgroundColor(Theme.INPUT_BG);
            restartButton.setEnabled(true);
            restartButton.setAlpha(1f);
        }
    }

    // -------------------------------------------------------------------------
    // Footer live counts — whitelist / rules / pool / history
    // -------------------------------------------------------------------------
    private void updateFooterCounts() {
        if (footerCounts == null) return;
        SharedPreferences prefs = getSharedPreferences(
                GhostService.MSG_PREFS, Context.MODE_PRIVATE);

        // Whitelist
        int wlCount = prefs.getInt("whitelist_count", -1);
        if (wlCount == -1) wlCount = GhostService.DEFAULT_WHITELIST.length;

        // Keyword rules
        int kwCount = prefs.getInt("kw_count", 0);

        // Pool
        int poolCount = prefs.getInt("pool_count", 0);
        if (poolCount == 0) poolCount = GhostService.IDX_SPAM_WARNING; // default size

        // History
        int histCount = 0;
        try {
            JSONArray arr = new JSONArray(prefs.getString(GhostService.HISTORY_KEY, "[]"));
            histCount = arr.length();
        } catch (Exception ignored) {}

        footerCounts.setText(
                wlCount + " whitelisted  ·  "
                        + kwCount + " keyword rules  ·  "
                        + poolCount + " pool msgs  ·  "
                        + histCount + " in history"
        );
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