package io.github.ghosthack.jmux.vt;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Named keys → the bytes an xterm-compatible terminal sends for them, so a caller can press
 * keys without attaching (the {@code input} operation). Pure: no state beyond the
 * application-cursor-keys mode the caller passes in (DECCKM, {@code CSI ?1 h}), which
 * switches the arrows and Home/End from CSI to SS3 form exactly as a real terminal does.
 *
 * <p>Grammar (names are case-insensitive):
 * <ul>
 *   <li>a single character (any one code point: {@code 1}, {@code y}, {@code /}, {@code é})
 *       → its UTF-8 bytes;</li>
 *   <li>{@code Enter} (CR), {@code Tab}, {@code BTab}/{@code S-Tab} ({@code CSI Z}),
 *       {@code Esc}/{@code Escape}, {@code Space}, {@code Backspace}/{@code BSpace} (DEL),
 *       {@code Delete}/{@code DC} ({@code CSI 3~}), {@code Insert}/{@code IC}
 *       ({@code CSI 2~}), {@code Up}/{@code Down}/{@code Right}/{@code Left},
 *       {@code Home}/{@code End}, {@code PageUp}/{@code PgUp}/{@code PPage},
 *       {@code PageDown}/{@code PgDn}/{@code NPage}, {@code F1}..{@code F12};</li>
 *   <li>{@code C-x}: control of a letter or one of {@code @[\]^_?} ({@code C-?} is DEL,
 *       {@code C-Space} and {@code C-@} are NUL);</li>
 *   <li>{@code M-key}: ESC followed by the encoding of {@code key} ({@code M-b},
 *       {@code M-Enter}, {@code M-C-x}).</li>
 * </ul>
 */
public final class KeyNames {

    private static final String ESC = "\u001b";
    private static final String CSI = ESC + "[";
    private static final String SS3 = ESC + "O";

    /** Keys whose encoding does not depend on the cursor-key mode. */
    private static final Map<String, String> FIXED = Map.ofEntries(
            Map.entry("enter", "\r"),
            Map.entry("tab", "\t"),
            Map.entry("btab", CSI + "Z"),
            Map.entry("s-tab", CSI + "Z"),
            Map.entry("esc", ESC),
            Map.entry("escape", ESC),
            Map.entry("space", " "),
            Map.entry("backspace", "\u007f"),
            Map.entry("bspace", "\u007f"),
            Map.entry("delete", CSI + "3~"),
            Map.entry("dc", CSI + "3~"),
            Map.entry("insert", CSI + "2~"),
            Map.entry("ic", CSI + "2~"),
            Map.entry("pageup", CSI + "5~"),
            Map.entry("pgup", CSI + "5~"),
            Map.entry("ppage", CSI + "5~"),
            Map.entry("pagedown", CSI + "6~"),
            Map.entry("pgdn", CSI + "6~"),
            Map.entry("npage", CSI + "6~"),
            Map.entry("f1", SS3 + "P"),
            Map.entry("f2", SS3 + "Q"),
            Map.entry("f3", SS3 + "R"),
            Map.entry("f4", SS3 + "S"),
            Map.entry("f5", CSI + "15~"),
            Map.entry("f6", CSI + "17~"),
            Map.entry("f7", CSI + "18~"),
            Map.entry("f8", CSI + "19~"),
            Map.entry("f9", CSI + "20~"),
            Map.entry("f10", CSI + "21~"),
            Map.entry("f11", CSI + "23~"),
            Map.entry("f12", CSI + "24~"));

    /** Cursor keys: the final byte, after CSI (normal) or SS3 (application mode). */
    private static final Map<String, Character> CURSOR = Map.of(
            "up", 'A', "down", 'B', "right", 'C', "left", 'D', "home", 'H', "end", 'F');

    /** Every key of {@code keys} in order, concatenated. Throws
     * {@link IllegalArgumentException} {@code "unknown key: <k>"} on the first bad name. */
    public static byte[] encode(List<String> keys, boolean applicationCursorKeys) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (String key : keys) {
            out.writeBytes(encode(key, applicationCursorKeys).getBytes(StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }

    private static String encode(String key, boolean applicationCursorKeys) {
        if (key == null || key.isEmpty()) {
            throw unknown(key);
        }
        if (key.codePointCount(0, key.length()) == 1) {
            return key; // a literal character, case preserved
        }
        String lower = key.toLowerCase(Locale.ROOT);
        String fixed = FIXED.get(lower);
        if (fixed != null) {
            return fixed;
        }
        Character cursor = CURSOR.get(lower);
        if (cursor != null) {
            return (applicationCursorKeys ? SS3 : CSI) + cursor;
        }
        if (lower.startsWith("m-") && key.length() > 2) {
            try {
                return ESC + encode(key.substring(2), applicationCursorKeys);
            } catch (IllegalArgumentException e) {
                throw unknown(key);
            }
        }
        if (lower.startsWith("c-") && key.length() > 2) {
            String target = key.substring(2);
            if (target.equalsIgnoreCase("space")) {
                return "\0";
            }
            if (target.length() == 1) {
                char c = target.charAt(0);
                if (c >= 'a' && c <= 'z') {
                    return String.valueOf((char) (c - 'a' + 1));
                }
                if (c >= 'A' && c <= 'Z') {
                    return String.valueOf((char) (c - 'A' + 1));
                }
                if (c == '?') {
                    return "\u007f";
                }
                if ("@[\\]^_".indexOf(c) >= 0) {
                    return String.valueOf((char) (c - '@'));
                }
            }
        }
        throw unknown(key);
    }

    private static IllegalArgumentException unknown(String key) {
        return new IllegalArgumentException("unknown key: " + key);
    }

    private KeyNames() {
    }
}
