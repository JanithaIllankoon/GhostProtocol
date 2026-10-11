package com.example.ghostprotocol;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.LinearLayout;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Manage the trusted contact whitelist. Titles listed here are NEVER
 * auto-replied to — GhostService stands down when it sees them.
 *
 * Matching uses exact case-insensitive comparison to prevent false positives
 * (e.g. "Alice" no longer accidentally matches "Alicia" or "Malice").
 */
public class WhitelistActivity extends AppCompatActivity {

    private LinearLayout listCard;
    private final List<String> entries = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        loadEntries();
        buildUI();
    }

    // -------------------------------------------------------------------------
    // Persistence
    // -------------------------------------------------------------------------
    private void loadEntries() {
        SharedPreferences prefs = getSharedPreferences(GhostService.MSG_PREFS, Context.MODE_PRIVATE);
        int count = prefs.getInt("whitelist_count", -1);
        entries.clear();
        if (count == -1) {
            // First run — seed from hardcoded defaults
            entries.addAll(Arrays.asList(GhostService.DEFAULT_WHITELIST));
        } else {
            for (int i = 0; i < count; i++) {
                String entry = prefs.getString("whitelist_" + i, "");
                if (!entry.isEmpty()) entries.add(entry);
            }
        }
    }

    private void saveEntries() {
        SharedPreferences prefs = getSharedPreferences(
                GhostService.MSG_PREFS, Context.MODE_PRIVATE);
        int oldCount = Math.max(prefs.getInt("whitelist_count", -1), 0);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putInt("whitelist_count", entries.size());
        for (int i = 0; i < entries.size(); i++) {
            editor.putString("whitelist_" + i, entries.get(i));
        }
        // Remove stale keys from a previously larger whitelist
        for (int i = entries.size(); i < oldCount; i++) {
            editor.remove("whitelist_" + i);
        }
        editor.apply();
    }

    // -------------------------------------------------------------------------
    // UI
    // -------------------------------------------------------------------------
    private void buildUI() {
        boolean focus = getSharedPreferences(GhostService.MSG_PREFS, Context.MODE_PRIVATE)
                .getBoolean(GhostService.KEY_FOCUS_MODE, false);

        LinearLayout column = Ui.screen(this, "Contact list",
                focus
                        ? "Focus mode is on, so only these people get auto-replies."
                        : "These people never get auto-replies.",
                true);

        column.addView(Ui.sectionLabel(this, "People"));
        listCard = Ui.card(this);
        column.addView(listCard);
        column.addView(Ui.hint(this,
                "Type each name exactly as it shows in WhatsApp. "
                        + "Turn on Focus mode on the home screen to flip this list, "
                        + "so only these people get replies."));

        rebuildList();
    }

    private void rebuildList() {
        listCard.removeAllViews();

        if (entries.isEmpty()) {
            Ui.addRow(listCard, Ui.emptyRow(this, "Nobody on the list yet."));
        }

        for (int i = 0; i < entries.size(); i++) {
            final int index = i;
            Ui.addRow(listCard, Ui.row(this, entries.get(i), null, null, 1,
                    v -> showEditDialog(index, entries.get(index))));
        }

        Ui.addRow(listCard, Ui.addRowButton(this, "Add person",
                v -> showEditDialog(-1, "")));
    }

    private void showEditDialog(final int index, String current) {
        EditText input = Ui.input(this, current, "e.g.  Alice Example", false);

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
                .setTitle(index == -1 ? "Add person" : "Edit person")
                .setView(Ui.dialogBody(this, "Name as shown in WhatsApp", input))
                .setPositiveButton("Save", null)
                .setNegativeButton("Cancel", null);
        if (index != -1) builder.setNeutralButton("Remove", null);
        AlertDialog dialog = builder.create();
        dialog.show();

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String val = input.getText().toString().trim();
            if (val.isEmpty()) {
                input.setError("Name can't be empty");
                return;
            }
            // Duplicate check (case-insensitive)
            for (int i = 0; i < entries.size(); i++) {
                if (i == index) continue;
                if (entries.get(i).equalsIgnoreCase(val)) {
                    input.setError("Already on the list");
                    return;
                }
            }
            if (index == -1) {
                entries.add(val);
            } else {
                entries.set(index, val);
            }
            saveEntries();
            rebuildList();
            dialog.dismiss();
        });

        if (index != -1) {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setTextColor(Theme.DANGER);
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                entries.remove(index);
                saveEntries();
                rebuildList();
                dialog.dismiss();
            });
        }
    }
}
