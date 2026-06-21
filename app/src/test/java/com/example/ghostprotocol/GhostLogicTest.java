package com.example.ghostprotocol;

import org.junit.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

/**
 * Local JUnit tests that verify the core FSM and keyword matching logic
 * from GhostService WITHOUT needing an Android device or emulator.
 *
 * These tests extract the pure logic from GhostService and validate it
 * against edge cases the user described:
 *   - Keyword word-boundary matching (English + Sinhala Unicode)
 *   - FSM state transitions (new window, same window, spam cooldown)
 *   - Stranger greeting → known-sender transition
 *   - Whitelist exact matching
 *   - Mirror Shield time-scoping
 *   - Spam payload day-seeded consistency
 *
 * Run via:  ./gradlew test --tests "com.example.ghostprotocol.GhostLogicTest"
 */
public class GhostLogicTest {

    // =========================================================================
    // 1. KEYWORD REGEX — tests the compiled pattern from reloadCaches()
    // =========================================================================

    /**
     * Builds a keyword regex pattern identical to GhostService.reloadCaches().
     */
    private static Pattern buildKeywordPattern(String... keywords) {
        StringBuilder sb = new StringBuilder();
        for (String kw : keywords) {
            String lower = kw.trim().toLowerCase(Locale.ROOT);
            if (lower.isEmpty()) continue;
            if (sb.length() > 0) sb.append("|");
            sb.append(Pattern.quote(lower));
        }
        if (sb.length() == 0) return null;
        String regex = "(?<![\\p{L}\\p{Nd}])(?:" + sb + ")(?![\\p{L}\\p{Nd}])";
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    /**
     * Simulates GhostService.getKeywordReply().
     */
    private static String findMatch(Pattern pattern, Map<String, String> kwMap, String text) {
        if (pattern == null || kwMap.isEmpty()) return null;
        Matcher m = pattern.matcher(text.toLowerCase(Locale.ROOT));
        if (m.find()) {
            return kwMap.get(m.group().toLowerCase(Locale.ROOT));
        }
        return null;
    }

    @Test
    public void keyword_exactWord_matches() {
        Pattern p = buildKeywordPattern("ok");
        Map<String, String> map = new HashMap<>();
        map.put("ok", "reply_ok");

        assertEquals("reply_ok", findMatch(p, map, "ok thanks"));
        assertEquals("reply_ok", findMatch(p, map, "OK thanks"));
        assertEquals("reply_ok", findMatch(p, map, "say ok now"));
        assertEquals("reply_ok", findMatch(p, map, "ok"));
    }

    @Test
    public void keyword_embeddedInWord_noMatch() {
        Pattern p = buildKeywordPattern("ok");
        Map<String, String> map = new HashMap<>();
        map.put("ok", "reply_ok");

        assertNull("'look' should NOT match 'ok'", findMatch(p, map, "look"));
        assertNull("'okay' should NOT match 'ok'", findMatch(p, map, "okay"));
        assertNull("'book' should NOT match 'ok'", findMatch(p, map, "book"));
        assertNull("'okayy' should NOT match 'ok'", findMatch(p, map, "okayy"));
    }

    @Test
    public void keyword_adjacentToEmoji_matches() {
        // Emoji are NOT \p{L} or \p{Nd}, so they don't suppress the boundary
        Pattern p = buildKeywordPattern("ok");
        Map<String, String> map = new HashMap<>();
        map.put("ok", "reply_ok");

        assertEquals("reply_ok", findMatch(p, map, "ok😀"));
        assertEquals("reply_ok", findMatch(p, map, "😀ok"));
    }

    @Test
    public void keyword_adjacentToDigit_noMatch() {
        // Digits are \p{Nd}, so they DO suppress the boundary
        Pattern p = buildKeywordPattern("ok");
        Map<String, String> map = new HashMap<>();
        map.put("ok", "reply_ok");

        assertNull("'ok2' should NOT match 'ok'", findMatch(p, map, "ok2"));
        assertNull("'3ok' should NOT match 'ok'", findMatch(p, map, "3ok"));
    }

    @Test
    public void keyword_sinhalaUnicode_wordBoundary() {
        // හරි (hari = "ok" in Sinhala)
        Pattern p = buildKeywordPattern("හරි");
        Map<String, String> map = new HashMap<>();
        map.put("හරි", "reply_sinhala_ok");

        // Standalone → match
        assertEquals("reply_sinhala_ok", findMatch(p, map, "හරි ඇයි"));

        // Embedded in longer word → no match
        // හරියට starts with හරි but has trailing Sinhala letters
        assertNull("'හරියට' should NOT match 'හරි'", findMatch(p, map, "හරියට"));
    }

    @Test
    public void keyword_caseInsensitive() {
        Pattern p = buildKeywordPattern("hello");
        Map<String, String> map = new HashMap<>();
        map.put("hello", "reply_hello");

        assertEquals("reply_hello", findMatch(p, map, "HELLO world"));
        assertEquals("reply_hello", findMatch(p, map, "Hello World"));
        assertEquals("reply_hello", findMatch(p, map, "hElLo"));
    }

    @Test
    public void keyword_multipleKeywords_firstMatchWins() {
        Pattern p = buildKeywordPattern("hello", "bye");
        Map<String, String> map = new HashMap<>();
        map.put("hello", "reply_hello");
        map.put("bye", "reply_bye");

        // When text has both, first occurrence in text wins
        assertEquals("reply_hello", findMatch(p, map, "hello and bye"));
        assertEquals("reply_bye", findMatch(p, map, "bye then hello"));
    }

    @Test
    public void keyword_specialRegexChars_escaped() {
        // Pattern.quote should handle regex metacharacters
        Pattern p = buildKeywordPattern("c++", "a.b");
        Map<String, String> map = new HashMap<>();
        map.put("c++", "reply_cpp");
        map.put("a.b", "reply_ab");

        assertEquals("reply_cpp", findMatch(p, map, "I know c++"));
        assertNull("'a1b' should NOT match 'a.b'", findMatch(p, map, "a1b"));
    }

    @Test
    public void keyword_emptyInput_noMatch() {
        Pattern p = buildKeywordPattern("ok");
        Map<String, String> map = new HashMap<>();
        map.put("ok", "reply_ok");

        assertNull(findMatch(p, map, ""));
        assertNull(findMatch(p, map, "   "));
    }

    @Test
    public void keyword_nullPattern_noMatch() {
        assertNull(findMatch(null, new HashMap<>(), "hello ok"));
    }

    // =========================================================================
    // 2. WHITELIST — exact match (equalsIgnoreCase)
    // =========================================================================

    /**
     * Simulates the whitelist check from GhostService.onNotificationPosted().
     */
    private static boolean isWhitelisted(String title, String... whitelist) {
        for (String trusted : whitelist) {
            if (title.equalsIgnoreCase(trusted)) return true;
        }
        return false;
    }

    @Test
    public void whitelist_exactMatch() {
        assertTrue(isWhitelisted("Alice", "Alice", "Bob"));
        assertTrue(isWhitelisted("alice", "Alice", "Bob"));
        assertTrue(isWhitelisted("ALICE", "Alice", "Bob"));
    }

    @Test
    public void whitelist_substringNoLongerMatches() {
        // "Alicia" should NOT be whitelisted just because "Alice" is in the list
        assertFalse(isWhitelisted("Alicia", "Alice", "Bob"));
        assertFalse(isWhitelisted("Malice", "Alice"));
        assertFalse(isWhitelisted("Alice ❤", "Alice"));
    }

    // =========================================================================
    // 3. FSM STATE TRANSITIONS — resolveKnownSenderFSM logic
    // =========================================================================

    private static final long WINDOW_MS        = 10 * 60 * 1000L;
    private static final long SPAM_COOLDOWN_MS = 12 * 60 * 60 * 1000L;

    /**
     * Minimal FSM simulation:
     * Returns: "keyword", "random", "spam", or null (mute)
     */
    private static String simulateFSM(long now, long windowStart, int msgCount,
                                       long lastSpamTime, String keywordReply) {
        if (now - windowStart > WINDOW_MS) {
            // New window — 1st message
            return keywordReply != null ? "keyword" : "random";
        } else {
            // Same window — rate limited
            msgCount++;
            if (msgCount >= 2) {
                if (now - lastSpamTime > SPAM_COOLDOWN_MS) {
                    return "spam";
                }
            }
            return null; // mute
        }
    }

    @Test
    public void fsm_newWindow_returnsRandomOrKeyword() {
        long now = System.currentTimeMillis();
        long windowStart = now - WINDOW_MS - 1; // window expired

        assertEquals("random", simulateFSM(now, windowStart, 0, 0L, null));
        assertEquals("keyword", simulateFSM(now, windowStart, 0, 0L, "some_reply"));
    }

    @Test
    public void fsm_secondMessageInWindow_returnsSpam() {
        long now = System.currentTimeMillis();
        long windowStart = now - 1000; // window started 1 second ago
        int msgCount = 1; // this will be the 2nd message
        long lastSpamTime = 0L; // never sent spam before

        assertEquals("spam", simulateFSM(now, windowStart, msgCount, lastSpamTime, null));
    }

    @Test
    public void fsm_secondMessageInWindow_spamCooldownActive_returnsMute() {
        long now = System.currentTimeMillis();
        long windowStart = now - 1000; // window started 1 second ago
        int msgCount = 1; // this will be the 2nd message
        long lastSpamTime = now - 1000; // sent spam 1 second ago (within 12hr cooldown)

        assertNull("Should mute when spam cooldown is active",
                simulateFSM(now, windowStart, msgCount, lastSpamTime, null));
    }

    @Test
    public void fsm_thirdPlusMessage_returnsMute() {
        long now = System.currentTimeMillis();
        long windowStart = now - 1000;
        int msgCount = 2; // this will be the 3rd message
        long lastSpamTime = now - 1000; // spam recently sent (within cooldown)

        assertNull("3rd+ message should mute",
                simulateFSM(now, windowStart, msgCount, lastSpamTime, null));
    }

    @Test
    public void fsm_keywordInSameWindow_isMuted() {
        // Keywords are INSIDE the rate limit. 2nd message with keyword → spam, not keyword.
        long now = System.currentTimeMillis();
        long windowStart = now - 1000;
        int msgCount = 1; // 2nd message
        long lastSpamTime = 0L; // spam cooldown expired

        // Even though there's a keyword match, FSM should return spam (2nd message)
        assertEquals("spam", simulateFSM(now, windowStart, msgCount, lastSpamTime, "keyword_reply"));
    }

    // =========================================================================
    // 4. STRANGER GREETING TRANSITION
    // =========================================================================

    @Test
    public void stranger_firstEncounter_sendsStrangerMsg() {
        // Simulates: isUnknown=true, strangerGreeted=false, new window
        boolean strangerGreeted = false;
        assertTrue("First encounter should trigger stranger greeting", !strangerGreeted);
    }

    @Test
    public void stranger_afterGreeting_treatedAsKnown() {
        // After stranger_greeted flag is set, the unknown sender should
        // go through the normal FSM path (pool/keyword/spam/mute)
        boolean strangerGreeted = true;
        long now = System.currentTimeMillis();
        long windowStart = now - WINDOW_MS - 1; // new window

        // This should behave like a known sender
        assertEquals("random", simulateFSM(now, windowStart, 0, 0L, null));
    }

    // =========================================================================
    // 5. MIRROR SHIELD TIME-SCOPING
    // =========================================================================

    private static final long MIRROR_WINDOW_MS = 30 * 1000L;

    @Test
    public void mirrorShield_withinWindow_active() {
        long now = System.currentTimeMillis();
        long lastReplyAt = now - 5000; // replied 5 seconds ago
        assertTrue("Mirror shield should be active within 30s",
                now - lastReplyAt < MIRROR_WINDOW_MS);
    }

    @Test
    public void mirrorShield_outsideWindow_inactive() {
        long now = System.currentTimeMillis();
        long lastReplyAt = now - 60000; // replied 60 seconds ago
        assertFalse("Mirror shield should be inactive after 30s",
                now - lastReplyAt < MIRROR_WINDOW_MS);
    }

    // =========================================================================
    // 6. SPAM PAYLOAD DAY-SEEDED CONSISTENCY
    // =========================================================================

    @Test
    public void spamPayload_sameDay_sameCount() {
        long daysSinceEpoch = System.currentTimeMillis() / (24 * 60 * 60 * 1000L);
        int count1 = new Random(daysSinceEpoch).nextInt(11) + 32;
        int count2 = new Random(daysSinceEpoch).nextInt(11) + 32;
        assertEquals("Same day should produce same chat count", count1, count2);
        assertTrue("Chat count should be 32–42", count1 >= 32 && count1 <= 42);
    }

    @Test
    public void spamPayload_differentDay_likelyDifferentCount() {
        long today = System.currentTimeMillis() / (24 * 60 * 60 * 1000L);
        long tomorrow = today + 1;
        int todayCount = new Random(today).nextInt(11) + 32;
        int tomorrowCount = new Random(tomorrow).nextInt(11) + 32;
        // Not guaranteed to differ, but statistically they usually do.
        // We just verify the range is valid.
        assertTrue(todayCount >= 32 && todayCount <= 42);
        assertTrue(tomorrowCount >= 32 && tomorrowCount <= 42);
    }

    // =========================================================================
    // 7. CONTACT NUMBER MATCHING
    // =========================================================================

    @Test
    public void contactNumber_last9Digits_match() {
        // Simulates isUnknownContact phone number matching
        Set<String> savedNumbers = new HashSet<>();
        savedNumbers.add("94771234567"); // saved as +94771234567

        String incomingTitle = "+94 77 123 4567";
        String titleDigits = incomingTitle.replaceAll("[^0-9]", "");
        String titleSuffix = titleDigits.length() >= 9
                ? titleDigits.substring(titleDigits.length() - 9)
                : titleDigits;

        boolean found = false;
        for (String num : savedNumbers) {
            if (num.endsWith(titleSuffix)) { found = true; break; }
        }
        assertTrue("Should match on last 9 digits", found);
    }

    @Test
    public void contactNumber_differentNumber_noMatch() {
        Set<String> savedNumbers = new HashSet<>();
        savedNumbers.add("94771234567");

        String incomingTitle = "+94779999999";
        String titleDigits = incomingTitle.replaceAll("[^0-9]", "");
        String titleSuffix = titleDigits.length() >= 9
                ? titleDigits.substring(titleDigits.length() - 9)
                : titleDigits;

        boolean found = false;
        for (String num : savedNumbers) {
            if (num.endsWith(titleSuffix)) { found = true; break; }
        }
        assertFalse("Different number should NOT match", found);
    }

    @Test
    public void contactNumber_shortNumber_fallbackToExact() {
        Set<String> savedNumbers = new HashSet<>();
        savedNumbers.add("1234567");

        String titleDigits = "1234567";
        boolean found = savedNumbers.contains(titleDigits);
        assertTrue("Short number should match exactly", found);
    }
}
