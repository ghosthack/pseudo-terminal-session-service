package io.github.ghosthack.jmux.vt;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KeyNamesTest {

    @Test
    void singleCharactersAreTheirUtf8Bytes() {
        assertEquals("1y/", text(List.of("1", "y", "/")));
        assertEquals("Y", text(List.of("Y")), "a literal keeps its case");
        assertArrayEquals("é".getBytes(StandardCharsets.UTF_8), KeyNames.encode(List.of("é"), false));
        assertArrayEquals("😀".getBytes(StandardCharsets.UTF_8), KeyNames.encode(List.of("😀"), false),
                "one code point, even as a surrogate pair");
    }

    @Test
    void namedKeysAreCaseInsensitive() {
        assertEquals("\r", text(List.of("Enter")));
        assertEquals("\r", text(List.of("ENTER")));
        assertEquals("\t", text(List.of("tab")));
        assertEquals("\u001b[Z", text(List.of("BTab")));
        assertEquals("\u001b[Z", text(List.of("S-Tab")));
        assertEquals("\u001b\u001b", text(List.of("Esc", "Escape")));
        assertEquals(" ", text(List.of("Space")));
        assertEquals("\u007f\u007f", text(List.of("Backspace", "BSpace")));
        assertEquals("\u001b[3~\u001b[3~", text(List.of("Delete", "DC")));
        assertEquals("\u001b[2~\u001b[2~", text(List.of("Insert", "IC")));
        assertEquals("\u001b[5~\u001b[5~\u001b[5~", text(List.of("PageUp", "PgUp", "PPage")));
        assertEquals("\u001b[6~\u001b[6~\u001b[6~", text(List.of("PageDown", "PgDn", "NPage")));
    }

    @Test
    void cursorKeysFollowApplicationMode() {
        List<String> keys = List.of("Up", "Down", "Right", "Left", "Home", "End");
        assertEquals("\u001b[A\u001b[B\u001b[C\u001b[D\u001b[H\u001b[F", text(keys));
        assertEquals("\u001bOA\u001bOB\u001bOC\u001bOD\u001bOH\u001bOF",
                new String(KeyNames.encode(keys, true), StandardCharsets.UTF_8));
        assertEquals("\u001b[3~", new String(KeyNames.encode(List.of("Delete"), true), StandardCharsets.UTF_8),
                "editing keys do not change with DECCKM");
    }

    @Test
    void functionKeys() {
        assertEquals("\u001bOP\u001bOQ\u001bOR\u001bOS", text(List.of("F1", "F2", "F3", "F4")));
        assertEquals("\u001b[15~\u001b[17~\u001b[18~\u001b[19~\u001b[20~\u001b[21~\u001b[23~\u001b[24~",
                text(List.of("F5", "F6", "F7", "F8", "F9", "F10", "F11", "F12")));
    }

    @Test
    void controlKeys() {
        assertEquals("\u0003", text(List.of("C-c")));
        assertEquals("\u0003", text(List.of("C-C")));
        assertEquals("\u0001\u001a", text(List.of("c-a", "C-z")));
        assertEquals("\u0000\u0000", text(List.of("C-Space", "C-@")));
        assertEquals("\u001b\u001c\u001d\u001e\u001f", text(List.of("C-[", "C-\\", "C-]", "C-^", "C-_")));
        assertEquals("\u007f", text(List.of("C-?")));
    }

    @Test
    void metaPrefixesEscRecursively() {
        assertEquals("\u001bb", text(List.of("M-b")));
        assertEquals("\u001b\r", text(List.of("M-Enter")));
        assertEquals("\u001b\u0018", text(List.of("M-C-x")));
        assertEquals("\u001b\u001bOA", new String(KeyNames.encode(List.of("M-Up"), true), StandardCharsets.UTF_8));
    }

    @Test
    void unknownKeysAreRejectedByName() {
        for (String bad : new String[]{"Bogus", "", "C-", "C-1", "C-ab", "M-", "M-Bogus", "F13", "ab"}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> KeyNames.encode(List.of("a", bad), false), bad);
            assertEquals("unknown key: " + bad, e.getMessage());
        }
    }

    private static String text(List<String> keys) {
        return new String(KeyNames.encode(keys, false), StandardCharsets.UTF_8);
    }
}
