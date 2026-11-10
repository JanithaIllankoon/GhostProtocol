package com.example.ghostprotocol;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Test Mode: posts fake WhatsApp-style notifications from this app, with a
 * reply button that comes back to {@link TestReplyReceiver}. GhostService
 * treats them exactly like real WhatsApp notifications, so the whole reply
 * flow can be tried without a second phone.
 *
 * Only notifications from this app that carry {@link #EXTRA_TEST} are
 * accepted, so nothing else the app posts is ever replied to.
 */
final class TestReplies {
    private TestReplies() {}

    static final String EXTRA_TEST = "com.janitha.ghostprotocol.TEST_NOTIFICATION";
    static final String EXTRA_ENTRY_ID = "test_entry_id";
    static final String KEY_REPLY = "test_reply";

    static final String PREFS = "GhostTest";
    static final String KEY_LOG = "log_json";
    private static final String KEY_NEXT_ID = "next_id";
    private static final int LOG_MAX = 20;

    private static final String CHANNEL_ID = "GhostProtocol_Test";

    /** How long to wait for a reply before showing "No reply sent". */
    static final long REPLY_WAIT_MS = 5000L;

    static boolean isTestNotification(Context ctx, StatusBarNotification sbn) {
        if (!ctx.getPackageName().equals(sbn.getPackageName())) return false;
        Notification n = sbn.getNotification();
        return n != null && n.extras != null && n.extras.getBoolean(EXTRA_TEST, false);
    }

    // -------------------------------------------------------------------------
    // Posting
    // -------------------------------------------------------------------------

    /** Posts a fake message (or missed call) from {@code sender}. Returns its log id. */
    static int post(Context ctx, String sender, String text, boolean call) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    "Test Replies", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Fake messages sent from the Test Replies screen");
            nm.createNotificationChannel(channel);
        }

        int id = nextId(ctx);
        Intent replyIntent = new Intent(ctx, TestReplyReceiver.class).putExtra(EXTRA_ENTRY_ID, id);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        // The reply text is added to this intent when it's sent, so it must be mutable.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags |= PendingIntent.FLAG_MUTABLE;
        PendingIntent replyPending = PendingIntent.getBroadcast(ctx, id, replyIntent, flags);

        RemoteInput input = new RemoteInput.Builder(KEY_REPLY).setLabel("Reply").build();
        Notification.Action reply = new Notification.Action.Builder(
                Icon.createWithResource(ctx, R.drawable.ic_send), "Reply", replyPending)
                .addRemoteInput(input)
                .build();

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(ctx, CHANNEL_ID)
                : new Notification.Builder(ctx);
        Bundle extras = new Bundle();
        extras.putBoolean(EXTRA_TEST, true);
        b.setSmallIcon(R.drawable.ic_chat)
                .setContentTitle(sender)
                .setContentText(text)
                .setCategory(call ? Notification.CATEGORY_MISSED_CALL : Notification.CATEGORY_MESSAGE)
                .addAction(reply)
                .addExtras(extras)
                .setAutoCancel(true);

        addLogEntry(ctx, id, sender, text, call);
        nm.notify(id, b.build()); // no tag: a tag with "-" would look like a group chat
        return id;
    }

    private static int nextId(Context ctx) {
        SharedPreferences p = prefs(ctx);
        int id = p.getInt(KEY_NEXT_ID, 7000);
        p.edit().putInt(KEY_NEXT_ID, id >= 7999 ? 7000 : id + 1).apply();
        return id;
    }

    // -------------------------------------------------------------------------
    // Result log (newest last)
    // -------------------------------------------------------------------------

    static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static JSONArray log(Context ctx) {
        try {
            return new JSONArray(prefs(ctx).getString(KEY_LOG, "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private static synchronized void addLogEntry(Context ctx, int id, String sender,
                                                 String text, boolean call) {
        try {
            JSONArray arr = log(ctx);
            JSONObject e = new JSONObject();
            e.put("id", id);
            e.put("time", System.currentTimeMillis());
            e.put("sender", sender);
            e.put("text", text);
            e.put("call", call);
            arr.put(e);
            JSONArray trimmed = new JSONArray();
            for (int i = Math.max(0, arr.length() - LOG_MAX); i < arr.length(); i++) {
                trimmed.put(arr.get(i));
            }
            prefs(ctx).edit().putString(KEY_LOG, trimmed.toString()).apply();
        } catch (Exception ignored) {}
    }

    static synchronized void recordReply(Context ctx, int id, CharSequence reply) {
        if (TextUtils.isEmpty(reply)) return;
        try {
            JSONArray arr = log(ctx);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject e = arr.getJSONObject(i);
                if (e.optInt("id") == id) {
                    e.put("reply", reply.toString());
                    e.put("replyTime", System.currentTimeMillis());
                }
            }
            prefs(ctx).edit().putString(KEY_LOG, arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    static void clearLog(Context ctx) {
        prefs(ctx).edit().remove(KEY_LOG).apply();
    }

    /** Forgets everything stored about {@code sender}, so the next test is a first message. */
    static void resetSender(Context ctx, String sender) {
        SharedPreferences.Editor editor =
                ctx.getSharedPreferences("GhostPrefs", Context.MODE_PRIVATE).edit();
        for (String prefix : GhostService.SENDER_KEY_PREFIXES) editor.remove(prefix + sender);
        editor.apply();
    }

    // -------------------------------------------------------------------------
    // Readiness
    // -------------------------------------------------------------------------

    /** A short reason the test can't run right now, or null when it can. */
    static String problem(Context ctx) {
        if (ctx.getSharedPreferences("GhostPrefs", Context.MODE_PRIVATE)
                .getBoolean("manually_stopped", false)) {
            return "Auto-Reply is off. Turn it on from the home screen first.";
        }
        if (!listenerEnabled(ctx)) {
            return "Notification access is off. Finish setup on the home screen first.";
        }
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (!nm.areNotificationsEnabled()) {
            return "Notifications are blocked for Ghost Protocol, so test messages can't be posted. "
                    + "Allow them in Settings › Apps › Ghost Protocol › Notifications.";
        }
        return null;
    }

    private static boolean listenerEnabled(Context ctx) {
        String flat = Settings.Secure.getString(ctx.getContentResolver(),
                "enabled_notification_listeners");
        if (TextUtils.isEmpty(flat)) return false;
        for (String name : flat.split(":")) {
            ComponentName cn = ComponentName.unflattenFromString(name);
            if (cn != null && ctx.getPackageName().equals(cn.getPackageName())) return true;
        }
        return false;
    }
}
