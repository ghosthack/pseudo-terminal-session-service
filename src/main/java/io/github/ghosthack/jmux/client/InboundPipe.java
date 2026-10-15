package io.github.ghosthack.jmux.client;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayDeque;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The bounded byte buffer between an attachment's socket reader thread and whoever calls
 * {@link SocketAttachedSession#read}. The bound is deliberate: a widget that stops
 * reading fills this, then the socket, and the server's per-client policy takes over —
 * backpressure instead of an unbounded heap. {@link #eof} and {@link #fail} end the
 * stream after whatever is already buffered has been read; {@link #close} ends it now.
 */
final class InboundPipe {

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition readable = lock.newCondition();
    private final Condition writable = lock.newCondition();
    private final ArrayDeque<byte[]> chunks = new ArrayDeque<>();
    private final int capacity;
    private int headOffset;
    private int buffered;
    private boolean eof;
    private boolean closed;
    private String failure;
    private boolean failureReported;

    InboundPipe(int capacity) {
        this.capacity = capacity;
    }

    /** Blocks while the pipe is full; returns false once the pipe has been closed. */
    boolean append(byte[] bytes) throws InterruptedException {
        if (bytes.length == 0) {
            return !closed;
        }
        lock.lock();
        try {
            while (buffered > 0 && buffered + bytes.length > capacity && !closed && !eof) {
                writable.await();
            }
            if (closed || eof) {
                return false;
            }
            chunks.add(bytes);
            buffered += bytes.length;
            readable.signalAll();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** -1 at the end of the stream; throws once when the server refused the attachment
     * mid-stream, then behaves as ended. */
    int read(byte[] buf, int off, int len) throws IOException {
        if (len == 0) {
            return 0;
        }
        lock.lock();
        try {
            while (chunks.isEmpty() && !eof && !closed && failure == null) {
                readable.await();
            }
            if (chunks.isEmpty()) {
                if (failure != null && !failureReported) {
                    failureReported = true;
                    throw new IOException(failure);
                }
                return -1;
            }
            int copied = 0;
            while (copied < len && !chunks.isEmpty()) {
                byte[] head = chunks.peek();
                int n = Math.min(len - copied, head.length - headOffset);
                System.arraycopy(head, headOffset, buf, off + copied, n);
                copied += n;
                headOffset += n;
                buffered -= n;
                if (headOffset == head.length) {
                    chunks.poll();
                    headOffset = 0;
                }
            }
            writable.signalAll();
            return copied;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // InterruptedIOException specifically (not the plain IOException supertype):
            // that's the JDK's own signal for "an I/O op was interrupted", and jediterm's
            // TerminalStarter special-cases it as an expected, quiet shutdown path (its
            // widget stops a session by Future.cancel(true), i.e. by interrupting this
            // exact thread) rather than logging it as an uncaught emulator error.
            InterruptedIOException wrapped = new InterruptedIOException("interrupted while reading");
            wrapped.initCause(e);
            throw wrapped;
        } finally {
            lock.unlock();
        }
    }

    /** The server ended the stream cleanly: buffered bytes stay readable, then -1. */
    void eof() {
        lock.lock();
        try {
            eof = true;
            readable.signalAll();
            writable.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** The server refused the attachment: buffered bytes stay readable, then an
     * {@link IOException} with {@code message}, then -1. */
    void fail(String message) {
        lock.lock();
        try {
            if (failure == null) {
                failure = message;
            }
            eof = true;
            readable.signalAll();
            writable.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** Local close: pending reads return -1 immediately and buffered bytes are dropped. */
    void close() {
        lock.lock();
        try {
            closed = true;
            chunks.clear();
            buffered = 0;
            readable.signalAll();
            writable.signalAll();
        } finally {
            lock.unlock();
        }
    }
}
