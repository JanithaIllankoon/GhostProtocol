package com.example.ghostprotocol;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

public class EditMessagesActivity extends AppCompatActivity {

    private LinearLayout poolCard;
    private LinearLayout specialCard;
    private LinearLayout timingCard;
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
    // Load / Save — system messages and timing
    // -------------------------------------------------------------------------
    private String loadSystemMsg(String key, String defaultMsg) {
        return getSharedPreferences(GhostService.MSG_PREFS, Context.MODE_PRIVATE)
                .getString(key, defaultMsg);
    }

    private void saveSystemMsg(String key, String value) {
        getSharedPreferences(GhostService.MSG_PREFS, Context.MODE_PRIVATE)
                .edit().putString(key, value).apply();
    }

    private int loadTiming(String key, int defaultVal) {
        return getSharedPreferences(GhostService.MSG_PREFS, Context.MODE_PRIVATE)
                .getInt(key, defaultVal);
    }

    // -------------------------------------------------------------------------
    // UI
    // -------------------------------------------------------------------------
    private void buildUI() {
        LinearLayout column = Ui.screen(this, "Away messages",
                "What people get when they message you while you're away.", true);

        column.addView(Ui.sectionLabel(this, "Messages"));
        poolCard = Ui.card(this);
        column.addView(poolCard);
        column.addView(Ui.hint(this, "One of these is picked at random each time."));

        column.addView(Ui.sectionLabel(this, "Special situations"));
        specialCard = Ui.card(this);
        column.addView(specialCard);

        column.addView(Ui.sectionLabel(this, "Timing"));
        timingCard = Ui.card(this);
        column.addView(timingCard);

        rebuildPool();
        rebuildSpecial();
        rebuildTiming();
    }

    private void rebuildPool() {
        poolCard.removeAllViews();
        for (int i = 0; i < messages.size(); i++) {
            final int index = i;
            Ui.addRow(poolCard, Ui.row(this, messages.get(i), null, null, 3,
                    v -> showPoolEditDialog(index, messages.get(index))));
        }
        Ui.addRow(poolCard, Ui.addRowButton(this, "Add message",
                v -> showPoolEditDialog(-1, "")));
    }

    private void rebuildSpecial() {
        specialCard.removeAllViews();
        addSpecialRow("Missed call",
                "Sent when someone calls you on WhatsApp. The call still rings as normal.",
                KEY_CALL_MSG, GhostService.DEFAULT_POOL[GhostService.IDX_CALL_DECLINE]);
        addSpecialRow("Unknown number",
                "Sent the first time someone who isn't in your contacts messages you.",
                KEY_STRANGER_MSG, GhostService.STRANGER_MSG);
        addSpecialRow("Repeated messages",
                "Sent when someone keeps messaging before you've had a chance to reply.",
                KEY_SPAM_MSG, GhostService.DEFAULT_POOL[GhostService.IDX_SPAM_WARNING]);
    }

    private void addSpecialRow(String title, String explanation, String prefKey, String defaultMsg) {
        Ui.addRow(specialCard, Ui.row(this, title, loadSystemMsg(prefKey, defaultMsg), null, 0,
                v -> showSystemEditDialog(title, explanation, prefKey, defaultMsg)));
    }

    private void rebuildTiming() {
        timingCard.removeAllViews();
        addTimingRow("Wait before replying again",
                "How long before the same person can get another away message.",
                GhostService.KEY_WINDOW_MIN, GhostService.DEFAULT_WINDOW_MIN, "minute");
        addTimingRow("Repeated-message reminder",
                "The \"repeated messages\" reply is sent to the same person at most once in this time.",
                GhostService.KEY_SPAM_COOLDOWN_HR, GhostService.DEFAULT_SPAM_COOLDOWN_HR, "hour");
    }

    private void addTimingRow(String title, String explanation, String prefKey,
                              int defaultVal, String unit) {
        int value = loadTiming(prefKey, defaultVal);
        Ui.addRow(timingCard, Ui.row(this, title, null, value + " " + unit + (value == 1 ? "" : "s"), 0,
                v -> showTimingDialog(title, explanation, prefKey, defaultVal, unit)));
    }

    // -------------------------------------------------------------------------
    // Dialogs
    // -------------------------------------------------------------------------
    private void showTimingDialog(String title, String explanation, String prefKey,
                                  int defaultVal, String unit) {
        EditText input = Ui.input(this, String.valueOf(loadTiming(prefKey, defaultVal)),
                null, false);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setMessage(explanation)
                .setView(Ui.dialogBody(this, "Number of " + unit + "s", input))
                .setPositiveButton("Save", null)
                .setNegativeButton("Cancel", null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String raw = input.getText().toString().trim();
            int val;
            try {
                val = Integer.parseInt(raw);
            } catch (NumberFormatException e) {
                input.setError("Enter a whole number");
                return;
            }
            if (val < 1) { input.setError("Must be at least 1"); return; }
            getSharedPreferences(GhostService.MSG_PREFS, Context.MODE_PRIVATE)
                    .edit().putInt(prefKey, val).apply();
            rebuildTiming();
            dialog.dismiss();
        });
    }

    private void showSystemEditDialog(String title, String explanation,
                                      String prefKey, String defaultMsg) {
        EditText input = Ui.input(this, loadSystemMsg(prefKey, defaultMsg), null, true);

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setMessage(explanation)
                .setView(Ui.dialogBody(this, input))
                .setPositiveButton("Save", null)
                .setNegativeButton("Cancel", null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String val = input.getText().toString().trim();
            if (val.isEmpty()) { input.setError("Message can't be empty"); return; }
            saveSystemMsg(prefKey, val);
            rebuildSpecial();
            dialog.dismiss();
        });
    }

    private void showPoolEditDialog(final int index, String current) {
        EditText input = Ui.input(this, current, "Type your message", true);

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
                .setTitle(index == -1 ? "New message" : "Edit message")
                .setView(Ui.dialogBody(this, input))
                .setPositiveButton("Save", null)
                .setNegativeButton("Cancel", null);
        if (index != -1) builder.setNeutralButton("Delete", null);
        AlertDialog dialog = builder.create();
        dialog.show();

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String val = input.getText().toString().trim();
            if (val.isEmpty()) { input.setError("Message can't be empty"); return; }
            if (index == -1) { messages.add(val); } else { messages.set(index, val); }
            saveMessages();
            rebuildPool();
            dialog.dismiss();
        });

        if (index != -1) {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setTextColor(Theme.DANGER);
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                if (messages.size() <= 1) {
                    input.setError("You need at least one message");
                    return;
                }
                messages.remove(index);
                saveMessages();
                rebuildPool();
                dialog.dismiss();
            });
        }
    }
}
