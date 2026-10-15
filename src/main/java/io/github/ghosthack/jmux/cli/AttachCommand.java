package io.github.ghosthack.jmux.cli;

import io.github.ghosthack.jmux.client.AttachedSession;
import io.github.ghosthack.jmux.client.SessionServerClient;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * {@code attach NAME} on a Unix console: the controlling terminal goes raw through
 * {@code stty} on {@code /dev/tty} (restored on every exit path, a shutdown hook
 * included), stdin is pumped to the session and session output to stdout, Ctrl-]
 * ({@code 0x1d}) detaches, and — there being no SIGWINCH for a JVM — the terminal size
 * is polled with {@code stty size} once a second and forwarded when it changes. An
 * explicit {@code --size} pins the session size for this attach and skips that poll
 * (its first tick would otherwise overwrite the flag with the terminal's real size).
 * Size announcements from other clients are printed as a notice rather than replayed.
 */
final class AttachCommand {

    static final byte DETACH_KEY = 0x1d;
    static final long RESIZE_POLL_MS = 1000;
    private static final long STTY_TIMEOUT_SECONDS = 5;

    private final SessionServerClient client;
    private final InputStream stdin;
    private final OutputStream stdout;
    private final PrintStream stderr;
    /** {@code stty <args>} on the controlling terminal; injectable so a test can drive
     * the size poll without touching (or needing) a real tty. */
    private final Function<String, Optional<String>> stty;

    AttachCommand(SessionServerClient client, InputStream stdin, OutputStream stdout, PrintStream stderr) {
        this(client, stdin, stdout, stderr, AttachCommand::runStty);
    }

    AttachCommand(SessionServerClient client, InputStream stdin, OutputStream stdout, PrintStream stderr,
                  Function<String, Optional<String>> stty) {
        this.client = client;
        this.stdin = stdin;
        this.stdout = stdout;
        this.stderr = stderr;
        this.stty = stty;
    }

    /** Exit code. {@code size} null means "ask the terminal" (and follow it). */
    int run(String name, int[] size) throws IOException {
        int[] initial = size != null ? size : ttySize().orElse(new int[]{80, 24});
        AttachedSession session = client.attach(name, initial[0], initial[1]);
        String saved = stty.apply("-g").orElse(null);
        Thread restore = new Thread(() -> restoreTty(saved), "hss-restore-tty");
        Runtime.getRuntime().addShutdownHook(restore);
        AtomicBoolean detached = new AtomicBoolean();
        try {
            stty.apply("raw -echo");
            Thread input = Thread.ofPlatform().daemon().name("hss-stdin").start(() -> pumpInput(session, detached));
            // An explicit --size pins the size: no poll, or its first tick would resize the
            // session (and reflow every other client) to whatever the terminal really is.
            Thread resize = size == null
                    ? Thread.ofVirtual().name("hss-resize-poll").start(() -> pollSize(session, initial))
                    : null;
            pumpOutput(session);
            if (resize != null) {
                resize.interrupt();
            }
            input.interrupt();
        } finally {
            session.close();
            Runtime.getRuntime().removeShutdownHook(restore);
            restoreTty(saved);
        }
        if (session.exitCode().isPresent()) {
            stderr.println("[session " + name + " exited with " + session.exitCode().getAsInt() + "]");
        } else {
            stderr.println(detached.get() ? "[detached]" : "[connection closed]");
        }
        return 0;
    }

    private void pumpOutput(AttachedSession session) throws IOException {
        SizeAnnouncementFilter filter = new SizeAnnouncementFilter((columns, rows) -> {
            try {
                stdout.write(("\r\n[session resized to " + columns + "x" + rows + " by another client]\r\n")
                        .getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // stdout gone: the read loop ends on its own next.
            }
        });
        byte[] buf = new byte[8192];
        while (true) {
            int n;
            try {
                n = session.read(buf, 0, buf.length);
            } catch (IOException e) {
                stderr.println("[" + e.getMessage() + "]");
                break;
            }
            if (n < 0) {
                break;
            }
            stdout.write(filter.filter(buf, 0, n));
            stdout.flush();
        }
        stdout.write(filter.flush());
        stdout.flush();
    }

    private void pumpInput(AttachedSession session, AtomicBoolean detached) {
        byte[] buf = new byte[1024];
        try {
            while (true) {
                int n = stdin.read(buf);
                if (n < 0) {
                    return;
                }
                int end = n;
                for (int i = 0; i < n; i++) {
                    if (buf[i] == DETACH_KEY) {
                        end = i;
                        break;
                    }
                }
                if (end > 0) {
                    byte[] chunk = new byte[end];
                    System.arraycopy(buf, 0, chunk, 0, end);
                    session.write(chunk);
                }
                if (end < n) {
                    detached.set(true);
                    session.close();
                    return;
                }
            }
        } catch (IOException e) {
            // The attachment ended (or stdin did): the output pump notices and returns.
        }
    }

    private void pollSize(AttachedSession session, int[] initial) {
        int[] last = initial;
        try {
            while (session.isConnected()) {
                Thread.sleep(RESIZE_POLL_MS);
                Optional<int[]> size = ttySize();
                if (size.isPresent() && (size.get()[0] != last[0] || size.get()[1] != last[1])) {
                    last = size.get();
                    session.resize(last[0], last[1]);
                }
            }
        } catch (InterruptedException | IOException ignored) {
            // Detached: stop polling.
        }
    }

    private void restoreTty(String saved) {
        if (saved != null) {
            stty.apply(saved);
        } else {
            stty.apply("sane");
        }
    }

    /** {columns, rows} of the controlling terminal, when {@code stty size} answers. */
    private Optional<int[]> ttySize() {
        return stty.apply("size").flatMap(out -> {
            String[] parts = out.strip().split("\\s+");
            try {
                if (parts.length == 2) {
                    int rows = Integer.parseInt(parts[0]);
                    int columns = Integer.parseInt(parts[1]);
                    if (rows > 0 && columns > 0) {
                        return Optional.of(new int[]{columns, rows});
                    }
                }
            } catch (NumberFormatException ignored) {
                // fall through
            }
            return Optional.empty();
        });
    }

    /** Runs {@code stty <args> < /dev/tty}; empty on failure (no tty, non-zero exit). */
    private static Optional<String> runStty(String args) {
        try {
            Process p = new ProcessBuilder(List.of("sh", "-c", "stty " + args + " < /dev/tty"))
                    .redirectErrorStream(true)
                    .start();
            byte[] output = p.getInputStream().readAllBytes();
            if (!p.waitFor(STTY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return Optional.empty();
            }
            return p.exitValue() == 0 ? Optional.of(new String(output, StandardCharsets.UTF_8)) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }
}
