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
import java.util.List;

public class KeywordRulesActivity extends AppCompatActivity {

    private LinearLayout listContainer;

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
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Theme.BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(40, 50, 40, 50);

        TextView header = new TextView(this);
        header.setText("Keyword Rules");
        header.setTextColor(Theme.PRIMARY);
        header.setTextSize(20);
        header.setTypeface(null, Typeface.BOLD);
        header.setGravity(Gravity.CENTER);
        header.setPadding(0, 0, 0, 8);

        TextView subHeader = new TextView(this);
        subHeader.setText("For saved contacts only: if an incoming message contains a keyword, the paired reply is sent immediately — bypassing the normal auto-reply logic. Unknown senders always get the stranger message instead.");
        subHeader.setTextColor(Theme.TEXT_MUTED);
        subHeader.setTextSize(12);
        subHeader.setGravity(Gravity.CENTER);
        subHeader.setPadding(0, 0, 0, 30);

        listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);

        Button addBtn = new Button(this);
        addBtn.setText("＋  ADD KEYWORD RULE");
        addBtn.setAllCaps(false);
        addBtn.setTextColor(Theme.PRIMARY);
        addBtn.setBackgroundColor(Theme.PRIMARY_BG);
        addBtn.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams addParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 120);
        addParams.setMargins(0, 20, 0, 0);
        addBtn.setLayoutParams(addParams);
        addBtn.setOnClickListener(v -> showRuleDialog(-1, "", ""));

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

        if (keywords.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No keyword rules yet.\nTap the button below to add one.");
            empty.setTextColor(Theme.TEXT_MUTED);
            empty.setTextSize(13);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, 20, 0, 20);
            listContainer.addView(empty);
            return;
        }

        for (int i = 0; i < keywords.size(); i++) {
            listContainer.addView(buildRuleCard(i));
        }
    }

    private View buildRuleCard(final int index) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(Theme.CARD_BG);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        cardParams.setMargins(0, 0, 0, 14);
        card.setLayoutParams(cardParams);
        card.setPadding(24, 20, 24, 16);

        TextView ifLabel = new TextView(this);
        ifLabel.setText("IF ANY OF THESE KEYWORDS ARE RECEIVED");
        ifLabel.setTextColor(Theme.TEXT_TERTIARY);
        ifLabel.setTextSize(10);
        ifLabel.setTypeface(null, Typeface.BOLD);
        ifLabel.setPadding(0, 0, 0, 8);

        LinearLayout chipsRow = new LinearLayout(this);
        chipsRow.setOrientation(LinearLayout.HORIZONTAL);
        chipsRow.setPadding(0, 0, 0, 10);
        String[] kwParts = keywords.get(index).split(",");
        for (String kw : kwParts) {
            String trimmed = kw.trim();
            if (trimmed.isEmpty()) continue;
            TextView chip = new TextView(this);
            chip.setText("\"" + trimmed + "\"");
            chip.setTextColor(Theme.ACCENT_CALL);
            chip.setTextSize(13);
            chip.setTypeface(null, Typeface.BOLD);
            LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            chipParams.setMargins(0, 0, 14, 0);
            chip.setLayoutParams(chipParams);
            chipsRow.addView(chip);
        }

        TextView sendLabel = new TextView(this);
        sendLabel.setText("↓  SEND THIS");
        sendLabel.setTextColor(Theme.TEXT_MUTED);
        sendLabel.setTextSize(10);
        sendLabel.setTypeface(null, Typeface.BOLD);
        sendLabel.setPadding(0, 0, 0, 6);

        TextView replyPreview = new TextView(this);
        String replyText = replies.get(index);
        replyPreview.setText(replyText.length() > 80 ? replyText.substring(0, 80) + "…" : replyText);
        replyPreview.setTextColor(Theme.TEXT_PRIMARY);
        replyPreview.setTextSize(13);
        replyPreview.setPadding(0, 0, 0, 14);

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
        editBtn.setOnClickListener(v -> showRuleDialog(index, keywords.get(index), replies.get(index)));

        Button deleteBtn = new Button(this);
        deleteBtn.setText("✕  Delete");
        deleteBtn.setAllCaps(false);
        deleteBtn.setTextColor(Theme.DANGER);
        deleteBtn.setBackgroundColor(Theme.DELETE_BG);
        deleteBtn.setTextSize(12);
        deleteBtn.setLayoutParams(new LinearLayout.LayoutParams(0, 90, 1f));
        deleteBtn.setOnClickListener(v -> {
            keywords.remove(index);
            replies.remove(index);
            saveRules();
            rebuildList();
        });

        btnRow.addView(editBtn);
        btnRow.addView(deleteBtn);

        card.addView(ifLabel);
        card.addView(chipsRow);
        card.addView(sendLabel);
        card.addView(replyPreview);
        card.addView(btnRow);
        return card;
    }

    // -------------------------------------------------------------------------
    // Add / Edit dialog
    // -------------------------------------------------------------------------
    private void showRuleDialog(final int index, String currentKeyword, String currentReply) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 20, 50, 10);
        layout.setBackgroundColor(Theme.CARD_BG);

        TextView kwLabel = new TextView(this);
        kwLabel.setText("Keywords — separate multiple with a comma");
        kwLabel.setTextColor(Theme.TEXT_MUTED);
        kwLabel.setTextSize(11);
        kwLabel.setPadding(0, 0, 0, 6);

        EditText kwInput = new EditText(this);
        kwInput.setText(currentKeyword);
        kwInput.setHint("e.g.  New Year, happy new year, HNY");
        kwInput.setHintTextColor(Theme.TEXT_DK);
        kwInput.setInputType(InputType.TYPE_CLASS_TEXT);
        kwInput.setBackgroundColor(Theme.INPUT_BG);
        kwInput.setTextColor(Theme.TEXT_PRIMARY);
        kwInput.setPadding(16, 14, 16, 14);
        LinearLayout.LayoutParams kwParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        kwParams.setMargins(0, 0, 0, 20);
        kwInput.setLayoutParams(kwParams);

        TextView replyLabel = new TextView(this);
        replyLabel.setText("Reply to send when any keyword is detected");
        replyLabel.setTextColor(Theme.TEXT_MUTED);
        replyLabel.setTextSize(11);
        replyLabel.setPadding(0, 0, 0, 6);

        EditText replyInput = new EditText(this);
        replyInput.setText(currentReply);
        replyInput.setHint("e.g.  Happy New Year! 🎉");
        replyInput.setHintTextColor(Theme.TEXT_DK);
        replyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        replyInput.setMinLines(3);
        replyInput.setBackgroundColor(Theme.INPUT_BG);
        replyInput.setTextColor(Theme.TEXT_PRIMARY);
        replyInput.setPadding(16, 14, 16, 14);

        layout.addView(kwLabel);
        layout.addView(kwInput);
        layout.addView(replyLabel);
        layout.addView(replyInput);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(index == -1 ? "Add Keyword Rule" : "Edit Keyword Rule")
                .setView(layout)
                .setPositiveButton("Save", null)
                .setNegativeButton("Cancel", null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String kwRaw  = kwInput.getText().toString().trim();
            String reply  = replyInput.getText().toString().trim();

            if (kwRaw.isEmpty()) {
                showAlert("Missing Keyword", "Please enter at least one keyword.");
                return;
            }
            if (reply.isEmpty()) {
                showAlert("Missing Reply", "Please enter a reply message.");
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
                            showAlert("Duplicate Keyword",
                                    "\"" + newKw.trim() + "\" already exists in another rule.");
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
    }

    private void showAlert(String title, String msg) {
        new AlertDialog.Builder(this)
                .setTitle(title).setMessage(msg)
                .setPositiveButton("OK", null).show();
    }
}
