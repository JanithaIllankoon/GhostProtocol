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
import java.util.List;

public class KeywordRulesActivity extends AppCompatActivity {

    private LinearLayout listCard;

    private final List<String> keywords = new ArrayList<>();
    private final List<String> replies  = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        loadRules();
        buildUI();
    }

    // -------------------------------------------------------------------------
    // Persistence
    // -------------------------------------------------------------------------
    private void loadRules() {
        SharedPreferences prefs = getSharedPreferences(GhostService.MSG_PREFS, Context.MODE_PRIVATE);
        int count = prefs.getInt("kw_count", 0);
        keywords.clear();
        replies.clear();
        for (int i = 0; i < count; i++) {
            keywords.add(prefs.getString("kw_key_" + i, ""));
            replies.add(prefs.getString("kw_reply_" + i, ""));
        }
    }

    private void saveRules() {
        SharedPreferences prefs = getSharedPreferences(GhostService.MSG_PREFS, Context.MODE_PRIVATE);
        int oldCount = prefs.getInt("kw_count", 0);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putInt("kw_count", keywords.size());
        for (int i = 0; i < keywords.size(); i++) {
            editor.putString("kw_key_" + i, keywords.get(i));
            editor.putString("kw_reply_" + i, replies.get(i));
        }
        // Remove stale keys from a previously larger rule set
        for (int i = keywords.size(); i < oldCount; i++) {
            editor.remove("kw_key_" + i);
            editor.remove("kw_reply_" + i);
        }
        editor.apply();
    }

    // -------------------------------------------------------------------------
    // UI
    // -------------------------------------------------------------------------
    private void buildUI() {
        LinearLayout column = Ui.screen(this, "Keyword Replies", null, true);

        listCard = Ui.card(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP);
        lp.topMargin = Ui.dp(this, 20);
        column.addView(listCard, lp);
        column.addView(Ui.hint(this, "Unknown numbers always get the Unknown Number reply."));

        rebuildList();
    }

    private void rebuildList() {
        listCard.removeAllViews();

        if (keywords.isEmpty()) {
            Ui.addRow(listCard, Ui.emptyRow(this,
                    "Reply differently when a message has certain words.\n"
                            + "For example, \"Happy New Year!\" to anyone who says \"new year\"."));
        }

        for (int i = 0; i < keywords.size(); i++) {
            final int index = i;
            Ui.addRow(listCard, Ui.row(this, formatKeywords(keywords.get(i)), replies.get(i),
                    null, 2, v -> showRuleDialog(index, keywords.get(index), replies.get(index))));
        }

        Ui.addRow(listCard, Ui.addRowButton(this, "Add Keyword Reply",
                v -> showRuleDialog(-1, "", "")));
    }

    /** "new year,HNY" → "“new year”, “HNY”" */
    private static String formatKeywords(String raw) {
        StringBuilder sb = new StringBuilder();
        for (String kw : raw.split(",")) {
            String trimmed = kw.trim();
            if (trimmed.isEmpty()) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append('“').append(trimmed).append('”');
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Add / Edit dialog
    // -------------------------------------------------------------------------
    private void showRuleDialog(final int index, String currentKeyword, String currentReply) {
        EditText kwInput = Ui.input(this, currentKeyword, "e.g.  new year, hny", false);
        EditText replyInput = Ui.input(this, currentReply, "e.g.  Happy New Year! 🎉", true);

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
                .setTitle(index == -1 ? "New Keyword Reply" : "Edit Keyword Reply")
                .setView(Ui.dialogBody(this,
                        "If a message contains (separate words with commas)", kwInput,
                        "Reply With", replyInput))
                .setPositiveButton("Save", null)
                .setNegativeButton("Cancel", null);
        if (index != -1) builder.setNeutralButton("Delete", null);
        AlertDialog dialog = builder.create();
        dialog.show();

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String kwRaw  = kwInput.getText().toString().trim();
            String reply  = replyInput.getText().toString().trim();

            if (kwRaw.isEmpty()) {
                kwInput.setError("Enter at least one word");
                return;
            }
            if (reply.isEmpty()) {
                replyInput.setError("Enter a reply");
                return;
            }

            // Check for duplicate keywords in other rules
            String[] newKws = kwRaw.split(",");
            for (String newKw : newKws) {
                String newKwLower = newKw.toLowerCase().trim();
                if (newKwLower.isEmpty()) continue;
                for (int i = 0; i < keywords.size(); i++) {
                    if (i == index) continue;
                    String[] existingKws = keywords.get(i).split(",");
                    for (String exKw : existingKws) {
                        if (exKw.toLowerCase().trim().equals(newKwLower)) {
                            kwInput.setError("\"" + newKw.trim() + "\" is already used in another rule");
                            return;
                        }
                    }
                }
            }

            if (index == -1) {
                keywords.add(kwRaw);
                replies.add(reply);
            } else {
                keywords.set(index, kwRaw);
                replies.set(index, reply);
            }
            saveRules();
            rebuildList();
            dialog.dismiss();
        });

        if (index != -1) {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setTextColor(Theme.DANGER);
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                keywords.remove(index);
                replies.remove(index);
                saveRules();
                rebuildList();
                dialog.dismiss();
            });
        }
    }
}
