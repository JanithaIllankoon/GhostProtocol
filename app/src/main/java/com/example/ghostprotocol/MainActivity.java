package com.example.ghostprotocol;

import android.Manifest;
import android.app.AppOpsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;

import org.json.JSONArray;

public class MainActivity extends AppCompatActivity {

    // Status card
    private View statusDot;
    private TextView statusTitle;
    private TextView statusBody;
    private TextView statusButton;
    private TextView statusLink;

    // Usage-access notice (hidden once granted)
    private LinearLayout usageNotice;

    // Focus mode + settings rows
    private MaterialSwitch focusSwitch;
    private TextView repliesSummary;
    private TextView keywordsSummary;
    private TextView contactsSummary;
    private TextView historySummary;

    private boolean manuallyStopped = false;
    private boolean restarting = false;

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
        refreshAll();
        healthPoller.post(healthCheck);

        // 6. USAGE ACCESS — required to detect when WhatsApp is open so the
        //    protocol can stand down. Without it, replies fire even while the
        //    user is actively chatting. Prompt if not yet granted.
        maybePromptUsageAccess();
    }

    // -------------------------------------------------------------------------
    // USAGE ACCESS — needed for foreground detection (stand down while chatting)
    // -------------------------------------------------------------------------
    private boolean hasUsageAccess() {
        try {
            AppOpsManager appOps = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
            if (appOps == null) return false;
            int mode;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                mode = appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                        Process.myUid(), getPackageName());
            } else {
                mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                        Process.myUid(), getPackageName());
            }
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    private void maybePromptUsageAccess() {
        if (hasUsageAccess()) return;
        new MaterialAlertDialogBuilder(this)
                .setTitle("One more permission")
                .setMessage("Allow usage access so Ghost Protocol can tell when you have "
                        + "WhatsApp open, and stay quiet while you're chatting.\n\n"
                        + "On the next screen, find Ghost Protocol and turn on "
                        + "\"Permit usage access\".")
                .setPositiveButton("Continue", (d, w) -> openUsageAccessSettings())
                .setNegativeButton("Later", null)
                .show();
    }

    private void openUsageAccessSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
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
                new MaterialAlertDialogBuilder(this)
                        .setTitle("Contacts not allowed")
                        .setMessage("Ghost Protocol uses your contacts to tell friends apart "
                                + "from unknown numbers. Without it, anyone shown only as a "
                                + "phone number is treated as unknown.\n\n"
                                + "You can allow it later in Settings › Apps › Ghost Protocol "
                                + "› Permissions.")
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
        LinearLayout column = Ui.screen(this, "Ghost Protocol",
                "Replies to your WhatsApp messages while you're away.", false);

        // App icon beside the title
        LinearLayout header = (LinearLayout) column.getChildAt(0);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.mipmap.ic_launcher_round);
        LinearLayout.LayoutParams iconParams =
                new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44));
        header.addView(icon, iconParams);

        column.addView(buildStatusCard(), spaced(16));
        column.addView(buildUsageNotice(), spaced(12));

        // --- Who gets replies ---
        column.addView(Ui.sectionLabel(this, "Who gets replies"));
        LinearLayout focusCard = Ui.card(this);
        focusCard.addView(buildFocusRow());
        column.addView(focusCard);

        // --- Settings ---
        column.addView(Ui.sectionLabel(this, "Settings"));
        LinearLayout settings = Ui.card(this);
        LinearLayout repliesRow = Ui.row(this, "Away messages", " ", null, 0,
                v -> open(EditMessagesActivity.class));
        repliesSummary = subtitleOf(repliesRow);
        Ui.addRow(settings, repliesRow);

        LinearLayout keywordsRow = Ui.row(this, "Keyword replies", " ", null, 0,
                v -> open(KeywordRulesActivity.class));
        keywordsSummary = subtitleOf(keywordsRow);
        Ui.addRow(settings, keywordsRow);

        LinearLayout contactsRow = Ui.row(this, "Contact list", " ", null, 0,
                v -> open(WhitelistActivity.class));
        contactsSummary = subtitleOf(contactsRow);
        Ui.addRow(settings, contactsRow);
        column.addView(settings);

        // --- Activity ---
        column.addView(Ui.sectionLabel(this, "Activity"));
        LinearLayout activity = Ui.card(this);
        LinearLayout historyRow = Ui.row(this, "Recent replies", " ", null, 0,
                v -> open(HistoryActivity.class));
        historySummary = subtitleOf(historyRow);
        Ui.addRow(activity, historyRow);
        column.addView(activity);
    }

    private View buildStatusCard() {
        LinearLayout card = Ui.card(this);
        int p = Ui.dp(this, 20);
        card.setPadding(p, p, p, Ui.dp(this, 14));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        statusDot = Ui.dot(this, Theme.TEXT_TERTIARY, 10);
        titleRow.addView(statusDot);
        statusTitle = Ui.bold(Ui.text(this, "Checking…", 22, Theme.TEXT_PRIMARY));
        statusTitle.setPadding(Ui.dp(this, 10), 0, 0, 0);
        titleRow.addView(statusTitle);
        card.addView(titleRow);

        statusBody = Ui.text(this, "", 14, Theme.TEXT_SECONDARY);
        statusBody.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 18));
        card.addView(statusBody);

        statusButton = Ui.button(this, "", true, null);
        card.addView(statusButton);

        statusLink = Ui.text(this, "", 13, Theme.TEXT_SECONDARY);
        statusLink.setGravity(Gravity.CENTER);
        statusLink.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 4));
        card.addView(statusLink, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        return card;
    }

    private View buildUsageNotice() {
        usageNotice = Ui.card(this);
        LinearLayout row = Ui.row(this, "Allow usage access",
                "So replies pause while you're using WhatsApp.", null, 0,
                v -> openUsageAccessSettings());
        TextView title = (TextView) ((LinearLayout) row.getChildAt(0)).getChildAt(0);
        title.setTextColor(Theme.WARN);
        usageNotice.addView(row);
        return usageNotice;
    }

    private View buildFocusRow() {
        LinearLayout row = Ui.row(this, "Focus mode", " ", null, 0, null);
        row.setBackground(Ui.pressable(this, Theme.SURFACE, 0));
        focusSwitch = new MaterialSwitch(this);
        focusSwitch.setClickable(false); // the whole row toggles
        focusSwitch.setFocusable(false);
        row.addView(focusSwitch);
        row.setOnClickListener(v -> toggleFocusMode());
        return row;
    }

    private static TextView subtitleOf(LinearLayout row) {
        LinearLayout texts = (LinearLayout) row.getChildAt(0);
        return (TextView) texts.getChildAt(1);
    }

    private LinearLayout.LayoutParams spaced(int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP);
        lp.topMargin = Ui.dp(this, topDp);
        return lp;
    }

    private void open(Class<?> target) {
        startActivity(new Intent(this, target));
    }

    // -------------------------------------------------------------------------
    // FOCUS MODE — inverse whitelist (reply ONLY to whitelisted contacts)
    // -------------------------------------------------------------------------
    private boolean loadFocusMode() {
        return getSharedPreferences(GhostService.MSG_PREFS, MODE_PRIVATE)
                .getBoolean(GhostService.KEY_FOCUS_MODE, false);
    }

    private void toggleFocusMode() {
        boolean enabled = !loadFocusMode();
        getSharedPreferences(GhostService.MSG_PREFS, MODE_PRIVATE)
                .edit().putBoolean(GhostService.KEY_FOCUS_MODE, enabled).apply();
        updateSummaries();
        Log.i("GhostProtocol", "Focus Mode " + (enabled ? "enabled" : "disabled") + ".");
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
        updateStatus();
        healthPoller.postDelayed(this::updateStatus, 1000);
    }

    private void forceRestartService() {
        restarting = true;
        updateStatus();

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
                restarting = false;
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
        refreshAll();
        healthPoller.post(healthCheck);
    }

    @Override
    protected void onPause() {
        super.onPause();
        healthPoller.removeCallbacks(healthCheck);
    }

    private void refreshAll() {
        updateStatus();
        updateSummaries();
        usageNotice.setVisibility(hasUsageAccess() ? View.GONE : View.VISIBLE);
    }

    private void updateStatus() {
        if (statusButton == null) return; // UI not yet built
        boolean listenerEnabled = isNotificationServiceEnabled();
        boolean keepAliveRunning = KeepAliveService.isRunning();

        if (restarting) {
            showStatus(Theme.TEXT_TERTIARY, "Restarting…",
                    "Give it a second.",
                    "Restarting…", false, null, null, null);
            statusButton.setEnabled(false);
            statusButton.setAlpha(0.5f);
            return;
        }
        statusButton.setEnabled(true);
        statusButton.setAlpha(1f);

        if (manuallyStopped) {
            showStatus(Theme.TEXT_TERTIARY, "Paused",
                    "Nobody will get an auto-reply until you turn it back on.",
                    "Turn on", true, v -> startProtocol(),
                    null, null);

        } else if (listenerEnabled && keepAliveRunning) {
            showStatus(Theme.ACCENT, "Active",
                    "Replying to WhatsApp messages while you're away.",
                    "Pause", false, v -> stopProtocol(),
                    "Not replying? Restart", v -> forceRestartService());

        } else if (listenerEnabled) {
            showStatus(Theme.WARN, "Needs a restart",
                    "Ghost Protocol stopped running in the background.",
                    "Restart", true, v -> forceRestartService(),
                    "Pause instead", v -> stopProtocol());

        } else {
            showStatus(Theme.DANGER, "Finish setup",
                    "Allow notification access so Ghost Protocol can see new WhatsApp messages.",
                    "Allow access", true,
                    v -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)),
                    "Pause", v -> stopProtocol());
        }
    }

    private void showStatus(int dotColor, String title, String body,
                            String buttonLabel, boolean primary, View.OnClickListener onButton,
                            String link, View.OnClickListener onLink) {
        Ui.setDotColor(statusDot, dotColor);
        statusTitle.setText(title);
        statusBody.setText(body);

        statusButton.setText(buttonLabel);
        statusButton.setTextColor(primary ? Theme.ON_ACCENT : Theme.TEXT_PRIMARY);
        statusButton.setBackground(Ui.pressable(this,
                primary ? Theme.ACCENT : Theme.SURFACE_HIGH, 25));
        statusButton.setOnClickListener(onButton);

        if (link == null) {
            statusLink.setVisibility(View.GONE);
        } else {
            statusLink.setVisibility(View.VISIBLE);
            statusLink.setText(link);
            statusLink.setOnClickListener(onLink);
        }
    }

    // -------------------------------------------------------------------------
    // Row summaries — whitelist / rules / pool / history
    // -------------------------------------------------------------------------
    private void updateSummaries() {
        if (repliesSummary == null) return;
        SharedPreferences prefs = getSharedPreferences(
                GhostService.MSG_PREFS, Context.MODE_PRIVATE);
        boolean focus = loadFocusMode();

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

        focusSwitch.setChecked(focus);
        TextView focusSummary = subtitleOf((LinearLayout) focusSwitch.getParent());
        focusSummary.setText(focus
                ? "Only people on your contact list get replies"
                : "Everyone gets replies, except your contact list");

        repliesSummary.setText(plural(poolCount, "message") + ", plus call & unknown-number replies");
        keywordsSummary.setText(kwCount == 0
                ? "Reply differently to certain words"
                : plural(kwCount, "rule"));
        contactsSummary.setText(plural(wlCount, "person", "people")
                + (focus ? " · the only ones who get replies" : " · never get replies"));
        historySummary.setText(histCount == 0
                ? "Nothing sent yet"
                : plural(histCount, "reply", "replies") + " sent");
    }

    private static String plural(int n, String one) {
        return plural(n, one, one + "s");
    }

    private static String plural(int n, String one, String many) {
        return n + " " + (n == 1 ? one : many);
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
