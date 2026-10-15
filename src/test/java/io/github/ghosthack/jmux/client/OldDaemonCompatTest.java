package io.github.ghosthack.jmux.client;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.ghosthack.jmux.cli.Cli;
import io.github.ghosthack.jmux.protocol.Frame;
import io.github.ghosthack.jmux.protocol.FrameCodec;
import io.github.ghosthack.jmux.protocol.FrameType;
import io.github.ghosthack.jmux.protocol.Json;
import io.github.ghosthack.jmux.protocol.Protocol;
import io.github.ghosthack.jmux.server.ServerHome;
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
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A current client against a daemon that speaks the same protocol (1) but predates the
 * operations and fields added since: no {@code outputBytes}, no {@code format} echo, and
 * {@code unknown op: X} for {@code input} / {@code wait} / {@code label} / {@code watch}.
 * The "daemon" is a scripted listener on a temp home; nothing is spawned.
 */
class OldDaemonCompatTest {

    private static final String OLD_SESSION = "{\"name\":\"s\",\"workdir\":\"/tmp\",\"columns\":80,\"rows\":24,"
            + "\"pid\":5,\"createdAt\":\"2026-01-01T00:00:00Z\",\"clients\":0,\"alive\":true,\"backend\":\"unix\"}";

    private Path home;
    private ScriptedServer server;
    private SessionServerClient client;

    @BeforeEach
    void start() throws IOException {
        home = Files.createTempDirectory("hss-old");
        server = ScriptedServer.start(ServerHome.socket(home), OldDaemonCompatTest::oldDaemon);
        client = new SessionServerClient(home, false);
    }

    @AfterEach
    void stop() throws IOException {
        server.close();
        try (Stream<Path> tree = Files.walk(home)) {
            tree.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    /** What the HEAD-before-these-ops daemon answers. */
    private static List<String> oldDaemon(JsonNode request) {
        String op = request.path(Protocol.F_OP).asText();
        return switch (op) {
            case Protocol.OP_PING -> List.of("{\"ok\":true,\"protocol\":1,\"version\":\"0.0.old\",\"pid\":1}");
            case Protocol.OP_LIST -> List.of("{\"ok\":true,\"sessions\":[" + OLD_SESSION + "]}");
            case Protocol.OP_CAPTURE -> List.of("{\"ok\":true,\"text\":\"plain\\n\",\"alive\":true,"
                    + "\"foregroundCommand\":\"sh\",\"processId\":5,\"title\":\"\",\"columns\":80,\"rows\":24}");
            case Protocol.OP_NEW, Protocol.OP_KILL, Protocol.OP_SEND_KEYS -> List.of("{\"ok\":true}");
            default -> List.of("{\"ok\":false,\"error\":\"unknown op: " + op + "\"}");
        };
    }

    @Test
    void anEnvTheDaemonDoesNotAcknowledgeWasNotApplied() {
        SessionServerClient.NewSessionResult dropped =
                client.newSession("s", home, 80, 24, null, null, Map.of("HM_TOKEN", "abc"));
        assertFalse(dropped.envApplied(), "the old daemon created the session but ignored env");
        assertEquals(0, dropped.pid(), "not reported");
        assertEquals("", dropped.backend(), "not reported");
        assertTrue(client.newSession("s", home, 80, 24, null, null, null).envApplied(),
                "nothing requested, nothing missing");
    }

    @Test
    void missingOutputBytesDecodesAsUnknown() {
        Session.Info info = client.list().get(0);
        assertEquals(-1, info.outputBytes(), "-1 = unknown, the daemon predates the counter");
        Session.Capture capture = client.capture("s").orElseThrow();
        assertEquals("plain\n", capture.text());
        assertEquals(-1, capture.outputBytes());
        assertEquals(CaptureFormat.TEXT, client.captureWithFormat("s", CaptureFormat.TEXT).orElseThrow().format());
    }

    @Test
    void aFormatTheDaemonDoesNotEchoIsUnsupportedNotPlainTextInDisguise() {
        for (CaptureFormat format : List.of(CaptureFormat.ANNOTATED, CaptureFormat.ANSI)) {
            SessionServerException e = assertThrows(SessionServerException.class, () -> client.capture("s", format));
            assertTrue(e.isUnsupported());
            assertFalse(e.isNoServer());
            assertTrue(e.getMessage().startsWith("unsupported by this jmux daemon: capture format " + format.wireName()),
                    e.getMessage());
        }
    }

    @Test
    void unknownOpRepliesBecomeUnsupported() {
        List<Runnable> calls = List.of(
                () -> client.input("s", "x", true, null),
                () -> client.awaitQuiet("s", -1, 100, 100),
                () -> client.label("s", Map.of("a", "b")),
                () -> client.watch(null, 500, e -> { }));
        List<String> ops = List.of(Protocol.OP_INPUT, Protocol.OP_WAIT, Protocol.OP_LABEL, Protocol.OP_WATCH);
        for (int i = 0; i < calls.size(); i++) {
            Runnable call = calls.get(i);
            SessionServerException e = assertThrows(SessionServerException.class, call::run);
            assertTrue(e.isUnsupported(), e.getMessage());
            assertTrue(e.getMessage().startsWith(SessionServerException.UNSUPPORTED_PREFIX + ops.get(i)),
                    e.getMessage());
        }
        SessionServerException refused = new SessionServerException("no such session: x");
        assertFalse(refused.isUnsupported(), "an ordinary refusal is not 'unsupported'");
    }

    @Test
    void cliReportsAnUnsupportedFormatAndPrintsTheEchoedOne() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        assertEquals(1, cli(out, err, "capture", "s", "--format", "annotated"));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("unsupported by this jmux daemon"), err.toString());
        out.reset();
        err.reset();
        assertEquals(0, cli(out, err, "capture", "s", "--json"));
        JsonNode node = parse(out.toString(StandardCharsets.UTF_8));
        assertEquals("text", node.get(Protocol.F_FORMAT).asText());
        assertEquals(-1, node.get(Protocol.F_OUTPUT_BYTES).asLong());
    }

    @Test
    void aStreamTheServerDropsCarriesItsErrorAndTheCliExitsNonZero() throws Exception {
        server.close();
        Files.deleteIfExists(ServerHome.socket(home));
        String dropped = "watch fell more than 4096 events behind; closed";
        server = ScriptedServer.start(ServerHome.socket(home), request -> List.of("{\"ok\":true}",
                "{\"ok\":true,\"event\":\"bell\",\"name\":\"s\",\"outputBytes\":3}",
                "{\"ok\":false,\"error\":\"" + dropped + "\"}"));

        List<SessionEvent> events = new CopyOnWriteArrayList<>();
        SessionServerClient.Watch watch = client.watch(null, 500, events::add);
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (watch.isOpen() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertFalse(watch.isOpen());
        assertEquals(Optional.of(dropped), watch.error());
        assertEquals(List.of(new SessionEvent("s", "bell", 3, null, null)), events);
        watch.close();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        assertEquals(1, cli(out, err, "watch"));
        assertEquals("{\"event\":\"bell\",\"name\":\"s\",\"outputBytes\":3}", out.toString(StandardCharsets.UTF_8).strip());
        assertEquals("error: " + dropped, err.toString(StandardCharsets.UTF_8).strip());
    }

    private int cli(ByteArrayOutputStream out, ByteArrayOutputStream err, String... argv) {
        List<String> args = new ArrayList<>(List.of(argv));
        args.add("--home");
        args.add(home.toString());
        return new Cli(InputStream.nullInputStream(), out, new PrintStream(err, true)).run(args.toArray(String[]::new));
    }

    private static JsonNode parse(String json) {
        try {
            return Json.parse(json.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    /** Answers each request frame with the script's RESPONSE frames; more than one reply
     * is a stream, after which the connection is closed. */
    private static final class ScriptedServer implements AutoCloseable {
        private final ServerSocketChannel listener;

        private ScriptedServer(ServerSocketChannel listener) {
            this.listener = listener;
        }

        static ScriptedServer start(Path socket, Function<JsonNode, List<String>> script) throws IOException {
            ServerSocketChannel listener = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
            listener.bind(UnixDomainSocketAddress.of(socket));
            Thread.ofVirtual().start(() -> {
                while (true) {
                    SocketChannel accepted;
                    try {
                        accepted = listener.accept();
                    } catch (IOException e) {
                        return;
                    }
                    Thread.ofVirtual().start(() -> serve(accepted, script));
                }
            });
            return new ScriptedServer(listener);
        }

        private static void serve(SocketChannel channel, Function<JsonNode, List<String>> script) {
            try (channel) {
                FrameCodec codec = new FrameCodec(Channels.newInputStream(channel), Channels.newOutputStream(channel));
                Frame frame;
                while ((frame = codec.read()) != null) {
                    if (frame.type() != FrameType.REQUEST) {
                        return;
                    }
                    List<String> replies = script.apply(Json.parse(frame.payload()));
                    for (String reply : replies) {
                        codec.write(Frame.json(FrameType.RESPONSE, reply));
                    }
                    if (replies.size() > 1) {
                        return;
                    }
                }
            } catch (IOException e) {
                // The client went away.
            }
        }

        @Override
        public void close() throws IOException {
            listener.close();
        }
    }
}
