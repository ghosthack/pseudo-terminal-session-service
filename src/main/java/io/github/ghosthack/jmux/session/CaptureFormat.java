package io.github.ghosthack.jmux.session;

import java.util.Locale;
import java.util.Optional;

/** What {@link Session#capture(CaptureFormat)} renders the visible screen as. */
public enum CaptureFormat {
    /** Plain rows, trailing whitespace trimmed (tmux {@code capture-pane -p} shape). */
    TEXT,
    /** {@link #TEXT} plus {@code ▶ } before selected rows and {@code ▮} at the visible cursor
     * (see {@code HeadlessTerminal.annotatedText()}). */
    ANNOTATED,
    /** The attach redraw stream without history, as a UTF-8 string: replaying it into a
     * terminal of the same size reproduces the screen, styles and modes. */
    ANSI;

    /** The wire name ({@code text} / {@code annotated} / {@code ansi}). */
    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The format with this wire name (case-insensitive), or empty. */
    public static Optional<CaptureFormat> fromWireName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        for (CaptureFormat format : values()) {
            if (format.wireName().equalsIgnoreCase(name.strip())) {
                return Optional.of(format);
            }
        }
        return Optional.empty();
    }
}
