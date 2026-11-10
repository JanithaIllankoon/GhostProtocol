package com.example.ghostprotocol;

import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

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
    }

    static final Situation CALL = new Situation("sys_call", "sys_call_msg",
            GhostService.DEFAULT_POOL[GhostService.IDX_CALL_DECLINE],
            "Missed Call",
            "Sent when someone calls you on WhatsApp. The call still rings as normal.");

    static final Situation STRANGER = new Situation("sys_stranger", "sys_stranger_msg",
            GhostService.STRANGER_MSG,
            "Unknown Number",
            "Sent the first time someone who isn't in your contacts messages you.");

    static final Situation SPAM = new Situation("sys_spam", "sys_spam_msg",
            GhostService.DEFAULT_POOL[GhostService.IDX_SPAM_WARNING],
            "Repeated Messages",
            "Sent when someone keeps messaging before you've had a chance to reply.");

    static final Situation[] ALL = {CALL, STRANGER, SPAM};

    static Situation byPrefix(String prefix) {
        for (Situation s : ALL) {
            if (s.prefix.equals(prefix)) return s;
        }
        return null;
    }

    /** Never empty: falls back to the legacy single message, then the default. */
    static List<String> load(SharedPreferences prefs, Situation s) {
        List<String> out = new ArrayList<>();
        int count = prefs.getInt(s.countKey(), -1);
        if (count < 0) {
            out.add(prefs.getString(s.legacyKey, s.defaultMessage));
        } else {
            for (int i = 0; i < count; i++) {
                String msg = prefs.getString(s.itemKey(i), "").trim();
                if (!msg.isEmpty()) out.add(msg);
            }
        }
        if (out.isEmpty()) out.add(s.defaultMessage);
        return out;
    }

    static void save(SharedPreferences prefs, Situation s, List<String> messages) {
        int oldCount = Math.max(prefs.getInt(s.countKey(), 0), 0);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putInt(s.countKey(), messages.size());
        for (int i = 0; i < messages.size(); i++) {
            editor.putString(s.itemKey(i), messages.get(i));
        }
        for (int i = messages.size(); i < oldCount; i++) {
            editor.remove(s.itemKey(i));
        }
        editor.remove(s.legacyKey); // now stored as a list
        editor.apply();
    }
}
