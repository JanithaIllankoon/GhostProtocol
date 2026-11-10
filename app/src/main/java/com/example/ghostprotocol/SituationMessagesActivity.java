package com.example.ghostprotocol;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.LinearLayout;
import androidx.appcompat.app.AppCompatActivity;

/**
 * Lists the messages for one special situation (missed call, unknown number
 * or repeated messages). One message that's on is picked at random each time.
 */
public class SituationMessagesActivity extends AppCompatActivity {

    static final String EXTRA_PREFIX = "situation_prefix";

    static Intent intent(Context ctx, SituationMessages.Situation s) {
        return new Intent(ctx, SituationMessagesActivity.class).putExtra(EXTRA_PREFIX, s.prefix);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SituationMessages.Situation situation =
                SituationMessages.byPrefix(getIntent().getStringExtra(EXTRA_PREFIX));
        if (situation == null) {
            finish();
            return;
        }
        SharedPreferences prefs = getSharedPreferences(GhostService.MSG_PREFS, Context.MODE_PRIVATE);

        LinearLayout column = Ui.screen(this, situation.title, situation.description, true);
        column.addView(Ui.sectionLabel(this, "Messages"));
        LinearLayout card = Ui.card(this);
        column.addView(card);
        column.addView(Ui.hint(this, "One message that's on is picked at random each time."));

        new MessageListEditor(this, card, new MessageListEditor.Store() {
            @Override public MessageList load() { return SituationMessages.load(prefs, situation); }
            @Override public void save(MessageList list) { SituationMessages.save(prefs, situation, list); }
        });
    }
}
