package com.example.ghostprotocol;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

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

    private LinearLayout listContainer;
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
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Theme.BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(40, 50, 40, 50);

        TextView header = new TextView(this);
        header.setText("Whitelist");
        header.setTextColor(Theme.PRIMARY);
        header.setTextSize(20);
        header.setTypeface(null, Typeface.BOLD);
        header.setGravity(Gravity.CENTER);
        header.setPadding(0, 0, 0, 8);

        TextView subHeader = new TextView(this);
        subHeader.setText("Contacts listed here are never auto-replied to. Match is exact (case-insensitive) — enter the name exactly as it appears in WhatsApp.");
        subHeader.setTextColor(Theme.TEXT_MUTED);
        subHeader.setTextSize(12);
        subHeader.setGravity(Gravity.CENTER);
        subHeader.setPadding(0, 0, 0, 30);

        listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);

        Button addBtn = new Button(this);
        addBtn.setText("＋  ADD CONTACT");
        addBtn.setAllCaps(false);
        addBtn.setTextColor(Theme.PRIMARY);
        addBtn.setBackgroundColor(Theme.PRIMARY_BG);
        addBtn.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams addParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 120);
        addParams.setMargins(0, 20, 0, 0);
        addBtn.setLayoutParams(addParams);
        addBtn.setOnClickListener(v -> showEditDialog(-1, ""));

        root.addView(header);
        root.addView(subHeader);
        root.addView(listContainer);
        root.addView(addBtn);
        scroll.addView(root);
        setContentView(scroll);

        rebuildList();
    }

    private void rebuildList() {
        listContainer.removeAllViews();

        if (entries.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No whitelisted contacts.\nTap the button below to add one.");
            empty.setTextColor(Theme.TEXT_MUTED);
            empty.setTextSize(13);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, 20, 0, 20);
            listContainer.addView(empty);
            return;
        }

        for (int i = 0; i < entries.size(); i++) {
            listContainer.addView(buildCard(i));
        }
    }

    private View buildCard(final int index) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(Theme.CARD_BG);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        cardParams.setMargins(0, 0, 0, 14);
        card.setLayoutParams(cardParams);
        card.setPadding(24, 20, 24, 16);

        TextView name = new TextView(this);
        name.setText(entries.get(index));
        name.setTextColor(Theme.TEXT_PRIMARY);
        name.setTextSize(15);
        name.setTypeface(null, Typeface.BOLD);
        name.setPadding(0, 0, 0, 14);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);

        Button editBtn = new Button(this);
        editBtn.setText("✎  Edit");
        editBtn.setAllCaps(false);
        editBtn.setTextColor(Theme.WARN);
        editBtn.setBackgroundColor(Theme.EDIT_BG);
        editBtn.setTextSize(12);
        LinearLayout.LayoutParams editParams = new LinearLayout.LayoutParams(0, 90, 1f);
        editParams.setMargins(0, 0, 8, 0);
        editBtn.setLayoutParams(editParams);
        editBtn.setOnClickListener(v -> showEditDialog(index, entries.get(index)));

        Button deleteBtn = new Button(this);
        deleteBtn.setText("✕  Remove");
        deleteBtn.setAllCaps(false);
        deleteBtn.setTextColor(Theme.DANGER);
        deleteBtn.setBackgroundColor(Theme.DELETE_BG);
        deleteBtn.setTextSize(12);
        deleteBtn.setLayoutParams(new LinearLayout.LayoutParams(0, 90, 1f));
        deleteBtn.setOnClickListener(v -> {
            entries.remove(index);
            saveEntries();
            rebuildList();
        });

        btnRow.addView(editBtn);
        btnRow.addView(deleteBtn);
        card.addView(name);
        card.addView(btnRow);
        return card;
    }

    private void showEditDialog(final int index, String current) {
        EditText input = new EditText(this);
        input.setText(current);
        input.setHint("e.g.  Alice Example");
        input.setHintTextColor(Theme.TEXT_DK);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setBackgroundColor(Theme.INPUT_BG);
        input.setTextColor(Theme.TEXT_PRIMARY);
        input.setPadding(20, 20, 20, 20);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(index == -1 ? "Add Contact" : "Edit Contact")
                .setView(input)
                .setPositiveButton("Save", null)
                .setNegativeButton("Cancel", null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String val = input.getText().toString().trim();
            if (val.isEmpty()) {
                showAlert("Empty", "Contact name can't be empty.");
                return;
            }
            // Duplicate check (case-insensitive)
            for (int i = 0; i < entries.size(); i++) {
                if (i == index) continue;
                if (entries.get(i).equalsIgnoreCase(val)) {
                    showAlert("Duplicate", "\"" + val + "\" is already whitelisted.");
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
    }

    private void showAlert(String title, String msg) {
        new AlertDialog.Builder(this)
                .setTitle(title).setMessage(msg)
                .setPositiveButton("OK", null).show();
    }
}