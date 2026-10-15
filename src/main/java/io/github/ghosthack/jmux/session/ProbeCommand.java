package io.github.ghosthack.jmux.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs a short diagnostic command ({@code ps}, {@code lsof}) for the probes and returns
 * its stdout. Never throws: a missing tool, a failure or a timeout is an empty result, and
 * the probe falls through to its next strategy. Only ever called from user actions and
 * the status poll, never from the pump or under a session lock.
 *
 * <p>The timeout is armed <em>before</em> the read: {@code readAllBytes} only returns once
 * the child closes its stdout, so a child that hangs with the pipe open ({@code lsof}
 * stuck in {@code stat()} on a dead network mount) would otherwise block the caller for
 * as long as the mount does. The reaper kills the command and its live descendants,
 * closing inherited stdout handles as well. Killing only a shell wrapper leaves its
 * children holding the pipe open on Linux.
 */
final class ProbeCommand {

    private static final Logger log = LoggerFactory.getLogger(ProbeCommand.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    /** One daemon thread that fires the deadline of whichever probe is overdue. */
    private static final ScheduledExecutorService REAPER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "probe-reaper");
        t.setDaemon(true);
        return t;
    });

    /** Stdout of {@code command}, regardless of exit code (macOS {@code ps -p} exits 1 when
     * any listed pid is gone but still prints the rest); empty when it could not run,
     * timed out or printed nothing. */
    static Optional<String> output(List<String> command) {
        return output(command, TIMEOUT);
    }

    /** {@link #output(List)} with an explicit deadline; package-private for the tests. */
    static Optional<String> output(List<String> command, Duration timeout) {
        Process process;
        try {
            process = new ProcessBuilder(command)
                    .redirectErrorStream(false)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (IOException e) {
            log.debug("cannot run {}: {}", command.get(0), e.toString());
            return Optional.empty();
        }
        AtomicBoolean timedOut = new AtomicBoolean();
        ScheduledFuture<?> kill = REAPER.schedule(() -> {
            timedOut.set(true);
            terminate(process);
        }, timeout.toMillis(), TimeUnit.MILLISECONDS);
        try {
            process.getOutputStream().close();
            // Read before waiting: a pipe that fills up would otherwise deadlock the child.
            String out;
            try (InputStream in = process.getInputStream()) {
                out = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            process.waitFor(); // stdout is closed, so this returns promptly
            if (timedOut.get()) {
                log.debug("{} timed out after {}", command.get(0), timeout);
                return Optional.empty();
            }
            return out.isEmpty() ? Optional.empty() : Optional.of(out);
        } catch (IOException e) {
            log.debug("reading {} failed: {}", command.get(0), e.toString());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            terminate(process);
            return Optional.empty();
        } finally {
            kill.cancel(false);
        }
    }

    private static void terminate(Process process) {
        // Snapshot descendants before terminating their parent, while ancestry is known.
        // Kill descendants first so inherited stdout handles cannot keep the read blocked.
        process.descendants().toList().reversed().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private ProbeCommand() {
    }
}
