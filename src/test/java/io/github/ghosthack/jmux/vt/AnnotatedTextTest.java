package io.github.ghosthack.jmux.vt;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** {@link HeadlessTerminal#annotatedText()} through the real pipeline ({@link VtPipe}). */
class AnnotatedTextTest {

    private static final String HIDE_CURSOR = "\u001b[?25l";

    @Test
    void plainScreenMatchesScreenTextWhenTheCursorIsHidden() {
        VtPipe pipe = new VtPipe(20, 4, 0);
        pipe.feed("one\r\ntwo  \r\n" + HIDE_CURSOR);
        assertEquals(pipe.terminal.screenText(), pipe.terminal.annotatedText());
        assertEquals("one\ntwo\n\n\n", pipe.terminal.annotatedText());
    }

    @Test
    void reverseVideoRowIsMarked() {
        VtPipe pipe = new VtPipe(20, 6, 0);
        pipe.feed("  apple\r\n\u001b[7m  banana\u001b[0m\r\n  cherry\r\n  damson\r\n" + HIDE_CURSOR);
        assertEquals(List.of("  apple", "▶   banana", "  cherry", "  damson", "", ""), rows(pipe));
    }

    @Test
    void backgroundColouredRowIsMarked() {
        VtPipe pipe = new VtPipe(20, 6, 0);
        // The highlight is a coloured run of spaces with the text inside it.
        pipe.feed("  apple\r\n\u001b[44m  banana        \u001b[0m\r\n  cherry\r\n  damson\r\n" + HIDE_CURSOR);
        assertEquals(List.of("  apple", "▶   banana", "  cherry", "  damson", "", ""), rows(pipe));
    }

    @Test
    void fullScreenColourThemeHasNoSelection() {
        VtPipe pipe = new VtPipe(10, 4, 0);
        // Every row on a blue background, one row on a second colour: the dominant
        // background is blue, so only the odd row would differ...
        pipe.feed("\u001b[44m\u001b[2J\u001b[Hab\r\n\u001b[42mcd\u001b[44m\r\nef\r\ngh" + HIDE_CURSOR);
        assertEquals(List.of("ab", "▶ cd", "ef", "gh"), rows(pipe));
        // ...but when more than a third of the rows stand out, it is a theme, not a selection.
        VtPipe themed = new VtPipe(10, 4, 0);
        themed.feed("\u001b[44m\u001b[2J\u001b[Hab\r\n\u001b[42mcd\r\nef\u001b[44m\r\ngh" + HIDE_CURSOR);
        assertEquals(List.of("ab", "cd", "ef", "gh"), rows(themed));
        VtPipe allInverse = new VtPipe(10, 3, 0);
        allInverse.feed("\u001b[7mab\r\ncd\r\nef" + HIDE_CURSOR);
        assertFalse(allInverse.terminal.annotatedText().contains("▶"));
    }

    @Test
    void cursorInTheMiddleOfText() {
        VtPipe pipe = new VtPipe(20, 3, 0);
        pipe.feed("hello world\u001b[1;6H");
        assertEquals("hello▮ world", rows(pipe).get(0));
    }

    @Test
    void cursorAfterTextAndPastTrailingSpace() {
        VtPipe pipe = new VtPipe(20, 3, 0);
        pipe.feed("$ ");
        assertEquals("$ ▮", rows(pipe).get(0), "trailing whitespace before the cursor is kept");
        pipe.feed("\r\nab\u001b[2;8H");
        assertEquals(List.of("$", "ab     ▮", ""), rows(pipe), "padded out to the cursor column");
    }

    @Test
    void cursorAtPendingWrapSitsOnTheLastColumn() {
        VtPipe pipe = new VtPipe(5, 3, 0);
        pipe.feed("abcde");
        assertEquals("abcd▮e", rows(pipe).get(0));
    }

    @Test
    void hiddenCursorIsNotMarked() {
        VtPipe pipe = new VtPipe(20, 3, 0);
        pipe.feed("text" + HIDE_CURSOR);
        assertFalse(pipe.terminal.annotatedText().contains("▮"));
        pipe.feed("\u001b[?25h");
        assertEquals("text▮", rows(pipe).get(0));
    }

    @Test
    void wideCharactersMapBufferColumnsToTextIndices() {
        VtPipe pipe = new VtPipe(20, 3, 0);
        pipe.feed("中文x");
        // 中 and 文 take two cells each, so x is at column 5 and the cursor at column 6.
        assertEquals("中文x▮", rows(pipe).get(0));
        pipe.feed("\u001b[1;5H");
        assertEquals("中文▮x", rows(pipe).get(0));
        assertEquals(pipe.terminal.screenText().replace("▮", ""),
                pipe.terminal.annotatedText().replace("▮", ""));
    }

    @Test
    void markerAndSelectionTogether() {
        VtPipe pipe = new VtPipe(20, 6, 0);
        pipe.feed("a\r\n\u001b[7m> b\u001b[0m\r\nc\r\nd\u001b[2;2H");
        assertEquals("▶ >▮ b", rows(pipe).get(1));
    }

    private static List<String> rows(VtPipe pipe) {
        String text = pipe.terminal.annotatedText();
        assertEquals('\n', text.charAt(text.length() - 1));
        return List.of(text.substring(0, text.length() - 1).split("\n", -1));
    }
}
