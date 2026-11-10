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
import android.graphics.drawable.GradientDrawable;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;

import org.json.JSONArray;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    // Status card
    private FrameLayout statusRing;
    private ImageView statusIcon;
    private TextView statusTitle;
    private TextView statusBody;
    private TextView statusButton;
    private TextView statusLink;

    // Usage-access notice (hidden once granted)
    private LinearLayout usageNotice;

    // Focus mode + settings rows
    private MaterialSwitch focusSwitch;
    private TextView focusSummary;
    private TextView exportSummary;
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

    // System file pickers for backup export / import
    private final ActivityResultLauncher<String> exportLauncher =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("application/json"),
                    uri -> { if (uri != null) exportTo(uri); });
    private final ActivityResultLauncher<String[]> importLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(),
                    uri -> { if (uri != null) importFrom(uri); });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Load persisted stop state — survives process death.
        // Old code used a non-persisted boolean, so killing the process
        // would auto-resume the protocol even after the user stopped it.
        manuallyStopped = loadManuallyStopped();
        ensureListenerEnabled();

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
                .setTitle("One More Permission")
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
                        .setTitle("Contacts Not Allowed")
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

        // App icon before the title
        LinearLayout bar = (LinearLayout) column.getChildAt(0);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.mipmap.ic_launcher_round);
        LinearLayout.LayoutParams iconParams =
                new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40));
        iconParams.setMarginEnd(Ui.dp(this, 12));
        bar.addView(icon, 0, iconParams);

        column.addView(buildStatusCard(), spaced(20));
        column.addView(buildUsageNotice(), spaced(12));

        // --- Replies ---
        column.addView(Ui.sectionLabel(this, "Replies"));
        LinearLayout replies = Ui.card(this);
        LinearLayout repliesRow = Ui.row(this, R.drawable.ic_chat, "Away Messages", " ", null, 0,
                v -> open(EditMessagesActivity.class));
        repliesSummary = Ui.subtitleOf(repliesRow);
        Ui.addRow(replies, repliesRow);

        LinearLayout keywordsRow = Ui.row(this, R.drawable.ic_tag, "Keyword Replies", " ", null, 0,
                v -> open(KeywordRulesActivity.class));
        keywordsSummary = Ui.subtitleOf(keywordsRow);
        Ui.addRow(replies, keywordsRow);
        column.addView(replies);

        // --- People ---
        column.addView(Ui.sectionLabel(this, "People"));
        LinearLayout people = Ui.card(this);
        Ui.addRow(people, buildFocusRow());
        LinearLayout contactsRow = Ui.row(this, R.drawable.ic_people, "Contact List", " ", null, 0,
                v -> open(WhitelistActivity.class));
        contactsSummary = Ui.subtitleOf(contactsRow);
        Ui.addRow(people, contactsRow);
        column.addView(people);

        // --- Activity ---
        column.addView(Ui.sectionLabel(this, "Activity"));
        LinearLayout activity = Ui.card(this);
        LinearLayout historyRow = Ui.row(this, R.drawable.ic_history, "Recent Replies", " ", null, 0,
                v -> open(HistoryActivity.class));
        historySummary = Ui.subtitleOf(historyRow);
        Ui.addRow(activity, historyRow);
        Ui.addRow(activity, Ui.row(this, R.drawable.ic_send, "Test Replies",
                "Try your replies without another phone", null, 0,
                v -> open(TestRepliesActivity.class)));
        column.addView(activity);

        // --- Backup ---
        column.addView(Ui.sectionLabel(this, "Backup & Restore"));
        LinearLayout backup = Ui.card(this);
        LinearLayout exportRow = Ui.row(this, R.drawable.ic_upload, "Export Backup", " ", null, 0,
                v -> exportLauncher.launch(backupFileName()));
        exportSummary = Ui.subtitleOf(exportRow);
        Ui.addRow(backup, exportRow);
        Ui.addRow(backup, Ui.row(this, R.drawable.ic_download, "Import Backup",
                "Restore from a backup file", null, 0,
                // Any file: some phones mislabel .json files, and the
                // contents are checked before anything is replaced.
                v -> importLauncher.launch(new String[]{"*/*"})));
        column.addView(backup);
        column.addView(Ui.hint(this,
                "Export a backup before you reinstall the app or switch phones, "
                        + "then import it to get your messages and settings back."));
    }

    private View buildStatusCard() {
        LinearLayout card = Ui.card(this);
        int p = Ui.dp(this, 20);
        card.setPadding(p, p, p, Ui.dp(this, 12));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        statusRing = new FrameLayout(this);
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        statusRing.setBackground(ring);
        statusIcon = Ui.icon(this, R.drawable.ic_eye, Theme.TEXT_TERTIARY, 26);
        statusRing.addView(statusIcon, new FrameLayout.LayoutParams(
                Ui.dp(this, 26), Ui.dp(this, 26), Gravity.CENTER));
        top.addView(statusRing, new LinearLayout.LayoutParams(Ui.dp(this, 56), Ui.dp(this, 56)));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(Ui.text(this, "Status", 13, Theme.TEXT_TERTIARY));
        statusTitle = Ui.bold(Ui.text(this, "Checking…", 24, Theme.TEXT_PRIMARY));
        texts.addView(statusTitle);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, Ui.WRAP, 1f);
        textParams.setMarginStart(Ui.dp(this, 16));
        top.addView(texts, textParams);
        card.addView(top);

        statusBody = Ui.text(this, "", 15, Theme.TEXT_SECONDARY);
        statusBody.setPadding(0, Ui.dp(this, 16), 0, Ui.dp(this, 20));
        card.addView(statusBody);

        statusButton = Ui.button(this, "", true, null);
        card.addView(statusButton);

        statusLink = Ui.bold(Ui.text(this, "", 14, Theme.TEXT_SECONDARY));
        statusLink.setGravity(Gravity.CENTER);
        statusLink.setMinHeight(Ui.dp(this, 44));
        statusLink.setBackground(Ui.pressable(this, Theme.SURFACE, 22));
        LinearLayout.LayoutParams linkParams = new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP);
        linkParams.topMargin = Ui.dp(this, 6);
        card.addView(statusLink, linkParams);
        return card;
    }

    private View buildUsageNotice() {
        usageNotice = Ui.card(this);
        LinearLayout row = Ui.row(this, R.drawable.ic_eye, "Allow Usage Access",
                "So replies pause while you're using WhatsApp.", null, 0,
                v -> openUsageAccessSettings());
        // Swap the mint icon chip for an amber one so it reads as a to-do.
        View chip = Ui.iconChip(this, R.drawable.ic_eye, Theme.WARN, Theme.alpha(Theme.WARN, 0.14f), 40);
        ((LinearLayout.LayoutParams) chip.getLayoutParams()).setMarginEnd(Ui.dp(this, 14));
        row.removeViewAt(0);
        row.addView(chip, 0);
        Ui.titleOf(row).setTextColor(Theme.WARN);
        usageNotice.addView(row);
        return usageNotice;
    }

    private View buildFocusRow() {
        LinearLayout row = Ui.row(this, R.drawable.ic_focus, "Focus Mode", " ", null, 0, null);
        focusSummary = Ui.subtitleOf(row);
        row.setBackground(Ui.pressable(this, Theme.SURFACE, 0));
        focusSwitch = new MaterialSwitch(this);
        focusSwitch.setClickable(false); // the whole row toggles
        focusSwitch.setFocusable(false);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP);
        sp.setMarginStart(Ui.dp(this, 8));
        row.addView(focusSwitch, sp);
        row.setOnClickListener(v -> toggleFocusMode());
        return row;
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
    // BACKUP — export / import all replies and settings as a JSON file
    // -------------------------------------------------------------------------
    private static final int MAX_BACKUP_BYTES = 2 * 1024 * 1024;

    private static String backupFileName() {
        String date = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        return "GhostProtocol-Backup-" + date + ".json";
    }

    private void exportTo(Uri uri) {
        new Thread(() -> {
            boolean ok;
            try {
                byte[] data = SettingsBackup.export(this).getBytes(StandardCharsets.UTF_8);
                try (OutputStream out = openForWrite(uri)) {
                    if (out == null) throw new IOException("No output stream");
                    out.write(data);
                }
                SettingsBackup.markExported(this);
                ok = true;
            } catch (Exception e) {
                Log.e("GhostProtocol", "Backup export failed", e);
                ok = false;
            }
            final boolean saved = ok;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                Toast.makeText(this, saved
                                ? "Backup saved"
                                : "Couldn't save the backup. Try a different folder.",
                        Toast.LENGTH_LONG).show();
                updateSummaries();
            });
        }).start();
    }

    /** "wt" truncates an existing file; some providers only accept "w". */
    private OutputStream openForWrite(Uri uri) throws IOException {
        try {
            return getContentResolver().openOutputStream(uri, "wt");
        } catch (IllegalArgumentException | FileNotFoundException e) {
            return getContentResolver().openOutputStream(uri, "w");
        }
    }

    private void importFrom(Uri uri) {
        new Thread(() -> {
            SettingsBackup.Backup backup = null;
            String error = null;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IOException("No input stream");
                backup = SettingsBackup.parse(readText(in));
            } catch (IllegalArgumentException e) {
                error = e.getMessage();
            } catch (Exception e) {
                Log.e("GhostProtocol", "Backup import failed", e);
                error = "Couldn't open that file.";
            }
            final SettingsBackup.Backup parsed = backup;
            final String message = error;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (parsed != null) {
                    confirmImport(parsed);
                } else {
                    new MaterialAlertDialogBuilder(this)
                            .setTitle("Can't Import")
                            .setMessage(message)
                            .setPositiveButton("OK", null)
                            .show();
                }
            });
        }).start();
    }

    private static String readText(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) != -1) {
            buf.write(chunk, 0, n);
            if (buf.size() > MAX_BACKUP_BYTES) {
                throw new IllegalArgumentException("This file is too large to be a Ghost Protocol backup.");
            }
        }
        return buf.toString("UTF-8");
    }

    private void confirmImport(SettingsBackup.Backup backup) {
        String saved = backup.exportedAt > 0
                ? ", saved on " + DateFormat.getDateInstance(DateFormat.MEDIUM)
                        .format(new Date(backup.exportedAt))
                : "";
        String summary = "This backup has " + plural(backup.messageCount(), "away message")
                + ", " + plural(backup.keywordCount(), "keyword reply", "keyword replies")
                + " and " + plural(backup.contactCount(), "person", "people")
                + " on the contact list" + saved + ".\n\n"
                + "It will replace your current messages and settings. "
                + "Your recent replies list stays as it is.";
        new MaterialAlertDialogBuilder(this)
                .setTitle("Import Backup?")
                .setMessage(summary)
                .setPositiveButton("Import", (d, w) -> {
                    if (SettingsBackup.apply(this, backup)) {
                        refreshAll();
                        Toast.makeText(this, "Backup imported", Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(this, "Couldn't import the backup. Try again.",
                                Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
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
    // Pausing only saves the flag; GhostService checks it and ignores every
    // notification while paused. The listener itself stays enabled — disabling
    // it made Android drop notification access whenever the app was updated
    // while paused, forcing the user to grant access again.
    private void stopProtocol() {
        manuallyStopped = true;
        saveManuallyStopped(true);
        stopService(new Intent(this, KeepAliveService.class));
        Log.i("GhostProtocol", "Protocol manually stopped.");
        updateStatus();
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
                    "Turn On", true, v -> startProtocol(),
                    null, null);

        } else if (listenerEnabled && keepAliveRunning) {
            showStatus(Theme.ACCENT, "Active",
                    "Replying to WhatsApp messages while you're away.",
                    "Pause", false, v -> stopProtocol(),
                    "Not Replying? Restart", v -> forceRestartService());

        } else if (listenerEnabled) {
            showStatus(Theme.WARN, "Needs a Restart",
                    "Ghost Protocol stopped running in the background.",
                    "Restart", true, v -> forceRestartService(),
                    "Pause Instead", v -> stopProtocol());

        } else {
            showStatus(Theme.DANGER, "Finish Setup",
                    "Allow notification access so Ghost Protocol can see new WhatsApp messages.",
                    "Allow Access", true,
                    v -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)),
                    "Pause", v -> stopProtocol());
        }
    }

    private void showStatus(int dotColor, String title, String body,
                            String buttonLabel, boolean primary, View.OnClickListener onButton,
                            String link, View.OnClickListener onLink) {
        GradientDrawable ring = (GradientDrawable) statusRing.getBackground();
        ring.setColor(Theme.alpha(dotColor, 0.12f));
        ring.setStroke(Ui.dp(this, 2), Theme.alpha(dotColor, 0.55f));
        statusIcon.setImageTintList(android.content.res.ColorStateList.valueOf(dotColor));
        statusTitle.setText(title);
        statusTitle.setTextColor(dotColor == Theme.TEXT_TERTIARY ? Theme.TEXT_PRIMARY : dotColor);
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

        long lastExport = SettingsBackup.lastExport(this);
        exportSummary.setText(lastExport == 0
                ? "Save your messages and settings to a file"
                : "Last saved " + DateUtils.getRelativeTimeSpanString(
                        lastExport, System.currentTimeMillis(), DateUtils.DAY_IN_MILLIS)
                        .toString().toLowerCase(Locale.getDefault()));
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
