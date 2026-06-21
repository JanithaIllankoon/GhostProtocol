package com.example.ghostprotocol;

import android.Manifest;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.os.Build;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.app.RemoteInput;
import android.util.Log;

import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GhostService extends NotificationListenerService {

    // =========================================================================
    // CONSTANTS  (all static final — compiler-inlinable)
    // =========================================================================
    private static final String TARGET_PACKAGE     = "com.whatsapp";
    private static final String TARGET_PACKAGE_BIZ = "com.whatsapp.w4b";  // WhatsApp Business

    // Shared RNG — avoids identical seeds when notifications arrive close together
    private static final Random RNG = new Random();

    // FSM timings
    private static final long WINDOW_MS        = 10 * 60 * 1000L;
    private static final long SPAM_COOLDOWN_MS = 12 * 60 * 60 * 1000L;

    // Shields
    private static final long PHOTOCOPIER_WINDOW_MS = 5 * 60 * 1000L;  // dedup window
    private static final long ECHO_SHIELD_MS        = 3 * 1000L;       // burst guard
    private static final int  MIRROR_MIN_LEN        = 20;              // substring min
    private static final long MIRROR_WINDOW_MS      = 30 * 1000L;     // only applies within 30s of last reply

    // Foreground check cache — critical for listener stability.
    private static final long FG_CACHE_MS = 2000L;
    private long    cachedFgAt     = 0L;
    private boolean cachedFgResult = false;

    // Contacts cache — reloaded every 5 min or on manual trigger.
    private static final long CONTACTS_RELOAD_MS = 5 * 60 * 1000L;
    private Set<String> contactNamesLower     = null;
    private Set<String> contactNumbersDigits  = null;
    private long contactsLoadedAt = 0L;

    // History log cap
    private static final int HISTORY_MAX = 50;
    static final String HISTORY_KEY = "history_json";

    // Generic first-contact reply; users can customize normal replies in the app.
    private static final String STRANGER_MSG =
            "Thanks for your message. This number is not in my contacts, so my reply may be delayed.";

    // Start empty so no personal contact names are committed to source control.
    static final String[] DEFAULT_WHITELIST = {};

    // =========================================================================
    // DEFAULT MESSAGE POOL
    // INDEX 0–4 : random auto-reply pool (first message in a new 10-min window)
    // INDEX 5   : spam warning            (2nd message in same window, once per 12 hrs)
    // INDEX 6   : call busy message       (sent as a text when someone calls)
    // =========================================================================
    static final String[] DEFAULT_POOL = {
            /* 0 */ "Thanks for your message. I am unavailable right now.",
            /* 1 */ "Message received. I will respond when I can.",
            /* 2 */ "I am away at the moment, but I have seen your message.",
            /* 3 */ "Thanks for reaching out. I will get back to you later.",
            /* 4 */ "I cannot reply right now. Please try again later.",
            /* 5 */ "I am still unavailable; repeated messages may be muted.",
            /* 6 */ "I cannot answer this call right now. Please leave a message."
    };
    static final int IDX_SPAM_WARNING = 5;
    static final int IDX_CALL_DECLINE = 6;

    static final String MSG_PREFS = "GhostMessages";

    // =========================================================================
    // IN-MEMORY CACHES — rebuilt on service start and when prefs change.
    // Eliminates per-notification SharedPreferences disk reads.
    // =========================================================================
    private volatile List<String> cachedWhitelist;
    private volatile String[]     cachedPool;
    private volatile String       cachedSpamMsg;
    private volatile String       cachedCallMsg;
    private volatile Set<String>  cachedSentStrings;
    private volatile Pattern      cachedKeywordPattern;
    private volatile Map<String, String> cachedKeywordToReply;  // lowercase kw → reply
    private volatile boolean cachesDirty = true;

    // Timestamp of the last reply sent — used to scope Mirror Shield
    private long lastReplyAtMs = 0L;

    // Tracks the trigger type for the last resolveKnownSenderFSM call
    private String lastFsmTrigger;

    // Prefs listener to mark caches dirty when user edits settings
    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener =
            (prefs, key) -> cachesDirty = true;

    // =========================================================================
    // LIFECYCLE — cache warm-up on service bind
    // =========================================================================
    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        getSharedPreferences(MSG_PREFS, MODE_PRIVATE)
                .registerOnSharedPreferenceChangeListener(prefListener);
        // Warm up caches and contacts on a background thread
        new Thread(() -> {
            reloadCaches();
            loadContactsIfStale();
        }).start();
        Log.i("GhostProtocol", "Listener connected — caches warming up.");
    }

    // =========================================================================
    // CACHE LOADER — all SharedPreferences reads in one shot
    // =========================================================================
    private synchronized void reloadCaches() {
        SharedPreferences prefs = getSharedPreferences(MSG_PREFS, MODE_PRIVATE);

        // --- Pool ---
        int count = prefs.getInt("pool_count", 0);
        if (count == 0) {
            cachedPool = Arrays.copyOf(DEFAULT_POOL, IDX_SPAM_WARNING);
        } else {
            String[] pool = new String[count];
            for (int i = 0; i < count; i++) {
                pool[i] = prefs.getString("pool_msg_" + i,
                        DEFAULT_POOL[Math.min(i, IDX_SPAM_WARNING - 1)]);
            }
            cachedPool = pool;
        }

        // --- System messages ---
        cachedSpamMsg = prefs.getString(EditMessagesActivity.KEY_SPAM_MSG,
                DEFAULT_POOL[IDX_SPAM_WARNING]);
        cachedCallMsg = prefs.getString(EditMessagesActivity.KEY_CALL_MSG,
                DEFAULT_POOL[IDX_CALL_DECLINE]);

        // --- Whitelist ---
        int wlCount = prefs.getInt("whitelist_count", -1);
        List<String> wl = new ArrayList<>();
        if (wlCount == -1) {
            wl.addAll(Arrays.asList(DEFAULT_WHITELIST));
        } else {
            for (int i = 0; i < wlCount; i++) {
                String entry = prefs.getString("whitelist_" + i, "");
                if (!entry.isEmpty()) wl.add(entry);
            }
        }
        cachedWhitelist = Collections.unmodifiableList(wl);

        // --- Keywords → compiled regex + keyword-to-reply map ---
        // Uses \p{L} and \p{Nd} for Unicode-aware word boundaries:
        //   (?<![\p{L}\p{Nd}])keyword(?![\p{L}\p{Nd}])
        // This replaces the O(K×M) manual codePointBefore/codePointAt loop
        // with a single compiled regex evaluation. ~10× faster, same semantics.
        int kwCount = prefs.getInt("kw_count", 0);
        Map<String, String> kwMap = new HashMap<>();
        StringBuilder regexParts = new StringBuilder();
        for (int i = 0; i < kwCount; i++) {
            String keywordsRaw = prefs.getString("kw_key_" + i, "").trim();
            String reply = prefs.getString("kw_reply_" + i, "").trim();
            if (keywordsRaw.isEmpty() || reply.isEmpty()) continue;
            for (String kw : keywordsRaw.split(",")) {
                String trimmed = kw.trim();
                if (trimmed.isEmpty()) continue;
                String lower = trimmed.toLowerCase(Locale.ROOT);
                kwMap.put(lower, reply);
                if (regexParts.length() > 0) regexParts.append("|");
                regexParts.append(Pattern.quote(lower));
            }
        }
        cachedKeywordToReply = Collections.unmodifiableMap(kwMap);
        if (regexParts.length() > 0) {
            String regex = "(?<![\\p{L}\\p{Nd}])(?:" + regexParts + ")(?![\\p{L}\\p{Nd}])";
            cachedKeywordPattern = Pattern.compile(regex,
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        } else {
            cachedKeywordPattern = null;
        }

        // --- Sent strings set (for Mirror Shield) ---
        Set<String> sent = new HashSet<>();
        if (cachedSpamMsg != null) sent.add(cachedSpamMsg);
        if (cachedCallMsg != null) sent.add(cachedCallMsg);
        sent.add(STRANGER_MSG);
        for (String s : cachedPool) { if (s != null) sent.add(s); }
        for (String v : kwMap.values()) { if (v != null) sent.add(v); }
        cachedSentStrings = Collections.unmodifiableSet(sent);

        cachesDirty = false;
        Log.d("GhostProtocol", "Caches reloaded: " + cachedPool.length + " pool, "
                + kwMap.size() + " keywords, " + wl.size() + " whitelist");
    }

    /** Ensures caches are loaded before use. Blocks only on first call. */
    private void ensureCachesLoaded() {
        if (cachedPool == null) {
            reloadCaches(); // sync fallback for first notification before onListenerConnected
        } else if (cachesDirty) {
            new Thread(this::reloadCaches).start(); // async refresh, use stale data for this msg
        }
    }

    // =========================================================================
    // KEYWORD MATCHER — uses pre-compiled regex with Unicode word boundaries
    //
    // Results are identical to the old manual codePointBefore/codePointAt loop:
    //   "ok" in "ok thanks"  → match       "ok" in "look"       → no match
    //   "ok" in "okay"       → no match    "ok" in "ok😀"       → match
    //   "හරි" in "හරියට"   → no match    "හරි" in "හරි ඇයි" → match
    // =========================================================================
    private String getKeywordReply(String text) {
        Pattern pattern = cachedKeywordPattern;
        Map<String, String> map = cachedKeywordToReply;
        if (pattern == null || map == null || map.isEmpty()) return null;

        Matcher m = pattern.matcher(text.toLowerCase(Locale.ROOT));
        if (m.find()) {
            String matched = m.group().toLowerCase(Locale.ROOT);
            String reply = map.get(matched);
            if (reply != null) {
                Log.i("GhostProtocol", "Keyword rule matched.");
                return reply;
            }
        }
        return null;
    }

    // =========================================================================
    // UNKNOWN CONTACT DETECTOR — backed by the phone's contacts
    //
    // A sender is "known" if:
    //   • the notification title matches a contact display name (case-insensitive), OR
    //   • the notification title is a phone number whose last 9 digits match
    //     a saved number (handles "+94771234567" vs "0771234567" variants)
    //
    // If permission is denied or the phone has no contacts, falls back to the
    // old heuristic: pure phone number → unknown, anything else → known.
    // =========================================================================
    private void loadContactsIfStale() {
        long now = System.currentTimeMillis();
        if (contactNamesLower != null && now - contactsLoadedAt < CONTACTS_RELOAD_MS) return;

        Set<String> names   = new HashSet<>();
        Set<String> numbers = new HashSet<>();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
                != PackageManager.PERMISSION_GRANTED) {
            contactNamesLower    = names;    // empty → signals fallback mode
            contactNumbersDigits = numbers;
            contactsLoadedAt     = now;
            Log.w("GhostProtocol", "READ_CONTACTS not granted — falling back to heuristic.");
            return;
        }

        Cursor c = null;
        try {
            c = getContentResolver().query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    new String[] {
                            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                            ContactsContract.CommonDataKinds.Phone.NUMBER
                    },
                    null, null, null);
            if (c != null) {
                while (c.moveToNext()) {
                    String name   = c.getString(0);
                    String number = c.getString(1);
                    if (name != null && !name.isEmpty()) {
                        names.add(name.toLowerCase(Locale.ROOT));
                    }
                    if (number != null) {
                        String digits = number.replaceAll("[^0-9]", "");
                        if (!digits.isEmpty()) numbers.add(digits);
                    }
                }
            }
        } catch (Exception e) {
            Log.e("GhostProtocol", "Contacts read failed: " + e.getMessage());
        } finally {
            if (c != null) c.close();
        }

        contactNamesLower    = names;
        contactNumbersDigits = numbers;
        contactsLoadedAt     = now;
        Log.d("GhostProtocol", "Contacts loaded: " + names.size()
                + " names, " + numbers.size() + " numbers");
    }

    private boolean isUnknownContact(String title) {
        if (title == null || title.isEmpty()) return true;

        loadContactsIfStale();

        // Fallback heuristic if permission denied or contacts are empty
        if (contactNamesLower.isEmpty() && contactNumbersDigits.isEmpty()) {
            String stripped = title.replaceAll("[+\\-\\s()0-9]", "");
            return stripped.isEmpty();
        }

        // Name match (case-insensitive, exact)
        String titleLower = title.toLowerCase(Locale.ROOT);
        if (contactNamesLower.contains(titleLower)) return false;

        // Phone number match — compare last 9 digits to handle
        // "+94771234567" vs "0771234567" vs "771234567"
        String titleDigits = title.replaceAll("[^0-9]", "");
        if (titleDigits.length() >= 7) {
            String titleSuffix = titleDigits.length() >= 9
                    ? titleDigits.substring(titleDigits.length() - 9)
                    : titleDigits;
            for (String num : contactNumbersDigits) {
                if (num.equals(titleDigits)) return false;
                if (num.length() >= titleSuffix.length() && num.endsWith(titleSuffix)) return false;
            }
        }

        return true; // not in contacts
    }

    // =========================================================================
    // FOREGROUND DETECTOR — cached (2s)
    // queryUsageStats is slow; calling it on every notification caused the
    // listener thread to back up and Android would silently unbind us.
    // Now checks both WhatsApp and WhatsApp Business.
    // =========================================================================
    @android.annotation.SuppressLint("MissingPermission")
    private boolean isWhatsAppInForeground() {
        long now = System.currentTimeMillis();
        if (now - cachedFgAt < FG_CACHE_MS) {
            return cachedFgResult;
        }
        boolean result = false;
        try {
            UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm != null) {
                List<UsageStats> appList = usm.queryUsageStats(
                        UsageStatsManager.INTERVAL_DAILY, now - 2_000, now);
                if (appList != null && !appList.isEmpty()) {
                    UsageStats latest = null;
                    for (UsageStats s : appList) {
                        if (latest == null || s.getLastTimeUsed() > latest.getLastTimeUsed())
                            latest = s;
                    }
                    if (latest != null) {
                        String pkg = latest.getPackageName();
                        if (TARGET_PACKAGE.equals(pkg) || TARGET_PACKAGE_BIZ.equals(pkg)) {
                            result = true;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e("GhostProtocol", "Foreground check failed: " + e.getMessage());
        }
        cachedFgAt = now;
        cachedFgResult = result;
        if (result) Log.d("GhostProtocol", "WhatsApp in foreground — standing down.");
        return result;
    }

    // =========================================================================
    // REPLY ACTION FINDER (inline or wearable-extended)
    // =========================================================================
    private Notification.Action getReplyAction(Notification notification) {
        if (notification.actions != null) {
            for (Notification.Action action : notification.actions) {
                if (action.getRemoteInputs() != null && action.getRemoteInputs().length > 0)
                    return action;
            }
        }
        Notification.WearableExtender wearable = new Notification.WearableExtender(notification);
        for (Notification.Action action : wearable.getActions()) {
            if (action != null && action.getRemoteInputs() != null && action.getRemoteInputs().length > 0) {
                Log.i("GhostProtocol", "Reply action extracted via Smartwatch Backdoor.");
                return action;
            }
        }
        return null;
    }

    // =========================================================================
    // REPLY SENDER
    // =========================================================================
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
                lastReplyAtMs = System.currentTimeMillis();
                Log.i("GhostProtocol", "Reply payload sent.");
            }
        } catch (PendingIntent.CanceledException e) {
            Log.e("GhostProtocol", "Reply send failed: " + e.getMessage());
        }
    }

    // =========================================================================
    // HISTORY LOGGER — runs on a background thread to avoid blocking the
    // notification listener with JSON parsing and SharedPreferences I/O.
    // =========================================================================
    private void logReplyHistory(String sender, String message, String trigger) {
        new Thread(() -> {
            try {
                SharedPreferences prefs = getSharedPreferences(MSG_PREFS, Context.MODE_PRIVATE);
                String existing = prefs.getString(HISTORY_KEY, "[]");
                JSONArray arr = new JSONArray(existing);

                JSONObject entry = new JSONObject();
                entry.put("sender",  sender);
                entry.put("message", message);
                entry.put("trigger", trigger);
                entry.put("time",    System.currentTimeMillis());
                arr.put(entry);

                // Trim to last HISTORY_MAX
                if (arr.length() > HISTORY_MAX) {
                    JSONArray trimmed = new JSONArray();
                    for (int i = arr.length() - HISTORY_MAX; i < arr.length(); i++) {
                        trimmed.put(arr.get(i));
                    }
                    arr = trimmed;
                }
                prefs.edit().putString(HISTORY_KEY, arr.toString()).apply();
            } catch (Exception e) {
                Log.e("GhostProtocol", "History log failed: " + e.getMessage());
            }
        }).start();
    }

    // =========================================================================
    // NOTIFICATION ENTRY POINT
    // =========================================================================
    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {

        if (sbn == null) return;
        String pkg = sbn.getPackageName();
        if (!TARGET_PACKAGE.equals(pkg) && !TARGET_PACKAGE_BIZ.equals(pkg)) return;

        // Ensure in-memory caches are loaded
        ensureCachesLoaded();

        Notification notification = sbn.getNotification();
        if (notification == null || notification.extras == null) return;
        if ((notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;

        Bundle extras = notification.extras;
        String title = extras.getString(Notification.EXTRA_TITLE);
        CharSequence textSeq = extras.getCharSequence(Notification.EXTRA_TEXT);

        if (title == null) return;
        String text = (textSeq != null) ? textSeq.toString() : "Media Content";

        if (title.contains("WhatsApp")
                || text.contains("new messages")
                || text.contains("Checking for new messages")) return;

        String lowerText = text.toLowerCase();

        // --- WHITELIST GATE — exact case-insensitive match ---
        // Changed from .contains() to .equalsIgnoreCase() to prevent false
        // positives (e.g. "Alice" no longer accidentally whitelists "Alicia").
        List<String> whitelist = cachedWhitelist;
        if (whitelist != null) {
            for (String trusted : whitelist) {
                if (title.equalsIgnoreCase(trusted)) {
                    Log.d("GhostProtocol", "Whitelisted contact detected — standing down.");
                    return;
                }
            }
        }

        // --- FOREGROUND CHECK (cached) ---
        if (isWhatsAppInForeground()) return;

        // --- GROUP MESSAGES: mute silently, no reply ---
        CharSequence conversationTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE);
        boolean isGroup = (conversationTitle != null);
        if (!isGroup) {
            String tag = sbn.getTag();
            isGroup = (tag != null && tag.contains("-"));
        }
        if (isGroup) {
            Log.d("GhostProtocol", "Group message detected — muting silently.");
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
                handleCall(notification, title);
            } else {
                Notification.Action replyAction = getReplyAction(notification);
                if (replyAction == null) {
                    Log.w("GhostProtocol", "No reply action found — muting without reply.");
                    muteNotification(sbn);
                    return;
                }
                handleIncomingMessage(title, text, textSeq == null,
                        isUnknownContact(title), sbn, replyAction);
            }

        } catch (Exception e) {
            Log.e("GhostProtocol", "Crash prevented: " + e.getMessage());
        }
    }

    // =========================================================================
    // CALL HANDLER — sends busy text, call still rings
    // =========================================================================
    private void handleCall(Notification notification, String callerName) {
        Notification.Action replyAction = getReplyAction(notification);
        if (replyAction != null) {
            String callMsg = cachedCallMsg != null
                    ? cachedCallMsg : DEFAULT_POOL[IDX_CALL_DECLINE];
            sendReply(replyAction, callMsg);
            logReplyHistory(callerName, callMsg, "call");
            Log.i("GhostProtocol", "Busy message sent to caller.");
        } else {
            Log.w("GhostProtocol", "Call received, but no reply action was found.");
        }
    }

    // =========================================================================
    // MESSAGE FSM — FIXED PRIORITY ORDER
    //
    // Flow per incoming message:
    //   1. Mute notification immediately (before any processing delay)
    //   2. Mirror Shield     — drop echoes (only active within 30s of last reply,
    //                          preventing false positives on generic pool messages)
    //   3. Photocopier Shield — drop Android duplicate re-deliveries
    //   4. Echo Shield       — drop bursts within 3 seconds
    //
    //   5a. UNKNOWN SENDER (never greeted) → STRANGER_MSG, flag as greeted
    //   5b. UNKNOWN SENDER (already greeted) → same track as known sender
    //                         (random pool + keywords + spam + mute)
    //
    //   5c. KNOWN SENDER → keywords evaluated INSIDE the rate limit:
    //       • 1st in window  → keyword reply (if match) or random pool
    //       • 2nd (12hr cd)  → spam warning (keywords DO NOT bypass this)
    //       • remaining      → mute silently
    //
    // Notification is ALWAYS muted regardless of which path fires.
    // =========================================================================
    private void handleIncomingMessage(String senderName, String text, boolean isMedia,
                                       boolean isUnknown,
                                       StatusBarNotification sbn,
                                       Notification.Action replyAction) {
        long now = System.currentTimeMillis();

        // Mute notification immediately — prevent it from flashing on screen.
        // Old code muted at the END of processing; on slow devices the
        // notification would briefly appear before being cancelled.
        muteNotification(sbn);

        try {
            SharedPreferences prefs = getSharedPreferences("GhostPrefs", Context.MODE_PRIVATE);
            long windowStart    = prefs.getLong("window_" + senderName, 0L);
            int  msgCount       = prefs.getInt("count_" + senderName, 0);
            long lastSeen       = prefs.getLong("last_seen_" + senderName, 0L);
            String lastText     = prefs.getString("last_text_" + senderName, "");
            long lastTextTime   = prefs.getLong("last_text_time_" + senderName, 0L);

            // --- MIRROR SHIELD ---
            // Only active within 30s of the last reply we sent, to prevent
            // false positives when someone legitimately sends our pool text.
            // Long sent strings → substring match (catches echoed phrasing).
            // Short sent strings → exact trimmed match (avoids eating innocent
            // messages that happen to contain short words like "ok").
            if (now - lastReplyAtMs < MIRROR_WINDOW_MS) {
                String trimmedText = text.trim();
                Set<String> sentSet = cachedSentStrings;
                if (sentSet != null) {
                    for (String sent : sentSet) {
                        if (sent == null || sent.isEmpty()) continue;
                        if (sent.length() >= MIRROR_MIN_LEN) {
                            if (text.contains(sent)) return;
                        } else {
                            if (trimmedText.equals(sent.trim())) return;
                        }
                    }
                }
            }

            // --- PHOTOCOPIER SHIELD (text only, 5-minute expiry) ---
            if (!isMedia) {
                if (text.equals(lastText) && (now - lastTextTime < PHOTOCOPIER_WINDOW_MS)) {
                    return;
                }
                prefs.edit()
                        .putString("last_text_" + senderName, text)
                        .putLong("last_text_time_" + senderName, now)
                        .apply();
            }

            // --- ECHO SHIELD ---
            if (now - lastSeen < ECHO_SHIELD_MS) {
                return;
            }
            prefs.edit().putLong("last_seen_" + senderName, now).apply();

            // =================================================================
            // PRIORITY SPLIT: unknown vs known
            // =================================================================
            String payload = null;
            String trigger = null;

            if (isUnknown) {
                // Check if this stranger has already received the stranger greeting.
                // Without this flag, isUnknown is always true for unsaved numbers
                // and the bot would send STRANGER_MSG every 10-min window forever.
                boolean strangerGreeted = prefs.getBoolean(
                        "stranger_greeted_" + senderName, false);

                if (!strangerGreeted) {
                    // FIRST ENCOUNTER EVER with this unknown sender
                    if (now - windowStart > WINDOW_MS) {
                        payload = STRANGER_MSG;
                        trigger = "stranger";
                        prefs.edit()
                                .putLong("window_" + senderName, now)
                                .putInt("count_" + senderName, 1)
                                .putBoolean("stranger_greeted_" + senderName, true)
                                .apply();
                    } else {
                        // Still in same window after a stranger greeting
                        msgCount++;
                        prefs.edit().putInt("count_" + senderName, msgCount).apply();
                        if (msgCount >= 2) {
                            long lastSpamTime = prefs.getLong(
                                    "spam_time_" + senderName, 0L);
                            if (now - lastSpamTime > SPAM_COOLDOWN_MS) {
                                prefs.edit().putLong(
                                        "spam_time_" + senderName, now).apply();
                                payload = buildSpamPayload();
                                trigger = "spam";
                            }
                        }
                    }
                } else {
                    // STRANGER ALREADY GREETED — transition to known-sender
                    // FSM track (random pool, keywords, spam, mute).
                    // This is the fix for the "strangers stuck as strangers" bug.
                    payload = resolveKnownSenderFSM(prefs, senderName, text,
                            now, windowStart, msgCount);
                    trigger = (payload != null) ? lastFsmTrigger : null;
                }

            } else {
                // KNOWN SENDER — keywords are checked INSIDE the rate limit
                // by resolveKnownSenderFSM, so they can't bypass the 10-min
                // window or spam prevention. This is the fix for the
                // "infinite keyword reply" exploit.
                payload = resolveKnownSenderFSM(prefs, senderName, text,
                        now, windowStart, msgCount);
                trigger = (payload != null) ? lastFsmTrigger : null;
            }

            // --- FIRE ---
            if (payload != null) {
                sendReply(replyAction, payload);
                logReplyHistory(senderName, payload, trigger);
                Log.i("GhostProtocol", "FSM reply sent [" + trigger + "].");
            } else {
                Log.d("GhostProtocol", "FSM: muting without reply.");
            }

        } catch (Exception e) {
            Log.e("GhostProtocol", "FSM error: " + e.getMessage());
        }
    }

    /**
     * Known-sender FSM logic — shared by both known contacts and
     * previously-greeted strangers.
     *
     * Keywords are checked INSIDE the rate limit gate:
     *   • 1st message in window → keyword reply (if match) or random pool
     *   • 2nd message (12hr cooldown) → spam warning
     *   • 3rd+ → mute silently
     *
     * Sets {@link #lastFsmTrigger} to the trigger type ("keyword", "random", "spam").
     */
    private String resolveKnownSenderFSM(SharedPreferences prefs, String senderName,
                                          String text, long now,
                                          long windowStart, int msgCount) {
        lastFsmTrigger = null;

        if (now - windowStart > WINDOW_MS) {
            // ---- New 10-minute window — 1st message ----
            String keywordReply = getKeywordReply(text);
            String payload;
            if (keywordReply != null) {
                payload = keywordReply;
                lastFsmTrigger = "keyword";
            } else {
                String[] pool = cachedPool;
                if (pool == null || pool.length == 0) {
                    pool = Arrays.copyOf(DEFAULT_POOL, IDX_SPAM_WARNING);
                }
                payload = pool[RNG.nextInt(pool.length)];
                lastFsmTrigger = "random";
            }
            prefs.edit()
                    .putLong("window_" + senderName, now)
                    .putInt("count_" + senderName, 1)
                    .apply();
            return payload;

        } else {
            // ---- Same window — rate limited ----
            msgCount++;
            prefs.edit().putInt("count_" + senderName, msgCount).apply();
            if (msgCount >= 2) {
                long lastSpamTime = prefs.getLong("spam_time_" + senderName, 0L);
                if (now - lastSpamTime > SPAM_COOLDOWN_MS) {
                    prefs.edit().putLong("spam_time_" + senderName, now).apply();
                    lastFsmTrigger = "spam";
                    return buildSpamPayload();
                }
            }
            return null; // mute silently
        }
    }

    // Builds the spam warning payload. Chat count is seeded from the day
    // so it stays consistent within a 24-hour period (avoids the erratic
    // "32 chats" / "41 chats" inconsistency that reveals it's a bot).
    private String buildSpamPayload() {
        String spamMsg = cachedSpamMsg != null
                ? cachedSpamMsg : DEFAULT_POOL[IDX_SPAM_WARNING];
        String sep = (spamMsg.endsWith(" ") || spamMsg.endsWith("\n")) ? "" : " ";
        long daysSinceEpoch = System.currentTimeMillis() / (24 * 60 * 60 * 1000L);
        int chatCount = new Random(daysSinceEpoch).nextInt(11) + 32; // consistent 32–42 per day
        return spamMsg + sep + "I am currently dealing with 📊 99+ unread messages across "
                + chatCount + " chats on this app.";
    }

    // =========================================================================
    // UTILITY
    // =========================================================================
    private void muteNotification(StatusBarNotification sbn) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cancelNotification(sbn.getKey());
        }
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            requestRebind(new ComponentName(this, GhostService.class));
        }
    }
}
