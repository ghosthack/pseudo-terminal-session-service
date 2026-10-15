package io.github.ghosthack.jmux.server;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.ghosthack.jmux.protocol.Protocol;
import io.github.ghosthack.jmux.session.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The daemon's transport: a Unix-domain listener on {@code <home>/server.sock} handing
 * every accepted socket to a {@link ClientConnection} on a virtual thread.
 *
 * <p>Startup order is fixed (SESSION-HOST.md "Lifecycle"): (1) take {@code server.lock};
 * if another server holds it, {@link #start} returns false having touched nothing else;
 * (2) holding the lock, probe-connect the socket file and delete it when nobody answers —
 * the JDK never unlinks a bound socket file on close, so a stale file is the normal state
 * after every exit; (3) bind; (4) write the pid into the lock file for diagnostics.
 * Shutdown (the {@code shutdown} op, the idle timer, or {@link #close}) kills every
 * session, closes the listener and the open connections, deletes the socket file and
 * releases the lock, in that order, then releases {@link #awaitExit}.
 */
public final class SessionServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SessionServer.class);

    private final ServeOptions options;
    private final SessionManager manager;
    private final RequestDispatcher dispatcher;
    private final Set<ClientConnection> connections = ConcurrentHashMap.newKeySet();
    private final CountDownLatch exited = new CountDownLatch(1);
    private final AtomicBoolean shuttingDown = new AtomicBoolean();
    private final long pid = ProcessHandle.current().pid();
    private final String version = ServerVersion.current();

    private ServerLock lock;
    private ServerSocketChannel listener;

    public SessionServer(ServeOptions options, SessionManager manager) {
        this.options = options;
        this.manager = manager;
        this.dispatcher = new RequestDispatcher(manager, this);
    }

    /**
     * Runs the startup sequence and begins accepting on a background thread. Returns
     * false — with a log line and nothing bound, deleted or written — when another server
     * owns the home; the caller exits 0 in that case.
     */
    public boolean start() throws IOException {
        Path home = options.home();
        Files.createDirectories(home);
        Optional<ServerLock> acquired = ServerLock.tryAcquire(ServerHome.lock(home));
        if (acquired.isEmpty()) {
            log.info("another server owns {}; exiting", home);
            return false;
        }
        lock = acquired.get();
        try {
            Path socket = ServerHome.socket(home);
            removeStaleSocket(socket);
            listener = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
            try {
                listener.bind(UnixDomainSocketAddress.of(socket));
            } catch (IOException e) {
                // The JDK's message ("Unix domain path too long") names neither the path
                // nor the fix; this is the one bind failure a user can act on directly.
                if (e.getMessage() != null && e.getMessage().toLowerCase().contains("too long")) {
                    throw new IOException("cannot bind " + socket + ": the socket path is "
                            + socket.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                            + " bytes, over the OS limit for Unix-domain sockets (104 on macOS, 108 on"
                            + " Linux and Windows); set " + ServerHome.HOME_ENV
                            + " (or -D" + ServerHome.HOME_PROPERTY + ") to a shorter directory", e);
                }
                throw e;
            }
            lock.writePid(pid);
        } catch (IOException | RuntimeException e) {
            lock.close();
            lock = null;
            throw e;
        }
        manager.setListener(this::onLastSessionEnded);
        Thread.ofVirtual().name("hss-accept").start(this::acceptLoop);
        log.info("jmux {} (protocol {}, pid {}) listening on {}",
                version, Protocol.VERSION, pid, ServerHome.socket(home));
        return true;
    }

    /** Blocks until the server has shut down. */
    public void awaitExit() throws InterruptedException {
        exited.await();
    }

    /** True when the server shut down within {@code timeout}. */
    public boolean awaitExitWithin(Duration timeout) throws InterruptedException {
        return exited.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
    }

    public boolean isRunning() {
        return listener != null && listener.isOpen();
    }

    public Path home() {
        return options.home();
    }

    public long pid() {
        return pid;
    }

    public String version() {
        return version;
    }

    /** Adds {@code protocol}, {@code version} and {@code pid} — the ping reply and the
     * fields a protocol-mismatch error carries. */
    ObjectNode identity(ObjectNode body) {
        return body.put(Protocol.F_PROTOCOL, Protocol.VERSION)
                .put(Protocol.F_VERSION, version)
                .put(Protocol.F_PID, pid);
    }

    /** Kills every session, stops listening, closes every connection, removes the socket
     * file and releases the lock. Safe to call from any thread and more than once. */
    public void shutdown() {
        if (!shuttingDown.compareAndSet(false, true)) {
            return;
        }
        log.info("shutting down");
        try {
            manager.killAll();
        } catch (RuntimeException e) {
            log.warn("killAll failed; continuing shutdown", e);
        }
        closeListener();
        // Socket file and lock go before the connections: the client that asked for the
        // shutdown sees EOF only once a follow-up `new` can already take the home over.
        try {
            Files.deleteIfExists(ServerHome.socket(options.home()));
        } catch (IOException e) {
            log.warn("could not delete socket file", e);
        }
        if (lock != null) {
            lock.close();
        }
        for (ClientConnection connection : connections) {
            connection.close();
        }
        exited.countDown();
    }

    @Override
    public void close() {
        if (lock == null) {
            return; // start() never got the lock: nothing of ours to release
        }
        shutdown();
    }

    void forget(ClientConnection connection) {
        connections.remove(connection);
    }

    /** The open connections (tests). */
    Set<ClientConnection> connections() {
        return Set.copyOf(connections);
    }

    private void onLastSessionEnded() {
        if (options.keepAlive()) {
            log.info("no sessions left; --keep-alive keeps the server up");
            return;
        }
        log.info("no sessions left; exiting");
        // Off the manager's timer thread: shutdown calls back into the manager.
        Thread.ofVirtual().name("hss-idle-exit").start(this::shutdown);
    }

    /** Step (2): under the lock, a socket file nobody answers on is stale and removed; one
     * that answers means a live server without the lock, which is not ours to replace. */
    private static void removeStaleSocket(Path socket) throws IOException {
        if (!Files.exists(socket)) {
            return;
        }
        boolean answered;
        try (SocketChannel probe = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            probe.connect(UnixDomainSocketAddress.of(socket));
            answered = true;
        } catch (IOException e) {
            answered = false; // missing file or ECONNREFUSED: nobody is listening
        }
        if (answered) {
            throw new IOException(socket + " is in use by a server that does not hold the lock");
        }
        log.info("removing stale socket file {}", socket);
        Files.deleteIfExists(socket);
    }

    private void acceptLoop() {
        while (true) {
            SocketChannel accepted;
            try {
                accepted = listener.accept();
            } catch (ClosedChannelException e) {
                return;
            } catch (IOException e) {
                if (!listener.isOpen()) {
                    return;
                }
                log.warn("accept failed", e);
                continue;
            }
            ClientConnection connection = new ClientConnection(accepted, this, dispatcher, options);
            connections.add(connection);
            Thread.ofVirtual().name("hss-conn").start(connection);
        }
    }

    private void closeListener() {
        if (listener == null) {
            return;
        }
        try {
            listener.close();
        } catch (IOException e) {
            log.debug("closing listener: {}", e.toString());
        }
    }
}
