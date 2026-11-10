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

public class EditMessagesActivity extends AppCompatActivity {

    private LinearLayout poolCard;
    private LinearLayout specialCard;
    private final List<String> messages = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        loadMessages();
        buildUI();
    }

    @Override
    protected void onResume() {
        super.onResume();
        rebuildSpecial(); // counts may have changed on a situation's screen
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
    // UI
    // -------------------------------------------------------------------------
    private void buildUI() {
        LinearLayout column = Ui.screen(this, "Away Messages", null, true);

        column.addView(Ui.sectionLabel(this, "Messages"));
        poolCard = Ui.card(this);
        column.addView(poolCard);
        column.addView(Ui.hint(this, "One is picked at random each time."));

        column.addView(Ui.sectionLabel(this, "Special Situations"));
        specialCard = Ui.card(this);
        column.addView(specialCard);

        rebuildPool(); // special situations are filled in onResume
    }

    private void rebuildPool() {
        poolCard.removeAllViews();
        for (int i = 0; i < messages.size(); i++) {
            final int index = i;
            Ui.addRow(poolCard, Ui.row(this, messages.get(i), null, null, 2,
                    v -> showPoolEditDialog(index, messages.get(index))));
        }
        Ui.addRow(poolCard, Ui.addRowButton(this, "Add Message",
                v -> showPoolEditDialog(-1, "")));
    }

    private void rebuildSpecial() {
        specialCard.removeAllViews();
        SharedPreferences prefs = getSharedPreferences(GhostService.MSG_PREFS, Context.MODE_PRIVATE);
        for (SituationMessages.Situation s : SituationMessages.ALL) {
            int count = SituationMessages.load(prefs, s).size();
            Ui.addRow(specialCard, Ui.row(this, s.title, null, String.valueOf(count), 0,
                    v -> startActivity(SituationMessagesActivity.intent(this, s))));
        }
    }

    // -------------------------------------------------------------------------
    // Dialogs
    // -------------------------------------------------------------------------
    private void showPoolEditDialog(final int index, String current) {
        EditText input = Ui.input(this, current, "Type your message", true);

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
                .setTitle(index == -1 ? "New Message" : "Edit Message")
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
