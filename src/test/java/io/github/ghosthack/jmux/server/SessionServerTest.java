package io.github.ghosthack.jmux.server;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.ghosthack.jmux.client.AttachedSession;
import io.github.ghosthack.jmux.client.SessionServerClient;
import io.github.ghosthack.jmux.cli.Cli;
import io.github.ghosthack.jmux.client.SessionServerException;
import io.github.ghosthack.jmux.protocol.Frame;
import io.github.ghosthack.jmux.protocol.FrameCodec;
import io.github.ghosthack.jmux.protocol.FrameType;
import io.github.ghosthack.jmux.protocol.Json;
import io.github.ghosthack.jmux.protocol.Protocol;
import io.github.ghosthack.jmux.session.CaptureFormat;
import io.github.ghosthack.jmux.session.Session;
import io.github.ghosthack.jmux.session.SessionEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The transport end to end: a real {@link SessionServer} bound in-process on a temporary
 * home, driven through the real {@link SessionServerClient} with spawning disabled, over
 * {@link FakeSessionManager}. Nothing here touches the user's home or forks a process.
 */
class SessionServerTest {

    private static final Duration WAIT = Duration.ofSeconds(10);

    private final List<Path> homes = new ArrayList<>();
    private final List<SessionServer> servers = new ArrayList<>();
    private final List<AutoCloseable> closeables = new ArrayList<>();
    private Path home;
    private FakeSessionManager manager;
    private SessionServer server;
    private SessionServerClient client;

    @BeforeEach
    void startServer() throws IOException {
        home = newHome();
        manager = new FakeSessionManager();
        server = start(ServeOptions.defaults(home), manager);
        client = new SessionServerClient(home, false);
    }

    @AfterEach
    void stopEverything() throws Exception {
        for (AutoCloseable c : closeables) {
            c.close();
        }
        for (SessionServer s : servers) {
            s.close();
        }
        for (Path h : homes) {
            try (Stream<Path> tree = Files.walk(h)) {
                tree.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    // ---- control operations

    @Test
    void pingReportsProtocolVersionAndPid() {
        Optional<SessionServerClient.ServerInfo> info = client.ping();
        assertTrue(info.isPresent());
        assertEquals(Protocol.VERSION, info.get().protocol());
        assertEquals(ServerVersion.current(), info.get().version());
        assertEquals(ProcessHandle.current().pid(), info.get().pid());
        assertTrue(client.isServerRunning());
    }

    @Test
    void deadHomeAnswersLikeNoTmuxServerAndNeverSpawns() throws IOException {
        Path dead = newHome();
        SessionServerClient deadClient = new SessionServerClient(dead, false);
        assertFalse(deadClient.isServerRunning());
        assertTrue(deadClient.ping().isEmpty());
        assertEquals(List.of(), deadClient.list());
        assertFalse(deadClient.has("x"));
        assertTrue(deadClient.capture("x").isEmpty());
        deadClient.kill("x");
        deadClient.shutdown();
        assertTrue(assertThrows(SessionServerException.class, () -> deadClient.sendKeys("x", "hi", true)).isNoServer());
        assertTrue(assertThrows(SessionServerException.class, () -> deadClient.currentPath("x")).isNoServer());
        assertTrue(assertThrows(SessionServerException.class, () -> deadClient.attach("x", 80, 24)).isNoServer());
        SessionServerException spawnRefused = assertThrows(SessionServerException.class,
                () -> deadClient.newSession("x", dead, 80, 24, null));
        assertTrue(spawnRefused.isNoServer());
        assertFalse(Files.exists(ServerHome.log(dead)), "a spawn would have created server.log");
        assertFalse(Files.exists(ServerHome.socket(dead)));
    }

    @Test
    void newHasListSendKeysCaptureCurrentPathKill() {
        client.newSession("s1", home, 100, 30, List.of("bash", "-l"));
        FakeSession fake = manager.session("s1");
        assertEquals(100, fake.columns);
        assertEquals(30, fake.rows);
        assertEquals(List.of("bash", "-l"), fake.command());
        assertEquals(home, fake.currentPath());

        assertTrue(client.has("s1"));
        assertFalse(client.has("nope"));
        List<Session.Info> listed = client.list();
        assertEquals(1, listed.size());
        assertEquals("s1", listed.get(0).name());
        assertEquals(home, listed.get(0).workdir());
        assertEquals(4242, listed.get(0).pid());
        assertEquals("fake", listed.get(0).backend());
        assertTrue(listed.get(0).alive());

        client.sendKeys("s1", "echo hi", true);
        client.sendKeys("s1", "q", false);
        assertEquals(List.of("echo hi<CR>", "q"), fake.sentKeys);

        Session.Capture capture = client.capture("s1").orElseThrow();
        assertEquals(FakeSession.CAPTURE_TEXT, capture.text());
        assertEquals("sh", capture.foregroundCommand());
        assertEquals(4242, capture.processId());
        assertEquals("the title", capture.title());
        assertEquals(100, capture.columns());
        assertEquals(30, capture.rows());
        assertTrue(client.capture("nope").isEmpty(), "a missing session captures as empty, like a dead pane");

        assertEquals(home, client.currentPath("s1"));
        SessionServerException missing = assertThrows(SessionServerException.class, () -> client.currentPath("nope"));
        assertFalse(missing.isNoServer());
        assertTrue(missing.getMessage().contains("no such session"), missing.getMessage());

        client.kill("s1");
        assertTrue(fake.killed);
        assertFalse(client.has("s1"));
        client.kill("s1"); // already gone is not an error
        assertEquals(List.of(), client.list());
    }

    @Test
    void duplicateNameIsRefused() {
        client.newSession("dup", home, 80, 24, null);
        SessionServerException e = assertThrows(SessionServerException.class,
                () -> client.newSession("dup", home, 80, 24, null));
        assertTrue(e.getMessage().contains("already exists"), e.getMessage());
        assertFalse(e.isNoServer());
    }

    @Test
    void controlConnectionCarriesSeveralRequests() throws IOException {
        try (SocketChannel channel = connect(home)) {
            FrameCodec codec = codec(channel);
            for (int i = 0; i < 3; i++) {
                codec.write(Frame.json(FrameType.REQUEST, "{\"op\":\"ping\",\"protocol\":1}"));
                JsonNode reply = Json.parse(codec.read().payload());
                assertTrue(reply.get(Protocol.F_OK).asBoolean());
            }
            codec.write(Frame.json(FrameType.REQUEST, "{\"op\":\"bogus\",\"protocol\":1}"));
            JsonNode reply = Json.parse(codec.read().payload());
            assertFalse(reply.get(Protocol.F_OK).asBoolean());
            assertTrue(reply.get(Protocol.F_ERROR).asText().contains("unknown op"));
        }
    }

    @Test
    void protocolMismatchIsAnsweredWithIdentityFieldsAndClosesTheConnection() throws IOException {
        try (SocketChannel channel = connect(home)) {
            FrameCodec codec = codec(channel);
            codec.write(Frame.json(FrameType.REQUEST, "{\"op\":\"ping\",\"protocol\":99}"));
            Frame reply = codec.read();
            assertEquals(FrameType.RESPONSE, reply.type());
            JsonNode body = Json.parse(reply.payload());
            assertFalse(body.get(Protocol.F_OK).asBoolean());
            assertEquals(Protocol.ERROR_PROTOCOL_MISMATCH, body.get(Protocol.F_ERROR).asText());
            assertEquals(Protocol.VERSION, body.get(Protocol.F_PROTOCOL).asInt());
            assertEquals(ServerVersion.current(), body.get(Protocol.F_VERSION).asText());
            assertEquals(ProcessHandle.current().pid(), body.get(Protocol.F_PID).asLong());
            assertNull(codec.read(), "the server closes after a mismatch");
        }
        try (SocketChannel channel = connect(home)) {
            FrameCodec codec = codec(channel);
            codec.write(Frame.json(FrameType.REQUEST, "{\"op\":\"ping\"}"));
            JsonNode body = Json.parse(codec.read().payload());
            assertEquals(Protocol.ERROR_PROTOCOL_MISMATCH, body.get(Protocol.F_ERROR).asText(),
                    "a request without the field is a mismatch too");
        }
    }

    @Test
    void clientTurnsMismatchIntoTheDesignedMessageWithoutSpawning() throws Exception {
        Path other = newHome();
        try (MismatchServer mismatch = MismatchServer.start(ServerHome.socket(other), 7, "9.9.9", 777)) {
            // Spawning enabled on purpose: a mismatch is a connected outcome and must not spawn.
            SessionServerClient spawning = new SessionServerClient(other, true);
            SessionServerException e = assertThrows(SessionServerException.class,
                    () -> spawning.newSession("x", other, 80, 24, null));
            assertEquals("jmux 9.9.9 (pid 777) speaks protocol 7; this app needs "
                    + Protocol.VERSION + ". Finish its sessions or run `jmux shutdown`, then retry",
                    e.getMessage());
            assertFalse(e.isNoServer());
            assertThrows(SessionServerException.class, spawning::list);
            assertFalse(Files.exists(ServerHome.log(other)), "no spawn happened");
            assertTrue(mismatch.isOpen());
        }
    }

    // ---- attach mode

    @Test
    void attachReceivesSnapshotThenLiveBytesInOrder() throws Exception {
        client.newSession("a", home, 80, 24, null);
        FakeSession fake = manager.session("a");
        AttachedSession attached = attach("a", 120, 40);
        assertEquals("<<SNAPSHOT>>", readText(attached, FakeSession.SNAPSHOT.length));
        // The fake hands the snapshot over before it records the client, and the writer can
        // deliver it in between: wait for the registration rather than assert it.
        Session.Client registered = awaitAttached(fake, 1);
        assertEquals(1, fake.attached.size());
        assertEquals(new FakeSession.Resize(120, 40, registered), fake.resizes.get(0),
                "attach resizes to the client's size, announcing to the others only");

        fake.push("hello ");
        fake.push("world");
        assertEquals("hello world", readText(attached, 11));
        assertTrue(attached.isConnected());
        assertTrue(attached.exitCode().isEmpty());
    }

    @Test
    void dataInReachesSessionWrite() throws Exception {
        client.newSession("w", home, 80, 24, null);
        FakeSession fake = manager.session("w");
        AttachedSession attached = attach("w", 80, 24);
        readText(attached, FakeSession.SNAPSHOT.length);
        attached.write("ls -la\r".getBytes(StandardCharsets.UTF_8));
        attached.write("x".getBytes(StandardCharsets.UTF_8));
        await(() -> fake.writtenText().equals("ls -la\rx"));
    }

    @Test
    void resizeReachesSessionWithThisClientExcepted() throws Exception {
        client.newSession("r", home, 80, 24, null);
        FakeSession fake = manager.session("r");
        AttachedSession first = attach("r", 80, 24);
        Session.Client firstServerSide = awaitAttached(fake, 1);
        AttachedSession second = attach("r", 80, 24);
        awaitAttached(fake, 2);
        readText(first, FakeSession.SNAPSHOT.length);
        readText(second, FakeSession.SNAPSHOT.length);
        assertEquals(0, fake.resizes.size(), "same size: no resize on attach");

        first.resize(132, 50);
        await(() -> fake.resizes.size() == 1);
        FakeSession.Resize resize = fake.resizes.get(0);
        assertEquals(132, resize.columns());
        assertEquals(50, resize.rows());
        assertSame(firstServerSide, resize.except());
        assertEquals("[8;50;132t", readText(second, 11), "the other client gets the in-band announcement");
    }

    @Test
    void detachRemovesTheClientAndEndsTheStream() throws Exception {
        client.newSession("d", home, 80, 24, null);
        FakeSession fake = manager.session("d");
        AttachedSession attached = attach("d", 80, 24);
        readText(attached, FakeSession.SNAPSHOT.length);
        Session.Client serverSide = fake.attached.get(0);
        attached.close();
        attached.close(); // idempotent
        await(() -> fake.detached.contains(serverSide));
        assertEquals(0, fake.clients.size());
        assertEquals(-1, attached.read(new byte[16], 0, 16));
        assertFalse(attached.isConnected());
        assertTrue(attached.exitCode().isEmpty());
        assertEquals(-1, attached.waitFor());
        assertThrows(IOException.class, () -> attached.write(new byte[]{1}));
        await(() -> client.list().get(0).clients() == 0);
    }

    @Test
    void closeUnblocksAPendingRead() throws Exception {
        client.newSession("u", home, 80, 24, null);
        AttachedSession attached = attach("u", 80, 24);
        readText(attached, FakeSession.SNAPSHOT.length);
        AtomicReference<Integer> result = new AtomicReference<>();
        Thread reader = Thread.ofVirtual().start(() -> {
            try {
                result.set(attached.read(new byte[16], 0, 16));
            } catch (IOException e) {
                result.set(-2);
            }
        });
        Thread.sleep(100);
        attached.close();
        reader.join(WAIT.toMillis());
        assertEquals(-1, result.get());
    }

    @Test
    void sessionExitDeliversTheExitCodeThenEof() throws Exception {
        client.newSession("e", home, 80, 24, null);
        FakeSession fake = manager.session("e");
        AttachedSession attached = attach("e", 80, 24);
        readText(attached, FakeSession.SNAPSHOT.length);
        fake.push("bye");
        fake.exit(3);
        assertEquals("bye", readText(attached, 3), "output queued before the exit still arrives");
        assertEquals(-1, attached.read(new byte[16], 0, 16));
        assertEquals(3, attached.waitFor());
        assertEquals(3, attached.exitCode().orElseThrow());
        assertFalse(attached.isConnected());
        assertFalse(client.has("e"));
    }

    @Test
    void cliCaptureOfMissingSessionOnLiveServerDoesNotClaimNoServer() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = new Cli(InputStream.nullInputStream(), out, new PrintStream(err, true))
                .run("capture", "ghost", "--home", home.toString());
        assertEquals(1, code);
        assertEquals("error: no such session: ghost" + System.lineSeparator(), err.toString());
        assertEquals("", out.toString());
        assertTrue(client.isServerRunning());
    }

    @Test
    void attachToMissingSessionIsRefusedNotNoServer() {
        SessionServerException e = assertThrows(SessionServerException.class, () -> client.attach("ghost", 80, 24));
        assertFalse(e.isNoServer());
        assertEquals("no such session: ghost", e.getMessage());
        assertTrue(client.isServerRunning(), "the control connection's refusal leaves the server fine");
    }

    // ---- slow clients

    @Test
    void slowClientNeverStallsTheOthersAndIsResnapshottedOnceItDrains() throws Exception {
        Path slowHome = newHome();
        FakeSessionManager slowManager = new FakeSessionManager();
        start(ServeOptions.defaults(slowHome).withClientBufferBytes(64 * 1024).withBehindTimeout(Duration.ofMinutes(5)),
                slowManager);
        SessionServerClient slowClient = new SessionServerClient(slowHome, false);
        slowClient.newSession("slow", slowHome, 80, 24, null);
        FakeSession fake = slowManager.session("slow");

        AttachedSession stuck = attach(slowClient, "slow", 80, 24); // never read until later
        AttachedSession healthy = attach(slowClient, "slow", 80, 24);
        await(() -> fake.clients.size() == 2);
        byte[] pushed = new byte[4 * 1024 * 1024];
        for (int i = 0; i < pushed.length; i++) {
            pushed[i] = (byte) ('a' + (i % 26));
        }
        int expectedForHealthy = FakeSession.SNAPSHOT.length + pushed.length;
        ByteArrayOutputStream received = new ByteArrayOutputStream();
        AtomicLong receivedCount = new AtomicLong();
        Thread reader = Thread.ofVirtual().start(() -> {
            try {
                byte[] buf = new byte[65536];
                while (received.size() < expectedForHealthy) {
                    int n = healthy.read(buf, 0, buf.length);
                    if (n < 0) {
                        return;
                    }
                    received.write(buf, 0, n);
                    receivedCount.set(received.size());
                }
            } catch (IOException ignored) {
                // the assertion below reports the shortfall
            }
        });
        // Paced so the healthy client never has more than half its server-side buffer in
        // flight (a reader that keeps up), while the stuck one absorbs nothing at all.
        long pushedSoFar = FakeSession.SNAPSHOT.length;
        long pacingDeadline = System.nanoTime() + WAIT.toNanos();
        for (int off = 0; off < pushed.length; off += 4096) {
            byte[] chunk = new byte[Math.min(4096, pushed.length - off)];
            System.arraycopy(pushed, off, chunk, 0, chunk.length);
            while (pushedSoFar - receivedCount.get() > 32 * 1024) {
                assertTrue(System.nanoTime() < pacingDeadline, "healthy client stopped receiving");
                Thread.sleep(1);
            }
            fake.push(chunk); // must never block, whatever the stuck client does
            pushedSoFar += chunk.length;
        }
        reader.join(WAIT.toMillis());
        assertEquals(expectedForHealthy, received.size(), "the healthy client received everything");
        byte[] all = received.toByteArray();
        assertArrayEquals(FakeSession.SNAPSHOT, Arrays.copyOf(all, FakeSession.SNAPSHOT.length));
        assertArrayEquals(pushed, Arrays.copyOfRange(all, FakeSession.SNAPSHOT.length, all.length));
        assertEquals(2, fake.clients.size(), "the stuck client is still attached (timeout far away)");
        // Socket and inbound-pipe buffers can accept early re-snapshots even while the
        // application is not reading. Assert delivery and ordering, not a platform-
        // dependent number of re-sync requests.
        // Now the stuck client drains and must receive a fresh snapshot.
        String marker = new String(FakeSession.RESNAPSHOT, StandardCharsets.UTF_8);
        StringBuilder seen = new StringBuilder();
        readUntil(stuck, marker, seen);
        assertTrue(seen.toString().startsWith("<<SNAPSHOT>>"), "its original snapshot came first");
        assertTrue(fake.snapshotForCalls.get() >= 1, "a re-sync was requested");
        assertTrue(stuck.isConnected());
        readUntil(stuck, "after-marker", new StringBuilder(), () -> fake.push("after-marker"));
    }

    @Test
    void stuckClientIsDroppedAfterTheBehindTimeout() throws Exception {
        Path slowHome = newHome();
        FakeSessionManager slowManager = new FakeSessionManager();
        start(ServeOptions.defaults(slowHome).withClientBufferBytes(64 * 1024).withBehindTimeout(Duration.ofMillis(300)),
                slowManager);
        SessionServerClient slowClient = new SessionServerClient(slowHome, false);
        slowClient.newSession("stuck", slowHome, 80, 24, null);
        FakeSession fake = slowManager.session("stuck");

        AttachedSession stuck = attach(slowClient, "stuck", 80, 24);
        Session.Client stuckServerSide = awaitAttached(fake, 1);
        AttachedSession healthy = attach(slowClient, "stuck", 80, 24);
        Session.Client healthyServerSide = awaitAttached(fake, 2);
        readText(healthy, FakeSession.SNAPSHOT.length);
        Thread drain = Thread.ofVirtual().start(() -> {
            try {
                byte[] buf = new byte[65536];
                while (healthy.read(buf, 0, buf.length) >= 0) {
                    // keep draining
                }
            } catch (IOException ignored) {
                // done
            }
        });
        byte[] chunk = new byte[4096];
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (!fake.detached.contains(stuckServerSide)) {
            assertTrue(System.nanoTime() < deadline, "stuck client was never dropped");
            fake.push(chunk);
            Thread.sleep(5);
        }
        assertEquals(List.of(healthyServerSide), fake.clients, "only the healthy client remains");
        fake.push("still here");
        await(() -> fake.clients.size() == 1);
        // The dropped client's stream ends (with the error when it got through, else EOF).
        byte[] buf = new byte[65536];
        long end = System.nanoTime() + WAIT.toNanos();
        while (true) {
            assertTrue(System.nanoTime() < end, "dropped client's stream never ended");
            try {
                if (stuck.read(buf, 0, buf.length) < 0) {
                    break;
                }
            } catch (IOException e) {
                assertTrue(e.getMessage().contains("fell behind"), e.getMessage());
                break;
            }
        }
        healthy.close();
        drain.join(WAIT.toMillis());
    }

    // ---- lifecycle

    @Test
    void secondServerOnTheSameHomeExitsWithoutBindingAndTheFirstKeepsServing() throws IOException {
        FakeSessionManager second = new FakeSessionManager();
        try (SessionServer redundant = new SessionServer(ServeOptions.defaults(home), second)) {
            assertFalse(redundant.start(), "the lock is held: exit 0 without touching anything");
            assertFalse(redundant.isRunning());
            assertNull(second.listener, "it never got as far as wiring the manager");
        }
        assertTrue(client.isServerRunning(), "closing the loser did not disturb the owner");
        assertTrue(Files.exists(ServerHome.socket(home)));
        String expectedPid = String.valueOf(ProcessHandle.current().pid());
        // The owner still holds server.lock exclusively here; Windows' mandatory locking
        // (unlike Unix's advisory locking) blocks even a same-process read of the locked
        // bytes through a second handle, so read the diagnostic pid only after releasing it.
        server.close();
        assertEquals(expectedPid, Files.readString(ServerHome.lock(home)).strip());
    }

    @Test
    void staleSocketFileIsRemovedAndBoundOver() throws IOException {
        Path stale = newHome();
        try (ServerSocketChannel leftover = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            leftover.bind(UnixDomainSocketAddress.of(ServerHome.socket(stale)));
        }
        assertTrue(Files.exists(ServerHome.socket(stale)), "the JDK leaves the socket file behind");
        SessionServer fresh = start(ServeOptions.defaults(stale), new FakeSessionManager());
        assertTrue(fresh.isRunning());
        assertTrue(new SessionServerClient(stale, false).isServerRunning());
    }

    @Test
    void shutdownKillsEverythingRemovesTheSocketAndReleasesTheLock() throws Exception {
        client.newSession("s", home, 80, 24, null);
        FakeSession fake = manager.session("s");
        AttachedSession attached = attach("s", 80, 24);
        readText(attached, FakeSession.SNAPSHOT.length);

        client.shutdown();
        assertTrue(manager.killAllCalled);
        assertTrue(fake.killed);
        assertFalse(Files.exists(ServerHome.socket(home)), "socket file removed");
        assertTrue(server.awaitExitWithin(WAIT));
        assertFalse(server.isRunning());
        try (FileChannel lock = FileChannel.open(ServerHome.lock(home), StandardOpenOption.WRITE)) {
            FileLock acquired = lock.tryLock();
            assertNotNull(acquired, "lock released");
            acquired.release();
        }
        assertFalse(client.isServerRunning());
        assertEquals(-1, attached.read(new byte[16], 0, 16), "attached connections were closed");
        client.shutdown(); // no server: a no-op
    }

    @Test
    void lastSessionEndingExitsTheServerUnlessKeptAlive() throws Exception {
        manager.fireIdle();
        assertTrue(server.awaitExitWithin(WAIT));
        assertFalse(Files.exists(ServerHome.socket(home)));

        Path kept = newHome();
        FakeSessionManager keptManager = new FakeSessionManager();
        SessionServer keepAlive = start(ServeOptions.defaults(kept).withKeepAlive(true), keptManager);
        keptManager.fireIdle();
        assertFalse(keepAlive.awaitExitWithin(Duration.ofMillis(300)));
        assertTrue(keepAlive.isRunning());
    }

    // ---- labels, input, capture formats, wait, watch

    @Test
    void labelsAreSetAtCreationListedAndMerged() throws Exception {
        client.newSession("l", home, 80, 24, null, Map.of("harness", "codex", "project", "/p"));
        FakeSession fake = manager.session("l");
        fake.push("12345");
        Session.Info info = client.list().get(0);
        assertEquals(Map.of("harness", "codex", "project", "/p"), info.labels());
        assertEquals(5, info.outputBytes());

        Map<String, String> changes = new HashMap<>();
        changes.put("display", "Reviewer");
        changes.put("project", null);
        assertEquals(Map.of("display", "Reviewer", "harness", "codex"), client.label("l", changes));
        assertEquals(Map.of("display", "Reviewer", "harness", "codex"), fake.labels());
        assertEquals(Map.of("display", "Reviewer", "harness", "codex"), client.list().get(0).labels());

        SessionServerException missing = assertThrows(SessionServerException.class,
                () -> client.label("nope", Map.of("a", "b")));
        assertTrue(missing.getMessage().contains("no such session: nope"), missing.getMessage());

        JsonNode badNew = raw("{\"op\":\"new\",\"protocol\":1,\"name\":\"x\",\"workdir\":\"" + home
                + "\",\"labels\":[\"a\"]}");
        assertFalse(badNew.get(Protocol.F_OK).asBoolean());
        assertTrue(badNew.get(Protocol.F_ERROR).asText().contains("labels must be an object"));
        JsonNode badLabel = raw("{\"op\":\"label\",\"protocol\":1,\"name\":\"l\",\"labels\":{\"a\":1}}");
        assertFalse(badLabel.get(Protocol.F_OK).asBoolean());
        assertTrue(badLabel.get(Protocol.F_ERROR).asText().contains("label a must be a string or null"));
    }

    @Test
    void inputWritesTextThenKeysAndRejectsUnknownKeysBeforeWriting() throws Exception {
        client.newSession("i", home, 80, 24, null);
        FakeSession fake = manager.session("i");
        client.input("i", "hello", false, List.of("Enter", "C-c"));
        assertEquals("hello\r\u0003", fake.writtenText());
        assertEquals(List.of(List.of("Enter", "C-c")), fake.inputs);

        client.input("i", "pasted text", true, null);
        assertEquals(List.of("pasted text"), fake.pastes);
        client.input("i", null, false, List.of("y"));
        assertEquals("hello\r\u0003<paste>pasted text</paste>y", fake.writtenText());

        SessionServerException unknown = assertThrows(SessionServerException.class,
                () -> client.input("i", "half", false, List.of("Enter", "Bogus")));
        assertEquals("unknown key: Bogus", unknown.getMessage());
        assertEquals("hello\r\u0003<paste>pasted text</paste>y", fake.writtenText(), "nothing was written");

        SessionServerException empty = assertThrows(SessionServerException.class,
                () -> client.input("i", null, false, List.of()));
        assertEquals("input needs text or keys", empty.getMessage());
        SessionServerException missing = assertThrows(SessionServerException.class,
                () -> client.input("nope", "x", false, null));
        assertTrue(missing.getMessage().contains("no such session: nope"));
        JsonNode badKeys = raw("{\"op\":\"input\",\"protocol\":1,\"name\":\"i\",\"keys\":\"Enter\"}");
        assertEquals("keys must be an array of key names", badKeys.get(Protocol.F_ERROR).asText());
    }

    @Test
    void requireBracketedPasteRefusesWithoutWritingUnlessTheProgramEnabledIt() throws Exception {
        client.newSession("b", home, 80, 24, null);
        FakeSession fake = manager.session("b");
        SessionServerException refused = assertThrows(SessionServerException.class,
                () -> client.input("b", "multi\nline", true, true, List.of("Enter")));
        assertEquals(Protocol.ERROR_BRACKETED_PASTE_DISABLED, refused.getMessage());
        assertEquals("", fake.writtenText(), "neither the text nor the keys were written");
        assertTrue(fake.pastes.isEmpty());
        assertTrue(fake.inputs.isEmpty());

        // Without the flag the same paste falls back as before; raw text and keys alone are
        // not pastes, so the flag does not affect them.
        client.input("b", "raw", true, false, null);
        client.input("b", "typed", false, true, List.of("Enter"));
        assertEquals("<paste>raw</paste>typed\r", fake.writtenText());

        fake.bracketedPaste = true;
        client.input("b", "multi\nline", true, true, List.of("Enter"));
        assertEquals(List.of("raw", "multi\nline"), fake.pastes);
        assertEquals("<paste>raw</paste>typed\r<paste>multi\nline</paste>\r", fake.writtenText());
    }

    @Test
    void captureFormatsAndTheirExtraFields() throws Exception {
        client.newSession("c", home, 80, 24, null);
        FakeSession fake = manager.session("c");
        fake.push("abc");
        Session.Capture annotated = client.capture("c", CaptureFormat.ANNOTATED).orElseThrow();
        assertEquals(FakeSession.ANNOTATED_TEXT, annotated.text());
        assertEquals(3, annotated.outputBytes());
        assertFalse(annotated.cursorVisible());
        assertEquals(FakeSession.ANSI_TEXT, client.capture("c", CaptureFormat.ANSI).orElseThrow().text());
        assertEquals(FakeSession.CAPTURE_TEXT, client.capture("c").orElseThrow().text());
        assertEquals(List.of(CaptureFormat.ANNOTATED, CaptureFormat.ANSI, CaptureFormat.TEXT), fake.captureFormats);
        assertTrue(client.capture("nope", CaptureFormat.ANNOTATED).isEmpty());

        JsonNode plain = raw("{\"op\":\"capture\",\"protocol\":1,\"name\":\"c\"}");
        assertEquals("text", plain.get(Protocol.F_FORMAT).asText(), "text is the default");
        assertEquals(3, plain.get(Protocol.F_OUTPUT_BYTES).asLong());
        assertFalse(plain.get(Protocol.F_CURSOR_VISIBLE).asBoolean());
        JsonNode bogus = raw("{\"op\":\"capture\",\"protocol\":1,\"name\":\"c\",\"format\":\"html\"}");
        assertFalse(bogus.get(Protocol.F_OK).asBoolean());
        assertTrue(bogus.get(Protocol.F_ERROR).asText().contains("unknown capture format: html"));
    }

    @Test
    void waitPassesClampedArgumentsAndReturnsTheResult() throws Exception {
        client.newSession("w", home, 80, 24, null);
        FakeSession fake = manager.session("w");
        fake.push("xy");
        Session.WaitResult result = client.awaitQuiet("w", 1, 200, 999_999);
        assertEquals(new Session.WaitResult(true, 2, true), result);
        assertEquals(new FakeSession.Wait(1, 200, Protocol.WAIT_MAX_MS), fake.waits.get(0));

        JsonNode defaults = raw("{\"op\":\"wait\",\"protocol\":1,\"name\":\"w\",\"timeoutMs\":-5}");
        assertTrue(defaults.get(Protocol.F_QUIET).asBoolean());
        assertEquals(new FakeSession.Wait(-1, Protocol.WAIT_DEFAULT_QUIET_MS, 0), fake.waits.get(1));
        raw("{\"op\":\"wait\",\"protocol\":1,\"name\":\"w\"}");
        assertEquals(new FakeSession.Wait(-1, Protocol.WAIT_DEFAULT_QUIET_MS, Protocol.WAIT_DEFAULT_TIMEOUT_MS),
                fake.waits.get(2));

        fake.waitResult = new Session.WaitResult(false, 7, true);
        assertEquals(new Session.WaitResult(false, 7, true), client.awaitQuiet("w", -1, 300, 100));
        SessionServerException missing = assertThrows(SessionServerException.class,
                () -> client.awaitQuiet("nope", -1, 300, 100));
        assertTrue(missing.getMessage().contains("no such session: nope"));
    }

    @Test
    void watchStreamsEventsOfTheNamedSessionsUntilClosed() throws Exception {
        client.newSession("a", home, 80, 24, null);
        client.newSession("b", home, 80, 24, null);
        FakeSession a = manager.session("a");
        FakeSession b = manager.session("b");
        List<SessionEvent> events = new CopyOnWriteArrayList<>();
        SessionServerClient.Watch watch = client.watch(List.of("a"), 250, events::add);
        closeables.add(watch);
        await(() -> a.watchers.size() == 1);
        assertEquals(250, a.watchers.get(0).quietMs());
        assertEquals(0, b.watchers.size(), "only the named session is watched");

        a.push("abcd");
        a.event(Protocol.EVENT_OUTPUT, null);
        a.event(Protocol.EVENT_TITLE, "vim");
        a.event(Protocol.EVENT_BELL, null);
        a.event(Protocol.EVENT_QUIET, null);
        a.exit(3);
        await(() -> events.size() == 5);
        assertEquals(List.of(
                new SessionEvent("a", "output", 4, null, null),
                new SessionEvent("a", "title", 4, "vim", null),
                new SessionEvent("a", "bell", 4, null, null),
                new SessionEvent("a", "quiet", 4, null, null),
                new SessionEvent("a", "exit", 4, null, 3)), events);
        assertTrue(watch.isOpen(), "an exit does not end the stream");

        watch.close();
        assertFalse(watch.isOpen());
        SessionServerClient.Watch onB = client.watch(List.of("b"), 500, e -> { });
        await(() -> b.watchers.size() == 1);
        onB.close();
        await(() -> b.watchers.isEmpty());

        SessionServerException missing = assertThrows(SessionServerException.class,
                () -> client.watch(List.of("b", "nope"), 500, e -> { }));
        assertTrue(missing.getMessage().contains("no such session: nope"), missing.getMessage());
        assertEquals(0, b.watchers.size(), "a refused watch registers nothing");
    }

    @Test
    void watchOfAllSessionsPicksUpSessionsCreatedLater() throws Exception {
        client.newSession("x", home, 80, 24, null);
        FakeSession x = manager.session("x");
        List<SessionEvent> events = new CopyOnWriteArrayList<>();
        SessionServerClient.Watch watch = client.watch(null, 500, events::add);
        closeables.add(watch);
        await(() -> x.watchers.size() == 1);
        client.newSession("y", home, 80, 24, null);
        FakeSession y = manager.session("y");
        assertEquals(1, y.watchers.size(), "registered by the time new answered");
        x.event(Protocol.EVENT_OUTPUT, null);
        y.event(Protocol.EVENT_BELL, null);
        await(() -> events.size() == 2);
        assertEquals("x", events.get(0).name());
        assertEquals("y", events.get(1).name());
        assertEquals("bell", events.get(1).type());

        watch.close();
        await(() -> manager.createdListeners.isEmpty() && x.watchers.isEmpty() && y.watchers.isEmpty());
    }

    @Test
    void watchOnTheWireIsResponseFramesAndEndsOnDetach() throws Exception {
        client.newSession("r", home, 80, 24, null);
        FakeSession fake = manager.session("r");
        try (SocketChannel channel = connect(home)) {
            FrameCodec codec = codec(channel);
            codec.write(Frame.json(FrameType.REQUEST, "{\"op\":\"watch\",\"protocol\":1,\"names\":\"r\"}"));
            JsonNode refused = Json.parse(codec.read().payload());
            assertEquals("names must be an array of session names", refused.get(Protocol.F_ERROR).asText());
            codec.write(Frame.json(FrameType.REQUEST, "{\"op\":\"watch\",\"protocol\":1,\"names\":[\"r\"]}"));
            assertTrue(Json.parse(codec.read().payload()).get(Protocol.F_OK).asBoolean(),
                    "the control connection survived the refusal");
            await(() -> fake.watchers.size() == 1);
            fake.push("hi");
            fake.event(Protocol.EVENT_TITLE, "t");
            Frame frame = codec.read();
            assertEquals(FrameType.RESPONSE, frame.type());
            assertEquals("{\"ok\":true,\"event\":\"title\",\"name\":\"r\",\"outputBytes\":2,\"title\":\"t\"}",
                    new String(frame.payload(), StandardCharsets.UTF_8));
            codec.write(Frame.of(FrameType.DETACH, new byte[0]));
            assertNull(codec.read(), "DETACH ends the stream");
            await(() -> fake.watchers.isEmpty());
        }
    }

    @Test
    void watchThatFallsFarBehindIsDroppedWithAnError() throws Exception {
        client.newSession("f", home, 80, 24, null);
        FakeSession fake = manager.session("f");
        try (SocketChannel channel = connect(home)) {
            FrameCodec codec = codec(channel);
            codec.write(Frame.json(FrameType.REQUEST, "{\"op\":\"watch\",\"protocol\":1,\"names\":[\"f\"]}"));
            assertTrue(Json.parse(codec.read().payload()).get(Protocol.F_OK).asBoolean());
            await(() -> fake.watchers.size() == 1);
            // Nobody reads: the socket fills, the writer blocks, and the queue overflows.
            for (int i = 0; i < 4 * WatchStream.CAPACITY && !fake.watchers.isEmpty(); i++) {
                fake.event(Protocol.EVENT_BELL, null);
            }
            JsonNode last = null;
            Frame frame;
            while ((frame = codec.read()) != null) {
                last = Json.parse(frame.payload());
                if (!last.get(Protocol.F_OK).asBoolean()) {
                    break;
                }
            }
            assertNotNull(last);
            assertFalse(last.get(Protocol.F_OK).asBoolean(), "the stream ends with an error: " + last);
            assertTrue(last.get(Protocol.F_ERROR).asText().contains("behind"));
            assertNull(codec.read(), "and the server closes");
            await(() -> fake.watchers.isEmpty());
        }
    }

    @Test
    void cliDrivesTheNewOperations() throws Exception {
        client.newSession("k", home, 80, 24, null, Map.of("harness", "shell"));
        FakeSession fake = manager.session("k");
        assertEquals(0, cli("input", "k", "--text", "ls", "Enter"));
        assertEquals("ls\r", fake.writtenText());
        assertEquals(0, cli("label", "k", "display=Main", "harness="));
        assertEquals(Map.of("display", "Main"), fake.labels());
        StringBuilder listed = new StringBuilder();
        assertEquals(0, cli(listed, "list", "--json"));
        JsonNode entry = Json.parse(listed.toString().getBytes(StandardCharsets.UTF_8)).get(0);
        assertEquals("Main", entry.get(Protocol.F_LABELS).get("display").asText());
        assertEquals(0, entry.get(Protocol.F_OUTPUT_BYTES).asLong());
        StringBuilder captured = new StringBuilder();
        assertEquals(0, cli(captured, "capture", "k", "--format", "annotated"));
        assertEquals(FakeSession.ANNOTATED_TEXT, captured.toString());
        StringBuilder waited = new StringBuilder();
        assertEquals(0, cli(waited, "wait", "k", "--quiet", "100", "--timeout", "200"));
        assertEquals("{\"quiet\":true,\"outputBytes\":0,\"alive\":true}", waited.toString().strip());
        assertEquals(new FakeSession.Wait(-1, 100, 200), fake.waits.get(0));
    }

    @Test
    void cliWatchPrintsOneJsonLinePerEventUntilTheStreamEnds() throws Exception {
        client.newSession("v", home, 80, 24, null);
        FakeSession fake = manager.session("v");
        StringBuilder printed = new StringBuilder();
        AtomicReference<Integer> code = new AtomicReference<>();
        Thread watcher = Thread.ofVirtual().start(() -> code.set(cli(printed, "watch", "v", "--quiet", "100")));
        await(() -> fake.watchers.size() == 1);
        fake.event(Protocol.EVENT_TITLE, "hello");
        fake.event(Protocol.EVENT_BELL, null);
        Thread.sleep(200); // let the reader print before the server goes away
        server.shutdown();
        watcher.join(WAIT.toMillis());
        assertEquals(0, code.get());
        assertEquals(List.of(
                "{\"event\":\"title\",\"name\":\"v\",\"outputBytes\":0,\"title\":\"hello\"}",
                "{\"event\":\"bell\",\"name\":\"v\",\"outputBytes\":0}"),
                printed.toString().lines().toList());
    }

    @Test
    void watchOfAllSessionsForgetsASessionOnceItExits() throws Exception {
        client.newSession("stay", home, 80, 24, null);
        List<SessionEvent> events = new CopyOnWriteArrayList<>();
        SessionServerClient.Watch watch = client.watch(null, 500, events::add);
        closeables.add(watch);
        FakeSession stay = manager.session("stay");
        await(() -> stay.watchers.size() == 1);
        client.newSession("gone", home, 80, 24, null);
        FakeSession gone = manager.session("gone");
        ClientConnection connection = server.connections().stream()
                .filter(c -> c.watchedSessions().contains(stay)).findFirst().orElseThrow();
        assertTrue(connection.watchedSessions().contains(gone));

        gone.exit(0);
        manager.remove(gone);
        await(() -> events.stream().anyMatch(e -> e.name().equals("gone") && e.type().equals("exit")));
        assertEquals(Set.of(stay), connection.watchedSessions(), "the ended session is no longer referenced");

        // A new session reusing the name is watched afresh; the old exit cannot evict it.
        client.newSession("gone", home, 80, 24, null);
        FakeSession again = manager.session("gone");
        assertEquals(Set.of(stay, again), connection.watchedSessions());
        watch.close();
        await(() -> stay.watchers.isEmpty() && again.watchers.isEmpty() && manager.createdListeners.isEmpty());
    }

    @Test
    void watchWhosePeerStopsReadingIsClosedAndUnregisteredAfterTheOverflow() throws Exception {
        client.newSession("s", home, 80, 24, null);
        FakeSession fake = manager.session("s");
        try (SocketChannel channel = connect(home)) {
            FrameCodec codec = codec(channel);
            codec.write(Frame.json(FrameType.REQUEST, "{\"op\":\"watch\",\"protocol\":1}"));
            assertTrue(Json.parse(codec.read().payload()).get(Protocol.F_OK).asBoolean());
            await(() -> fake.watchers.size() == 1 && manager.createdListeners.size() == 1);
            // Never read again: the writer blocks on the full socket and the queue overflows.
            for (int i = 0; i < 4 * WatchStream.CAPACITY && !fake.watchers.isEmpty(); i++) {
                fake.event(Protocol.EVENT_BELL, null);
            }
            // Still not reading: the overflow alone must close the socket and unwind the
            // connection, which unregisters it everywhere.
            await(() -> fake.watchers.isEmpty() && manager.createdListeners.isEmpty());
            await(() -> server.connections().isEmpty());
        }
    }

    @Test
    void newPassesTheEnvironmentAndRejectsABadOne() throws Exception {
        SessionServerClient.NewSessionResult created =
                client.newSession("e", home, 80, 24, null, null, Map.of("HM_TOKEN", "abc", "EMPTY", ""));
        assertEquals(Map.of("HM_TOKEN", "abc", "EMPTY", ""), manager.environments.get("e"));
        assertTrue(created.envApplied(), "the daemon acknowledged env");
        assertEquals(manager.session("e").info().pid(), created.pid());
        assertEquals(manager.session("e").info().backend(), created.backend());
        client.newSession("plain", home, 80, 24, null);
        assertEquals(Map.of(), manager.environments.get("plain"));

        // The ack is on the wire only when there was something to acknowledge.
        JsonNode acked = raw("{\"op\":\"new\",\"protocol\":1,\"name\":\"ack\",\"workdir\":\"" + home
                + "\",\"env\":{\"A\":\"1\"}}");
        assertTrue(acked.path(Protocol.F_ENV).asBoolean(false), acked.toString());
        for (String noEnv : new String[]{"", ",\"env\":{}"}) {
            JsonNode plain = raw("{\"op\":\"new\",\"protocol\":1,\"name\":\"noack" + noEnv.length()
                    + "\",\"workdir\":\"" + home + "\"" + noEnv + "}");
            assertTrue(plain.get(Protocol.F_OK).asBoolean(), plain.toString());
            assertFalse(plain.has(Protocol.F_ENV), plain.toString());
        }
        assertTrue(client.newSession("e2", home, 80, 24, null, null, Map.of()).envApplied(),
                "nothing requested, nothing missing");

        for (String[] bad : new String[][]{
                {"[\"A=1\"]", "env must be an object of strings"},
                {"{\"A\":1}", "env A must be a string"},
                {"{\"A=B\":\"x\"}", "invalid environment variable name: 'A=B'"},
                {"{\"\":\"x\"}", "invalid environment variable name: ''"},
                {"{\"A\":\"x\\u0000y\"}", "env A must not contain NUL"}}) {
            JsonNode reply = raw("{\"op\":\"new\",\"protocol\":1,\"name\":\"bad\",\"workdir\":\"" + home
                    + "\",\"env\":" + bad[0] + "}");
            assertFalse(reply.get(Protocol.F_OK).asBoolean(), bad[0]);
            assertEquals(bad[1], reply.get(Protocol.F_ERROR).asText());
        }
        assertTrue(manager.find("bad").isEmpty(), "a refused env creates nothing");
    }

    @Test
    void cliWaitTakesByteCountsAndDurationsBeyondInt() throws Exception {
        client.newSession("big", home, 80, 24, null);
        FakeSession fake = manager.session("big");
        assertEquals(0, cli("wait", "big", "--since", "3000000000", "--quiet", "100", "--timeout", "4294967296"));
        assertEquals(new FakeSession.Wait(3_000_000_000L, 100, Protocol.WAIT_MAX_MS), fake.waits.get(0));
    }

    @Test
    void cliCaptureJsonPrintsTheFormatTheServerEchoed() throws Exception {
        client.newSession("j", home, 80, 24, null);
        StringBuilder captured = new StringBuilder();
        assertEquals(0, cli(captured, "capture", "j", "--format", "annotated", "--json"));
        JsonNode node = Json.parse(captured.toString().getBytes(StandardCharsets.UTF_8));
        assertEquals("annotated", node.get(Protocol.F_FORMAT).asText());
        assertEquals(FakeSession.ANNOTATED_TEXT, node.get(Protocol.F_TEXT).asText());
        SessionServerClient.FormattedCapture formatted = client.captureWithFormat("j", CaptureFormat.ANSI)
                .orElseThrow();
        assertEquals(CaptureFormat.ANSI, formatted.format());
        assertEquals(FakeSession.ANSI_TEXT, formatted.capture().text());
    }

    private int cli(String... argv) {
        return cli(new StringBuilder(), argv);
    }

    private int cli(StringBuilder stdout, String... argv) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        List<String> args = new ArrayList<>(List.of(argv));
        args.add("--home");
        args.add(home.toString());
        int code = new Cli(InputStream.nullInputStream(), out, new PrintStream(err, true))
                .run(args.toArray(String[]::new));
        stdout.append(out.toString(StandardCharsets.UTF_8));
        assertEquals("", err.toString(StandardCharsets.UTF_8));
        return code;
    }

    /** One request over a fresh raw connection. */
    private JsonNode raw(String request) throws IOException {
        try (SocketChannel channel = connect(home)) {
            FrameCodec codec = codec(channel);
            codec.write(Frame.json(FrameType.REQUEST, request));
            return Json.parse(codec.read().payload());
        }
    }

    // ---- helpers

    private Path newHome() throws IOException {
        Path created = Files.createTempDirectory("hss");
        homes.add(created);
        return created;
    }

    private SessionServer start(ServeOptions options, FakeSessionManager sessionManager) throws IOException {
        SessionServer started = new SessionServer(options, sessionManager);
        servers.add(started);
        assertTrue(started.start(), "bound on " + options.home());
        return started;
    }

    private AttachedSession attach(String name, int columns, int rows) {
        return attach(client, name, columns, rows);
    }

    private AttachedSession attach(SessionServerClient via, String name, int columns, int rows) {
        AttachedSession attached = via.attach(name, columns, rows);
        closeables.add(attached);
        return attached;
    }

    /** The server registers a client with the session only after answering the attach
     * request, so wait for the registration before indexing {@code fake.attached}. */
    private static Session.Client awaitAttached(FakeSession fake, int count) throws InterruptedException {
        await(() -> fake.attached.size() == count);
        return fake.attached.get(count - 1);
    }

    private static String readText(AttachedSession attached, int length) throws IOException {
        byte[] out = new byte[length];
        int filled = 0;
        while (filled < length) {
            int n = attached.read(out, filled, length - filled);
            if (n < 0) {
                throw new AssertionError("stream ended after " + filled + " of " + length + " bytes: "
                        + new String(out, 0, filled, StandardCharsets.ISO_8859_1));
            }
            filled += n;
        }
        return new String(out, StandardCharsets.ISO_8859_1);
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "condition not met within " + WAIT);
            Thread.sleep(10);
        }
    }

    private static SocketChannel connect(Path home) throws IOException {
        SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
        channel.connect(UnixDomainSocketAddress.of(ServerHome.socket(home)));
        return channel;
    }

    private static FrameCodec codec(SocketChannel channel) {
        return new FrameCodec(Channels.newInputStream(channel), Channels.newOutputStream(channel));
    }

    /** A listener that answers every request with a protocol mismatch, standing in for a
     * future daemon build. */
    private static final class MismatchServer implements AutoCloseable {
        private final ServerSocketChannel listener;

        private MismatchServer(ServerSocketChannel listener) {
            this.listener = listener;
        }

        static MismatchServer start(Path socket, int protocol, String version, long pid) throws IOException {
            ServerSocketChannel listener = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
            listener.bind(UnixDomainSocketAddress.of(socket));
            String reply = Json.error(Protocol.ERROR_PROTOCOL_MISMATCH)
                    .put(Protocol.F_PROTOCOL, protocol).put(Protocol.F_VERSION, version).put(Protocol.F_PID, pid)
                    .toString();
            Thread.ofVirtual().start(() -> {
                while (true) {
                    try (SocketChannel accepted = listener.accept()) {
                        FrameCodec codec = codec(accepted);
                        codec.read();
                        codec.write(Frame.json(FrameType.RESPONSE, reply));
                    } catch (IOException e) {
                        return;
                    }
                }
            });
            return new MismatchServer(listener);
        }

        boolean isOpen() {
            return listener.isOpen();
        }

        @Override
        public void close() throws IOException {
            listener.close();
        }
    }
    // ---- review follow-ups: sizes, BEHIND ordering, final snapshot at exit

    @Test
    void newRejectsUnusableSizesWithoutCreatingASession() {
        SessionServerException zero = assertThrows(SessionServerException.class,
                () -> client.newSession("tiny", home, 0, 0, null));
        assertTrue(zero.getMessage().contains("invalid size 0x0"), zero.getMessage());
        SessionServerException huge = assertThrows(SessionServerException.class,
                () -> client.newSession("huge", home, 100_000, 100_000, null));
        assertTrue(huge.getMessage().contains("invalid size"), huge.getMessage());
        assertTrue(manager.sessions().isEmpty(), "nothing was spawned");
        assertTrue(client.isServerRunning(), "the connection answered instead of dropping");
    }

    @Test
    void attachRejectsUnusableSizeAndResizeIgnoresIt() throws Exception {
        client.newSession("sized", home, 80, 24, null);
        FakeSession fake = manager.session("sized");
        SessionServerException e = assertThrows(SessionServerException.class, () -> client.attach("sized", 0, 24));
        assertTrue(e.getMessage().contains("invalid size 0x24"), e.getMessage());
        assertEquals(0, fake.clients.size());
        AttachedSession attached = attach(client, "sized", 80, 24);
        attached.resize(-1, 0);
        attached.resize(100, 30);
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (!(fake.columns == 100 && fake.rows == 30)) {
            assertTrue(System.nanoTime() < deadline, "valid resize never applied");
            Thread.sleep(10);
        }
        assertEquals(1, fake.resizes.size(), "the invalid RESIZE was ignored, not applied");
    }

    /** Attaches a client that never reads and pushes enough through the fake session that
     * the server declares it BEHIND (the socket buffer absorbs some, the 64 KiB server-side
     * queue the rest). Returns the still-unread attachment. */
    private AttachedSession attachAndFallBehind(SessionServerClient slowClient, FakeSession fake) throws Exception {
        AttachedSession stuck = attach(slowClient, "slow", 80, 24);
        // The attach response precedes server-side registration. Wait for the fake
        // client before pumping output or exiting; unlike PtySession, the fake does
        // not replay an exit that happened before registration.
        await(() -> fake.clients.size() == 1);
        byte[] chunk = new byte[4096];
        java.util.Arrays.fill(chunk, (byte) 'x');
        for (int pushed = 0; pushed < 2 * 1024 * 1024; pushed += chunk.length) {
            fake.push(chunk); // never blocks, whatever the stuck client does
        }
        return stuck;
    }

    private static String readUntil(AttachedSession attached, String marker, StringBuilder seen) throws Exception {
        return readUntil(attached, marker, seen, () -> { });
    }

    /** Reads until {@code marker} has arrived, within {@link #WAIT}: the read runs on its own
     * thread, so a stream that goes silent fails the test instead of blocking it for ever.
     * {@code nudge} runs first and again whenever the stream stays silent for 200 ms. */
    private static String readUntil(AttachedSession attached, String marker, StringBuilder seen, Runnable nudge)
            throws Exception {
        byte[] buf = new byte[65536];
        long deadline = System.nanoTime() + WAIT.toNanos();
        long nudgeNanos = Duration.ofMillis(200).toNanos();
        nudge.run();
        FutureTask<Integer> read = null;
        try {
            while (seen.indexOf(marker) < 0) {
                long left = deadline - System.nanoTime();
                assertTrue(left > 0, "'" + marker + "' never arrived");
                if (read == null) {
                    read = new FutureTask<>(() -> attached.read(buf, 0, buf.length));
                    Thread.ofVirtual().name("test-read").start(read);
                }
                int n;
                try {
                    n = read.get(Math.min(left, nudgeNanos), TimeUnit.NANOSECONDS);
                } catch (TimeoutException silent) {
                    nudge.run();
                    continue;
                }
                read = null;
                assertTrue(n >= 0, "stream ended before '" + marker + "'");
                seen.append(new String(buf, 0, n, StandardCharsets.ISO_8859_1));
            }
        } finally {
            if (read != null) {
                read.cancel(true); // interrupts the blocked read
            }
        }
        return seen.toString();
    }

    @Test
    void behindClientDropsLiveBytesAheadOfTheResyncAndKeepsThoseAfterIt() throws Exception {
        Path slowHome = newHome();
        FakeSessionManager slowManager = new FakeSessionManager();
        start(ServeOptions.defaults(slowHome).withClientBufferBytes(64 * 1024).withBehindTimeout(Duration.ofMinutes(5)),
                slowManager);
        SessionServerClient slowClient = new SessionServerClient(slowHome, false);
        slowClient.newSession("slow", slowHome, 80, 24, null);
        FakeSession fake = slowManager.session("slow");
        // Both are handed over inside snapshotFor, i.e. under the session lock: the one
        // before the snapshot models a pump batch racing the writer's re-sync request.
        fake.liveBeforeSnapshot = "<<LIVE-BEFORE>>".getBytes(StandardCharsets.US_ASCII);
        fake.liveAfterSnapshot = "<<LIVE-AFTER>>".getBytes(StandardCharsets.US_ASCII);
        AttachedSession stuck = attachAndFallBehind(slowClient, fake);

        StringBuilder seen = new StringBuilder();
        String all = readUntil(stuck, "<<LIVE-AFTER>>", seen);
        // The contract is ordering, not adjacency: a re-sync happened, the live bytes handed
        // over BEFORE it (while the client was still BEHIND) were dropped, and the ones handed
        // over AFTER it survive in order. Buffered pre-BEHIND bytes may sit between them; what
        // must never happen is a pre-snapshot live byte reaching the client or LIVE-AFTER
        // arriving before any snapshot.
        assertFalse(all.contains("<<LIVE-BEFORE>>"), "live bytes handed over before the snapshot are dropped");
        int snapshot = all.indexOf("<<RESNAPSHOT>>");
        assertTrue(snapshot >= 0, "a re-snapshot arrived");
        assertTrue(all.indexOf("<<LIVE-AFTER>>") > snapshot, "live bytes after the snapshot follow it");
        assertTrue(fake.snapshotForCalls.get() >= 1, "at least one re-sync");
        // The client usually re-syncs several times while the 2 MiB is pushed, so the first
        // LIVE-AFTER read above can come from an early re-sync while the client is BEHIND
        // again, its pipe still full. A marker pushed now is then dropped (by design, as
        // any live output while BEHIND) and the next re-sync carries only RESNAPSHOT and
        // LIVE-AFTER; pushing it once and blocking on the read hung this test. Push it again
        // whenever the stream stays silent, until one lands after the final re-sync.
        StringBuilder more = new StringBuilder();
        readUntil(stuck, "after-marker", more, () -> fake.push("after-marker"));
        assertTrue(stuck.isConnected(), "live delivery stays on after the re-sync");
    }

    @Test
    void behindClientGetsAFinalSnapshotBeforeSessionExited() throws Exception {
        Path slowHome = newHome();
        FakeSessionManager slowManager = new FakeSessionManager();
        start(ServeOptions.defaults(slowHome).withClientBufferBytes(64 * 1024).withBehindTimeout(Duration.ofMinutes(5)),
                slowManager);
        SessionServerClient slowClient = new SessionServerClient(slowHome, false);
        slowClient.newSession("slow", slowHome, 80, 24, null);
        FakeSession fake = slowManager.session("slow");
        AttachedSession stuck = attachAndFallBehind(slowClient, fake);
        fake.exit(7); // the session ends while the client is still BEHIND

        // The bug this pins: a BEHIND client used to get SESSION_EXITED with its stale
        // pre-BEHIND screen and no redraw. Now it is re-synced around exit, so its byte
        // stream carries a fresh snapshot before EOF and it still learns the exit code.
        // (Whether a trailing live batch follows the snapshot depends on socket timing, so
        // the race-free invariant is "a re-sync happened", not "the snapshot is the last byte".)
        StringBuilder seen = new StringBuilder();
        byte[] buf = new byte[65536];
        int n;
        while ((n = stuck.read(buf, 0, buf.length)) >= 0) {
            seen.append(new String(buf, 0, n, StandardCharsets.ISO_8859_1));
        }
        assertTrue(seen.indexOf("<<RESNAPSHOT>>") >= 0, "the BEHIND client was re-synced before exit");
        assertEquals(java.util.OptionalInt.of(7), stuck.exitCode());
        assertTrue(fake.snapshotForCalls.get() >= 1, "a re-sync was taken around SESSION_EXITED");
    }
}
