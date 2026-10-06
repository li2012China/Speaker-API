package net.speakerapi.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Normalizes game text before it reaches the TTS engine:
 * <ul>
 *   <li>strips Minecraft {@code §x} color codes</li>
 *   <li>strips HTML / JSON-ish tags and control characters</li>
 *   <li>normalizes full-width quotes</li>
 *   <li>truncates over-long input</li>
 *   <li>splits into speakable sentences</li>
 *   <li>optional number-to-Chinese expansion (config switch)</li>
 * </ul>
 */
public final class TextPreprocessor {

    // § followed by a hex digit / style letter (0-9 a-f k-o r, case-insensitive).
    private static final Pattern COLOR_CODE = Pattern.compile("§[0-9a-fk-orA-FK-OR]");
    private static final Pattern HTML = Pattern.compile("<[^>]+>");
    private static final Pattern CONTROL = Pattern.compile("[\u0000-\u001f\u007f]");

    private final boolean convertNumbers;
    private final int maxLength;

    public TextPreprocessor(boolean convertNumbers, int maxLength) {
        this.convertNumbers = convertNumbers;
        this.maxLength = maxLength;
    }

    /** Remove markup / control chars and clamp length. */
    public String clean(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw;
        s = COLOR_CODE.matcher(s).replaceAll("");
        s = HTML.matcher(s).replaceAll("");
        s = CONTROL.matcher(s).replaceAll("");
        s = s.replace('‘', '\'').replace('’', '\'')
                .replace('“', '"').replace('”', '"')
                .replace('　', ' ');
        if (s.length() > maxLength) {
            s = s.substring(0, maxLength);
        }
        return s.trim();
    }

    /** Split into speakable sentences on Chinese/English terminators and newlines. */
    public List<String> splitSentences(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            cur.append(c);
            if (isSentenceEnd(c) || c == '\n') {
                String seg = cur.toString().trim();
                if (!seg.isEmpty()) {
                    out.add(seg);
                }
                cur.setLength(0);
            }
        }
        String tail = cur.toString().trim();
        if (!tail.isEmpty()) {
            out.add(tail);
        }
        return out;
    }

    private static boolean isSentenceEnd(char c) {
        return c == '。' || c == '！' || c == '？'
                || c == '!' || c == '?' || c == '.';
    }

    /**
     * Optional number-to-Chinese, e.g. "血量50%" -&gt; "血量百分之五十".
     * Intentionally a thin hook: wire in a CN-numeral helper if enabled in config.
     */
    public String expandNumbers(String text) {
        if (!convertNumbers) {
            return text;
        }
        // TODO: replace digit runs with spoken Chinese per config rules.
        return text;
    }

    /** Full pipeline used by the API layer. */
    public List<String> prepare(String raw) {
        String cleaned = expandNumbers(clean(raw));
        return splitSentences(cleaned);
    }
}
