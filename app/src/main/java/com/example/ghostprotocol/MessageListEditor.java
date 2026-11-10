package com.example.ghostprotocol;

import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;

/**
 * Renders an editable message list into a card: tap a message to edit or
 * delete it, "+ Add Message" to add one, and — once there are at least two —
 * a switch per message to take it out of the random pick without deleting it.
 * At least one message always stays on.
 */
final class MessageListEditor {

    interface Store {
        MessageList load();
        void save(MessageList list);
    }

    private final AppCompatActivity activity;
    private final LinearLayout card;
    private final Store store;
    private MessageList list;

    MessageListEditor(AppCompatActivity activity, LinearLayout card, Store store) {
        this.activity = activity;
        this.card = card;
        this.store = store;
        this.list = store.load();
        rebuild();
    }

    private void rebuild() {
        card.removeAllViews();
        boolean canSwitch = list.size() >= 2;
        for (int i = 0; i < list.size(); i++) {
            final int index = i;
            LinearLayout row = Ui.row(activity, list.texts.get(i), null, null, 2,
                    v -> showEditDialog(index));
            boolean on = list.on.get(i);
            if (!on) Ui.titleOf(row).setTextColor(Theme.TEXT_TERTIARY);
            if (canSwitch) {
                row.removeViewAt(row.getChildCount() - 1); // the switch replaces the chevron
                MaterialSwitch sw = new MaterialSwitch(activity);
                sw.setChecked(on);
                sw.setContentDescription(on ? "Turn off" : "Turn on");
                sw.setOnCheckedChangeListener((b, checked) -> setOn(index, checked));
                LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP);
                sp.setMarginStart(Ui.dp(activity, 12));
                row.addView(sw, sp);
            }
            Ui.addRow(card, row);
        }
        Ui.addRow(card, Ui.addRowButton(activity, "Add Message", v -> showEditDialog(-1)));
    }

    private void setOn(int index, boolean on) {
        if (!on && list.enabledCount() <= 1) {
            Toast.makeText(activity, "At least one message needs to stay on",
                    Toast.LENGTH_SHORT).show();
            card.post(this::rebuild); // flip the switch back
            return;
        }
        list.on.set(index, on);
        store.save(list);
        card.post(this::rebuild); // restyle after the switch animation starts
    }

    private void showEditDialog(final int index) {
        boolean editing = index != -1;
        EditText input = Ui.input(activity, editing ? list.texts.get(index) : "",
                "Type your message", true);

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity)
                .setTitle(editing ? "Edit Message" : "New Message")
                .setView(Ui.dialogBody(activity, input))
                .setPositiveButton("Save", null)
                .setNegativeButton("Cancel", null);
        if (editing) builder.setNeutralButton("Delete", null);
        AlertDialog dialog = builder.create();
        dialog.show();

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String val = input.getText().toString().trim();
            if (val.isEmpty()) { input.setError("Message can't be empty"); return; }
            for (int i = 0; i < list.size(); i++) {
                if (i != index && list.texts.get(i).equals(val)) {
                    input.setError("This message is already on the list");
                    return;
                }
            }
            if (editing) list.texts.set(index, val); else list.add(val, true);
            store.save(list);
            rebuild();
            dialog.dismiss();
        });

        if (editing) {
            View delete = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setTextColor(Theme.DANGER);
            delete.setOnClickListener(v -> {
                if (list.size() <= 1) {
                    input.setError("You need at least one message");
                    return;
                }
                if (list.on.get(index) && list.enabledCount() <= 1) {
                    input.setError("Turn on another message first");
                    return;
                }
                list.remove(index);
                store.save(list);
                rebuild();
                dialog.dismiss();
            });
        }
    }
}
