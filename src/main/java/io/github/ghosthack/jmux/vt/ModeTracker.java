package io.github.ghosthack.jmux.vt;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Tracks, from the token stream, the terminal state a redraw must restore that the text
 * buffer does not hold and jediterm does not expose (or exposes only via a display
 * callback): DEC private modes set/reset with {@code CSI ? Pm h/l} (1 cursor keys, 7
 * autowrap, 25 cursor visible, 1000/1002/1003 mouse tracking, 1004 focus, 1006 SGR mouse,
 * 1049/47/1047 alternate screen, 2004 bracketed paste), DECSCUSR ({@code CSI Ps SP q}),
 * DECKPAM/DECKPNM ({@code ESC =} / {@code ESC >}), the OSC 0/2 title, OSC 7 cwd, and RIS
 * ({@code ESC c}), which resets everything. Pure: no I/O, no threads; feed it every
 * token the emulator gets, in order.
 *
 * <p>DECSTBM ({@code CSI t;b r}) is tracked too — clamped like jediterm, and on a resize
 * its bottom margin shifts by the row delta exactly as {@code JediTerminal.doResize} does
 * ({@code myScrollRegionBottom += newRows - oldRows}, JediTerminal.java:1055). Feed row
 * deltas through {@link #resized(int, int)}.
 */
public final class ModeTracker {

    private int rows;

    private boolean applicationCursorKeys; // DEC 1
    private boolean originMode;            // DEC 6
    private boolean autoWrap = true;       // DEC 7 (default on, JediTerminal.initModes)
    private boolean cursorVisible = true;  // DEC 25 (default on)
    private int mouseTrackingMode;         // 1000 / 1002 / 1003, 0 = none
    private boolean mouseSgr;              // DEC 1006
    private boolean focusReporting;        // DEC 1004
    private boolean alternateScreen;       // DEC 47 / 1047 / 1049
    private boolean bracketedPaste;        // DEC 2004
    private boolean applicationKeypad;     // DECKPAM / DECKPNM
    private int cursorShape;               // DECSCUSR 0..6, 0 = default

    private int scrollTop = 1;             // 1-based
    private int scrollBottom;              // 1-based; initialised to rows

    private String title = "";
    private String reportedCwd;            // null until reported

    ModeTracker(int rows) {
        this.rows = rows;
        this.scrollBottom = rows;
    }

    public void observe(VtToken token) {
        switch (token.kind()) {
            case CSI -> observeCsi(token.csiBody(), token.csiFinal());
            case ESCAPE -> observeEscape(token.bytes());
            case OSC -> observeOsc(token.bytes());
            default -> {
            }
        }
    }

    private void observeCsi(String body, char finalChar) {
        switch (finalChar) {
            case 'h', 'l' -> {
                if (body.startsWith("?")) {
                    boolean set = finalChar == 'h';
                    for (String p : body.substring(1).split(";", -1)) {
                        Integer mode = parseIntOrNull(p);
                        if (mode != null) {
                            applyDecPrivateMode(mode, set);
                        }
                    }
                }
            }
            case 'q' -> {
                // DECSCUSR: CSI Ps SP q. Ignore XTVERSION (CSI > q).
                if (!body.startsWith(">")) {
                    int ps = leadingInt(body);
                    cursorShape = ps == 0 ? 1 : ps; // 0 and 1 both mean a blinking block
                }
            }
            case 'r' -> {
                // DECSTBM: CSI t;b r. CSI ? ... r is "restore DEC private modes" — not us.
                if (!body.startsWith("?")) {
                    setScrollingRegion(body);
                }
            }
            default -> {
            }
        }
    }

    private void applyDecPrivateMode(int mode, boolean set) {
        switch (mode) {
            case 1 -> applicationCursorKeys = set;
            case 6 -> originMode = set;
            case 7 -> autoWrap = set;
            case 25 -> cursorVisible = set;
            case 1000 -> mouseTrackingMode = set ? 1000 : 0;
            case 1002 -> mouseTrackingMode = set ? 1002 : 0;
            case 1003 -> mouseTrackingMode = set ? 1003 : 0;
            case 1004 -> focusReporting = set;
            case 1006 -> mouseSgr = set;
            case 47, 1047, 1049 -> alternateScreen = set;
            case 2004 -> bracketedPaste = set;
            default -> {
            }
        }
    }

    private void setScrollingRegion(String body) {
        String[] parts = body.split(";", -1);
        int top = parts.length > 0 && !parts[0].isEmpty() ? leadingInt(parts[0]) : 1;
        int bottom = parts.length > 1 && !parts[1].isEmpty() ? leadingInt(parts[1]) : rows;
        if (top == 0) {
            top = 1;
        }
        if (bottom == 0) {
            bottom = rows;
        }
        // Clamp exactly like JediTerminal.setScrollingRegion (JediTerminal.java:742-743).
        scrollTop = Math.max(1, top);
        scrollBottom = Math.min(rows, bottom);
    }

    private void observeEscape(byte[] bytes) {
        if (bytes.length == 2 && bytes[0] == 0x1B) {
            switch ((char) (bytes[1] & 0xFF)) {
                case 'c' -> reset();          // RIS
                case '=' -> applicationKeypad = true;  // DECKPAM
                case '>' -> applicationKeypad = false; // DECKPNM
                default -> {
                }
            }
        }
    }

    private void observeOsc(byte[] bytes) {
        String body = oscBody(bytes);
        String[] args = body.split(";", -1);
        Integer ps = parseIntOrNull(args[0]);
        if (ps == null) {
            return;
        }
        switch (ps) {
            case 0, 1, 2 -> {
                // Icon name / window title — jediterm sets the title for all three
                // (JediEmulator.doProcessOsc case 0/1/2).
                if (args.length > 1) {
                    title = args[1];
                }
            }
            case 7 -> {
                if (args.length > 1) {
                    reportedCwd = decodeFileUri(args[1]);
                }
            }
            case 9 -> {
                // OSC 9 ; 9 ; <path> — iTerm2 current-directory report.
                if (args.length > 2 && "9".equals(args[1])) {
                    reportedCwd = args[2];
                }
            }
            default -> {
            }
        }
    }

    /** RIS: reset the tracked modes to their power-on defaults. Title and cwd are display
     * state (jediterm's {@code reset} does not clear them), so they are left untouched. */
    private void reset() {
        applicationCursorKeys = false;
        originMode = false;
        autoWrap = true;
        cursorVisible = true;
        mouseTrackingMode = 0;
        mouseSgr = false;
        focusReporting = false;
        alternateScreen = false;
        bracketedPaste = false;
        applicationKeypad = false;
        cursorShape = 0;
        scrollTop = 1;
        scrollBottom = rows;
    }

    /** Applies a text-buffer resize: shift the bottom margin by the row delta, matching
     * {@code JediTerminal.doResize} (JediTerminal.java:1055). The top margin is unchanged. */
    void resized(int oldRows, int newRows) {
        this.rows = newRows;
        scrollBottom += newRows - oldRows;
    }

    public HeadlessTerminal.Modes snapshot() {
        return new HeadlessTerminal.Modes(cursorVisible, cursorShape, bracketedPaste,
                applicationCursorKeys, applicationKeypad, mouseTrackingMode, mouseSgr,
                focusReporting, alternateScreen, autoWrap, originMode, scrollTop, scrollBottom,
                title);
    }

    /** OSC 7 {@code file://host/path} → decoded path, or null if never reported. */
    public String reportedCwd() {
        return reportedCwd;
    }

    // --- parsing helpers -------------------------------------------------------------

    private static String oscBody(byte[] bytes) {
        // bytes = ESC ] <body> <terminator>; terminator is BEL or ESC \.
        int start = 2;
        int end = bytes.length;
        if (end > start && (bytes[end - 1] & 0xFF) == 0x07) { // BEL
            end -= 1;
        } else if (end > start + 1 && (bytes[end - 2] & 0xFF) == 0x1B && (bytes[end - 1] & 0xFF) == '\\') {
            end -= 2; // ESC \
        }
        if (end < start) {
            end = start;
        }
        return new String(bytes, start, end - start, StandardCharsets.UTF_8);
    }

    private static String decodeFileUri(String value) {
        // file://host/path -> /path (host dropped), percent-decoded.
        String rest = value;
        if (rest.startsWith("file://")) {
            rest = rest.substring("file://".length());
            int slash = rest.indexOf('/');
            rest = slash >= 0 ? rest.substring(slash) : "";
        }
        return percentDecode(rest);
    }

    private static String percentDecode(String s) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length()) {
                int hi = Character.digit(s.charAt(i + 1), 16);
                int lo = Character.digit(s.charAt(i + 2), 16);
                if (hi >= 0 && lo >= 0) {
                    bytes.write((hi << 4) | lo);
                    i += 2;
                    continue;
                }
            }
            // ASCII fast path; non-ASCII chars re-encode to UTF-8 below.
            byte[] enc = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
            bytes.write(enc, 0, enc.length);
        }
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }

    private static Integer parseIntOrNull(String s) {
        if (s.isEmpty()) {
            return null;
        }
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') {
                return null;
            }
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int leadingInt(String s) {
        int i = 0;
        int value = 0;
        while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
            value = value * 10 + (s.charAt(i) - '0');
            i++;
        }
        return value;
    }
}
