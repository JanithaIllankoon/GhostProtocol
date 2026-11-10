package com.example.ghostprotocol;

import android.content.SharedPreferences;

/**
 * Storage for the away-message pool: {@code pool_count}, {@code pool_msg_<i>}
 * and {@code pool_off_<i>} (present only for messages switched off).
 * With no saved pool, the built-in defaults are used.
 */
final class PoolMessages {
    private PoolMessages() {}

    static MessageList load(SharedPreferences prefs) {
        MessageList list = new MessageList();
        int count = prefs.getInt("pool_count", 0);
        if (count == 0) {
            for (int i = 0; i < GhostService.IDX_SPAM_WARNING; i++) {
                list.add(GhostService.DEFAULT_POOL[i], true);
            }
        } else {
            for (int i = 0; i < count; i++) {
                String text = prefs.getString("pool_msg_" + i,
                        GhostService.DEFAULT_POOL[Math.min(i, GhostService.IDX_SPAM_WARNING - 1)]);
                list.add(text, !prefs.getBoolean("pool_off_" + i, false));
            }
        }
        return list;
    }

    static void save(SharedPreferences prefs, MessageList list) {
        int oldCount = prefs.getInt("pool_count", 0);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putInt("pool_count", list.size());
        for (int i = 0; i < list.size(); i++) {
            editor.putString("pool_msg_" + i, list.texts.get(i));
            if (list.on.get(i)) editor.remove("pool_off_" + i);
            else editor.putBoolean("pool_off_" + i, true);
        }
        // Remove stale keys from a previously larger pool
        for (int i = list.size(); i < oldCount; i++) {
            editor.remove("pool_msg_" + i);
            editor.remove("pool_off_" + i);
        }
        editor.apply();
    }
}
