package io.github.ghosthack.jmux.cli;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SizeAnnouncementFilterTest {

    private static final String ESC = "";

    private final List<String> notices = new ArrayList<>();
    private final SizeAnnouncementFilter filter = new SizeAnnouncementFilter(
            (columns, rows) -> notices.add(columns + "x" + rows));

    @Test
    void announcementIsReplacedByANoticeAndEverythingElsePasses() {
        String in = "before" + ESC + "[8;40;120t" + "after" + ESC + "[31mred" + ESC + "[0m";
        assertEquals("before" + "after" + ESC + "[31mred" + ESC + "[0m", pass(in));
        assertEquals(List.of("120x40"), notices);
    }

    @Test
    void announcementSplitAcrossReadsIsStillRecognised() {
        StringBuilder out = new StringBuilder();
        out.append(pass("x" + ESC + "[8;"));
        out.append(pass("2"));
        out.append(pass("4;80"));
        out.append(pass("ty"));
        assertEquals("xy", out.toString());
        assertEquals(List.of("80x24"), notices);
    }

    @Test
    void lookalikesAreReleasedIntact() {
        String other = ESC + "[8;24;80z" + ESC + "[8t" + ESC + "[9;1;1t" + ESC + ESC + "[8;1;1t";
        assertEquals(ESC + "[8;24;80z" + ESC + "[8t" + ESC + "[9;1;1t" + ESC, pass(other));
        assertEquals(List.of("1x1"), notices, "the ESC ESC case: the second ESC opens a real announcement");
    }

    @Test
    void trailingPartialIsHeldThenFlushed() {
        assertEquals("abc", pass("abc" + ESC + "[8;1"));
        assertEquals(ESC + "[8;1", new String(filter.flush(), StandardCharsets.ISO_8859_1));
        assertEquals(List.of(), notices);
    }

    private String pass(String text) {
        byte[] in = text.getBytes(StandardCharsets.ISO_8859_1);
        return new String(filter.filter(in, 0, in.length), StandardCharsets.ISO_8859_1);
    }
}
