package io.github.ghosthack.jmux.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.ghosthack.jmux.protocol.Json;
import io.github.ghosthack.jmux.protocol.Protocol;
import io.github.ghosthack.jmux.server.ServerHome;
import io.github.ghosthack.jmux.session.CaptureFormat;
import io.github.ghosthack.jmux.session.Session;
import io.github.ghosthack.jmux.session.SessionEvent;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The library Harness Manager (and the CLI) use to talk to a session server. One instance
 * per server home. Every control call opens a short-lived connection (request/response,
 * then close) — cheap on a Unix socket, and it keeps the client stateless across server
 * restarts. {@link #attach} opens a dedicated connection that lives as long as the
 * attachment.
 *
 * <p><b>Only {@link #newSession} spawns a server</b> (tmux parity: only {@code new-session}
 * starts a tmux server). When no server is listening (socket missing, connect refused, or
 * EOF before the response) every other call reports "no server running" the way
 * {@code RealTmux} does: {@link #list} → empty, {@link #has} → false, {@link #capture} →
 * empty, {@link #kill} and {@link #shutdown} → return normally, {@link #currentPath},
 * {@link #sendKeys}, {@link #input}, {@link #label}, {@link #awaitQuiet}, {@link #watch}
 * and {@link #attach} → {@link SessionServerException} whose
 * {@link SessionServerException#isNoServer()} is true. {@code newSession} spawns through
 * {@link ServerLauncher} and poll-connects for up to {@link #CONNECT_TIMEOUT_SECONDS}
 * (missing/refused/EOF all mean keep polling); spawning is serialized per client instance
 * so concurrent callers join one in-flight spawn.
 *
 * <p>Every request carries {@code "protocol": Protocol.VERSION}. A server that answers
 * with a protocol mismatch produces a {@link SessionServerException} naming its version and
 * pid and telling the user to finish its sessions or run {@code shutdown}; the client never
 * spawns, kills or replaces a running server on a mismatch.
 */
public final class SessionServerClient {

    public static final int CONNECT_TIMEOUT_SECONDS = 10;

    /** How a server answers an operation it does not know (every build so far). */
    private static final String UNKNOWN_OP_PREFIX = "unknown op: ";

    /** How often the post-spawn poll retries the connect. */
    private static final long SPAWN_POLL_INTERVAL_MS = 100;

    private final Path home;
    private final boolean spawn;
    /** How a server is started ({@code ServerLauncher::spawn} outside tests). */
    private final Function<Path, Process> spawner;
    /** The one spawn in flight for this home, joined by every concurrent {@code newSession}. */
    private CompletableFuture<Void> spawnInFlight;

    /** A client over the default home ({@code ServerHome.resolve()}) with auto-spawn. */
    public static SessionServerClient forDefaultHome() {
        return new SessionServerClient(ServerHome.resolve(), true);
    }

    /** {@code spawn} false disables auto-spawn entirely (tests that bind their own server
     * in-process must use this so a race can never fork a real daemon). */
    public SessionServerClient(Path home, boolean spawn) {
        this(home, spawn, ServerLauncher::spawn);
    }

    /** Test seam: a client whose spawn step is {@code spawner} instead of the real launcher. */
    SessionServerClient(Path home, boolean spawn, Function<Path, Process> spawner) {
        this.home = home.toAbsolutePath().normalize();
        this.spawn = spawn;
        this.spawner = spawner;
    }

    public Path home() {
        return home;
    }

    /** True if a server answers {@code ping} right now; never spawns one. */
    public boolean isServerRunning() {
        return ping().isPresent();
    }

    public record ServerInfo(int protocol, String version, long pid) {
    }

    /** Empty when no server is running. */
    public Optional<ServerInfo> ping() {
        return request(Protocol.OP_PING, null).map(reply -> new ServerInfo(
                reply.path(Protocol.F_PROTOCOL).asInt(0),
                JsonDecoding.text(reply, Protocol.F_VERSION),
                reply.path(Protocol.F_PID).asLong(0)));
    }

    public List<Session.Info> list() {
        return request(Protocol.OP_LIST, null)
                .map(reply -> JsonDecoding.infos(reply.get(Protocol.F_SESSIONS)))
                .orElse(List.of());
    }

    public boolean has(String name) {
        return request(Protocol.OP_HAS, named(name))
                .map(reply -> reply.path(Protocol.F_PRESENT).asBoolean(false))
                .orElse(false);
    }

    /** {@code command} null/empty = the platform login shell. Spawns the server if needed. */
    public void newSession(String name, Path workdir, int columns, int rows, List<String> command) {
        newSession(name, workdir, columns, rows, command, null);
    }

    /** As above, with the session's initial {@code labels} (null/empty = none). */
    public void newSession(String name, Path workdir, int columns, int rows, List<String> command,
                           Map<String, String> labels) {
        newSession(name, workdir, columns, rows, command, labels, null);
    }

    /** What {@code new} reported: the session's {@code pid} (0 when the daemon did not say)
     * and {@code backend} (empty likewise), and whether the requested environment was
     * applied — false only when a non-empty {@code env} was sent and the reply lacks the
     * daemon's {@code env} acknowledgement, i.e. a daemon that predates {@code env} started
     * the session without it. */
    public record NewSessionResult(long pid, String backend, boolean envApplied) {
    }

    /** As above, with {@code env} (null/empty = none): variables merged into the session's
     * environment after the server's defaults ({@code TERM} and {@code JMUX_SESSION} cannot
     * be overridden). A daemon that predates {@code env} ignores it and still creates the
     * session; {@link NewSessionResult#envApplied()} is then false. */
    public NewSessionResult newSession(String name, Path workdir, int columns, int rows, List<String> command,
                                       Map<String, String> labels, Map<String, String> env) {
        ObjectNode args = named(name)
                .put(Protocol.F_WORKDIR, workdir.toAbsolutePath().normalize().toString())
                .put(Protocol.F_COLUMNS, columns)
                .put(Protocol.F_ROWS, rows);
        if (command != null && !command.isEmpty()) {
            ArrayNode array = args.putArray(Protocol.F_COMMAND);
            command.forEach(array::add);
        }
        if (labels != null && !labels.isEmpty()) {
            ObjectNode labelsNode = args.putObject(Protocol.F_LABELS);
            labels.forEach(labelsNode::put);
        }
        if (env != null && !env.isEmpty()) {
            ObjectNode envNode = args.putObject(Protocol.F_ENV);
            env.forEach(envNode::put);
        }
        Optional<JsonNode> reply = request(Protocol.OP_NEW, args);
        if (reply.isEmpty()) {
            ensureServer();
            reply = request(Protocol.OP_NEW, args);
        }
        if (reply.isEmpty()) {
            throw new SessionServerException("session server at " + home + " stopped answering after it started");
        }
        JsonNode created = reply.get();
        boolean envSent = env != null && !env.isEmpty();
        return new NewSessionResult(created.path(Protocol.F_PID).asLong(0),
                JsonDecoding.text(created, Protocol.F_BACKEND),
                !envSent || created.path(Protocol.F_ENV).asBoolean(false));
    }

    public void kill(String name) {
        request(Protocol.OP_KILL, named(name));
    }

    public void sendKeys(String name, String text, boolean enter) {
        request(Protocol.OP_SEND_KEYS, named(name).put(Protocol.F_TEXT, text).put(Protocol.F_ENTER, enter))
                .orElseThrow(() -> SessionServerException.noServer(name));
    }

    /** Merges {@code changes} into the session's labels (a null value removes the key) and
     * returns the resulting labels. */
    public Map<String, String> label(String name, Map<String, String> changes) {
        ObjectNode labels = Json.object();
        changes.forEach((key, value) -> {
            if (value == null) {
                labels.putNull(key);
            } else {
                labels.put(key, value);
            }
        });
        ObjectNode args = named(name);
        args.set(Protocol.F_LABELS, labels);
        return request(Protocol.OP_LABEL, args)
                .map(reply -> JsonDecoding.labels(reply.get(Protocol.F_LABELS)))
                .orElseThrow(() -> SessionServerException.noServer(name));
    }

    /** {@code text} (null = none) as a paste or raw typing, then the named {@code keys}
     * (null/empty = none). An unknown key name fails before anything is written. */
    public void input(String name, String text, boolean paste, List<String> keys) {
        input(name, text, paste, false, keys);
    }

    /** As above; with {@code requireBracketedPaste} a paste the program has not enabled
     * bracketed paste for (DEC 2004) is refused, nothing written, instead of being typed
     * raw: {@link SessionServerException} with
     * {@link Protocol#ERROR_BRACKETED_PASTE_DISABLED}. */
    public void input(String name, String text, boolean paste, boolean requireBracketedPaste, List<String> keys) {
        ObjectNode args = named(name).put(Protocol.F_PASTE, paste);
        if (requireBracketedPaste) {
            args.put(Protocol.F_REQUIRE_BRACKETED_PASTE, true);
        }
        if (text != null) {
            args.put(Protocol.F_TEXT, text);
        }
        if (keys != null && !keys.isEmpty()) {
            ArrayNode array = args.putArray(Protocol.F_KEYS);
            keys.forEach(array::add);
        }
        request(Protocol.OP_INPUT, args).orElseThrow(() -> SessionServerException.noServer(name));
    }

    /** Blocks until the session's output is quiet (see {@link Session#awaitQuiet}); the server
     * clamps both durations to {@link Protocol#WAIT_MAX_MS}. */
    public Session.WaitResult awaitQuiet(String name, long sinceBytes, long quietMs, long timeoutMs) {
        ObjectNode args = named(name)
                .put(Protocol.F_SINCE_BYTES, sinceBytes)
                .put(Protocol.F_QUIET_MS, quietMs)
                .put(Protocol.F_TIMEOUT_MS, timeoutMs);
        return request(Protocol.OP_WAIT, args)
                .map(reply -> new Session.WaitResult(
                        reply.path(Protocol.F_QUIET).asBoolean(false),
                        reply.path(Protocol.F_OUTPUT_BYTES).asLong(0),
                        reply.path(Protocol.F_ALIVE).asBoolean(false)))
                .orElseThrow(() -> SessionServerException.noServer(name));
    }

    /** A live {@code watch} stream; {@link #close} ends it. */
    public interface Watch extends AutoCloseable {
        /** False once the server ended the stream (EOF, or dropped for falling behind) or
         * after {@link #close}. */
        boolean isOpen();

        /** The server's reason when it ended the stream with an error (a watcher that fell
         * too far behind), else empty: an EOF, a normal end or {@link #close}. */
        Optional<String> error();

        @Override
        void close();
    }

    /**
     * Opens a dedicated connection that streams events of the {@code names} sessions (null or
     * empty = every session, including ones created later) to {@code onEvent}, in order, on
     * a virtual reader thread. {@code quietMs} is the silence that raises {@code quiet}.
     */
    public Watch watch(List<String> names, long quietMs, Consumer<SessionEvent> onEvent) {
        boolean all = names == null || names.isEmpty();
        Supplier<SessionServerException> noServer = () -> all
                ? new SessionServerException("no session server is running at " + home, null, true)
                : SessionServerException.noServer(names.get(0));
        ServerConnection connection = ServerConnection.open(ServerHome.socket(home)).orElseThrow(noServer);
        boolean handedOver = false;
        try {
            ObjectNode args = Json.object().put(Protocol.F_QUIET_MS, quietMs);
            if (!all) {
                ArrayNode array = args.putArray(Protocol.F_NAMES);
                names.forEach(array::add);
            }
            checkOk(connection.request(Protocol.OP_WATCH, args));
            handedOver = true;
            return new SocketWatch(connection, onEvent);
        } catch (ServerConnection.NoServerException e) {
            throw noServer.get();
        } catch (IOException e) {
            throw new SessionServerException("watch failed: " + e.getMessage(), e);
        } finally {
            if (!handedOver) {
                connection.close();
            }
        }
    }

    /** Empty when the session (or the server) does not exist. */
    public Optional<Session.Capture> capture(String name) {
        return capture(name, CaptureFormat.TEXT);
    }

    /** {@link #capture(String)} in {@code format}. A daemon that predates capture formats
     * answers with plain text and no {@code format} echo; asking it for anything but
     * {@link CaptureFormat#TEXT} throws {@link SessionServerException#unsupported} instead of
     * passing that text off as the requested format. */
    public Optional<Session.Capture> capture(String name, CaptureFormat format) {
        return captureWithFormat(name, format).map(FormattedCapture::capture);
    }

    /** A capture and the format the server says it is in. */
    public record FormattedCapture(Session.Capture capture, CaptureFormat format) {
    }

    /** {@link #capture(String, CaptureFormat)} plus the server's echoed format (a daemon
     * that predates formats only ever sends {@link CaptureFormat#TEXT}). */
    public Optional<FormattedCapture> captureWithFormat(String name, CaptureFormat format) {
        Optional<JsonNode> reply;
        try {
            reply = request(Protocol.OP_CAPTURE, named(name).put(Protocol.F_FORMAT, format.wireName()));
        } catch (SessionServerException e) {
            if (e.getMessage() != null && e.getMessage().startsWith("no such session")) {
                return Optional.empty();
            }
            throw e;
        }
        return reply.map(node -> {
            String echoed = JsonDecoding.text(node, Protocol.F_FORMAT);
            CaptureFormat actual = echoed.isEmpty() ? CaptureFormat.TEXT
                    : CaptureFormat.fromWireName(echoed).orElse(null);
            if (actual != format) {
                throw SessionServerException.unsupported("capture format " + format.wireName()
                        + (echoed.isEmpty() ? "" : " (it answered " + echoed + ")"));
            }
            return new FormattedCapture(JsonDecoding.capture(node), actual);
        });
    }

    public Path currentPath(String name) {
        return request(Protocol.OP_CURRENT_PATH, named(name))
                .map(reply -> Path.of(JsonDecoding.text(reply, Protocol.F_PATH)))
                .orElseThrow(() -> SessionServerException.noServer(name));
    }

    /** Opens an attach-mode connection; the redraw bytes are the first thing readable. */
    public AttachedSession attach(String name, int columns, int rows) {
        ServerConnection connection = ServerConnection.open(ServerHome.socket(home))
                .orElseThrow(() -> SessionServerException.noServer(name));
        boolean handedOver = false;
        try {
            JsonNode reply = connection.request(Protocol.OP_ATTACH,
                    named(name).put(Protocol.F_COLUMNS, columns).put(Protocol.F_ROWS, rows));
            checkOk(reply);
            handedOver = true;
            return new SocketAttachedSession(name, connection);
        } catch (ServerConnection.NoServerException e) {
            throw SessionServerException.noServer(name);
        } catch (IOException e) {
            throw new SessionServerException("attach to " + name + " failed: " + e.getMessage(), e);
        } finally {
            if (!handedOver) {
                connection.close();
            }
        }
    }

    /** Asks the server to exit (it kills its sessions); returns once the socket is closed.
     * No-op if none runs. */
    public void shutdown() {
        Optional<ServerConnection> opened = ServerConnection.open(ServerHome.socket(home));
        if (opened.isEmpty()) {
            return;
        }
        try (ServerConnection connection = opened.get()) {
            checkOk(connection.request(Protocol.OP_SHUTDOWN, null));
            connection.drainToEof();
        } catch (ServerConnection.NoServerException e) {
            // It went away before answering: shut down is shut down.
        } catch (IOException e) {
            throw new SessionServerException("shutdown failed: " + e.getMessage(), e);
        }
    }

    /** One request/response over a fresh connection. Empty means no server is running;
     * an {@code ok:false} reply (mismatch included) becomes a {@link SessionServerException}. */
    private Optional<JsonNode> request(String op, ObjectNode args) {
        Optional<ServerConnection> opened = ServerConnection.open(ServerHome.socket(home));
        if (opened.isEmpty()) {
            return Optional.empty();
        }
        try (ServerConnection connection = opened.get()) {
            JsonNode reply = connection.request(op, args);
            checkOk(reply);
            return Optional.of(reply);
        } catch (ServerConnection.NoServerException e) {
            return Optional.empty();
        } catch (IOException e) {
            throw new SessionServerException(op + " failed: " + e.getMessage(), e);
        }
    }

    private static void checkOk(JsonNode reply) {
        if (reply.path(Protocol.F_OK).asBoolean(false)) {
            return;
        }
        String error = JsonDecoding.text(reply, Protocol.F_ERROR);
        if (error.startsWith(UNKNOWN_OP_PREFIX)) {
            // Same protocol, older build: it predates an operation added without a bump.
            throw SessionServerException.unsupported(error.substring(UNKNOWN_OP_PREFIX.length()));
        }
        if (Protocol.ERROR_PROTOCOL_MISMATCH.equals(error)) {
            throw new SessionServerException("jmux " + JsonDecoding.text(reply, Protocol.F_VERSION)
                    + " (pid " + reply.path(Protocol.F_PID).asLong(0) + ") speaks protocol "
                    + reply.path(Protocol.F_PROTOCOL).asInt(0) + "; this app needs " + Protocol.VERSION
                    + ". Finish its sessions or run `jmux shutdown`, then retry");
        }
        throw new SessionServerException(error.isEmpty() ? "request refused" : error);
    }

    /** Makes sure a server answers, spawning one when allowed. Concurrent callers share
     * the spawn in flight; the poll ends on the first ping or at the deadline. */
    private void ensureServer() {
        if (isServerRunning()) {
            return;
        }
        if (!spawn) {
            throw new SessionServerException("no session server is running at " + home
                    + " and this client does not spawn one", null, true);
        }
        CompletableFuture<Void> inFlight;
        synchronized (this) {
            if (spawnInFlight == null || spawnInFlight.isDone()) {
                spawnInFlight = CompletableFuture.runAsync(this::spawnAndWait);
            }
            inFlight = spawnInFlight;
        }
        try {
            inFlight.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof SessionServerException cause) {
                throw cause;
            }
            throw new SessionServerException("starting the session server failed", e.getCause());
        }
    }

    private void spawnAndWait() {
        Process process = spawner.apply(home);
        long deadline = System.nanoTime() + CONNECT_TIMEOUT_SECONDS * 1_000_000_000L;
        while (true) {
            // ping() throws on a protocol mismatch: a connected outcome that ends the poll.
            if (ping().isPresent()) {
                return;
            }
            // A spawned server that failed will never start listening (a socket path over
            // the OS limit, an unwritable home): fail now with its own words instead of
            // making the user wait out the full deadline. Exit 0 is different: it means a
            // concurrently spawned peer already owns the lock and is about to listen (see
            // SessionServer.start), so that case keeps polling.
            if (!process.isAlive() && process.exitValue() != 0) {
                throw new SessionServerException("jmux exited with status "
                        + process.exitValue() + " before listening on " + ServerHome.socket(home)
                        + logTail(ServerHome.log(home)));
            }
            if (System.nanoTime() >= deadline) {
                throw new SessionServerException("jmux did not start listening on "
                        + ServerHome.socket(home) + " within " + CONNECT_TIMEOUT_SECONDS + " s; see "
                        + ServerHome.log(home));
            }
            try {
                Thread.sleep(SPAWN_POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SessionServerException("interrupted while waiting for the session server", e);
            }
        }
    }

    private static ObjectNode named(String name) {
        return Json.object().put(Protocol.F_NAME, name);
    }
    /** The last few lines of the server log, formatted for an exception message. */
    private static String logTail(Path log) {
        try {
            List<String> lines = java.nio.file.Files.readAllLines(log);
            List<String> tail = lines.subList(Math.max(0, lines.size() - 5), lines.size());
            return tail.isEmpty() ? "" : "; " + log + " ends with:\n" + String.join("\n", tail);
        } catch (java.io.IOException e) {
            return "";
        }
    }
}
