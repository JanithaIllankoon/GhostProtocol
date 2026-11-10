package com.example.ghostprotocol;

import android.app.NotificationManager;
import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/** Receives the reply Ghost Protocol "sent" to a test notification. */
public class TestReplyReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        int id = intent.getIntExtra(TestReplies.EXTRA_ENTRY_ID, -1);
        Bundle results = RemoteInput.getResultsFromIntent(intent);
        CharSequence reply = results != null ? results.getCharSequence(TestReplies.KEY_REPLY) : null;
        TestReplies.recordReply(ctx, id, reply);
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm != null && id != -1) nm.cancel(id);
    }
}
