package io.github.ghosthack.jmux.vt;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** See SESSION-HOST.md "SGR normalisation" and "Tests (server)" → {@code SgrNormalizerTest}. */
class SgrNormalizerTest {

    private static VtToken csi(String seq) {
        return new VtToken(VtToken.Kind.CSI, seq.getBytes(StandardCharsets.US_ASCII));
    }

    private static String normalized(String seq) {
        return SgrNormalizer.normalize(csi(seq)).asString();
    }

    @Test
    void rgbWithEmptyColourSpaceId() {
        assertEquals("[38;2;10;20;30m", normalized("[38:2::10:20:30m"));
    }

    @Test
    void rgbWithoutColourSpaceId() {
        assertEquals("[48;2;1;2;3m", normalized("[48:2:1:2:3m"));
    }

    @Test
    void indexed256() {
        assertEquals("[38;5;9m", normalized("[38:5:9m"));
    }

    @Test
    void underlineStyleToPlainUnderline() {
        assertEquals("[4m", normalized("[4:3m"));
    }

    @Test
    void underlineOffFromSubparameterZero() {
        assertEquals("[24m", normalized("[4:0m"));
    }

    @Test
    void underlineColourDropped() {
        // 58:… is dropped; the surrounding parameters survive.
        assertEquals("[1;4m", normalized("[1;4;58:2::1:2:3m"));
    }

    @Test
    void underlineColourResetDropped() {
        // 59 (default underline colour) is dropped when the token is being normalised — i.e.
        // when it carries a colon somewhere. A colon-free token passes through untouched.
        assertEquals("[4m", normalized("[59;4:3m"));
        VtToken colonFree = csi("[1;59m");
        assertSame(colonFree, SgrNormalizer.normalize(colonFree));
    }

    @Test
    void otherSubparametersDropped() {
        assertEquals("[38;5;9;1m", normalized("[38:5:9;1:2m"));
    }

    @Test
    void mixedGroups() {
        assertEquals("[0;38;2;255;0;0;4m", normalized("[0;38:2::255:0:0;4:3m"));
    }

    @Test
    void tokensWithoutColonAreReturnedUnchanged() {
        VtToken t = csi("[38;5;9m");
        assertSame(t, SgrNormalizer.normalize(t));
    }

    @Test
    void nonSgrTokensAreReturnedUnchanged() {
        VtToken t = csi("[6n");
        assertSame(t, SgrNormalizer.normalize(t));
        VtToken text = new VtToken(VtToken.Kind.TEXT, "a:b".getBytes(StandardCharsets.US_ASCII));
        assertSame(text, SgrNormalizer.normalize(text));
    }
}
