package com.example.ghostprotocol;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.LinearLayout;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * Lists the messages for one special situation (missed call, unknown number
 * or repeated messages). One of them is picked at random each time.
 */
public class SituationMessagesActivity extends AppCompatActivity {

    static final String EXTRA_PREFIX = "situation_prefix";

    private SituationMessages.Situation situation;
    private LinearLayout listCard;
    private final List<String> messages = new ArrayList<>();

    static Intent intent(Context ctx, SituationMessages.Situation s) {
        return new Intent(ctx, SituationMessagesActivity.class).putExtra(EXTRA_PREFIX, s.prefix);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        situation = SituationMessages.byPrefix(getIntent().getStringExtra(EXTRA_PREFIX));
        if (situation == null) {
            finish();
            return;
        }
        messages.addAll(SituationMessages.load(prefs(), situation));

        LinearLayout column = Ui.screen(this, situation.title, situation.description, true);
        column.addView(Ui.sectionLabel(this, "Messages"));
        listCard = Ui.card(this);
        column.addView(listCard);
        column.addView(Ui.hint(this, "One is picked at random each time."));
        rebuildList();
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(GhostService.MSG_PREFS, Context.MODE_PRIVATE);
    }

    private void rebuildList() {
        listCard.removeAllViews();
        for (int i = 0; i < messages.size(); i++) {
            final int index = i;
            Ui.addRow(listCard, Ui.row(this, messages.get(i), null, null, 2,
                    v -> showEditDialog(index, messages.get(index))));
        }
        Ui.addRow(listCard, Ui.addRowButton(this, "Add Message", v -> showEditDialog(-1, "")));
    }

    private void showEditDialog(final int index, String current) {
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
            for (int i = 0; i < messages.size(); i++) {
                if (i != index && messages.get(i).equals(val)) {
                    input.setError("This message is already on the list");
                    return;
                }
            }
            if (index == -1) messages.add(val); else messages.set(index, val);
            SituationMessages.save(prefs(), situation, messages);
            rebuildList();
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
                SituationMessages.save(prefs(), situation, messages);
                rebuildList();
                dialog.dismiss();
            });
        }
    }
}
