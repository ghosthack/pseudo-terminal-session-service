package io.github.ghosthack.jmux.vt;

import com.jediterm.core.Color;
import com.jediterm.terminal.TerminalColor;
import com.jediterm.terminal.TextStyle;
import com.jediterm.terminal.model.JediTerminal;
import com.jediterm.terminal.model.TerminalLine;
import com.jediterm.terminal.model.TerminalTextBuffer;

import java.nio.charset.StandardCharsets;

/**
 * Turns the headless terminal's state back into the escape-sequence stream a freshly
 * attached client needs to show the same thing. The contract is a round trip: feeding the
 * output of {@link #serialize} into a fresh jediterm emulator of the same size yields the
 * same cells, styles, cursor position and modes as the source terminal (that is the unit
 * test, see ScreenSerializerTest).
 *
 * <p>Output order (see SESSION-HOST.md "Redraw on attach"):
 * <ol>
 *   <li>reset to a known state: {@code CSI ?1049 l}, {@code CSI 0 m}, {@code CSI ?7 h},
 *       {@code CSI r}, {@code CSI ?25 l} (hidden while drawing), {@code CSI H},
 *       {@code CSI 2 J}, {@code CSI 3 J};</li>
 *   <li>the last {@code historyLines} history lines, then the screen lines of the
 *       <em>normal</em> buffer, each as styled runs; the DWC placeholder cell after a wide
 *       character is skipped; trailing default-style blank cells are dropped and replaced
 *       by {@code CSI K}; a {@code CR LF} between lines except after a wrapped line or the
 *       last screen line; an entirely blank continuation of a wrapped line still writes
 *       its first cell so the client's deferred wrap fires;</li>
 *   <li>if on the alternate screen: the main buffer is not emitted; the stored cursor is
 *       staged, {@code CSI ?1049 h} switches (and the client saves that cursor), then
 *       {@code CSI 0 m}, {@code CSI ?7 h}, {@code CSI H} and the alternate buffer's rows
 *       are emitted the same way;</li>
 *   <li>the tail: scroll region, autowrap, DECSCUSR, bracketed paste, application cursor
 *       keys, keypad, mouse mode/format, focus reporting, title, current pen, the cursor
 *       (with pending-wrap handling) and cursor visibility.</li>
 * </ol>
 * Everything is emitted under the text buffer's lock so the snapshot is consistent.
 */
public final class ScreenSerializer {

    private static final char DWC = ''; // CharUtils.DWC — the placeholder after a wide char

    public static byte[] serialize(JediTerminal terminal, TerminalTextBuffer buffer,
                                   HeadlessTerminal.Modes modes, int historyLines) {
        int width = buffer.getWidth();
        int height = buffer.getHeight();
        boolean alternate = modes.alternateScreen();

        StringBuilder sb = new StringBuilder();
        TextStyle[] pen = {TextStyle.EMPTY}; // the client's current pen after our last SGR

        // (1) reset prefix.
        sb.append("[?1049l"); // leave the alternate screen if the client was on it
        sb.append("[0m");     // default pen
        sb.append("[?7h");    // autowrap on while drawing (wrapped lines need it)
        sb.append("[r");      // full scroll region
        sb.append("[?25l");   // hide the cursor while drawing
        sb.append("[H");      // home
        sb.append("[2J");     // clear screen (moves blank lines to history)
        sb.append("[3J");     // clear history

        if (!alternate) {
            // (2) history lines then the (normal) screen lines.
            int histCount = Math.min(historyLines, buffer.getHistoryLinesCount());
            int total = histCount + height;
            int emitted = 0;
            // One flag across both loops: a wrapped history line's continuation can be row 0.
            boolean afterWrap = false;
            for (int i = -histCount; i < 0; i++) {
                afterWrap = emitRow(sb, buffer.getLine(i), width, ++emitted == total, pen, afterWrap);
            }
            for (int y = 0; y < height; y++) {
                afterWrap = emitRow(sb, buffer.getLine(y), width, ++emitted == total, pen, afterWrap);
            }
        } else {
            // (3) alternate screen: stage the stored cursor, switch, then draw the alt rows.
            emitAlternatePrefix(sb, terminal, width, height, pen);
            boolean afterWrap = false;
            for (int y = 0; y < height; y++) {
                afterWrap = emitRow(sb, buffer.getLine(y), width, y == height - 1, pen, afterWrap);
            }
        }

        // (4) tail.
        emitTail(sb, terminal, buffer, modes, width, height, pen);

        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void emitAlternatePrefix(StringBuilder sb, JediTerminal terminal,
                                            int width, int height, TextStyle[] pen) {
        sb.append("[r"); // reset the main buffer's scroll region before switching
        HeadlessTerminal.StoredCursor stored = storedCursor(terminal);
        if (stored != null) {
            // The main-buffer cursor the client should save on ?1049 h. Clamp to the main
            // buffer's recorded size (falling back to the current size).
            int mainCols = width;
            int mainRows = height;
            if (terminal instanceof HeadlessTerminal.RecordingTerminal rt && rt.recordedMainSize() != null) {
                mainCols = rt.recordedMainSize().getColumns();
                mainRows = rt.recordedMainSize().getRows();
            }
            sb.append(stored.originMode() ? "[?6h" : "[?6l");
            sb.append(stored.autoWrap() ? "[?7h" : "[?7l");
            emitSgr(sb, stored.style(), pen);
            int col = clamp(stored.cursorX() + 1, 1, mainCols);
            int row = clamp(stored.cursorY(), 1, mainRows);
            sb.append("[").append(row).append(';').append(col).append('H');
        } else {
            // Nothing saved: match JediTerminal.restoreCursor's null branch.
            sb.append("[0m");
            pen[0] = TextStyle.EMPTY;
            sb.append("[?6l");
            sb.append("[?7h");
            sb.append("[H");
        }
        sb.append("[?1049h"); // client switches to alt and saves the cursor above
        sb.append("[0m");
        pen[0] = TextStyle.EMPTY;
        sb.append("[?7h"); // ensure autowrap on for drawing the alt rows
        // Draw the alt rows from home, not from the staged (saved) cursor: a prompt leaves
        // the main cursor at column > 0 and row 0 would start there. CSI r above made the
        // region full-screen, so this lands at 1;1 even if the stored DECOM was staged on.
        sb.append("[H");
    }

    private static void emitTail(StringBuilder sb, JediTerminal terminal, TerminalTextBuffer buffer,
                                 HeadlessTerminal.Modes modes, int width, int height, TextStyle[] pen) {
        sb.append("[?6l"); // origin off so the CUP below is absolute
        if (!(modes.scrollTop() == 1 && modes.scrollBottom() == height)) {
            // Precedes CUP because DECSTBM homes the cursor.
            sb.append("[").append(modes.scrollTop()).append(';').append(modes.scrollBottom()).append('r');
        }
        sb.append(terminal.isAutoWrap() ? "[?7h" : "[?7l");
        if (modes.cursorShape() != 0) {
            sb.append("[").append(modes.cursorShape()).append(" q");
        }
        if (modes.bracketedPaste()) {
            sb.append("[?2004h");
        }
        if (modes.applicationCursorKeys()) {
            sb.append("[?1h");
        }
        if (modes.applicationKeypad()) {
            sb.append("="); // DECKPAM
        }
        if (modes.mouseTrackingMode() != 0) {
            sb.append("[?").append(modes.mouseTrackingMode()).append('h');
        }
        if (modes.mouseSgr()) {
            sb.append("[?1006h");
        }
        if (modes.focusReporting()) {
            sb.append("[?1004h");
        }
        if (!modes.title().isEmpty()) {
            sb.append("]2;").append(modes.title()).append('');
        }

        // The current pen.
        TextStyle current = terminal.getStyleState().getCurrent();
        emitSgr(sb, current, pen);

        // The cursor, with pending-wrap handling.
        int cx = terminal.getCursorX(); // 1-based; == width+1 at the pending-wrap position
        int cy = terminal.getCursorY();
        if (cx == width + 1) {
            TerminalLine line = buffer.getLine(cy - 1);
            char last = line.charAt(width - 1);
            if (last == DWC || Character.isLowSurrogate(last)) {
                // Wide char (CJK+DWC, or emoji surrogate pair) at columns width-2, width-1.
                int startCol = width - 2;
                TextStyle s = styleAt(line, startCol);
                sb.append("[").append(cy).append(';').append(width - 1).append('H');
                emitSgr(sb, s, pen);
                char c0 = line.charAt(startCol);
                if (Character.isHighSurrogate(c0)) {
                    sb.append(c0).append(line.charAt(width - 1));
                } else {
                    sb.append(c0); // CJK: the DWC follows automatically when the client writes it
                }
            } else {
                char ch = last == '\0' ? ' ' : last;
                TextStyle s = styleAt(line, width - 1);
                sb.append("[").append(cy).append(';').append(width).append('H');
                emitSgr(sb, s, pen);
                sb.append(ch);
            }
            // Restore the pen so getStyleState().getCurrent() matches the source.
            emitSgr(sb, current, pen);
        } else {
            sb.append("[").append(cy).append(';').append(cx).append('H');
        }

        if (terminal.isOriginMode()) {
            sb.append("[?6h");
        }
        sb.append(modes.cursorVisible() ? "[?25h" : "[?25l");
    }

    /** Emits one line and returns whether it was wrapped. A wrapped line emits all
     * {@code width} cells with no {@code CSI K} and no CRLF (the client's deferred wrap sets
     * the flag on the next line's first write); a non-wrapped line emits up to its last
     * non-blank cell, then {@code CSI 0 m}, {@code CSI K}, and CRLF unless it is the last
     * line. {@code afterWrap} says the previous line was wrapped, so an entirely blank
     * continuation still writes one cell to fire that deferred wrap. */
    private static boolean emitRow(StringBuilder sb, TerminalLine line, int width, boolean isLast,
                                   TextStyle[] pen, boolean afterWrap) {
        if (line.isWrapped()) {
            int x = 0;
            while (x < width) {
                x = emitCell(sb, line, x, width, pen);
            }
            // No CSI K, no CRLF: the next line's first write triggers the wrap flag.
            return true;
        } else {
            int last = lastNonBlank(line, width);
            int x = 0;
            if (last < 0 && afterWrap) {
                // Blank continuation of a wrapped line: the client sits at the previous
                // line's pending-wrap position and neither CSI K nor CRLF fires the deferred
                // wrap (CSI K even clears the flag), so write this row's first (blank) cell
                // with its own style; CSI K below then erases from column 1.
                x = emitCell(sb, line, 0, 1, pen);
            }
            while (x <= last) {
                x = emitCell(sb, line, x, last + 1, pen);
            }
            sb.append("[0m"); // never CSI K under an active SGR (jediterm's erase is BCE)
            pen[0] = TextStyle.EMPTY;
            sb.append("[K");
            if (!isLast) {
                sb.append("\r\n");
            }
            return false;
        }
    }

    /** Emits the cell at {@code x} (skipping a DWC placeholder) and returns the next index.
     * {@code limit} bounds how far a wide character's second half may extend. */
    private static int emitCell(StringBuilder sb, TerminalLine line, int x, int limit, TextStyle[] pen) {
        char c = line.charAt(x);
        if (c == DWC) {
            return x + 1; // second half of a wide char; already emitted with the lead
        }
        emitSgr(sb, styleAt(line, x), pen);
        if (Character.isHighSurrogate(c) && x + 1 < limit) {
            // Supplementary code point (emoji): a surrogate pair occupies two buffer columns.
            sb.append(c).append(line.charAt(x + 1));
            return x + 2;
        }
        sb.append(c == '\0' ? ' ' : c);
        if (x + 1 < limit && line.charAt(x + 1) == DWC) {
            return x + 2; // wide char (CJK): skip its DWC placeholder
        }
        return x + 1;
    }

    private static int lastNonBlank(TerminalLine line, int width) {
        for (int x = width - 1; x >= 0; x--) {
            if (!isBlank(line, x)) {
                return x;
            }
        }
        return -1;
    }

    /** A blank cell is NUL or space with default background and neither INVERSE nor
     * UNDERLINED; anything else (a coloured/inverse/underlined space, a real glyph, a DWC)
     * is content. */
    private static boolean isBlank(TerminalLine line, int x) {
        char c = line.charAt(x);
        if (c != ' ' && c != '\0') {
            return false;
        }
        TextStyle s = styleAt(line, x);
        return s.getBackground() == null
                && !s.hasOption(TextStyle.Option.INVERSE)
                && !s.hasOption(TextStyle.Option.UNDERLINED);
    }

    private static TextStyle styleAt(TerminalLine line, int x) {
        TextStyle s = line.getStyleAt(x);
        return s == null ? TextStyle.EMPTY : s;
    }

    /** Emits {@code CSI 0;<params> m} to move the client's pen to {@code target}, unless it
     * is already there. Always resets first (BCE-safe and always correct); {@code target}'s
     * attributes and colours are restated in full. */
    private static void emitSgr(StringBuilder sb, TextStyle target, TextStyle[] pen) {
        if (target.equals(pen[0])) {
            return;
        }
        sb.append("[0").append(sgrParams(target)).append('m');
        pen[0] = target;
    }

    /** The SGR parameters for a style, each prefixed with ';' (so it appends after the "0"
     * reset); empty for the default style. */
    private static String sgrParams(TextStyle s) {
        StringBuilder p = new StringBuilder();
        if (s.hasOption(TextStyle.Option.BOLD)) p.append(";1");
        if (s.hasOption(TextStyle.Option.DIM)) p.append(";2");
        if (s.hasOption(TextStyle.Option.ITALIC)) p.append(";3");
        if (s.hasOption(TextStyle.Option.UNDERLINED)) p.append(";4");
        if (s.hasOption(TextStyle.Option.SLOW_BLINK)) p.append(";5");
        if (s.hasOption(TextStyle.Option.RAPID_BLINK)) p.append(";6");
        if (s.hasOption(TextStyle.Option.INVERSE)) p.append(";7");
        if (s.hasOption(TextStyle.Option.HIDDEN)) p.append(";8");
        appendColor(p, s.getForeground(), true);
        appendColor(p, s.getBackground(), false);
        return p.toString();
    }

    private static void appendColor(StringBuilder p, TerminalColor color, boolean foreground) {
        if (color == null) {
            return; // default (implied by the leading 0 reset)
        }
        if (color.isIndexed()) {
            int idx = color.getColorIndex();
            if (idx < 8) {
                p.append(';').append(foreground ? 30 + idx : 40 + idx);
            } else if (idx < 16) {
                p.append(';').append(foreground ? 90 + (idx - 8) : 100 + (idx - 8));
            } else {
                p.append(foreground ? ";38;5;" : ";48;5;").append(idx);
            }
        } else {
            // Truecolor. The fork stores 256-palette colours as RGB too, so emitting 38;2
            // reproduces them identically on the round trip.
            Color c = color.toColor();
            p.append(foreground ? ";38;2;" : ";48;2;")
                    .append(c.getRed()).append(';').append(c.getGreen()).append(';').append(c.getBlue());
        }
    }

    private static HeadlessTerminal.StoredCursor storedCursor(JediTerminal terminal) {
        if (terminal instanceof HeadlessTerminal.RecordingTerminal rt) {
            return rt.recordedStoredCursor();
        }
        return null;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private ScreenSerializer() {
    }
}
