package io.github.ghosthack.jmux.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.ghosthack.jmux.protocol.Frame;
import io.github.ghosthack.jmux.protocol.FrameCodec;
import io.github.ghosthack.jmux.protocol.FrameType;
import io.github.ghosthack.jmux.protocol.Json;
import io.github.ghosthack.jmux.protocol.Protocol;
import io.github.ghosthack.jmux.session.Session;
import io.github.ghosthack.jmux.session.SessionEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * One accepted socket, on its own virtual thread. Starts as a control connection (any
 * number of request/response pairs, answered from this thread) and, after a successful
 * {@code attach}, becomes dedicated to that session: from then on this thread only reads
 * (DATA_IN, RESIZE, DETACH) and the {@link AttachedClient}'s writer owns the socket's
 * outbound side. Whatever ends the inbound loop — DETACH, EOF, a bad frame, the session
 * exiting — the client is detached from the session and the socket closed exactly once.
 *
 * <p>A successful {@code watch} likewise makes the connection dedicated: a
 * {@link WatchStream} is registered on the watched sessions (on all of them, and on every
 * one created later, when no names were given) and owns the outbound side; this thread then
 * only waits for DETACH or EOF, and closing unregisters it everywhere.
 */
final class ClientConnection implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(ClientConnection.class);

    private final SocketChannel channel;
    private final FrameCodec codec;
    private final SessionServer server;
    private final RequestDispatcher dispatcher;
    private final ServeOptions options;

    private final AtomicBoolean closed = new AtomicBoolean();

    private volatile Session session;
    private volatile AttachedClient client;
    private volatile WatchStream watch;
    /** Every session the watch is registered on, with the per-session watcher it was
     * registered as, to unregister on close. An entry leaves when its session exits, so a
     * long-lived watch of all sessions does not keep every ended session reachable. */
    private final Map<Session, Session.Watcher> watched = new ConcurrentHashMap<>();
    private volatile Consumer<Session> watchNewSessions;

    ClientConnection(SocketChannel channel, SessionServer server, RequestDispatcher dispatcher, ServeOptions options) {
        this.channel = channel;
        this.codec = new FrameCodec(Channels.newInputStream(channel), Channels.newOutputStream(channel));
        this.server = server;
        this.dispatcher = dispatcher;
        this.options = options;
    }

    @Override
    public void run() {
        try {
            while (true) {
                Frame frame = codec.read();
                if (frame == null) {
                    return;
                }
                if (client != null) {
                    if (!handleAttached(frame)) {
                        return;
                    }
                } else if (watch != null) {
                    if (frame.type() == FrameType.DETACH) {
                        return;
                    }
                    log.debug("ignoring {} frame in watch mode", frame.type());
                } else if (!handleControl(frame)) {
                    return;
                }
            }
        } catch (IOException e) {
            // EOF mid-frame, a reset, or our own close from the writer/watchdog/shutdown.
            log.debug("connection ended: {}", e.toString());
        } catch (RuntimeException e) {
            log.warn("connection handler failed", e);
        } finally {
            close();
        }
    }

    /** Closes the socket (and the attach-mode writer) from any thread; idempotent. */
    void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (client != null) {
            session.detach(client);
            client.close();
        }
        if (watch != null) {
            Consumer<Session> listener = watchNewSessions;
            if (listener != null) {
                dispatcher.manager().removeCreatedListener(listener);
            }
            watched.forEach(Session::removeWatcher);
            watched.clear();
            watch.close();
        }
        try {
            channel.close();
        } catch (IOException ignored) {
            // Nothing else to release for a socket that will not close.
        }
        server.forget(this);
    }

    /** False when the connection must end after this frame. */
    private boolean handleControl(Frame frame) throws IOException {
        if (frame.type() != FrameType.REQUEST) {
            codec.write(response(Json.error("expected a request frame, got " + frame.type())));
            return false;
        }
        JsonNode request;
        try {
            request = Json.parse(frame.payload());
        } catch (IOException e) {
            codec.write(response(Json.error("malformed request: " + e.getMessage())));
            return false;
        }
        if (!request.isObject()) {
            codec.write(response(Json.error("request must be a JSON object")));
            return false;
        }
        JsonNode protocol = request.get(Protocol.F_PROTOCOL);
        if (protocol == null || !protocol.canConvertToInt() || protocol.asInt() != Protocol.VERSION) {
            codec.write(response(server.identity(Json.error(Protocol.ERROR_PROTOCOL_MISMATCH))));
            return false;
        }
        String op = JsonEncoding.textField(request, Protocol.F_OP);
        if (op == null) {
            codec.write(response(Json.error("request has no op")));
            return true;
        }
        switch (op) {
            case Protocol.OP_ATTACH:
                return attach(request);
            case Protocol.OP_WATCH:
                return watch(request);
            case Protocol.OP_SHUTDOWN:
                codec.write(response(Json.ok()));
                // Synchronous on purpose: the client's shutdown() returns when this socket
                // closes, and that must be after the lock is released so a follow-up `new`
                // cannot spawn a server that loses the lock to a dying one.
                server.shutdown();
                return false;
            default:
                ObjectNode reply;
                try {
                    reply = dispatcher.handle(op, request);
                } catch (RuntimeException e) {
                    // A server-side failure must come back as an answer, not as a silently
                    // closed socket the client can only report as "stopped answering".
                    log.warn("request {} failed", op, e);
                    reply = Json.error("internal error handling " + op + ": " + e);
                }
                codec.write(response(reply != null ? reply : Json.error("unknown op: " + op)));
                return true;
        }
    }

    private boolean attach(JsonNode request) throws IOException {
        Optional<Session> found = dispatcher.find(request);
        if (found.isEmpty()) {
            String name = JsonEncoding.textField(request, Protocol.F_NAME);
            codec.write(response(RequestDispatcher.noSuchSession(name == null ? "" : name)));
            return true;
        }
        int columns = JsonEncoding.intField(request, Protocol.F_COLUMNS, 80);
        int rows = JsonEncoding.intField(request, Protocol.F_ROWS, 24);
        Optional<String> badSize = RequestDispatcher.invalidSize(columns, rows);
        if (badSize.isPresent()) {
            codec.write(response(Json.error(badSize.get())));
            return true;
        }
        // The ok goes out from this thread before the writer exists, so it precedes every
        // DATA_OUT; from here on only the writer touches the outbound side.
        codec.write(response(Json.ok()));
        session = found.get();
        client = new AttachedClient(session, channel, codec, options);
        client.start();
        session.attach(client, columns, rows);
        return true;
    }

    private boolean watch(JsonNode request) throws IOException {
        List<String> names = new ArrayList<>();
        JsonNode namesNode = request.get(Protocol.F_NAMES);
        if (namesNode != null && !namesNode.isNull()) {
            if (!namesNode.isArray()) {
                codec.write(response(Json.error("names must be an array of session names")));
                return true;
            }
            for (JsonNode name : namesNode) {
                if (!name.isTextual()) {
                    codec.write(response(Json.error("names must be an array of session names")));
                    return true;
                }
                names.add(name.asText());
            }
        }
        List<Session> sessions = new ArrayList<>();
        for (String name : names) {
            Optional<Session> found = dispatcher.manager().find(name);
            if (found.isEmpty()) {
                codec.write(response(RequestDispatcher.noSuchSession(name)));
                return true;
            }
            sessions.add(found.get());
        }
        long quietMs = RequestDispatcher.clampMs(JsonEncoding.longField(request, Protocol.F_QUIET_MS,
                Protocol.WATCH_DEFAULT_QUIET_MS));
        // As with attach: the ok precedes every event because the writer starts after it.
        codec.write(response(Json.ok()));
        WatchStream stream = new WatchStream(channel, codec, quietMs);
        watch = stream;
        stream.start();
        if (names.isEmpty()) {
            // Listener first, then the current list: a session created in between is seen
            // twice, and register() registers it once.
            Consumer<Session> listener = created -> register(stream, created);
            watchNewSessions = listener;
            dispatcher.manager().addCreatedListener(listener);
            sessions = dispatcher.manager().sessions();
        }
        for (Session s : sessions) {
            register(stream, s);
        }
        return true;
    }

    private void register(WatchStream stream, Session s) {
        if (closed.get()) {
            return;
        }
        SessionWatch watcher = new SessionWatch(stream, s, watched);
        if (watched.putIfAbsent(s, watcher) != null) {
            return; // seen twice (created listener and the list): already registered
        }
        // An exited session emits its exit from inside addWatcher, which removes it again.
        s.addWatcher(watcher);
        if (closed.get()) {
            s.removeWatcher(watcher); // close() ran in between and may have missed it
        }
    }

    /** The sessions the watch is currently registered on (tests). */
    Set<Session> watchedSessions() {
        return Set.copyOf(watched.keySet());
    }

    /**
     * The watcher one session of a watch is registered as: forwards to the connection's
     * {@link WatchStream} and, on the session's {@code exit} (its last event: the session
     * drops its watchers then), forgets the session. Runs under the session lock like any
     * watcher; a map removal does not block. Identity equality on purpose: sessions key
     * their watchers by it.
     */
    private static final class SessionWatch implements Session.Watcher {

        private final WatchStream stream;
        private final Session session;
        private final Map<Session, Session.Watcher> watched;

        SessionWatch(WatchStream stream, Session session, Map<Session, Session.Watcher> watched) {
            this.stream = stream;
            this.session = session;
            this.watched = watched;
        }

        @Override
        public void onEvent(SessionEvent event) {
            stream.onEvent(event);
            if (Protocol.EVENT_EXIT.equals(event.type())) {
                watched.remove(session, this);
            }
        }

        @Override
        public long quietMs() {
            return stream.quietMs();
        }
    }

    private boolean handleAttached(Frame frame) {
        switch (frame.type()) {
            case DATA_IN:
                session.write(frame.payload());
                return true;
            case RESIZE:
                try {
                    JsonNode size = Json.parse(frame.payload());
                    int columns = JsonEncoding.intField(size, Protocol.F_COLUMNS, 80);
                    int rows = JsonEncoding.intField(size, Protocol.F_ROWS, 24);
                    Optional<String> badSize = RequestDispatcher.invalidSize(columns, rows);
                    if (badSize.isPresent()) {
                        log.debug("ignoring RESIZE: {}", badSize.get());
                        return true;
                    }
                    session.resize(columns, rows, client);
                } catch (IOException e) {
                    log.debug("ignoring malformed RESIZE: {}", e.getMessage());
                }
                return true;
            case DETACH:
                return false;
            default:
                log.debug("ignoring {} frame in attach mode", frame.type());
                return true;
        }
    }

    private static Frame response(ObjectNode body) {
        return Frame.of(FrameType.RESPONSE, Json.bytes(body));
    }
}
