package io.github.ghosthack.jmux.vt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * See SESSION-HOST.md "Tests (server)" → {@code PumpTest}: feeding a stream in random chunk
 * sizes yields the same screen as one read, and the broadcast bytes concatenate to the input
 * minus the stripped queries and the SGR rewrites (which is exactly what a single-read pass
 * through the same tokenizer + normalizer + filter produces).
 */
class PumpTest {

    @Test
    void typingAroundDcsAndCursorSequencesSurvivesEverySplit() {
        String[] streams = {
                "blah blah",
                "bl\u001bPzz\u001b\\ah blah",
                "\u001b[3;1H\u001bPzz\u001b\\\u001b[0%m\u001b[6n\u001b[1;1Hblah blah",
                "bla\u001b[1;3Ha\u001b[1;4Hh blah",
                "\u001b[?2026hbl\u001bPzz\u001b\\ah blah\u001b[?2026l"
        };
        for (String stream : streams) {
            byte[] input = stream.getBytes(StandardCharsets.UTF_8);
            VtPipe expected = oneShot(input);
            assertEquals("blah blah", expected.terminal.screenText().strip());
            for (int split = 0; split <= input.length; split++) {
                VtPipe pipe = new VtPipe(COLS, ROWS, HISTORY);
                pipe.feed(input, 0, split);
                pipe.feed(input, split, input.length - split);
                assertEquals("blah blah", pipe.terminal.screenText().strip(), "split " + split);
                assertEquals(10, pipe.terminal.terminal().getCursorX(), "split " + split);
                assertArrayEquals(expected.forwardedBytes(), pipe.forwardedBytes(), "split " + split);
                VtPipe client = oneShot(pipe.forwardedBytes());
                assertEquals("blah blah", client.terminal.screenText().strip(), "client split " + split);
            }
            VtPipe bytewise = new VtPipe(COLS, ROWS, HISTORY);
            bytewise.feedChunked(input, 1);
            assertEquals("blah blah", bytewise.terminal.screenText().strip());
            assertArrayEquals(expected.forwardedBytes(), bytewise.forwardedBytes());
        }
    }

    private static final int COLS = 24;
    private static final int ROWS = 8;
    private static final int HISTORY = 500;

    /** A representative mix of text (curly quotes, emoji), CSI (SGR incl. colon sub-params,
     * cursor moves, erase), OSC (title, unterminated then terminated), DCS, and queries. */
    private static byte[] syntheticStream() {
        StringBuilder sb = new StringBuilder();
        sb.append("plain ascii line one\r\n");
        sb.append("“curly” and emoji 🛑🚀 here\r\n");
        sb.append("[1;31mbold red[0m [38:2::10:200:30mtruecolor-colon[m\r\n");
        sb.append("[4:3municode underline[m tail\r\n");
        sb.append("]0;pump title 🛑after title\r\n");
        sb.append("query DA [c CPR [6n keeps going\r\n");
        sb.append("[38;5;208m256 colour[m and [48;2;1;2;3mrgb bg[m\r\n");
        sb.append("P1;2|somedcs\\ dcs done\r\n");
        sb.append("[2;5Hmoved cursor[K wrote\r\n");
        sb.append("winop [8;30;100t ignored and [?996n passed\r\n");
        sb.append("a very long line that will certainly wrap around the terminal width boundary here\r\n");
        sb.append("[?1002h[?1006h[?2004h mode soup [3 q");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static VtPipe oneShot(byte[] input) {
        VtPipe pipe = new VtPipe(COLS, ROWS, HISTORY);
        pipe.feed(input);
        return pipe;
    }

    private static VtPipe chunked(byte[] input, long seed) {
        VtPipe pipe = new VtPipe(COLS, ROWS, HISTORY);
        Random rnd = new Random(seed);
        int off = 0;
        while (off < input.length) {
            int len = Math.min(1 + rnd.nextInt(7), input.length - off);
            pipe.feed(input, off, len);
            off += len;
        }
        return pipe;
    }

    @ParameterizedTest
    @ValueSource(longs = {1L, 2L, 7L, 42L, 1234L, 99999L})
    void randomChunkingMatchesOneRead(long seed) {
        byte[] input = syntheticStream();
        VtPipe one = oneShot(input);
        VtPipe many = chunked(input, seed);

        // (a) Identical resulting screen state: two equal terminals serialize identically.
        assertArrayEquals(one.terminal.serialize(HISTORY), many.terminal.serialize(HISTORY),
                "screen state differs for seed " + seed);

        // (b) Identical broadcast stream: the forwarded bytes (each token's bytesToForward
        // plus open string fragments, in order) do not depend on chunk boundaries.
        assertArrayEquals(one.forwardedBytes(), many.forwardedBytes(),
                "forwarded bytes differ for seed " + seed);
    }

    @Test
    void forwardedStreamOmitsQueriesAndRewritesSgr() {
        byte[] input = syntheticStream();
        VtPipe pipe = oneShot(input);
        byte[] forwarded = pipe.forwardedBytes();

        // The server-answered queries (DA1, CPR) are gone; the passed-through ones remain.
        String text = new String(forwarded, StandardCharsets.UTF_8);
        org.junit.jupiter.api.Assertions.assertFalse(text.contains("[c"), "DA1 must be stripped");
        org.junit.jupiter.api.Assertions.assertFalse(text.contains("[6n"), "CPR must be stripped");
        org.junit.jupiter.api.Assertions.assertFalse(text.contains("[8;30;100t"), "XTWINOPS 8 stripped");
        org.junit.jupiter.api.Assertions.assertTrue(text.contains("[?996n"), "996 passes through");
        // The colon SGRs were rewritten to the semicolon form.
        org.junit.jupiter.api.Assertions.assertFalse(text.contains("38:2:"), "colon SGR rewritten");
        org.junit.jupiter.api.Assertions.assertTrue(text.contains("[38;2;10;200;30m"), "rewritten form present");

        // A client that feeds only the forwarded bytes ends up with the same screen the
        // server holds (queries do not paint; SGR rewrites are applied to both sides).
        VtPipe client = new VtPipe(COLS, ROWS, HISTORY);
        client.feed(forwarded);
        assertArrayEquals(pipe.terminal.serialize(HISTORY), client.terminal.serialize(HISTORY),
                "client view differs from server view");
    }
}
