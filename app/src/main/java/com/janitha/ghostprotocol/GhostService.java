package com.janitha.ghostprotocol;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.app.RemoteInput;
import android.util.Log;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

public class GhostService extends NotificationListenerService {

    private final List<String> WHITELIST = Arrays.asList("Contacts - Not Included"); // Ignores them
    private final String TARGET_PACKAGE = "com.whatsapp";

    // INDEX 0–4 : random auto-reply pool (first message in a new 10-min window)
    // INDEX 5   : spam warning            (2nd message in same window, once per 12 hrs)
    // INDEX 6   : call busy message       (sent as a text when someone calls — call rings normally)
    //
    // ALL outbound strings live here — Mirror Shield's single loop catches every
    // echo WhatsApp reflects back, no string can ever escape coverage.
    private final String[] MESSAGE_POOL = {
            /* 0 */ "Text Message",
            /* 1 */ "Text Message",
            /* 2 */ "Text Message",
            /* 3 */ "Text Message",
            /* 4 */ "Text Message",
            /* 5 */ "User Replied for Second Time within 10min Window ###",
            /* 6 */ "Respond to Incoming Calls Text ###"
    };

    // Stable indices — never use magic numbers elsewhere in the code
    private static final int POOL_RANDOM_END  = 5; // exclusive: Random picks from [0, 5)
    private static final int IDX_SPAM_WARNING = 5;
    private static final int IDX_CALL_DECLINE = 6;

    // --- FINITE STATE MACHINE (FSM) TIMINGS ---
    private final long WINDOW_MS = 10 * 60 * 1000;           // 10-minute reply window
    private final long SPAM_COOLDOWN_MS = 12 * 60 * 60 * 1000L; // 12-hour spam warning cooldown

    // -------------------------------------------------------------------------
    // FOREGROUND DETECTOR
    // FIX: was querying the last 10 seconds. When you rapidly open/close multiple
    // apps, the OS keeps WhatsApp marked as "recently active" for that entire
    // window — every message during that time hit the early return silently.
    // Reduced to 2 seconds: tight enough that only truly active foreground use
    // suppresses replies, not residual activity from app-switching.
    // -------------------------------------------------------------------------
    @android.annotation.SuppressLint("MissingPermission")
    private boolean isWhatsAppInForeground() {
        try {
            UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm == null) return false;

            long time = System.currentTimeMillis();
            // 2-second window — tight enough to avoid stale data from app switching
            List<UsageStats> appList = usm.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY, time - 2_000, time);

            if (appList != null && !appList.isEmpty()) {
                UsageStats latest = null;
                for (UsageStats s : appList) {
                    if (latest == null || s.getLastTimeUsed() > latest.getLastTimeUsed()) {
                        latest = s;
                    }
                }
                if (latest != null && TARGET_PACKAGE.equals(latest.getPackageName())) {
                    Log.d("GhostProtocol", "WhatsApp is in foreground — standing down.");
                    return true;
                }
            }
        } catch (Exception e) {
            Log.e("GhostProtocol", "Foreground check failed: " + e.getMessage());
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // SMARTWATCH BACKDOOR — finds the inline-reply action on any OEM
    // -------------------------------------------------------------------------
    private Notification.Action getReplyAction(Notification notification) {
        // 1. Standard Android actions
        if (notification.actions != null) {
            for (Notification.Action action : notification.actions) {
                if (action.getRemoteInputs() != null && action.getRemoteInputs().length > 0) {
                    return action;
                }
            }
        }

        // 2. WearableExtender fallback (Samsung, etc.)
        Notification.WearableExtender wearable = new Notification.WearableExtender(notification);
        for (Notification.Action action : wearable.getActions()) {
            if (action != null && action.getRemoteInputs() != null && action.getRemoteInputs().length > 0) {
                Log.i("GhostProtocol", "Reply action extracted via Smartwatch Backdoor.");
                return action;
            }
        }

        return null; // No reply box found on either path
    }

    // -------------------------------------------------------------------------
    // SHARED REPLY SENDER — reused by both message FSM and call handler
    // -------------------------------------------------------------------------
    private void sendReply(Notification.Action replyAction, String payload) {
        RemoteInput[] remoteInputs = replyAction.getRemoteInputs();
        if (remoteInputs == null || remoteInputs.length == 0) return;

        Bundle replyBundle = new Bundle();
        replyBundle.putCharSequence(remoteInputs[0].getResultKey(), payload);

        Intent localIntent = new Intent();
        RemoteInput.addResultsToIntent(remoteInputs, localIntent, replyBundle);

        try {
            if (replyAction.actionIntent != null) {
                replyAction.actionIntent.send(this, 0, localIntent);
                Log.i("GhostProtocol", "Payload sent: " + payload.substring(0, Math.min(40, payload.length())) + "…");
            }
        } catch (PendingIntent.CanceledException e) {
            Log.e("GhostProtocol", "Reply send failed: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // NOTIFICATION ENTRY POINT
    // -------------------------------------------------------------------------
    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {

        if (sbn == null || !TARGET_PACKAGE.equals(sbn.getPackageName())) return;

        Notification notification = sbn.getNotification();
        if (notification == null || notification.extras == null) return;

        // Drop the group summary banner (not an actual message)
        if ((notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;

        Bundle extras = notification.extras;
        String title = extras.getString(Notification.EXTRA_TITLE);
        CharSequence textSeq = extras.getCharSequence(Notification.EXTRA_TEXT);

        if (title == null) return;
        String text = (textSeq != null) ? textSeq.toString() : "Media Content";

        // Drop WhatsApp system notifications (not real messages)
        if (title.contains("WhatsApp") || text.contains("new messages") || text.contains("Checking for new messages")) return;

        String lowerText = text.toLowerCase();

        // --- WHITELIST GATE — always first ---
        for (String trusted : WHITELIST) {
            if (title.contains(trusted)) {
                Log.d("GhostProtocol", "Whitelisted contact [" + trusted + "] — standing down completely.");
                return;
            }
        }

        // --- SLOW FOREGROUND CHECK ---
        if (isWhatsAppInForeground()) return;

        // --- GROUP MESSAGES: mute silently, no reply ---
        CharSequence conversationTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE);
        boolean isGroup = (conversationTitle != null);
        if (!isGroup) {
            String tag = sbn.getTag();
            isGroup = (tag != null && tag.contains("-"));
        }
        if (isGroup) {
            Log.d("GhostProtocol", "Group message from [" + title + "] — muting silently.");
            muteNotification(sbn);
            return;
        }

        try {
            boolean isCall = Notification.CATEGORY_CALL.equals(notification.category)
                    || Notification.CATEGORY_MISSED_CALL.equals(notification.category)
                    || lowerText.contains("ringing")
                    || lowerText.contains("incoming call")
                    || (lowerText.contains("missed") && lowerText.contains("call"));

            if (isCall) {
                // Call rings normally — we only send the busy text message.
                // No decline, no mute. The caller hears it ring as usual.
                handleCall(notification, title);

            } else {
                // Regular text / media message — run through the FSM
                Notification.Action replyAction = getReplyAction(notification);

                if (replyAction == null) {
                    Log.w("GhostProtocol", "No reply action found — muting without reply.");
                    muteNotification(sbn);
                    return;
                }

                handleIncomingMessage(title, text, textSeq == null, sbn, replyAction);
            }

        } catch (Exception e) {
            Log.e("GhostProtocol", "Crash prevented: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // CALL HANDLER — sends the busy message only.
    // The call rings normally. We do NOT decline or mute anything.
    // -------------------------------------------------------------------------
    private void handleCall(Notification notification, String callerName) {
        Notification.Action replyAction = getReplyAction(notification);
        if (replyAction != null) {
            sendReply(replyAction, MESSAGE_POOL[IDX_CALL_DECLINE]);
            Log.i("GhostProtocol", "Busy message sent to caller: " + callerName);
        } else {
            Log.w("GhostProtocol", "Call from " + callerName + ": no reply action found.");
        }
    }

    // -------------------------------------------------------------------------
    // MESSAGE FSM
    // Window rules:
    //   • 1st message in window  → send a random quote
    //   • 2nd+ message in window → send spam warning (once per 12 hrs), then mute
    //   • Remaining messages     → mute silently
    // Notification is ALWAYS muted, whether or not a reply was sent.
    // FIX: forceSpamWarning flag removed (calls no longer route here).
    // FIX: notification is now cancelled even after a reply is sent.
    // -------------------------------------------------------------------------
    private void handleIncomingMessage(String senderName, String text, boolean isMedia,
                                       StatusBarNotification sbn,
                                       Notification.Action replyAction) {
        long now = System.currentTimeMillis();

        try {
            SharedPreferences prefs = getSharedPreferences("GhostPrefs", Context.MODE_PRIVATE);
            long windowStart = prefs.getLong("window_" + senderName, 0L);
            int msgCount    = prefs.getInt("count_" + senderName, 0);
            long lastSeen   = prefs.getLong("last_seen_" + senderName, 0L);
            String lastText = prefs.getString("last_text_" + senderName, "");

            // --- MIRROR SHIELD: ignore echoes of every string we have ever sent ---
            // Because MESSAGE_POOL is the single source of truth for ALL outbound text
            // (random quotes, call-decline, AND spam warning), one loop covers everything.
            // Without this, WhatsApp's echo of e.g. CALL_DECLINE_MESSAGE would slip through
            // and trigger a second, unintended random-quote reply to the same person.
            for (String sent : MESSAGE_POOL) {
                if (text.contains(sent)) {
                    muteNotification(sbn);
                    return;
                }
            }

            // --- PHOTOCOPIER SHIELD: stop Android re-delivering the same notification twice ---
            // ONLY applied to real text content. Media notifications (stickers, images, GIFs,
            // audio, video) all produce the same short descriptor string every time
            // (e.g. "📷 Photo", "😀", "GIF"), so equality-checking their text would block
            // every second sticker/image as a "duplicate" even though it's a distinct send.
            // The Microsecond Echo Shield (3 s window below) already handles true Android
            // re-deliveries of the same notification object for both text and media.
            boolean isMediaMessage = isMedia;
            if (!isMediaMessage && text.equals(lastText)) {
                muteNotification(sbn);
                return;
            }
            // Only persist lastText for real text messages — media descriptors are too
            // generic to be useful as a dedup key.
            if (!isMediaMessage) {
                prefs.edit().putString("last_text_" + senderName, text).apply();
            }

            // --- MICROSECOND ECHO SHIELD: ignore bursts within 3 seconds ---
            if (now - lastSeen < 3000) {
                muteNotification(sbn);
                return;
            }
            prefs.edit().putLong("last_seen_" + senderName, now).apply();

            // --- FSM DECISION ---
            String payload = null;

            if (now - windowStart > WINDOW_MS) {
                // New 10-minute window → send the random quote, reset counter
                prefs.edit()
                        .putLong("window_" + senderName, now)
                        .putInt("count_" + senderName, 1)
                        .apply();

                payload = MESSAGE_POOL[new Random().nextInt(POOL_RANDOM_END)];

            } else {
                // Still inside the current window
                msgCount++;
                prefs.edit().putInt("count_" + senderName, msgCount).apply();

                if (msgCount >= 2) {
                    long lastSpamTime = prefs.getLong("spam_time_" + senderName, 0L);
                    if (now - lastSpamTime > SPAM_COOLDOWN_MS) {
                        // 2nd message in window AND spam cooldown has expired → warn once
                        prefs.edit().putLong("spam_time_" + senderName, now).apply();

                        int chatCount = new Random().nextInt(12) + 15;
                        payload = MESSAGE_POOL[IDX_SPAM_WARNING]
                                + ", he currently has 99+ messages from " + chatCount + " chats on this app.";
                    }
                    // else: spam cooldown still active → payload stays null → mute only
                }
                // msgCount == 1 inside window shouldn't happen (window resets on 1st msg),
                // but if it does, payload stays null → mute only
            }

            // --- EXECUTION ---
            if (payload != null) {
                sendReply(replyAction, payload);
                Log.i("GhostProtocol", "FSM replied to " + senderName);
            } else {
                Log.d("GhostProtocol", "FSM: muting without reply for " + senderName);
            }

        } catch (Exception e) {
            Log.e("GhostProtocol", "FSM error: " + e.getMessage());
        }

        // FIX: ALWAYS mute the notification — whether or not a reply was sent.
        // Previously, muting only happened in the "no reply" branch.
        muteNotification(sbn);
    }

    // -------------------------------------------------------------------------
    // UTILITY: cancel / mute a notification
    // -------------------------------------------------------------------------
    private void muteNotification(StatusBarNotification sbn) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cancelNotification(sbn.getKey());
        }
    }

    // -------------------------------------------------------------------------
    // SERVICE LIFECYCLE
    // -------------------------------------------------------------------------
    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            requestRebind(new ComponentName(this, GhostService.class));
        }
    }
}