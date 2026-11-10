package com.example.ghostprotocol;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.LinearLayout;
import androidx.appcompat.app.AppCompatActivity;

/** Away messages (random pool) plus links to the special-situation lists. */
public class EditMessagesActivity extends AppCompatActivity {

    private LinearLayout specialCard;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SharedPreferences prefs = getSharedPreferences(GhostService.MSG_PREFS, Context.MODE_PRIVATE);

        LinearLayout column = Ui.screen(this, "Away Messages", null, true);

        column.addView(Ui.sectionLabel(this, "Messages"));
        LinearLayout poolCard = Ui.card(this);
        column.addView(poolCard);
        column.addView(Ui.hint(this, "One message that's on is picked at random each time."));
        new MessageListEditor(this, poolCard, new MessageListEditor.Store() {
            @Override public MessageList load() { return PoolMessages.load(prefs); }
            @Override public void save(MessageList list) { PoolMessages.save(prefs, list); }
        });

        column.addView(Ui.sectionLabel(this, "Special Situations"));
        specialCard = Ui.card(this);
        column.addView(specialCard); // filled in onResume
    }

    @Override
    protected void onResume() {
        super.onResume();
        rebuildSpecial(); // counts may have changed on a situation's screen
    }

    private void rebuildSpecial() {
        specialCard.removeAllViews();
        SharedPreferences prefs = getSharedPreferences(GhostService.MSG_PREFS, Context.MODE_PRIVATE);
        for (SituationMessages.Situation s : SituationMessages.ALL) {
            String count = SituationMessages.load(prefs, s).countLabel();
            Ui.addRow(specialCard, Ui.row(this, s.title, null, count, 0,
                    v -> startActivity(SituationMessagesActivity.intent(this, s))));
        }
    }
}
