package io.github.ghosthack.jmux.server;

import io.github.ghosthack.jmux.protocol.Frame;
import io.github.ghosthack.jmux.protocol.FrameCodec;
import io.github.ghosthack.jmux.protocol.FrameType;
import io.github.ghosthack.jmux.protocol.Json;
import io.github.ghosthack.jmux.session.Session;
import io.github.ghosthack.jmux.session.SessionEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The server's side of one {@code watch} connection: a {@link Session.Watcher} registered
 * on every watched session, a bounded event queue, and the writer thread that turns each
 * event into a RESPONSE frame — the only thing that writes to the socket once the
 * connection is in watch mode. {@link #onEvent} runs under a session lock and only
 * enqueues. A watcher that lets {@link #CAPACITY} events pile up is dropped: the queue is
 * discarded, an error RESPONSE is written and the socket closed, and the connection's
 * reader unwinds and unregisters it. The error frame is best-effort: a peer that stopped
 * reading leaves the writer blocked in a write, so the overflow also starts a closer that
 * gives the writer {@link #OVERFLOW_GRACE_MS} to get the error out and then closes the
 * socket regardless, which unblocks the writer and the reader alike.
 */
final class WatchStream implements Session.Watcher {

    private static final Logger log = LoggerFactory.getLogger(WatchStream.class);

    /** Events, not bytes: activity is edge-triggered, so a healthy reader never gets near. */
    static final int CAPACITY = 4096;

    /** How long an overflowed watch's writer gets to deliver the error before the socket
     * is closed under it. */
    static final long OVERFLOW_GRACE_MS = 2_000;

    private final SocketChannel channel;
    private final FrameCodec codec;
    private final long quietMs;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition ready = lock.newCondition();
    private final ArrayDeque<SessionEvent> queue = new ArrayDeque<>();
    private boolean overflowed;
    private boolean closed;

    private final Thread writer;

    WatchStream(SocketChannel channel, FrameCodec codec, long quietMs) {
        this.channel = channel;
        this.codec = codec;
        this.quietMs = quietMs;
        this.writer = Thread.ofVirtual().name("hss-watch-writer").unstarted(this::drain);
    }

    void start() {
        writer.start();
    }

    @Override
    public long quietMs() {
        return quietMs;
    }

    @Override
    public void onEvent(SessionEvent event) {
        lock.lock();
        try {
            if (closed || overflowed) {
                return;
            }
            if (queue.size() >= CAPACITY) {
                queue.clear();
                overflowed = true;
                log.info("a watch fell {} events behind; dropping it", CAPACITY);
                // Off this (session-locked) thread: starting a virtual thread never blocks.
                Thread.ofVirtual().name("hss-watch-overflow").start(this::closeAfterGrace);
            } else {
                queue.add(event);
            }
            ready.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** Stops the writer and closes the socket; idempotent. */
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

    private void drain() {
        try {
            while (true) {
                SessionEvent next;
                boolean dropped;
                lock.lock();
                try {
                    while (!closed && !overflowed && queue.isEmpty()) {
                        ready.await();
                    }
                    if (closed) {
                        return;
                    }
                    dropped = overflowed;
                    next = queue.poll();
                } finally {
                    lock.unlock();
                }
                if (dropped) {
                    codec.write(Frame.of(FrameType.RESPONSE, Json.bytes(Json.error(
                            "watch fell more than " + CAPACITY + " events behind; closed"))));
                    return;
                }
                codec.write(Frame.of(FrameType.RESPONSE, Json.bytes(JsonEncoding.event(next))));
            }
        } catch (IOException e) {
            log.debug("watch writer stopped: {}", e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            log.warn("watch writer failed", e);
        } finally {
            closeChannel();
        }
    }

    /** The overflow closer: waits for the writer to send the error and stop, but not for
     * a writer stuck on a peer that no longer reads. */
    private void closeAfterGrace() {
        try {
            writer.join(Duration.ofMillis(OVERFLOW_GRACE_MS));
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
