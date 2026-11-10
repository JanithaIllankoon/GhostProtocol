package com.example.ghostprotocol;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.text.format.DateUtils;
import android.util.Log;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

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

/** Secondary settings: timing, backup, testing, troubleshooting and permissions. */
public class SettingsActivity extends AppCompatActivity {

    private LinearLayout waitRow;
    private LinearLayout remindRow;
    private LinearLayout exportRow;
    private LinearLayout notifAccessRow;
    private LinearLayout usageAccessRow;

    private final ActivityResultLauncher<String> exportLauncher =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("application/json"),
                    uri -> { if (uri != null) exportTo(uri); });
    private final ActivityResultLauncher<String[]> importLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(),
                    uri -> { if (uri != null) importFrom(uri); });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout column = Ui.screen(this, "Settings", null, true);

        column.addView(Ui.sectionLabel(this, "Timing"));
        LinearLayout timing = Ui.card(this);
        waitRow = Ui.row(this, "Reply Again After", null, "", 0, v -> showTimingDialog(
                "Reply Again After",
                "How long before the same person can get another away message.",
                GhostService.KEY_WINDOW_MIN, GhostService.DEFAULT_WINDOW_MIN, "minute"));
        Ui.addRow(timing, waitRow);
        remindRow = Ui.row(this, "Remind Again After", null, "", 0, v -> showTimingDialog(
                "Remind Again After",
                "The Repeated Messages reply goes to the same person at most once in this time.",
                GhostService.KEY_SPAM_COOLDOWN_HR, GhostService.DEFAULT_SPAM_COOLDOWN_HR, "hour"));
        Ui.addRow(timing, remindRow);
        column.addView(timing);

        column.addView(Ui.sectionLabel(this, "Backup"));
        LinearLayout backup = Ui.card(this);
        exportRow = Ui.row(this, "Export Backup", null, "", 0,
                v -> exportLauncher.launch(backupFileName()));
        Ui.addRow(backup, exportRow);
        // Any file: some phones mislabel .json files, and the contents are
        // checked before anything is replaced.
        Ui.addRow(backup, Ui.row(this, "Import Backup", null, null, 0,
                v -> importLauncher.launch(new String[]{"*/*"})));
        column.addView(backup);

        column.addView(Ui.sectionLabel(this, "Help"));
        LinearLayout help = Ui.card(this);
        Ui.addRow(help, Ui.row(this, "Test Replies", null, null, 0,
                v -> startActivity(new Intent(this, TestRepliesActivity.class))));
        Ui.addRow(help, Ui.row(this, "Restart Auto-Reply", null, null, 0, v -> {
            AppState.restart(this);
            Toast.makeText(this, AppState.isPaused(this) ? "Auto-Reply is off" : "Restarted",
                    Toast.LENGTH_SHORT).show();
        }));
        column.addView(help);

        column.addView(Ui.sectionLabel(this, "Permissions"));
        LinearLayout perms = Ui.card(this);
        notifAccessRow = Ui.row(this, "Notification Access", null, "", 0,
                v -> AppState.openNotificationAccessSettings(this));
        Ui.addRow(perms, notifAccessRow);
        usageAccessRow = Ui.row(this, "Usage Access", null, "", 0,
                v -> AppState.openUsageAccessSettings(this));
        Ui.addRow(perms, usageAccessRow);
        column.addView(perms);

        TextView version = Ui.text(this, "Version " + AppState.versionName(this), 13, Theme.TEXT_TERTIARY);
        version.setGravity(Gravity.CENTER);
        version.setPadding(0, Ui.dp(this, 28), 0, 0);
        column.addView(version, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshValues();
    }

    private void refreshValues() {
        Ui.valueOf(waitRow).setText(plural(loadTiming(GhostService.KEY_WINDOW_MIN,
                GhostService.DEFAULT_WINDOW_MIN), "minute"));
        Ui.valueOf(remindRow).setText(plural(loadTiming(GhostService.KEY_SPAM_COOLDOWN_HR,
                GhostService.DEFAULT_SPAM_COOLDOWN_HR), "hour"));

        long lastExport = SettingsBackup.lastExport(this);
        Ui.valueOf(exportRow).setText(lastExport == 0 ? "Never"
                : DateUtils.getRelativeTimeSpanString(lastExport, System.currentTimeMillis(),
                        DateUtils.DAY_IN_MILLIS));

        setOnOff(notifAccessRow, AppState.hasNotificationAccess(this));
        setOnOff(usageAccessRow, AppState.hasUsageAccess(this));
    }

    private void setOnOff(LinearLayout row, boolean on) {
        TextView v = Ui.valueOf(row);
        v.setText(on ? "On" : "Off");
        v.setTextColor(on ? Theme.TEXT_SECONDARY : Theme.WARN);
    }

    private static String plural(int n, String unit) {
        return n + " " + unit + (n == 1 ? "" : "s");
    }

    // -------------------------------------------------------------------------
    // Timing
    // -------------------------------------------------------------------------
    private int loadTiming(String key, int defaultVal) {
        return getSharedPreferences(GhostService.MSG_PREFS, Context.MODE_PRIVATE)
                .getInt(key, defaultVal);
    }

    private void showTimingDialog(String title, String explanation, String prefKey,
                                  int defaultVal, String unit) {
        EditText input = Ui.input(this, String.valueOf(loadTiming(prefKey, defaultVal)), null, false);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setMessage(explanation)
                .setView(Ui.dialogBody(this,
                        Character.toUpperCase(unit.charAt(0)) + unit.substring(1) + "s", input))
                .setPositiveButton("Save", null)
                .setNegativeButton("Cancel", null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            int val;
            try {
                val = Integer.parseInt(input.getText().toString().trim());
            } catch (NumberFormatException e) {
                input.setError("Enter a whole number");
                return;
            }
            if (val < 1) { input.setError("Must be at least 1"); return; }
            getSharedPreferences(GhostService.MSG_PREFS, Context.MODE_PRIVATE)
                    .edit().putInt(prefKey, val).apply();
            refreshValues();
            dialog.dismiss();
        });
    }

    // -------------------------------------------------------------------------
    // Backup — export / import all replies and settings as a JSON file
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
                Toast.makeText(this, saved ? "Backup saved"
                                : "Couldn't save the backup. Try a different folder.",
                        Toast.LENGTH_LONG).show();
                refreshValues();
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
                ? " Saved " + DateFormat.getDateInstance(DateFormat.MEDIUM)
                        .format(new Date(backup.exportedAt)) + "."
                : "";
        String summary = backup.messageCount() + " away messages, "
                + backup.keywordCount() + " keyword replies, "
                + backup.contactCount() + " people." + saved + "\n\n"
                + "This replaces your current messages and settings.";
        new MaterialAlertDialogBuilder(this)
                .setTitle("Import Backup?")
                .setMessage(summary)
                .setPositiveButton("Import", (d, w) -> {
                    boolean ok = SettingsBackup.apply(this, backup);
                    Toast.makeText(this, ok ? "Backup imported"
                            : "Couldn't import the backup. Try again.", Toast.LENGTH_LONG).show();
                    refreshValues();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
