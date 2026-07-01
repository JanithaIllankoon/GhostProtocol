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

public class EditMessagesActivity extends AppCompatActivity {

    private LinearLayout listContainer;
    private final List<String> messages = new ArrayList<>();

    static final String KEY_SPAM_MSG = "sys_spam_msg";
    static final String KEY_CALL_MSG = "sys_call_msg";
    static final String KEY_STRANGER_MSG = "sys_stranger_msg";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        loadMessages();
        buildUI();
    }

    // -------------------------------------------------------------------------
    // Load / Save — random pool
    // -------------------------------------------------------------------------
    private void loadMessages() {
        SharedPreferences prefs = getSharedPreferences(GhostService.MSG_PREFS, Context.MODE_PRIVATE);
        int count = prefs.getInt("pool_count", 0);
        messages.clear();
        if (count == 0) {
            for (int i = 0; i < GhostService.IDX_SPAM_WARNING; i++) {
                messages.add(GhostService.DEFAULT_POOL[i]);
            }
        } else {
            for (int i = 0; i < count; i++) {
                messages.add(prefs.getString("pool_msg_" + i, ""));
            }
        }
    }

    private void saveMessages() {
        SharedPreferences prefs = getSharedPreferences(GhostService.MSG_PREFS, Context.MODE_PRIVATE);
        int oldCount = prefs.getInt("pool_count", 0);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putInt("pool_count", messages.size());
        for (int i = 0; i < messages.size(); i++) {
            editor.putString("pool_msg_" + i, messages.get(i));
        }
        // Remove stale keys from a previously larger pool
        for (int i = messages.size(); i < oldCount; i++) {
            editor.remove("pool_msg_" + i);
        }
        editor.apply();
    }

    // -------------------------------------------------------------------------
    // Load / Save — system messages
    // -------------------------------------------------------------------------
    private String loadSystemMsg(String key, String defaultMsg) {
        return getSharedPreferences(GhostService.MSG_PREFS, Context.MODE_PRIVATE)
                .getString(key, defaultMsg);
    }

    private void saveSystemMsg(String key, String value) {
        getSharedPreferences(GhostService.MSG_PREFS, Context.MODE_PRIVATE)
                .edit().putString(key, value).apply();
    }

    // -------------------------------------------------------------------------
    // UI
    // -------------------------------------------------------------------------
    private void buildUI() {
        ScrollView scroll = new ScrollView(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Theme.BG);
        root.setPadding(40, 50, 40, 60);

        TextView header = new TextView(this);
        header.setText("Message Settings");
        header.setTextColor(Theme.PRIMARY);
        header.setTextSize(20);
        header.setTypeface(null, Typeface.BOLD);
        header.setGravity(Gravity.CENTER);
        header.setPadding(0, 0, 0, 30);

        root.addView(header);
        root.addView(makeSectionLabel(
                "SYSTEM MESSAGES",
                "Always sent in specific situations — not chosen randomly."));

        root.addView(buildSystemCard(
                "Spam Warning",
                "Sent once per 12 hrs when someone messages more than once inside a 10-min window.",
                KEY_SPAM_MSG,
                GhostService.DEFAULT_POOL[GhostService.IDX_SPAM_WARNING],
                Theme.ACCENT_SPAM));

        root.addView(buildSystemCard(
                "Incoming Call Reply",
                "Sent as a text when someone calls you on WhatsApp. The call still rings normally.",
                KEY_CALL_MSG,
                GhostService.DEFAULT_POOL[GhostService.IDX_CALL_DECLINE],
                Theme.ACCENT_CALL));

        root.addView(buildSystemCard(
                "Stranger Reply",
                "Sent the first time someone who is NOT in your contacts messages you.",
                KEY_STRANGER_MSG,
                GhostService.STRANGER_MSG,
                Theme.PRIMARY));

        root.addView(makeSectionLabel(
                "RANDOM AUTO-REPLIES",
                "One of these is picked at random for the first message in a new 10-min window."));

        listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(listContainer);

        Button addBtn = new Button(this);
        addBtn.setText("＋  ADD NEW MESSAGE");
        addBtn.setAllCaps(false);
        addBtn.setTextColor(Theme.PRIMARY);
        addBtn.setBackgroundColor(Theme.PRIMARY_BG);
        addBtn.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams addParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 120);
        addParams.setMargins(0, 16, 0, 0);
        addBtn.setLayoutParams(addParams);
        addBtn.setOnClickListener(v -> showPoolEditDialog(-1, ""));
        root.addView(addBtn);

        scroll.addView(root);
        setContentView(scroll);

        rebuildList();
    }

    // -------------------------------------------------------------------------
    // System message card
    // -------------------------------------------------------------------------
    private View buildSystemCard(String title, String description,
                                 String prefKey, String defaultMsg, int accentColor) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(Theme.CARD_BG);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        cardParams.setMargins(0, 0, 0, 14);
        card.setLayoutParams(cardParams);
        card.setPadding(24, 20, 24, 16);

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(accentColor);
        titleView.setTextSize(12);
        titleView.setTypeface(null, Typeface.BOLD);
        titleView.setPadding(0, 0, 0, 4);

        TextView descView = new TextView(this);
        descView.setText(description);
        descView.setTextColor(Theme.TEXT_MUTED);
        descView.setTextSize(11);
        descView.setPadding(0, 0, 0, 10);

        TextView preview = new TextView(this);
        preview.setText(loadSystemMsg(prefKey, defaultMsg));
        preview.setTextColor(Theme.TEXT_PRIMARY);
        preview.setTextSize(13);
        preview.setPadding(0, 0, 0, 14);

        Button editBtn = new Button(this);
        editBtn.setText("✎  Edit this message");
        editBtn.setAllCaps(false);
        editBtn.setTextColor(Theme.WARN);
        editBtn.setBackgroundColor(Theme.EDIT_BG);
        editBtn.setTextSize(12);
        editBtn.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 90));
        editBtn.setOnClickListener(v ->
                showSystemEditDialog(title, prefKey, defaultMsg, preview));

        card.addView(titleView);
        card.addView(descView);
        card.addView(preview);
        card.addView(editBtn);
        return card;
    }

    // -------------------------------------------------------------------------
    // Random pool cards
    // -------------------------------------------------------------------------
    private void rebuildList() {
        listContainer.removeAllViews();
        for (int i = 0; i < messages.size(); i++) {
            listContainer.addView(buildPoolCard(i));
        }
    }

    private View buildPoolCard(final int index) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(Theme.CARD_BG);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        cardParams.setMargins(0, 0, 0, 14);
        card.setLayoutParams(cardParams);
        card.setPadding(24, 20, 24, 16);

        TextView label = new TextView(this);
        label.setText("Message " + (index + 1));
        label.setTextColor(Theme.PRIMARY);
        label.setTextSize(11);
        label.setTypeface(null, Typeface.BOLD);
        label.setPadding(0, 0, 0, 6);

        TextView preview = new TextView(this);
        preview.setText(messages.get(index));
        preview.setTextColor(Theme.TEXT_PRIMARY);
        preview.setTextSize(13);
        preview.setPadding(0, 0, 0, 14);

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
        editBtn.setOnClickListener(v -> showPoolEditDialog(index, messages.get(index)));

        Button deleteBtn = new Button(this);
        deleteBtn.setText("✕  Delete");
        deleteBtn.setAllCaps(false);
        deleteBtn.setTextColor(Theme.DANGER);
        deleteBtn.setBackgroundColor(Theme.DELETE_BG);
        deleteBtn.setTextSize(12);
        deleteBtn.setLayoutParams(new LinearLayout.LayoutParams(0, 90, 1f));
        deleteBtn.setOnClickListener(v -> {
            if (messages.size() <= 1) {
                showAlert("Can't Delete", "You need at least one auto-reply message.");
                return;
            }
            messages.remove(index);
            saveMessages();
            rebuildList();
        });

        btnRow.addView(editBtn);
        btnRow.addView(deleteBtn);
        card.addView(label);
        card.addView(preview);
        card.addView(btnRow);
        return card;
    }

    // -------------------------------------------------------------------------
    // Dialogs
    // -------------------------------------------------------------------------
    private void showSystemEditDialog(String title, String prefKey,
                                      String defaultMsg, TextView previewView) {
        EditText input = new EditText(this);
        input.setText(loadSystemMsg(prefKey, defaultMsg));
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setMinLines(3);
        input.setBackgroundColor(Theme.INPUT_BG);
        input.setTextColor(Theme.TEXT_PRIMARY);
        input.setPadding(20, 20, 20, 20);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Edit: " + title)
                .setView(input)
                .setPositiveButton("Save", null)
                .setNegativeButton("Cancel", null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String val = input.getText().toString().trim();
            if (val.isEmpty()) { showAlert("Empty", "Message can't be empty."); return; }
            saveSystemMsg(prefKey, val);
            previewView.setText(val);
            dialog.dismiss();
        });
    }

    private void showPoolEditDialog(final int index, String current) {
        EditText input = new EditText(this);
        input.setText(current);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setMinLines(3);
        input.setBackgroundColor(Theme.INPUT_BG);
        input.setTextColor(Theme.TEXT_PRIMARY);
        input.setPadding(20, 20, 20, 20);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(index == -1 ? "Add Message" : "Edit Message")
                .setView(input)
                .setPositiveButton("Save", null)
                .setNegativeButton("Cancel", null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String val = input.getText().toString().trim();
            if (val.isEmpty()) { showAlert("Empty", "Message can't be empty."); return; }
            if (index == -1) { messages.add(val); } else { messages.set(index, val); }
            saveMessages();
            rebuildList();
            dialog.dismiss();
        });
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------
    private View makeSectionLabel(String title, String subtitle) {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 10, 0, 12);
        section.setLayoutParams(params);

        TextView label = new TextView(this);
        label.setText(title);
        label.setTextColor(Theme.TEXT_TERTIARY);
        label.setTextSize(11);
        label.setTypeface(null, Typeface.BOLD);
        label.setPadding(0, 0, 0, 4);

        TextView sub = new TextView(this);
        sub.setText(subtitle);
        sub.setTextColor(Theme.TEXT_QUATERNARY);
        sub.setTextSize(11);

        section.addView(label);
        section.addView(sub);
        return section;
    }

    private void showAlert(String title, String msg) {
        new AlertDialog.Builder(this)
                .setTitle(title).setMessage(msg)
                .setPositiveButton("OK", null).show();
    }
}
