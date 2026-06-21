package com.example.ghostprotocol;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Scrollable log of the last 50 replies this app has sent.
 * Helps verify the protocol is actually firing and debug missed replies.
 */
public class HistoryActivity extends AppCompatActivity {

    private LinearLayout listContainer;

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
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Theme.BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(40, 50, 40, 50);

        TextView header = new TextView(this);
        header.setText("Reply History");
        header.setTextColor(Theme.PRIMARY);
        header.setTextSize(20);
        header.setTypeface(null, Typeface.BOLD);
        header.setGravity(Gravity.CENTER);
        header.setPadding(0, 0, 0, 8);

        TextView subHeader = new TextView(this);
        subHeader.setText("Last 50 replies sent. Newest first. Useful for checking that replies are actually firing.");
        subHeader.setTextColor(Theme.TEXT_MUTED);
        subHeader.setTextSize(12);
        subHeader.setGravity(Gravity.CENTER);
        subHeader.setPadding(0, 0, 0, 26);

        listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);

        Button clearBtn = new Button(this);
        clearBtn.setText("🗑  Clear History");
        clearBtn.setAllCaps(false);
        clearBtn.setTextColor(Theme.DANGER);
        clearBtn.setBackgroundColor(Theme.DELETE_BG);
        clearBtn.setTypeface(null, Typeface.BOLD);
        clearBtn.setTextSize(13);
        LinearLayout.LayoutParams clearParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 110);
        clearParams.setMargins(0, 18, 0, 0);
        clearBtn.setLayoutParams(clearParams);
        clearBtn.setOnClickListener(v ->
                new AlertDialog.Builder(this)
                        .setTitle("Clear all history?")
                        .setMessage("This removes all logged replies. This cannot be undone.")
                        .setPositiveButton("Clear", (d, w) -> {
                            getSharedPreferences(GhostService.MSG_PREFS, Context.MODE_PRIVATE)
                                    .edit()
                                    .remove(GhostService.HISTORY_KEY)
                                    .apply();
                            rebuildList();
                        })
                        .setNegativeButton("Cancel", null)
                        .show());

        root.addView(header);
        root.addView(subHeader);
        root.addView(listContainer);
        root.addView(clearBtn);
        scroll.addView(root);
        setContentView(scroll);
    }

    private void rebuildList() {
        listContainer.removeAllViews();

        SharedPreferences prefs = getSharedPreferences(GhostService.MSG_PREFS, Context.MODE_PRIVATE);
        String json = prefs.getString(GhostService.HISTORY_KEY, "[]");

        try {
            JSONArray arr = new JSONArray(json);
            if (arr.length() == 0) {
                showEmpty();
                return;
            }
            // Newest first
            for (int i = arr.length() - 1; i >= 0; i--) {
                JSONObject entry = arr.getJSONObject(i);
                listContainer.addView(buildCard(entry));
            }
        } catch (Exception e) {
            showEmpty();
        }
    }

    private void showEmpty() {
        TextView empty = new TextView(this);
        empty.setText("No replies logged yet.\nHistory builds up as messages arrive.");
        empty.setTextColor(Theme.TEXT_MUTED);
        empty.setTextSize(13);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(0, 40, 0, 40);
        listContainer.addView(empty);
    }

    private View buildCard(JSONObject entry) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(Theme.CARD_BG);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        cardParams.setMargins(0, 0, 0, 12);
        card.setLayoutParams(cardParams);
        card.setPadding(24, 18, 24, 18);

        String sender  = entry.optString("sender", "Unknown");
        String message = entry.optString("message", "");
        String trigger = entry.optString("trigger", "");
        long time      = entry.optLong("time", 0L);

        // --- Top row: trigger badge + sender + relative time ---
        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);
        topRow.setPadding(0, 0, 0, 8);

        TextView badge = new TextView(this);
        badge.setText(triggerLabel(trigger));
        badge.setTextColor(triggerColor(trigger));
        badge.setBackgroundColor(triggerBgColor(trigger));
        badge.setTextSize(10);
        badge.setTypeface(null, Typeface.BOLD);
        badge.setPadding(14, 6, 14, 6);
        LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        badgeParams.setMargins(0, 0, 12, 0);
        badge.setLayoutParams(badgeParams);

        TextView senderView = new TextView(this);
        senderView.setText(sender);
        senderView.setTextColor(Theme.TEXT_PRIMARY);
        senderView.setTextSize(13);
        senderView.setTypeface(null, Typeface.BOLD);
        senderView.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView timeView = new TextView(this);
        if (time > 0) {
            CharSequence rel = DateUtils.getRelativeTimeSpanString(
                    time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS);
            timeView.setText(rel);
        }
        timeView.setTextColor(Theme.TEXT_TERTIARY);
        timeView.setTextSize(10);

        topRow.addView(badge);
        topRow.addView(senderView);
        topRow.addView(timeView);

        // --- Message body ---
        TextView body = new TextView(this);
        String preview = message.length() > 140 ? message.substring(0, 140) + "…" : message;
        body.setText(preview);
        body.setTextColor(Theme.TEXT_SECONDARY);
        body.setTextSize(12);

        card.addView(topRow);
        card.addView(body);
        return card;
    }

    // -------------------------------------------------------------------------
    // Trigger badge helpers
    // -------------------------------------------------------------------------
    private String triggerLabel(String trigger) {
        switch (trigger) {
            case "keyword":  return "KEYWORD";
            case "random":   return "RANDOM";
            case "spam":     return "SPAM WARN";
            case "stranger": return "STRANGER";
            case "call":     return "CALL";
            default:         return trigger.toUpperCase();
        }
    }

    private int triggerColor(String trigger) {
        switch (trigger) {
            case "keyword":  return Theme.ACCENT_CALL;
            case "random":   return Theme.PRIMARY;
            case "spam":     return Theme.ACCENT_SPAM;
            case "stranger": return Theme.WARN;
            case "call":     return Theme.ACCENT_CALL;
            default:         return Theme.TEXT_SECONDARY;
        }
    }

    private int triggerBgColor(String trigger) {
        switch (trigger) {
            case "keyword":  return 0xFF001A22;
            case "random":   return Theme.PRIMARY_BG;
            case "spam":     return 0xFF2A1A00;
            case "stranger": return Theme.WARN_BG;
            case "call":     return 0xFF001A22;
            default:         return Theme.INPUT_BG;
        }
    }
}
