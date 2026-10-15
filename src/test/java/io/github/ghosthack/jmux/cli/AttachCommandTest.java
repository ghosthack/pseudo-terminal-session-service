package io.github.ghosthack.jmux.cli;

import io.github.ghosthack.jmux.client.AttachedSession;
import io.github.ghosthack.jmux.client.SessionServerClient;
import io.github.ghosthack.jmux.server.ServeOptions;
import io.github.ghosthack.jmux.server.SessionManagerFactory;
import io.github.ghosthack.jmux.server.SessionServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AttachCommand}'s size handling against a real in-process server hosting a real
 * {@code /bin/sh} session, observed through a second, plain {@link SessionServerClient}
 * attachment that receives the in-band size announcements. {@code stty} is faked so no
 * test ever touches the developer's terminal.
 */
@EnabledOnOs({OS.MAC, OS.LINUX})
class AttachCommandTest {

    private static final Duration WAIT = Duration.ofSeconds(10);
    private static final String SESSION = "foo";

    @TempDir
    Path home;

    private SessionServer server;
    private SessionServerClient client;
    private AttachedSession observer;
    private final ByteArrayOutputStream observed = new ByteArrayOutputStream();
    private Thread observerReader;

    @BeforeEach
    void startServerAndObserver() throws IOException {
        ServeOptions options = ServeOptions.defaults(home);
        server = new SessionServer(options, SessionManagerFactory.createSessionManager(options));
        assertTrue(server.start());
        client = new SessionServerClient(home, false);
        client.newSession(SESSION, home, 80, 24, List.of("/bin/sh", "-l"));
        observer = client.attach(SESSION, 80, 24);
        observerReader = Thread.ofVirtual().start(() -> {
            byte[] buf = new byte[8192];
            try {
                int n;
                while ((n = observer.read(buf, 0, buf.length)) >= 0) {
                    synchronized (observed) {
                        observed.write(buf, 0, n);
                    }
                }
            } catch (IOException ignored) {
                // closed by the test
            }
        });
    }

    @AfterEach
    void stopEverything() throws Exception {
        observer.close();
        server.close();
        observerReader.join(WAIT.toMillis());
    }

    @Test
    void explicitSizeIsNotOverriddenByTerminalPoll() throws Exception {
        Run run = Run.start(client, new int[]{200, 50}, fakeStty("24 80"));
        await(() -> count(observedText(), "[8;50;200t") == 1);
        // Outwait two poll ticks: a poll that ran would have resized to the terminal's 80x24.
        Thread.sleep(AttachCommand.RESIZE_POLL_MS * 2 + 500);
        run.detach();
        assertEquals(0, run.exitCode());
        String text = observedText();
        assertEquals(1, count(text, "[8;50;200t"), "one announcement, from the attach handshake");
        assertFalse(text.contains("[8;24;80t"), "the stty poll never overrode --size");
        assertTrue(run.stderr().contains("[detached]"), run.stderr());
    }

    @Test
    void terminalPollStillResizesWhenNoSizeGiven() throws Exception {
        // First answer: the attach size (same as the observer's, so no resize on attach);
        // then the terminal "grows" and the poll must forward it.
        Run run = Run.start(client, null, fakeStty("24 80", "30 100"));
        await(() -> observedText().contains("[8;30;100t"));
        run.detach();
        assertEquals(0, run.exitCode());
    }

    // --- helpers -------------------------------------------------------------------

    /** A fake {@code stty}: {@code size} answers from the queue (the last answer repeats),
     * everything else succeeds silently. */
    private static Function<String, Optional<String>> fakeStty(String... sizes) {
        Deque<String> answers = new ArrayDeque<>(List.of(sizes));
        return args -> {
            if (args.equals("size")) {
                String next = answers.size() > 1 ? answers.poll() : answers.peek();
                return Optional.of(next + "\n");
            }
            return Optional.of(args.equals("-g") ? "saved-state" : "");
        };
    }

    private String observedText() {
        synchronized (observed) {
            return observed.toString(StandardCharsets.ISO_8859_1);
        }
    }

    private static int count(String text, String needle) {
        int n = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "condition not met within " + WAIT);
            Thread.sleep(10);
        }
    }

    /** One {@link AttachCommand#run} on its own thread, detached by feeding the detach key. */
    private static final class Run {
        private final DetachWhenReleased stdin = new DetachWhenReleased();
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private final ByteArrayOutputStream err = new ByteArrayOutputStream();
        private final AtomicInteger code = new AtomicInteger(-1);
        private Thread thread;

        static Run start(SessionServerClient client, int[] size, Function<String, Optional<String>> stty) {
            Run run = new Run();
            AttachCommand command = new AttachCommand(client, run.stdin, run.out, new PrintStream(run.err, true), stty);
            run.thread = Thread.ofPlatform().daemon().start(() -> {
                try {
                    run.code.set(command.run(SESSION, size));
                } catch (IOException e) {
                    throw new AssertionError(e);
                }
            });
            return run;
        }

        void detach() throws InterruptedException {
            stdin.release.countDown();
            thread.join(WAIT.toMillis());
            assertFalse(thread.isAlive(), "attach did not end after the detach key");
        }

        int exitCode() {
            return code.get();
        }

        String stderr() {
            return err.toString(StandardCharsets.UTF_8);
        }
    }

    /** Blocks until released, then yields the detach key once and EOF after it. */
    private static final class DetachWhenReleased extends InputStream {
        final CountDownLatch release = new CountDownLatch(1);
        private boolean delivered;

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (delivered || len == 0) {
                return delivered ? -1 : 0;
            }
            try {
                if (!release.await(WAIT.toMillis() * 3, TimeUnit.MILLISECONDS)) {
                    return -1;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return -1;
            }
            delivered = true;
            b[off] = AttachCommand.DETACH_KEY;
            return 1;
        }
    }
}
