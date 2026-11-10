package com.example.ghostprotocol;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Lets the user send fake WhatsApp messages and calls to themselves and see
 * what Ghost Protocol replies. See {@link TestReplies}.
 */
public class TestRepliesActivity extends AppCompatActivity {

    private TextView problemView;
    private LinearLayout problemCard;
    private EditText senderInput;
    private EditText messageInput;
    private LinearLayout resultsCard;
    private TextView clearAction;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = this::rebuildResults;

    // Kept as a field: SharedPreferences holds listeners weakly.
    private final SharedPreferences.OnSharedPreferenceChangeListener logListener =
            (p, key) -> { if (TestReplies.KEY_LOG.equals(key)) handler.post(refresh); };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUI();
    }

    @Override
    protected void onResume() {
        super.onResume();
        TestReplies.prefs(this).registerOnSharedPreferenceChangeListener(logListener);
        String problem = TestReplies.problem(this);
        problemCard.setVisibility(problem == null ? View.GONE : View.VISIBLE);
        problemView.setText(problem);
        rebuildResults();
    }

    @Override
    protected void onPause() {
        super.onPause();
        TestReplies.prefs(this).unregisterOnSharedPreferenceChangeListener(logListener);
        handler.removeCallbacks(refresh);
    }

    // -------------------------------------------------------------------------
    // UI
    // -------------------------------------------------------------------------
    private void buildUI() {
        LinearLayout column = Ui.screen(this, "Test Replies",
                "Fake messages and calls, just for you. Nothing is sent to anyone.", true);

        clearAction = Ui.headerAction(column, "Clear", v -> {
            TestReplies.clearLog(this);
            rebuildResults();
        });

        // --- Warning when testing can't work right now ---
        problemCard = Ui.card(this);
        int p = Ui.dp(this, 16);
        problemCard.setPadding(p, p, p, p);
        problemView = Ui.text(this, "", 14, Theme.WARN);
        problemCard.addView(problemView);
        column.addView(problemCard, spaced(16));

        // --- Composer ---
        column.addView(Ui.sectionLabel(this, "Send a Test"));
        LinearLayout composer = Ui.card(this);
        composer.setPadding(p, p, p, Ui.dp(this, 8));

        senderInput = Ui.input(this, "Test Person", "Name or phone number", false);
        messageInput = Ui.input(this, "Hey, are you free?", "Their message", false);
        messageInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        composer.addView(fieldLabel("From", true));
        composer.addView(senderInput);
        composer.addView(fieldLabel("Message", false));
        composer.addView(messageInput);

        TextView sendMessage = Ui.button(this, "Send Test Message", true, v -> send(false));
        LinearLayout.LayoutParams first = new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP);
        first.topMargin = Ui.dp(this, 20);
        composer.addView(sendMessage, first);

        TextView sendCall = Ui.button(this, "Send Test Call", false, v -> send(true));
        LinearLayout.LayoutParams second = new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP);
        second.topMargin = Ui.dp(this, 10);
        composer.addView(sendCall, second);

        TextView reset = Ui.bold(Ui.text(this, "Reset This Person", 14, Theme.TEXT_SECONDARY));
        reset.setGravity(Gravity.CENTER);
        reset.setMinHeight(Ui.dp(this, 44));
        reset.setBackground(Ui.pressable(this, Theme.SURFACE, 22));
        reset.setOnClickListener(v -> resetSender());
        LinearLayout.LayoutParams third = new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP);
        third.topMargin = Ui.dp(this, 6);
        composer.addView(reset, third);
        column.addView(composer);

        column.addView(Ui.hint(this,
                "Saved contact names get away messages. Other names and numbers get the "
                        + "Unknown Number reply."));

        // --- Results ---
        column.addView(Ui.sectionLabel(this, "Results"));
        resultsCard = Ui.card(this);
        column.addView(resultsCard);
        column.addView(Ui.hint(this,
                "No reply usually means a wait time or your Reply To setting stopped it."));
    }

    private TextView fieldLabel(String label, boolean first) {
        TextView tv = Ui.bold(Ui.text(this, label, 13, Theme.TEXT_SECONDARY));
        tv.setPadding(Ui.dp(this, 2), first ? 0 : Ui.dp(this, 14), 0, Ui.dp(this, 6));
        return tv;
    }

    private LinearLayout.LayoutParams spaced(int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP);
        lp.topMargin = Ui.dp(this, topDp);
        return lp;
    }

    // -------------------------------------------------------------------------
    // Actions
    // -------------------------------------------------------------------------
    private void send(boolean call) {
        String problem = TestReplies.problem(this);
        if (problem != null) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("Can't Test Yet")
                    .setMessage(problem)
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }
        String sender = senderInput.getText().toString().trim();
        String message = messageInput.getText().toString().trim();
        if (sender.isEmpty()) { senderInput.setError("Enter a name or number"); return; }
        if (sender.contains("WhatsApp")) {
            senderInput.setError("Pick a name without \"WhatsApp\" in it");
            return;
        }
        if (!call && message.isEmpty()) { messageInput.setError("Enter a message"); return; }

        hideKeyboard();
        TestReplies.post(this, sender, call ? "Missed voice call" : message, call);
        rebuildResults();
        // Show "No reply sent" once the wait is over if nothing came back.
        handler.postDelayed(refresh, TestReplies.REPLY_WAIT_MS + 300);
    }

    private void resetSender() {
        String sender = senderInput.getText().toString().trim();
        if (sender.isEmpty()) { senderInput.setError("Enter a name or number"); return; }
        TestReplies.resetSender(this, sender);
        Toast.makeText(this, "Next test from " + sender + " counts as their first message",
                Toast.LENGTH_SHORT).show();
    }

    private void hideKeyboard() {
        View focus = getCurrentFocus();
        if (focus == null) return;
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(focus.getWindowToken(), 0);
        focus.clearFocus();
    }

    // -------------------------------------------------------------------------
    // Results list
    // -------------------------------------------------------------------------
    private void rebuildResults() {
        resultsCard.removeAllViews();
        JSONArray log = TestReplies.log(this);
        clearAction.setVisibility(log.length() == 0 ? View.GONE : View.VISIBLE);
        if (log.length() == 0) {
            Ui.addRow(resultsCard, Ui.emptyRow(this, "No tests yet.\nSend one above."));
            return;
        }
        boolean waiting = false;
        long now = System.currentTimeMillis();
        for (int i = log.length() - 1; i >= 0; i--) { // newest first
            JSONObject e = log.optJSONObject(i);
            if (e == null) continue;
            boolean pending = !e.has("reply") && now - e.optLong("time") < TestReplies.REPLY_WAIT_MS;
            waiting |= pending;
            Ui.addRow(resultsCard, buildResultRow(e, pending, now));
        }
        if (waiting) {
            handler.removeCallbacks(refresh);
            handler.postDelayed(refresh, 1000);
        }
    }

    private View buildResultRow(JSONObject e, boolean pending, long now) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 14));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView sender = Ui.bold(Ui.text(this, e.optString("sender"), 15, Theme.TEXT_PRIMARY));
        sender.setSingleLine(true);
        sender.setEllipsize(TextUtils.TruncateAt.END);
        top.addView(sender, new LinearLayout.LayoutParams(0, Ui.WRAP, 1f));
        CharSequence when = DateUtils.getRelativeTimeSpanString(
                e.optLong("time"), now, DateUtils.SECOND_IN_MILLIS);
        TextView time = Ui.text(this, when, 12, Theme.TEXT_TERTIARY);
        time.setPadding(Ui.dp(this, 12), 0, 0, 0);
        top.addView(time);
        row.addView(top);

        String asked = e.optBoolean("call") ? "Called you" : "Said: " + e.optString("text");
        TextView askedView = Ui.text(this, asked, 14, Theme.TEXT_SECONDARY);
        askedView.setMaxLines(2);
        askedView.setEllipsize(TextUtils.TruncateAt.END);
        askedView.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 8));
        row.addView(askedView);

        if (e.has("reply")) {
            row.addView(Ui.tag(this, "Replied"), new LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP));
            TextView reply = Ui.text(this, e.optString("reply"), 15, Theme.TEXT_PRIMARY);
            reply.setPadding(0, Ui.dp(this, 6), 0, 0);
            row.addView(reply);
        } else {
            TextView status = Ui.text(this, pending ? "Waiting for a reply…" : "No reply sent",
                    14, Theme.TEXT_TERTIARY);
            row.addView(status);
        }
        return row;
    }
}
