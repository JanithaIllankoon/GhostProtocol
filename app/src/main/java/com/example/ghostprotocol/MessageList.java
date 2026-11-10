package com.example.ghostprotocol;

import java.util.ArrayList;
import java.util.List;

/**
 * An editable list of reply messages, each switched on or off. Messages that
 * are off stay in the list but are never picked as a reply.
 */
final class MessageList {
    final List<String> texts = new ArrayList<>();
    final List<Boolean> on = new ArrayList<>();

    void add(String text, boolean enabled) {
        texts.add(text);
        on.add(enabled);
    }

    void remove(int i) {
        texts.remove(i);
        on.remove(i);
    }

    int size() {
        return texts.size();
    }

    int enabledCount() {
        int n = 0;
        for (Boolean b : on) if (b) n++;
        return n;
    }

    /** The messages that can be sent. Falls back to all of them if none are on. */
    String[] enabledTexts() {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            if (on.get(i)) out.add(texts.get(i));
        }
        if (out.isEmpty()) out.addAll(texts);
        return out.toArray(new String[0]);
    }

    /** "5", or "4 of 5" when some are off. */
    String countLabel() {
        int enabled = enabledCount();
        return enabled == size() ? String.valueOf(size()) : enabled + " of " + size();
    }
}
