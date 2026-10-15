package io.github.ghosthack.jmux.vt;

import com.jediterm.terminal.TerminalMode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** See SESSION-HOST.md "Headless emulator" (ModeTracker) and "Tests (server)" →
 * {@code ModeTrackerTest}, including the scroll region across a resize. */
class ModeTrackerTest {

    /** Feeds a raw ModeTracker every token of {@code s} (SGR-normalised, as the pump does). */
    private static void observe(ModeTracker tracker, String s) {
        VtTokenizer.Batch batch = new VtTokenizer().feed(s.getBytes(StandardCharsets.UTF_8));
        for (VtToken t : batch.tokens()) {
            tracker.observe(SgrNormalizer.normalize(t));
        }
    }

    private static HeadlessTerminal.Modes track(String s) {
        ModeTracker tracker = new ModeTracker(24);
        observe(tracker, s);
        return tracker.snapshot();
    }

    @Test
    void bracketedPaste() {
        assertTrue(track("[?2004h").bracketedPaste());
        assertFalse(track("[?2004h[?2004l").bracketedPaste());
    }

    @Test
    void applicationCursorKeysAndKeypad() {
        assertTrue(track("[?1h").applicationCursorKeys());
        assertTrue(track("=").applicationKeypad());
        assertFalse(track("=>").applicationKeypad());
    }

    @Test
    void autoWrapDefaultsOnAndTracksReset() {
        assertTrue(track("").autoWrap());
        assertFalse(track("[?7l").autoWrap());
    }

    @Test
    void cursorVisibleDefaultsOn() {
        assertTrue(track("").cursorVisible());
        assertFalse(track("[?25l").cursorVisible());
    }

    @Test
    void originMode() {
        assertTrue(track("[?6h").originMode());
    }

    @Test
    void mouseTrackingAndSgrFormat() {
        assertEquals(1000, track("[?1000h").mouseTrackingMode());
        assertEquals(1002, track("[?1002h").mouseTrackingMode());
        assertEquals(1003, track("[?1003h").mouseTrackingMode());
        assertEquals(0, track("[?1000h[?1000l").mouseTrackingMode());
        assertTrue(track("[?1006h").mouseSgr());
    }

    @Test
    void focusReporting() {
        assertTrue(track("[?1004h").focusReporting());
    }

    @Test
    void alternateScreen() {
        assertTrue(track("[?1049h").alternateScreen());
        assertFalse(track("[?1049h[?1049l").alternateScreen());
        assertTrue(track("[?47h").alternateScreen());
    }

    @Test
    void cursorShapeFromDecscusr() {
        assertEquals(4, track("[4 q").cursorShape());
        assertEquals(1, track("[0 q").cursorShape()); // 0 normalises to a blinking block
    }

    @Test
    void title() {
        assertEquals("hello", track("]0;hello").title());
        assertEquals("win", track("]2;win\\").title());
    }

    @Test
    void reportedCwdFromOsc7() {
        ModeTracker tracker = new ModeTracker(24);
        observe(tracker, "]7;file://myhost/home/me%20dir");
        assertEquals("/home/me dir", tracker.reportedCwd());
    }

    @Test
    void reportedCwdFromOsc9() {
        ModeTracker tracker = new ModeTracker(24);
        observe(tracker, "]9;9;/tmp/work");
        assertEquals("/tmp/work", tracker.reportedCwd());
        assertNull(new ModeTracker(24).reportedCwd());
    }

    @Test
    void risResetsEverything() {
        ModeTracker tracker = new ModeTracker(24);
        observe(tracker, "[?7l[?25l[?2004h[?1000h[4 q=[3;20r");
        observe(tracker, "c"); // RIS
        HeadlessTerminal.Modes m = tracker.snapshot();
        assertTrue(m.autoWrap());
        assertTrue(m.cursorVisible());
        assertFalse(m.bracketedPaste());
        assertEquals(0, m.mouseTrackingMode());
        assertEquals(0, m.cursorShape());
        assertFalse(m.applicationKeypad());
        assertEquals(1, m.scrollTop());
        assertEquals(24, m.scrollBottom());
    }

    @Test
    void scrollRegionClampedLikeJediterm() {
        HeadlessTerminal.Modes m = track("[3;20r");
        assertEquals(3, m.scrollTop());
        assertEquals(20, m.scrollBottom());
        // CSI r resets to the full height.
        assertEquals(1, track("[3;20r[r").scrollTop());
        assertEquals(24, track("[3;20r[r").scrollBottom());
        // Bottom is clamped to the height.
        assertEquals(24, track("[3;99r").scrollBottom());
    }

    @Test
    void scrollRegionShiftsWithResizeLikeJediterm() {
        // Drive a real HeadlessTerminal so the tracker and jediterm can be cross-checked.
        HeadlessTerminal term = new HeadlessTerminal(80, 24, 100, b -> {
        });
        apply(term, "[3;20r");
        assertEquals(3, term.modes().scrollTop());
        assertEquals(20, term.modes().scrollBottom());
        assertEquals(3, term.terminal().getScrollRegionTop());

        term.resize(80, 30); // +6 rows
        // JediTerminal.doResize shifts the bottom margin by the row delta (JediTerminal.java:1055).
        assertEquals(26, term.modes().scrollBottom());
        assertEquals(3, term.modes().scrollTop());
        assertEquals(3, term.terminal().getScrollRegionTop());

        term.resize(80, 24); // -6 rows
        assertEquals(20, term.modes().scrollBottom());
        assertEquals(3, term.terminal().getScrollRegionTop());
    }

    @Test
    void modesCrossCheckJediterm() {
        HeadlessTerminal term = new HeadlessTerminal(80, 24, 100, b -> {
        });
        apply(term, "[?1h[?7l[?6h[?1049h");
        HeadlessTerminal.Modes m = term.modes();
        assertEquals(term.terminal().isModelEnabled(TerminalMode.CursorKey), m.applicationCursorKeys());
        assertEquals(term.terminal().isAutoWrap(), m.autoWrap());
        assertEquals(term.terminal().isOriginMode(), m.originMode());
        assertEquals(term.buffer().isUsingAlternateBuffer(), m.alternateScreen());
        assertEquals(term.terminal().getScrollRegionTop(), m.scrollTop());
    }

    private static void apply(HeadlessTerminal term, String s) {
        VtTokenizer.Batch batch = new VtTokenizer().feed(s.getBytes(StandardCharsets.UTF_8));
        java.util.List<VtToken> norm = new java.util.ArrayList<>();
        for (VtToken t : batch.tokens()) {
            norm.add(SgrNormalizer.normalize(t));
        }
        term.apply(norm);
    }
}
