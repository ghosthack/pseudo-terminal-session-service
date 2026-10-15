package io.github.ghosthack.jmux.server;

import io.github.ghosthack.jmux.protocol.Frame;
import io.github.ghosthack.jmux.protocol.FrameCodec;
import io.github.ghosthack.jmux.protocol.FrameType;
import io.github.ghosthack.jmux.protocol.Json;
import io.github.ghosthack.jmux.protocol.Protocol;
import io.github.ghosthack.jmux.session.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The server's view of one attached connection: a bounded outbound byte queue and the
 * writer thread that drains it — the only thing that ever writes to the socket once the
 * connection is in attach mode. The pump ({@link #onOutput}) and attach only enqueue.
 *
 * <p>Overflow policy (tmux's blocked-client behaviour): when a chunk would not fit behind
 * what is already queued, the queued live bytes are dropped, the client is marked BEHIND
 * and further output is discarded; when the writer finds the queue empty it asks the
 * session for a fresh snapshot ({@link Session#snapshotFor}), which arrives through
 * {@link #onSnapshot} under the session lock — that is where BEHIND is cleared, so no live
 * batch can slip in ahead of the redraw. A client still BEHIND when the session ends is
 * re-synced once more before SESSION_EXITED, so it shows the final screen rather than
 * whatever it had when it fell behind. A single chunk is always accepted into an
 * empty queue whatever its size, so a snapshot larger than the buffer cannot loop the
 * client through BEHIND for ever. A BEHIND client that completes no write for
 * {@link ServeOptions#behindTimeout()} is closed with an error RESPONSE (best effort: the
 * socket is by definition not draining) and its connection unwinds through
 * {@link ClientConnection}, which detaches it.
 *
 * <p>Nothing here blocks the caller of {@link #onOutput} or {@link #onExit}.
 */
final class AttachedClient implements Session.Client {

    private static final Logger log = LoggerFactory.getLogger(AttachedClient.class);

    /** How long the watchdog gives the writer to deliver the error RESPONSE before it
     * closes the socket underneath it. */
    private static final Duration ERROR_DELIVERY_GRACE = Duration.ofSeconds(1);

    private final Session session;
    private final SocketChannel channel;
    private final FrameCodec codec;
    private final int capacity;
    private final Duration behindTimeout;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition ready = lock.newCondition();
    private final ArrayDeque<byte[]> queue = new ArrayDeque<>();
    private int queued;
    private boolean behind;
    /** A re-snapshot has been requested and not yet handed over ({@link #onSnapshot}). */
    private boolean resyncRequested;
    private boolean watchdogArmed;
    /** SESSION_EXITED or the error RESPONSE: written after the queue drains, then the
     * socket is closed. Null while the attachment is healthy. */
    private Frame terminal;
    private boolean closed;
    private long lastProgressNanos = System.nanoTime();

    private final Thread writer;

    AttachedClient(Session session, SocketChannel channel, FrameCodec codec, ServeOptions options) {
        this.session = session;
        this.channel = channel;
        this.codec = codec;
        this.capacity = options.clientBufferBytes();
        this.behindTimeout = options.behindTimeout();
        this.writer = Thread.ofVirtual().name("hss-writer-" + session.name()).unstarted(this::drain);
    }

    void start() {
        writer.start();
    }

    @Override
    public void onOutput(byte[] bytes) {
        if (bytes.length == 0) {
            return;
        }
        lock.lock();
        try {
            if (closed || terminal != null || behind) {
                return;
            }
            if (queued > 0 && queued + bytes.length > capacity) {
                queue.clear();
                queued = 0;
                behind = true;
                armWatchdog();
                log.debug("client of {} fell behind; dropped its queue", session.name());
                return;
            }
            queue.add(bytes);
            queued += bytes.length;
            ready.signalAll();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void onSnapshot(byte[] bytes) {
        lock.lock();
        try {
            if (closed) {
                return;
            }
            // Called under the session lock: clearing BEHIND here (and not a moment earlier
            // in the writer) is what guarantees nothing precedes the redraw.
            behind = false;
            resyncRequested = false;
            queue.clear();
            queued = 0;
            if (bytes.length > 0) {
                queue.add(bytes);
                queued = bytes.length;
            }
            ready.signalAll();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void onExit(int exitCode) {
        String json = Json.object().put(Protocol.F_NAME, session.name())
                .put(Protocol.F_EXIT_CODE, exitCode).toString();
        terminate(Frame.json(FrameType.SESSION_EXITED, json));
    }

    /** Stops the writer and closes the socket; idempotent. Called by the connection when
     * its inbound side ends (DETACH, EOF, error). */
    void close() {
        lock.lock();
        try {
            closed = true;
            ready.signalAll();
        } finally {
            lock.unlock();
        }
        closeChannel();
    }

    private void terminate(Frame frame) {
        lock.lock();
        try {
            if (closed || terminal != null) {
                return;
            }
            terminal = frame;
            ready.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** The writer loop: live bytes in order, a re-snapshot when BEHIND has drained (also
     * once before the terminal frame, so a client that fell behind still sees the final
     * screen), and finally the terminal frame, after which the socket is closed. */
    private void drain() {
        try {
            while (true) {
                Frame next = null;
                boolean resnapshot = false;
                lock.lock();
                try {
                    while (true) {
                        if (closed) {
                            return;
                        }
                        if (!queue.isEmpty()) {
                            byte[] chunk = queue.poll();
                            queued -= chunk.length;
                            next = Frame.of(FrameType.DATA_OUT, chunk);
                            break;
                        }
                        if (behind && !resyncRequested) {
                            // Drained while BEHIND: ask for a snapshot. BEHIND stays set until
                            // onSnapshot hands it over under the session lock.
                            resyncRequested = true;
                            resnapshot = true;
                            break;
                        }
                        if (terminal != null && (!behind || resyncRequested)) {
                            // Healthy, or the re-sync was requested and either landed (queue
                            // drained above) or can no longer arrive: send the terminal frame.
                            next = terminal;
                            break;
                        }
                        ready.await();
                    }
                } finally {
                    lock.unlock();
                }
                if (resnapshot) {
                    try {
                        session.snapshotFor(this);
                    } catch (RuntimeException e) {
                        // Leave resyncRequested set: the terminal frame (if any) goes out on
                        // the next pass, and otherwise the watchdog drops the client.
                        log.warn("re-snapshot for a client of {} failed", session.name(), e);
                    }
                    continue;
                }
                codec.write(next);
                markProgress();
                if (next.type() != FrameType.DATA_OUT) {
                    closeChannel();
                    return;
                }
            }
        } catch (IOException e) {
            // The client went away or the connection was closed underneath us; the reader
            // side sees the same and detaches.
            log.debug("writer for {} stopped: {}", session.name(), e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            log.warn("writer for {} failed", session.name(), e);
        } finally {
            closeChannel();
        }
    }

    private void markProgress() {
        lock.lock();
        try {
            lastProgressNanos = System.nanoTime();
        } finally {
            lock.unlock();
        }
    }

    /** Must hold {@link #lock}. */
    private void armWatchdog() {
        if (watchdogArmed) {
            return;
        }
        watchdogArmed = true;
        Thread.ofVirtual().name("hss-watchdog-" + session.name()).start(this::watch);
    }

    /** Sleeps until the BEHIND client has either recovered or gone {@code behindTimeout}
     * without a completed write, then drops it. */
    private void watch() {
        try {
            while (true) {
                long waitNanos;
                lock.lock();
                try {
                    if (!behind || closed || terminal != null) {
                        watchdogArmed = false;
                        return;
                    }
                    waitNanos = lastProgressNanos + behindTimeout.toNanos() - System.nanoTime();
                    if (waitNanos <= 0) {
                        watchdogArmed = false;
                        break;
                    }
                } finally {
                    lock.unlock();
                }
                Thread.sleep(Duration.ofNanos(waitNanos));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        log.info("client of {} stayed behind for {}; dropping it", session.name(), behindTimeout);
        terminate(Frame.json(FrameType.RESPONSE, Json.error("client fell behind and did not drain within "
                + behindTimeout.toSeconds() + " s; detached").toString()));
        try {
            writer.join(ERROR_DELIVERY_GRACE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        closeChannel();
    }

    private void closeChannel() {
        try {
            channel.close();
        } catch (IOException ignored) {
            // Already closed by the other side or the reader; nothing else to release.
        }
    }
}
