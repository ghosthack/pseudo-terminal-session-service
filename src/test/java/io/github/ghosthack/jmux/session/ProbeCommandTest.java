package io.github.ghosthack.jmux.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ProbeCommand}'s contract: stdout regardless of exit code, and a deadline that
 * holds even when the child keeps its stdout open (the {@code lsof}-on-a-dead-mount case).
 */
@EnabledOnOs({OS.MAC, OS.LINUX})
class ProbeCommandTest {

    @Test
    void childThatKeepsStdoutOpenIsKilledAtTheDeadline() {
        long t0 = System.nanoTime();
        Optional<String> out = ProbeCommand.output(List.of("sh", "-c", "echo partial; sleep 30 & wait"), Duration.ofMillis(300));
        long tookMs = (System.nanoTime() - t0) / 1_000_000;
        assertEquals(Optional.empty(), out, "a timeout is an empty result, not the partial output");
        assertTrue(tookMs < 5000, "the deadline was enforced while reading, took " + tookMs + " ms");
    }

    @Test
    void fastChildIsNotReaped() {
        assertEquals(Optional.of("ok\n"), ProbeCommand.output(List.of("sh", "-c", "echo ok"), Duration.ofSeconds(5)));
    }

    @Test
    void outputIsReturnedRegardlessOfExitCode() {
        assertEquals(Optional.of("partial\n"),
                ProbeCommand.output(List.of("sh", "-c", "echo partial; exit 1"), Duration.ofSeconds(5)));
    }
}
