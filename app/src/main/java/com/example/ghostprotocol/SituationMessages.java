package com.example.ghostprotocol;

import android.content.SharedPreferences;


/**
 * The special-situation replies (missed call, unknown number, repeated
 * messages). Each situation keeps a list of messages and one is picked at
 * random every time it fires.
 *
 * Storage: {@code <prefix>_count} plus {@code <prefix>_0 … _n}. Before lists
 * existed each situation stored a single string under its legacy key; that
 * value is read as a one-item list until the list is first saved.
 */
final class SituationMessages {
    private SituationMessages() {}

    static final class Situation {
        final String prefix;
        final String legacyKey;
        final String defaultMessage;
        final String title;
        final String description;

        Situation(String prefix, String legacyKey, String defaultMessage,
                  String title, String description) {
            this.prefix = prefix;
            this.legacyKey = legacyKey;
            this.defaultMessage = defaultMessage;
            this.title = title;
            this.description = description;
        }

        String countKey() { return prefix + "_count"; }
        String itemKey(int i) { return prefix + "_" + i; }
        String offKey(int i) { return prefix + "_off_" + i; }
    }

    static final Situation CALL = new Situation("sys_call", "sys_call_msg",
            GhostService.DEFAULT_POOL[GhostService.IDX_CALL_DECLINE],
            "Missed Call",
            "Sent when you get a WhatsApp call. The call still rings.");

    static final Situation STRANGER = new Situation("sys_stranger", "sys_stranger_msg",
            GhostService.STRANGER_MSG,
            "Unknown Number",
            "Sent the first time an unsaved number messages you.");

    static final Situation SPAM = new Situation("sys_spam", "sys_spam_msg",
            GhostService.DEFAULT_POOL[GhostService.IDX_SPAM_WARNING],
            "Repeated Messages",
            "Sent when someone keeps messaging before you reply.");

    static final Situation[] ALL = {CALL, STRANGER, SPAM};

    static Situation byPrefix(String prefix) {
        for (Situation s : ALL) {
            if (s.prefix.equals(prefix)) return s;
        }
        return null;
    }

    /**
     * All messages with their on/off state. Never empty: falls back to the
     * legacy single message, then the default.
     */
    static MessageList load(SharedPreferences prefs, Situation s) {
        MessageList list = new MessageList();
        int count = prefs.getInt(s.countKey(), -1);
        if (count < 0) {
            list.add(prefs.getString(s.legacyKey, s.defaultMessage), true);
        } else {
            for (int i = 0; i < count; i++) {
                String msg = prefs.getString(s.itemKey(i), "").trim();
                if (!msg.isEmpty()) list.add(msg, !prefs.getBoolean(s.offKey(i), false));
            }
        }
        if (list.size() == 0) list.add(s.defaultMessage, true);
        return list;
    }

    /** The messages that can be sent right now (switched on). */
    static String[] enabled(SharedPreferences prefs, Situation s) {
        return load(prefs, s).enabledTexts();
    }

    static void save(SharedPreferences prefs, Situation s, MessageList list) {
        int oldCount = Math.max(prefs.getInt(s.countKey(), 0), 0);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putInt(s.countKey(), list.size());
        for (int i = 0; i < list.size(); i++) {
            editor.putString(s.itemKey(i), list.texts.get(i));
            if (list.on.get(i)) editor.remove(s.offKey(i));
            else editor.putBoolean(s.offKey(i), true);
        }
        for (int i = list.size(); i < oldCount; i++) {
            editor.remove(s.itemKey(i));
            editor.remove(s.offKey(i));
        }
        editor.remove(s.legacyKey); // now stored as a list
        editor.apply();
    }
}
