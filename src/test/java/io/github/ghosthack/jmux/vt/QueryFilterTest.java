package io.github.ghosthack.jmux.vt;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** See SESSION-HOST.md "Query policy" and "Tests (server)" → {@code QueryFilterTest}: every
 * row of the policy table. */
class QueryFilterTest {

    private static boolean stripped(String seq) {
        VtToken.Kind kind = VtToken.Kind.CSI;
        if (!seq.startsWith("[")) {
            kind = VtToken.Kind.TEXT;
        }
        return QueryFilter.isServerAnswered(new VtToken(kind, seq.getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void da1IsStripped() {
        assertTrue(stripped("[c"));
        assertTrue(stripped("[0c"));
        assertTrue(stripped("[=c")); // CSI = c is still primary DA in jediterm
    }

    @Test
    void da2AbsentOrZeroIsStripped() {
        assertTrue(stripped("[>c"));
        assertTrue(stripped("[>0c"));
        assertTrue(stripped("[>0;1c"));
    }

    @Test
    void da2NonZeroPassesThrough() {
        assertFalse(stripped("[>1c"));
    }

    @Test
    void dsrCprStripped() {
        assertTrue(stripped("[5n")); // device status
        assertTrue(stripped("[6n")); // cursor position report
    }

    @Test
    void decxcprPassesThrough() {
        assertFalse(stripped("[?6n"));
    }

    @Test
    void colorSchemeQueryPassesThrough() {
        assertFalse(stripped("[?996n"));
    }

    @Test
    void dsrWithoutFiveOrSixPassesThrough() {
        assertFalse(stripped("[n"));
        assertFalse(stripped("[0n"));
    }

    @Test
    void xtwinops8Stripped() {
        assertTrue(stripped("[8;24;80t"));
    }

    @Test
    void otherXtwinopsPassThrough() {
        assertFalse(stripped("[22;0t")); // save title on stack
        assertFalse(stripped("[18t"));   // report size in chars
    }

    @Test
    void unrelatedSequencesPassThrough() {
        assertFalse(stripped("[?2004h")); // bracketed paste
        assertFalse(stripped("[38;5;9m")); // SGR
        assertFalse(stripped("[>0;1;0c" .replace(">0", ">1"))); // DA2 non-zero already covered
        assertFalse(stripped("[?6;1$y")); // DECRQM report body
    }

    @Test
    void nonCsiNeverStripped() {
        assertFalse(QueryFilter.isServerAnswered(
                new VtToken(VtToken.Kind.OSC, "]10;?".getBytes(StandardCharsets.US_ASCII))));
    }
}
