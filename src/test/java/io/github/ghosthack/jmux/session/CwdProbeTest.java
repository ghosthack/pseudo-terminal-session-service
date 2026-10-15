package io.github.ghosthack.jmux.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SESSION-HOST.md "Tests (server)" → {@code CwdProbeTest}: the lsof parser and a live probe. */
class CwdProbeTest {

    @Test
    void parsesLsofFieldOutput() {
        String out = "p4242\nfcwd\nn/private/tmp/some dir\n";
        assertEquals(Optional.of(Path.of("/private/tmp/some dir")), CwdProbe.parseLsof(out));
    }

    @Test
    void ignoresOtherDescriptorsAndEmptyOutput() {
        String mixed = "p4242\nftxt\nn/usr/bin/sleep\nfcwd\nn/tmp/x\nf0\nn/dev/ttys001\n";
        assertEquals(Optional.of(Path.of("/tmp/x")), CwdProbe.parseLsof(mixed));
        assertEquals(Optional.empty(), CwdProbe.parseLsof("p4242\nftxt\nn/usr/bin/sleep\n"));
        assertEquals(Optional.empty(), CwdProbe.parseLsof(""));
    }

    @Test
    void unknownPidIsEmpty() {
        // No process on any platform has this pid; every strategy must fall through cleanly.
        assertEquals(Optional.empty(), CwdProbe.cwdOf(Integer.MAX_VALUE - 1));
    }

    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void liveProcessReportsItsDirectory(@TempDir Path tempDir) throws IOException, InterruptedException {
        Process process = new ProcessBuilder("sleep", "10").directory(tempDir.toFile()).start();
        try {
            Optional<Path> cwd = CwdProbe.cwdOf(process.pid());
            assertTrue(cwd.isPresent(), "cwd of live pid " + process.pid());
            // macOS puts temp dirs under /private, which lsof reports resolved.
            assertEquals(tempDir.toRealPath(), cwd.get().toRealPath());
        } finally {
            process.destroyForcibly();
            process.waitFor();
        }
    }
}
