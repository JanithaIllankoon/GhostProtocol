package com.example.ghostprotocol;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;

/**
 * Saves the user's replies and settings to a JSON file and restores them.
 *
 * Everything in {@link GhostService#MSG_PREFS} is included except the reply
 * history, which is only a log. Each value keeps its type so it is restored
 * exactly as it was saved.
 */
final class SettingsBackup {
    private SettingsBackup() {}

    static final String FORMAT = "ghostprotocol-backup";
    static final int VERSION = 1;

    /** Where the time of the last export is kept (not part of the backup). */
    static final String KEY_LAST_EXPORT = "last_backup_at";

    /** A parsed backup file, ready to be applied. */
    static final class Backup {
        final Map<String, Object> values;
        final long exportedAt;

        Backup(Map<String, Object> values, long exportedAt) {
            this.values = values;
            this.exportedAt = exportedAt;
        }

        int messageCount() {
            return intOr("pool_count", 0, GhostService.IDX_SPAM_WARNING);
        }

        int keywordCount() {
            return intOr("kw_count", 0, 0);
        }

        int contactCount() {
            int n = intOr("whitelist_count", -1, GhostService.DEFAULT_WHITELIST.length);
            return Math.max(n, 0);
        }

        /** Saved count, or {@code fallback} when the key is missing or equal to {@code unset}. */
        private int intOr(String key, int unset, int fallback) {
            Object v = values.get(key);
            if (!(v instanceof Integer) || (Integer) v == unset) return fallback;
            return (Integer) v;
        }
    }

    // -------------------------------------------------------------------------
    // Export
    // -------------------------------------------------------------------------

    static String export(Context ctx) throws JSONException {
        SharedPreferences prefs = ctx.getSharedPreferences(GhostService.MSG_PREFS, Context.MODE_PRIVATE);
        JSONObject settings = new JSONObject();
        for (Map.Entry<String, ?> e : new TreeMap<>(prefs.getAll()).entrySet()) {
            if (GhostService.HISTORY_KEY.equals(e.getKey())) continue;
            JSONObject item = encode(e.getValue());
            if (item != null) settings.put(e.getKey(), item);
        }
        JSONObject root = new JSONObject();
        root.put("format", FORMAT);
        root.put("version", VERSION);
        root.put("exportedAt", System.currentTimeMillis());
        root.put("settings", settings);
        return root.toString(2);
    }

    private static JSONObject encode(Object value) throws JSONException {
        JSONObject item = new JSONObject();
        if (value instanceof String)       item.put("type", "string");
        else if (value instanceof Integer) item.put("type", "int");
        else if (value instanceof Long)    item.put("type", "long");
        else if (value instanceof Boolean) item.put("type", "bool");
        else if (value instanceof Float)   item.put("type", "float");
        else return null; // string sets are not used by the app
        item.put("value", value);
        return item;
    }

    static void markExported(Context ctx) {
        ctx.getSharedPreferences("GhostPrefs", Context.MODE_PRIVATE)
                .edit().putLong(KEY_LAST_EXPORT, System.currentTimeMillis()).apply();
    }

    static long lastExport(Context ctx) {
        return ctx.getSharedPreferences("GhostPrefs", Context.MODE_PRIVATE)
                .getLong(KEY_LAST_EXPORT, 0L);
    }

    // -------------------------------------------------------------------------
    // Import
    // -------------------------------------------------------------------------

    /** Parses and validates a backup file. Throws with a user-facing message if it isn't one. */
    static Backup parse(String json) throws IllegalArgumentException {
        JSONObject root;
        try {
            root = new JSONObject(json);
        } catch (JSONException e) {
            throw new IllegalArgumentException("This file isn't a Ghost Protocol backup.");
        }
        if (!FORMAT.equals(root.optString("format"))) {
            throw new IllegalArgumentException("This file isn't a Ghost Protocol backup.");
        }
        if (root.optInt("version", 0) > VERSION) {
            throw new IllegalArgumentException(
                    "This backup was made by a newer version of Ghost Protocol. Update the app first.");
        }
        JSONObject settings = root.optJSONObject("settings");
        if (settings == null) {
            throw new IllegalArgumentException("This backup is empty or damaged.");
        }

        Map<String, Object> values = new TreeMap<>();
        Iterator<String> keys = settings.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (GhostService.HISTORY_KEY.equals(key)) continue;
            JSONObject item = settings.optJSONObject(key);
            Object value = item == null ? null : decode(item);
            if (value != null) values.put(key, value);
        }
        return new Backup(values, root.optLong("exportedAt", 0L));
    }

    private static Object decode(JSONObject item) {
        if (!item.has("value")) return null;
        try {
            switch (item.optString("type")) {
                case "string": return item.getString("value");
                case "int":    return item.getInt("value");
                case "long":   return item.getLong("value");
                case "bool":   return item.getBoolean("value");
                case "float":  return (float) item.getDouble("value");
                default:       return null;
            }
        } catch (JSONException e) {
            return null;
        }
    }

    /**
     * Replaces the current settings with the backup. Reply history is kept.
     * Returns false if the write failed.
     */
    static boolean apply(Context ctx, Backup backup) {
        SharedPreferences prefs = ctx.getSharedPreferences(GhostService.MSG_PREFS, Context.MODE_PRIVATE);
        String history = prefs.getString(GhostService.HISTORY_KEY, null);

        // clear() runs before the puts, so old numbered keys from a longer
        // list (pool_msg_7, kw_key_3 …) don't linger after the restore.
        SharedPreferences.Editor editor = prefs.edit().clear();
        for (Map.Entry<String, Object> e : backup.values.entrySet()) {
            Object v = e.getValue();
            if (v instanceof String)       editor.putString(e.getKey(), (String) v);
            else if (v instanceof Integer) editor.putInt(e.getKey(), (Integer) v);
            else if (v instanceof Long)    editor.putLong(e.getKey(), (Long) v);
            else if (v instanceof Boolean) editor.putBoolean(e.getKey(), (Boolean) v);
            else if (v instanceof Float)   editor.putFloat(e.getKey(), (Float) v);
        }
        if (history != null) editor.putString(GhostService.HISTORY_KEY, history);
        return editor.commit();
    }
}
