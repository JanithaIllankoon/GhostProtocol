package com.example.ghostprotocol;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Scrollable log of the last 50 replies this app has sent.
 * Helps verify the protocol is actually firing and debug missed replies.
 */
public class HistoryActivity extends AppCompatActivity {

    private LinearLayout listCard;
    private TextView clearAction;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUI();
    }

    @Override
    protected void onResume() {
        super.onResume();
        rebuildList();
    }

    private void buildUI() {
        LinearLayout column = Ui.screen(this, "Recent replies",
                "The last 50 auto-replies, newest first.", true);

        clearAction = Ui.headerAction(column, "Clear", v ->
                new MaterialAlertDialogBuilder(this)
                        .setTitle("Clear recent replies?")
                        .setMessage("This removes the list below. It can't be undone.")
                        .setPositiveButton("Clear", (d, w) -> {
                            getSharedPreferences(GhostService.MSG_PREFS, Context.MODE_PRIVATE)
                                    .edit()
                                    .remove(GhostService.HISTORY_KEY)
                                    .apply();
                            rebuildList();
                        })
                        .setNegativeButton("Cancel", null)
                        .show());

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP);
        lp.topMargin = Ui.dp(this, 16);
        listCard = Ui.card(this);
        column.addView(listCard, lp);
    }

    private void rebuildList() {
        listCard.removeAllViews();

        SharedPreferences prefs = getSharedPreferences(GhostService.MSG_PREFS, Context.MODE_PRIVATE);
        String json = prefs.getString(GhostService.HISTORY_KEY, "[]");

        try {
            JSONArray arr = new JSONArray(json);
            if (arr.length() == 0) {
                showEmpty();
                return;
            }
            clearAction.setVisibility(View.VISIBLE);
            // Newest first
            for (int i = arr.length() - 1; i >= 0; i--) {
                JSONObject entry = arr.getJSONObject(i);
                Ui.addRow(listCard, buildRow(entry));
            }
        } catch (Exception e) {
            showEmpty();
        }
    }

    private void showEmpty() {
        listCard.removeAllViews();
        clearAction.setVisibility(View.GONE);
        Ui.addRow(listCard, Ui.emptyRow(this,
                "Nothing yet.\nReplies will show up here once they're sent."));
    }

    private View buildRow(JSONObject entry) {
        String sender  = entry.optString("sender", "Unknown");
        String message = entry.optString("message", "");
        String trigger = entry.optString("trigger", "");
        long time      = entry.optLong("time", 0L);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 14));

        // --- Top row: sender + relative time ---
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView senderView = Ui.bold(Ui.text(this, sender, 15, Theme.TEXT_PRIMARY));
        senderView.setSingleLine(true);
        senderView.setEllipsize(TextUtils.TruncateAt.END);
        top.addView(senderView, new LinearLayout.LayoutParams(0, Ui.WRAP, 1f));

        if (time > 0) {
            CharSequence rel = DateUtils.getRelativeTimeSpanString(
                    time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS);
            TextView timeView = Ui.text(this, rel, 12, Theme.TEXT_TERTIARY);
            timeView.setPadding(Ui.dp(this, 12), 0, 0, 0);
            top.addView(timeView);
        }
        row.addView(top);

        // --- Message body ---
        TextView body = Ui.text(this, message, 14, Theme.TEXT_SECONDARY);
        body.setMaxLines(3);
        body.setEllipsize(TextUtils.TruncateAt.END);
        body.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 8));
        row.addView(body);

        // --- Why it was sent ---
        LinearLayout.LayoutParams tagParams = new LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP);
        row.addView(Ui.tag(this, triggerLabel(trigger)), tagParams);
        return row;
    }

    private String triggerLabel(String trigger) {
        switch (trigger) {
            case "keyword":  return "Keyword reply";
            case "random":   return "Away message";
            case "spam":     return "Repeated messages";
            case "stranger": return "Unknown number";
            case "call":     return "Missed call";
            default:         return trigger.isEmpty() ? "Reply" : trigger;
        }
    }
}
