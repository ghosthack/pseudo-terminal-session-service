package io.github.ghosthack.jmux.vt;

import com.jediterm.core.Color;
import com.jediterm.core.util.TermSize;
import com.jediterm.terminal.CursorShape;
import com.jediterm.terminal.RequestOrigin;
import com.jediterm.terminal.TerminalColor;
import com.jediterm.terminal.TerminalDisplay;
import com.jediterm.terminal.TerminalMode;
import com.jediterm.terminal.TextStyle;
import com.jediterm.terminal.emulator.JediEmulator;
import com.jediterm.terminal.emulator.mouse.MouseFormat;
import com.jediterm.terminal.emulator.mouse.MouseMode;
import com.jediterm.terminal.model.JediTerminal;
import com.jediterm.terminal.model.StyleState;
import com.jediterm.terminal.model.TerminalLine;
import com.jediterm.terminal.model.TerminalSelection;
import com.jediterm.terminal.model.TerminalTextBuffer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A jediterm-core terminal with no display: {@code JediTerminal} + {@code TerminalTextBuffer}
 * + {@code JediEmulator}, fed <em>synchronously</em> by the session's pump thread through
 * {@link #apply(List)}. It is the server's source of truth for {@link #screenText()}
 * (capture), {@link #serialize(int)} (redraw on attach) and the pane title.
 *
 * <p>Threading: {@code apply}, {@code resize} and {@code serialize} are all called by the
 * session under its lock; the getters may be called from any thread and take the text
 * buffer's lock (as jediterm's own {@code TerminalStarter.postResize} does from another
 * thread). There is no emulator thread: the emulator is constructed once over a refillable
 * {@code ArrayTerminalDataStream} subclass (its {@code myBuf}/{@code myOffset}/{@code myLength}
 * are protected; {@code DataStreamIteratingEmulator.myDataStream} is final, so it is
 * refilled, not replaced) and a batch is applied with
 * {@code while (emulator.hasNext()) emulator.next(); emulator.resetEof();} after decoding
 * the tokens' bytes with a streaming UTF-8 decoder. Because tokens are always complete
 * sequences, a batch never ends mid-sequence, which is what makes this safe: jediterm
 * silently drops a sequence that hits EOF half-way.
 *
 * <p>Neither {@code TerminalStarter} nor {@code TtyBasedArrayDataStream} is used — their
 * pull loop has no hook between read and apply.
 *
 * <p>The underlying {@code JediTerminal} subclass (package-private here):
 * <ul>
 *   <li>forwards {@code deviceStatusReport(String)} / {@code deviceAttributes(byte[])} to
 *       {@link Replies#send(byte[])} (the session's PTY writer queue), except the colour-
 *       scheme report {@code CSI ?997;…n}, which is dropped — only a client knows its
 *       theme, so {@code CSI ?996n} is passed through to clients (see {@link QueryFilter});</li>
 *   <li>ignores {@code resize(TermSize, RequestOrigin.Remote)} (a program's XTWINOPS 8);
 *       the session's own resizes use {@code RequestOrigin.User};</li>
 *   <li>records the DECSC cursor on {@code saveCursor()} and the main buffer's size on
 *       {@code useAlternateBuffer(true)}, because jediterm keeps both private.</li>
 * </ul>
 * The recording {@code TerminalDisplay} returns {@code ambiguousCharsAreDoubleWidth() ==
 * false} and null window colours (so OSC 10/11 queries produce no server reply).
 */
public final class HeadlessTerminal implements AutoCloseable {

    /** Where the emulator's own replies (DA, DSR, CPR) go: the session's PTY writer queue. */
    public interface Replies {
        void send(byte[] bytes);
    }

    /** Everything the redraw needs that is not in the text buffer, tracked from the
     * token stream by {@link ModeTracker} plus jediterm's public mode getters. */
    public record Modes(boolean cursorVisible, int cursorShape /* DECSCUSR 0..6, 0 = default */,
                        boolean bracketedPaste, boolean applicationCursorKeys, boolean applicationKeypad,
                        int mouseTrackingMode /* 1000/1002/1003, 0 = none */, boolean mouseSgr,
                        boolean focusReporting, boolean alternateScreen, boolean autoWrap,
                        boolean originMode, int scrollTop /* 1-based */, int scrollBottom /* 1-based */,
                        String title) {
    }

    /** What {@code ESC 7} / {@code CSI ?1049 h} saved, as jediterm's private
     * {@code StoredCursor} would; null when nothing was saved since the last RIS. */
    public record StoredCursor(int cursorX, int cursorY, TextStyle style, boolean autoWrap,
                               boolean originMode) {
    }

    private final RecordingTerminal terminal;
    private final TerminalTextBuffer buffer;
    private final RecordingDisplay display;
    private final ModeTracker modeTracker;
    private final RefillableStream stream;
    private final JediEmulator emulator;

    private final CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE);
    /** Undecoded trailing bytes carried to the next {@link #apply} (normally empty, since
     * the tokenizer already holds incomplete UTF-8 tails). */
    private byte[] carry = new byte[0];

    private boolean closed;

    public HeadlessTerminal(int columns, int rows, int maxHistoryLines, Replies replies) {
        StyleState styleState = new StyleState();
        this.buffer = new TerminalTextBuffer(columns, rows, styleState, maxHistoryLines);
        this.display = new RecordingDisplay();
        this.terminal = new RecordingTerminal(display, buffer, styleState, replies);
        this.modeTracker = new ModeTracker(rows);
        this.stream = new RefillableStream();
        this.emulator = new JediEmulator(stream, terminal);
    }

    /** Applies a batch of complete tokens to the emulator and the mode tracker, in order.
     * Returns when every token has been applied. Called under the session lock only. */
    public void apply(List<VtToken> tokens) {
        if (closed || tokens.isEmpty()) {
            return;
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (VtToken token : tokens) {
            bytes.write(token.bytes(), 0, token.bytes().length);
            modeTracker.observe(token);
        }
        char[] chars = decode(bytes.toByteArray());
        if (chars.length == 0) {
            return;
        }
        stream.refill(chars);
        try {
            while (emulator.hasNext()) {
                emulator.next();
            }
        } catch (Exception e) {
            // JediEmulator swallows sequence errors internally; a stray exception must never
            // let one token corrupt the pump. The stream is left drained for the next batch.
        } finally {
            emulator.resetEof();
        }
    }

    /** Decodes {@code batch} (prepended with any carried tail) as UTF-8, REPLACE on malformed,
     * carrying an incomplete trailing code point to the next call. */
    private char[] decode(byte[] batch) {
        byte[] all;
        if (carry.length == 0) {
            all = batch;
        } else {
            all = new byte[carry.length + batch.length];
            System.arraycopy(carry, 0, all, 0, carry.length);
            System.arraycopy(batch, 0, all, carry.length, batch.length);
        }
        ByteBuffer in = ByteBuffer.wrap(all);
        StringBuilder sb = new StringBuilder(all.length);
        CharBuffer out = CharBuffer.allocate(Math.max(16, all.length));
        while (true) {
            CoderResult cr = decoder.decode(in, out, false);
            out.flip();
            sb.append(out);
            out.clear();
            if (cr.isUnderflow()) {
                break;
            }
            // Overflow: the CharBuffer filled up; drain and continue.
        }
        carry = new byte[in.remaining()];
        in.get(carry);
        char[] result = new char[sb.length()];
        sb.getChars(0, sb.length(), result, 0);
        return result;
    }

    /** Resizes the text buffer ({@code RequestOrigin.User}) and shifts the tracked scroll
     * region's bottom by the row delta, as {@code JediTerminal.doResize} does. */
    public void resize(int columns, int rows) {
        if (closed) {
            return;
        }
        int oldRows = terminal.getTerminalHeight();
        terminal.resize(new TermSize(columns, rows), RequestOrigin.User);
        int newRows = terminal.getTerminalHeight();
        modeTracker.resized(oldRows, newRows);
    }

    public int columns() {
        return terminal.getTerminalWidth();
    }

    public int rows() {
        return terminal.getTerminalHeight();
    }

    /** The visible screen: one string per row, trailing whitespace trimmed, DWC placeholder
     * cells removed; lines joined with {@code \n}, and the result ends with {@code \n}
     * (tmux {@code capture-pane -p} shape). */
    public String screenText() {
        buffer.lock();
        try {
            StringBuilder sb = new StringBuilder();
            int height = buffer.getHeight();
            for (int y = 0; y < height; y++) {
                sb.append(trimTrailing(stripDwc(buffer.getLine(y).getText())));
                sb.append('\n');
            }
            return sb.toString();
        } finally {
            buffer.unlock();
        }
    }

    /** Prefix of a row {@link #annotatedText()} considers selected. */
    public static final String SELECTION_MARKER = "\u25b6 ";
    /** Inserted by {@link #annotatedText()} where the visible cursor is. */
    public static final String CURSOR_MARKER = "\u25ae";

    /**
     * {@link #screenText()} with two hints a reader of plain text would otherwise lose:
     * <ul>
     *   <li><b>Selection</b>: a row with a non-blank cell drawn in reverse video, or on a
     *       background other than the screen's dominant one (the most frequent background
     *       of the non-blank cells, default included), is prefixed {@link #SELECTION_MARKER}.
     *       When more than a third of the non-blank rows qualify the colours are a theme,
     *       not a selection, and no row is marked.</li>
     *   <li><b>Cursor</b>: when the cursor is visible, {@link #CURSOR_MARKER} is inserted
     *       (not substituted) at its column in its row, padding with spaces past the text.</li>
     * </ul>
     * A blank cell is a space or NUL with the default background and no reverse video.
     */
    public String annotatedText() {
        buffer.lock();
        try {
            int height = buffer.getHeight();
            int width = buffer.getWidth();
            String[] raw = new String[height];
            TerminalLine[] lines = new TerminalLine[height];
            Map<TerminalColor, Integer> backgrounds = new HashMap<>();
            for (int y = 0; y < height; y++) {
                lines[y] = buffer.getLine(y);
                raw[y] = lines[y].getText();
                for (int x = 0; x < width; x++) {
                    TextStyle style = styleAt(lines[y], x);
                    if (!isBlank(raw[y], x, style)) {
                        backgrounds.merge(style.getBackground(), 1, Integer::sum);
                    }
                }
            }
            TerminalColor dominant = null;
            int best = -1;
            for (Map.Entry<TerminalColor, Integer> entry : backgrounds.entrySet()) {
                if (entry.getValue() > best) {
                    best = entry.getValue();
                    dominant = entry.getKey();
                }
            }
            boolean[] highlighted = new boolean[height];
            int contentRows = 0;
            int highlightedRows = 0;
            for (int y = 0; y < height; y++) {
                boolean content = false;
                for (int x = 0; x < width; x++) {
                    TextStyle style = styleAt(lines[y], x);
                    if (isBlank(raw[y], x, style)) {
                        continue;
                    }
                    content = true;
                    if (style.hasOption(TextStyle.Option.INVERSE)
                            || !Objects.equals(style.getBackground(), dominant)) {
                        highlighted[y] = true;
                        break;
                    }
                }
                if (content) {
                    contentRows++;
                }
                if (highlighted[y]) {
                    highlightedRows++;
                }
            }
            boolean selection = highlightedRows > 0 && highlightedRows * 3 <= contentRows;
            int cursorRow = -1;
            int cursorColumn = -1;
            if (display.cursorVisible()) {
                cursorRow = terminal.getCursorY() - 1;
                cursorColumn = Math.min(terminal.getCursorX(), width) - 1;
            }
            StringBuilder sb = new StringBuilder();
            for (int y = 0; y < height; y++) {
                String text = trimTrailing(stripDwc(raw[y]));
                if (y == cursorRow && cursorColumn >= 0) {
                    int index = textIndex(raw[y], cursorColumn);
                    StringBuilder row = new StringBuilder(text);
                    while (row.length() < index) {
                        row.append(' ');
                    }
                    row.insert(index, CURSOR_MARKER);
                    text = row.toString();
                }
                if (selection && highlighted[y]) {
                    sb.append(SELECTION_MARKER);
                }
                sb.append(text).append('\n');
            }
            return sb.toString();
        } finally {
            buffer.unlock();
        }
    }

    /** The last OSC 0/2 title, or "" if never set. */
    public String title() {
        return display.title();
    }

    /** The last OSC 7 / OSC 9;9 working directory the shell reported, or null. */
    public String reportedCwd() {
        return modeTracker.reportedCwd();
    }

    public Modes modes() {
        buffer.lock();
        try {
            return modesLocked();
        } finally {
            buffer.unlock();
        }
    }

    public StoredCursor storedCursor() {
        return terminal.recordedStoredCursor();
    }

    /** The redraw stream for a freshly attached client at the current size: see
     * SESSION-HOST.md "Redraw on attach" and {@link ScreenSerializer}. Includes up to
     * {@code historyLines} history lines before the screen. */
    public byte[] serialize(int historyLines) {
        buffer.lock();
        try {
            return ScreenSerializer.serialize(terminal, buffer, modesLocked(), historyLines);
        } finally {
            buffer.unlock();
        }
    }

    /** Test/diagnostic access: the screen rows as plain text, untrimmed. */
    public List<String> screenLines() {
        buffer.lock();
        try {
            int height = buffer.getHeight();
            List<String> lines = new ArrayList<>(height);
            for (int y = 0; y < height; y++) {
                lines.add(stripDwc(buffer.getLine(y).getText()));
            }
            return lines;
        } finally {
            buffer.unlock();
        }
    }

    /** Test access to the underlying terminal and buffer (round-trip comparisons). */
    public JediTerminal terminal() {
        return terminal;
    }

    public TerminalTextBuffer buffer() {
        return buffer;
    }

    /** Releases nothing OS-level (there is no thread); marks the instance closed so a late
     * {@code apply} is a no-op. Idempotent. */
    @Override
    public void close() {
        closed = true;
    }

    // --- internals -------------------------------------------------------------------

    /**
     * Same as {@link #modes()} but without re-taking the buffer lock (already held). Sources
     * each field from the most authoritative place: the recording display for the states
     * jediterm only surfaces through callbacks, {@code JediTerminal}'s public getters for the
     * ones it exposes, and the {@link ModeTracker} for what neither offers (DECSCUSR number,
     * focus reporting, the scroll region's bottom margin).
     */
    private Modes modesLocked() {
        Modes tracked = modeTracker.snapshot();
        return new Modes(
                display.cursorVisible(),
                tracked.cursorShape(),
                display.bracketedPaste(),
                terminal.isModelEnabled(TerminalMode.CursorKey),
                terminal.isModelEnabled(TerminalMode.Keypad),
                mouseModeNumeric(display.mouseMode()),
                display.mouseFormat() == MouseFormat.MOUSE_FORMAT_SGR,
                tracked.focusReporting(),
                display.alternateScreen(),
                terminal.isAutoWrap(),
                terminal.isOriginMode(),
                terminal.getScrollRegionTop(),
                tracked.scrollBottom(),
                display.title());
    }

    private static int mouseModeNumeric(MouseMode mode) {
        return switch (mode) {
            case MOUSE_REPORTING_NORMAL -> 1000;
            case MOUSE_REPORTING_BUTTON_MOTION -> 1002;
            case MOUSE_REPORTING_ALL_MOTION -> 1003;
            default -> 0;
        };
    }

    /** The index in {@code stripDwc(raw)} of buffer column {@code column}: DWC placeholder
     * cells take a buffer column but no text, columns past the line's text count as spaces. */
    private static int textIndex(String raw, int column) {
        int index = 0;
        int end = Math.min(column, raw.length());
        for (int i = 0; i < end; i++) {
            if (raw.charAt(i) != '\uE000') {
                index++;
            }
        }
        return index + Math.max(0, column - raw.length());
    }

    private static boolean isBlank(String text, int x, TextStyle style) {
        char c = x < text.length() ? text.charAt(x) : ' ';
        return (c == ' ' || c == '\0') && style.getBackground() == null
                && !style.hasOption(TextStyle.Option.INVERSE);
    }

    private static TextStyle styleAt(TerminalLine line, int x) {
        TextStyle style = line.getStyleAt(x);
        return style == null ? TextStyle.EMPTY : style;
    }

    private static String stripDwc(String s) {
        if (s.indexOf('') < 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String trimTrailing(String s) {
        int end = s.length();
        while (end > 0) {
            char c = s.charAt(end - 1);
            if (c == ' ' || c == '\t' || c == '\0') {
                end--;
            } else {
                break;
            }
        }
        return s.substring(0, end);
    }

    /**
     * The refillable data stream feeding the single reused {@link JediEmulator}. Its buffer
     * is replaced per batch; {@code DataStreamIteratingEmulator.myDataStream} is final so
     * the stream instance is kept and refilled (ArrayTerminalDataStream.java protected fields).
     */
    static final class RefillableStream extends com.jediterm.terminal.ArrayTerminalDataStream {
        RefillableStream() {
            super(new char[0], 0, 0);
        }

        void refill(char[] data) {
            myBuf = data;
            myOffset = 0;
            myLength = data.length;
        }
    }

    /**
     * The recording {@code JediTerminal}. Overrides the reply sinks and buffer/cursor
     * snapshot points that jediterm keeps private.
     */
    static final class RecordingTerminal extends JediTerminal {
        private final Replies replies;
        private volatile StoredCursor recordedStoredCursor;
        private volatile TermSize recordedMainSize;

        RecordingTerminal(TerminalDisplay display, TerminalTextBuffer buffer, StyleState styleState,
                          Replies replies) {
            super(display, buffer, styleState);
            this.replies = replies;
        }

        @Override
        public void deviceStatusReport(String str) {
            // Drop the colour-scheme report CSI ?997;<code>n — only a client knows its theme.
            // (JediEmulator.deviceStatusReport, JediEmulator.java:770-775.)
            if (str.startsWith("[?997")) {
                return;
            }
            replies.send(str.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public void deviceAttributes(byte[] response) {
            replies.send(response);
        }

        @Override
        public void resize(@NotNull TermSize newTermSize, @NotNull RequestOrigin origin) {
            // A program's XTWINOPS 8 (CSI 8;h;w t) arrives as RequestOrigin.Remote; it would
            // resize the emulator but not the PTY, so ignore it. The session resizes with User.
            if (origin == RequestOrigin.Remote) {
                return;
            }
            super.resize(newTermSize, origin);
        }

        @Override
        public void saveCursor() {
            super.saveCursor();
            recordedStoredCursor = new StoredCursor(getX(), getCursorY(), getStyleState().getCurrent(),
                    isAutoWrap(), isOriginMode());
        }

        @Override
        public void useAlternateBuffer(boolean enabled) {
            if (enabled && !getTerminalTextBuffer().isUsingAlternateBuffer()) {
                recordedMainSize = getSize();
            }
            super.useAlternateBuffer(enabled);
        }

        @Override
        public void reset(boolean fullReset) {
            super.reset(fullReset);
            if (fullReset) {
                recordedStoredCursor = null; // RIS clears the DECSC store
            }
        }

        StoredCursor recordedStoredCursor() {
            return recordedStoredCursor;
        }

        TermSize recordedMainSize() {
            return recordedMainSize;
        }
    }

    /**
     * The recording {@code TerminalDisplay}: keeps only what a redraw needs and jediterm
     * surfaces through display callbacks. Returns {@code ambiguousCharsAreDoubleWidth()
     * == false} and null window colours so OSC 10/11 queries get no server reply.
     */
    static final class RecordingDisplay implements TerminalDisplay {
        private volatile String title = "";
        private volatile boolean cursorVisible = true;
        private volatile MouseMode mouseMode = MouseMode.MOUSE_REPORTING_NONE;
        private volatile MouseFormat mouseFormat = MouseFormat.MOUSE_FORMAT_XTERM;
        private volatile boolean bracketedPaste;
        private volatile boolean alternateScreen;

        @Override
        public void setCursor(int x, int y) {
        }

        @Override
        public void setCursorShape(@Nullable CursorShape cursorShape) {
            // The serialized DECSCUSR value (0..6, distinguishing e.g. blinking vs steady
            // block, which the CursorShape enum keeps but a redraw needs as a number) is
            // tracked by ModeTracker from the CSI Ps SP q tokens, so nothing is stored here.
        }

        @Override
        public void beep() {
        }

        @Override
        public void scrollArea(int scrollRegionTop, int scrollRegionSize, int dy) {
        }

        @Override
        public void setCursorVisible(boolean isCursorVisible) {
            this.cursorVisible = isCursorVisible;
        }

        @Override
        public void useAlternateScreenBuffer(boolean useAlternateScreenBuffer) {
            this.alternateScreen = useAlternateScreenBuffer;
        }

        @Override
        public String getWindowTitle() {
            return title;
        }

        @Override
        public void setWindowTitle(@NotNull String windowTitle) {
            this.title = windowTitle;
        }

        @Override
        public @Nullable TerminalSelection getSelection() {
            return null;
        }

        @Override
        public void terminalMouseModeSet(@NotNull MouseMode mouseMode) {
            this.mouseMode = mouseMode;
        }

        @Override
        public void setMouseFormat(@NotNull MouseFormat mouseFormat) {
            this.mouseFormat = mouseFormat;
        }

        @Override
        public boolean ambiguousCharsAreDoubleWidth() {
            return false;
        }

        @Override
        public void setBracketedPasteMode(boolean bracketedPasteModeEnabled) {
            this.bracketedPaste = bracketedPasteModeEnabled;
        }

        @Override
        public @Nullable Color getWindowForeground() {
            return null;
        }

        @Override
        public @Nullable Color getWindowBackground() {
            return null;
        }

        String title() {
            return title;
        }

        boolean cursorVisible() {
            return cursorVisible;
        }

        MouseMode mouseMode() {
            return mouseMode;
        }

        MouseFormat mouseFormat() {
            return mouseFormat;
        }

        boolean bracketedPaste() {
            return bracketedPaste;
        }

        boolean alternateScreen() {
            return alternateScreen;
        }
    }
}
