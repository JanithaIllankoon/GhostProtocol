package com.example.ghostprotocol;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
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

/**
 * Home screen: one on/off control, the things people edit most, and a gear
 * for everything else (see {@link SettingsActivity}).
 */
public class MainActivity extends AppCompatActivity {

    // Status
    private FrameLayout statusRing;
    private ImageView statusIcon;
    private TextView statusLine;
    private MaterialSwitch powerSwitch;
    private LinearLayout problemBox;
    private TextView problemText;
    private TextView problemButton;

    // Usage-access prompt (hidden once granted)
    private View usageCard;

    // Rows whose trailing value / title change
    private LinearLayout messagesRow;
    private LinearLayout keywordsRow;
    private LinearLayout replyToRow;
    private LinearLayout listRow;
    private LinearLayout historyRow;

    private boolean restarting = false;

    private final Handler healthPoller = new Handler(Looper.getMainLooper());
    private final Runnable healthCheck = new Runnable() {
        @Override
        public void run() {
            updateStatus();
            healthPoller.postDelayed(this, 10000);
        }
    };

    // Replaces the deprecated startActivityForResult for overlay permission.
    private final ActivityResultLauncher<Intent> overlayLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (Settings.canDrawOverlays(this)) proceedWithSetup();
                        // If still denied, the user can reopen the app to try again.
                    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ensureListenerEnabled();

        // 1. OVERLAY PERMISSION
        if (!Settings.canDrawOverlays(this)) {
            overlayLauncher.launch(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        proceedWithSetup();
    }

    /**
     * Runs once overlay permission is confirmed. Requests the remaining
     * permissions, builds the UI and starts the anchor service.
     */
    private void proceedWithSetup() {
        // 2. Permissions are requested one at a time — Android can silently drop
        //    the second of two requestPermissions() calls fired together.
        //    POST_NOTIFICATIONS first; READ_CONTACTS follows in the result handler.
        boolean willPromptNotifications = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                    this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, 101);
            willPromptNotifications = true;
        }
        if (!willPromptNotifications) requestContactsIfNeeded();

        // 3. UI
        buildUI();

        // 4. Anchor service
        if (!AppState.isPaused(this)) AppState.startKeepAlive(this);

        // 5. Initial state + health polling
        refreshAll();
        healthPoller.post(healthCheck);

        // 6. Usage access — lets the app stay quiet while WhatsApp is open.
        maybePromptUsageAccess();
    }

    /** Re-enables the listener if an older version disabled it on pause. */
    private void ensureListenerEnabled() {
        ComponentName ghost = new ComponentName(this, GhostService.class);
        PackageManager pm = getPackageManager();
        if (pm.getComponentEnabledSetting(ghost) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
            pm.setComponentEnabledSetting(ghost,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
            Log.i("GhostProtocol", "Re-enabled listener left disabled by an older pause.");
        }
    }

    // -------------------------------------------------------------------------
    // Permissions
    // -------------------------------------------------------------------------
    private void maybePromptUsageAccess() {
        if (AppState.hasUsageAccess(this)) return;
        new MaterialAlertDialogBuilder(this)
                .setTitle("Allow Usage Access")
                .setMessage("This lets Ghost Protocol stay quiet while you're using WhatsApp.\n\n"
                        + "On the next screen, turn it on for Ghost Protocol.")
                .setPositiveButton("Continue", (d, w) -> AppState.openUsageAccessSettings(this))
                .setNegativeButton("Later", null)
                .show();
    }

    private void requestContactsIfNeeded() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                    this, new String[]{Manifest.permission.READ_CONTACTS}, 102);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 101) {
            requestContactsIfNeeded(); // notifications dialog closed — now contacts
            return;
        }
        if (requestCode == 102 && grantResults.length > 0
                && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("Contacts Not Allowed")
                    .setMessage("Without contacts, everyone is treated as an unknown number. "
                            + "You can allow it later in Settings › Apps › Ghost Protocol.")
                    .setPositiveButton("OK", null)
                    .show();
        }
    }

    // -------------------------------------------------------------------------
    // UI
    // -------------------------------------------------------------------------
    private void buildUI() {
        LinearLayout column = Ui.screen(this, "Ghost Protocol", null, false);

        LinearLayout bar = (LinearLayout) column.getChildAt(0);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.mipmap.ic_launcher_round);
        LinearLayout.LayoutParams iconParams =
                new LinearLayout.LayoutParams(Ui.dp(this, 36), Ui.dp(this, 36));
        iconParams.setMarginEnd(Ui.dp(this, 12));
        bar.addView(icon, 0, iconParams);
        bar.addView(Ui.iconButton(this, R.drawable.ic_settings, "Settings",
                v -> open(SettingsActivity.class)));

        column.addView(buildStatusCard(), spaced(12));
        usageCard = buildUsageCard();
        column.addView(usageCard, spaced(12));

        // Replies
        column.addView(Ui.sectionLabel(this, "Replies"));
        LinearLayout replies = Ui.card(this);
        messagesRow = Ui.row(this, R.drawable.ic_chat, "Away Messages", null, "", 0,
                v -> open(EditMessagesActivity.class));
        Ui.addRow(replies, messagesRow);
        keywordsRow = Ui.row(this, R.drawable.ic_tag, "Keyword Replies", null, "", 0,
                v -> open(KeywordRulesActivity.class));
        Ui.addRow(replies, keywordsRow);
        column.addView(replies);

        // People
        column.addView(Ui.sectionLabel(this, "People"));
        LinearLayout people = Ui.card(this);
        replyToRow = Ui.row(this, R.drawable.ic_focus, "Reply To", null, "", 0,
                v -> chooseReplyTo());
        Ui.addRow(people, replyToRow);
        listRow = Ui.row(this, R.drawable.ic_people, "Never Reply To", null, "", 0,
                v -> open(WhitelistActivity.class));
        Ui.addRow(people, listRow);
        column.addView(people);

        // Activity
        column.addView(Ui.sectionLabel(this, "Activity"));
        LinearLayout activity = Ui.card(this);
        historyRow = Ui.row(this, R.drawable.ic_history, "Recent Replies", null, "", 0,
                v -> open(HistoryActivity.class));
        Ui.addRow(activity, historyRow);
        column.addView(activity);
    }

    /** Status ring, "Auto-Reply" with its state, and the on/off switch. */
    private View buildStatusCard() {
        LinearLayout card = Ui.card(this);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        int p = Ui.dp(this, 18);
        top.setPadding(p, p, p, p);
        top.setBackground(Ui.pressable(this, Theme.SURFACE, 0));
        top.setOnClickListener(v -> togglePower());

        statusRing = new FrameLayout(this);
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        statusRing.setBackground(ring);
        statusIcon = Ui.icon(this, R.drawable.ic_eye, Theme.TEXT_TERTIARY, 24);
        statusRing.addView(statusIcon, new FrameLayout.LayoutParams(
                Ui.dp(this, 24), Ui.dp(this, 24), Gravity.CENTER));
        top.addView(statusRing, new LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 52)));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(Ui.bold(Ui.text(this, "Auto-Reply", 20, Theme.TEXT_PRIMARY)));
        statusLine = Ui.bold(Ui.text(this, "", 15, Theme.TEXT_SECONDARY));
        texts.addView(statusLine);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, Ui.WRAP, 1f);
        tp.setMarginStart(Ui.dp(this, 16));
        top.addView(texts, tp);

        powerSwitch = new MaterialSwitch(this);
        powerSwitch.setClickable(false); // the whole row toggles
        powerSwitch.setFocusable(false);
        top.addView(powerSwitch);
        card.addView(top);

        // Shown only when something needs fixing
        problemBox = new LinearLayout(this);
        problemBox.setOrientation(LinearLayout.VERTICAL);
        problemBox.setPadding(p, 0, p, p);
        problemText = Ui.text(this, "", 14, Theme.TEXT_SECONDARY);
        problemText.setPadding(0, 0, 0, Ui.dp(this, 14));
        problemBox.addView(problemText);
        problemButton = Ui.button(this, "", true, null);
        problemBox.addView(problemButton);
        card.addView(problemBox);
        return card;
    }

    private View buildUsageCard() {
        LinearLayout card = Ui.card(this);
        LinearLayout row = Ui.row(this, R.drawable.ic_eye, "Allow Usage Access",
                "Stays quiet while you're using WhatsApp", null, 0,
                v -> AppState.openUsageAccessSettings(this));
        View chip = Ui.iconChip(this, R.drawable.ic_eye, Theme.WARN, Theme.alpha(Theme.WARN, 0.14f), 40);
        ((LinearLayout.LayoutParams) chip.getLayoutParams()).setMarginEnd(Ui.dp(this, 14));
        row.removeViewAt(0);
        row.addView(chip, 0);
        Ui.titleOf(row).setTextColor(Theme.WARN);
        card.addView(row);
        return card;
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
    // Reply To — "Everyone" (list is excluded) or "Only Selected People"
    // (list is the only people who get replies). Stored as focus_mode.
    // -------------------------------------------------------------------------
    private boolean onlySelected() {
        return getSharedPreferences(GhostService.MSG_PREFS, MODE_PRIVATE)
                .getBoolean(GhostService.KEY_FOCUS_MODE, false);
    }

    private void chooseReplyTo() {
        String[] options = {"Everyone", "Only Selected People"};
        new MaterialAlertDialogBuilder(this)
                .setTitle("Reply To")
                .setSingleChoiceItems(options, onlySelected() ? 1 : 0, (d, which) -> {
                    getSharedPreferences(GhostService.MSG_PREFS, MODE_PRIVATE)
                            .edit().putBoolean(GhostService.KEY_FOCUS_MODE, which == 1).apply();
                    updateSummaries();
                    d.dismiss();
                })
                .show();
    }

    // -------------------------------------------------------------------------
    // On / off / restart
    // -------------------------------------------------------------------------
    private void togglePower() {
        if (restarting) return;
        if (AppState.isPaused(this)) {
            // Pausing only saves a flag that GhostService checks; the listener
            // stays enabled so Android never revokes notification access.
            AppState.setPaused(this, false);
            AppState.startKeepAlive(this);
            healthPoller.postDelayed(this::updateStatus, 1000);
        } else {
            AppState.setPaused(this, true);
            stopService(new Intent(this, KeepAliveService.class));
        }
        updateStatus();
    }

    private void restart() {
        restarting = true;
        updateStatus();
        AppState.restart(this);
        healthPoller.postDelayed(() -> {
            restarting = false;
            updateStatus();
        }, 2000);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (powerSwitch == null) return; // UI not built yet (overlay permission pending)
        if (!AppState.isPaused(this)) AppState.startKeepAlive(this);
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
        usageCard.setVisibility(AppState.hasUsageAccess(this) ? View.GONE : View.VISIBLE);
    }

    private void updateStatus() {
        if (powerSwitch == null) return;
        boolean paused = AppState.isPaused(this);

        if (restarting) {
            showStatus(Theme.TEXT_TERTIARY, "Restarting…", true, null, null, null);
        } else if (paused) {
            showStatus(Theme.TEXT_TERTIARY, "Off", false, null, null, null);
        } else if (!AppState.hasNotificationAccess(this)) {
            showStatus(Theme.DANGER, "Needs Access", true,
                    "Allow notification access so it can see new WhatsApp messages.",
                    "Allow Access", v -> AppState.openNotificationAccessSettings(this));
        } else if (!KeepAliveService.isRunning()) {
            showStatus(Theme.WARN, "Not Running", true,
                    "It stopped running in the background.",
                    "Restart", v -> restart());
        } else {
            showStatus(Theme.ACCENT, "On", true, null, null, null);
        }
    }

    private void showStatus(int color, String line, boolean on,
                            String problem, String action, View.OnClickListener onAction) {
        GradientDrawable ring = (GradientDrawable) statusRing.getBackground();
        ring.setColor(Theme.alpha(color, 0.12f));
        ring.setStroke(Ui.dp(this, 2), Theme.alpha(color, 0.55f));
        statusIcon.setImageTintList(ColorStateList.valueOf(color));
        statusLine.setText(line);
        statusLine.setTextColor(color == Theme.TEXT_TERTIARY ? Theme.TEXT_SECONDARY : color);
        powerSwitch.setChecked(on);
        powerSwitch.setEnabled(!restarting);

        if (problem == null) {
            problemBox.setVisibility(View.GONE);
        } else {
            problemBox.setVisibility(View.VISIBLE);
            problemText.setText(problem);
            problemButton.setText(action);
            problemButton.setOnClickListener(onAction);
        }
    }

    // -------------------------------------------------------------------------
    // Row values
    // -------------------------------------------------------------------------
    private void updateSummaries() {
        if (messagesRow == null) return;
        SharedPreferences prefs = getSharedPreferences(GhostService.MSG_PREFS, Context.MODE_PRIVATE);
        boolean only = onlySelected();

        int poolCount = prefs.getInt("pool_count", 0);
        if (poolCount == 0) poolCount = GhostService.IDX_SPAM_WARNING; // default pool size
        int kwCount = prefs.getInt("kw_count", 0);
        int wlCount = prefs.getInt("whitelist_count", -1);
        if (wlCount == -1) wlCount = GhostService.DEFAULT_WHITELIST.length;
        int histCount = 0;
        try {
            histCount = new JSONArray(prefs.getString(GhostService.HISTORY_KEY, "[]")).length();
        } catch (Exception ignored) {}

        Ui.valueOf(messagesRow).setText(String.valueOf(poolCount));
        Ui.valueOf(keywordsRow).setText(countOrNone(kwCount));
        Ui.valueOf(replyToRow).setText(only ? "Selected" : "Everyone");
        Ui.titleOf(listRow).setText(only ? "Only Reply To" : "Never Reply To");
        Ui.valueOf(listRow).setText(countOrNone(wlCount));
        Ui.valueOf(historyRow).setText(countOrNone(histCount));
    }

    private static String countOrNone(int n) {
        return n == 0 ? "None" : String.valueOf(n);
    }
}
