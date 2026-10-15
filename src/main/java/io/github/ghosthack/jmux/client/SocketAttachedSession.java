package io.github.ghosthack.jmux.client;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.ghosthack.jmux.protocol.Frame;
import io.github.ghosthack.jmux.protocol.FrameCodec;
import io.github.ghosthack.jmux.protocol.FrameType;
import io.github.ghosthack.jmux.protocol.Json;
import io.github.ghosthack.jmux.protocol.Protocol;

import java.io.IOException;
import java.util.OptionalInt;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link AttachedSession} over one attach-mode connection. A reader thread demultiplexes
 * the inbound frames: DATA_OUT into the {@link InboundPipe} that {@link #read} drains,
 * SESSION_EXITED into {@link #exitCode} followed by EOF, and an {@code ok:false} RESPONSE
 * (the server dropping a client that fell behind) into an {@link IOException} on the next
 * read. Writes go straight to the codec from the calling thread; the codec serializes
 * them against a concurrent {@link #close}.
 */
final class SocketAttachedSession implements AttachedSession {

    /** Enough for a full redraw plus a burst; beyond it the reader blocks and the server
     * sees a slow client (see {@link InboundPipe}). */
    static final int PIPE_CAPACITY = 1024 * 1024;

    private final String name;
    private final ServerConnection connection;
    private final FrameCodec codec;
    private final InboundPipe pipe = new InboundPipe(PIPE_CAPACITY);
    private final CountDownLatch ended = new CountDownLatch(1);
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile boolean connected = true;
    private volatile OptionalInt exitCode = OptionalInt.empty();

    SocketAttachedSession(String name, ServerConnection connection) {
        this.name = name;
        this.connection = connection;
        this.codec = connection.codec();
        Thread.ofVirtual().name("hss-attach-reader-" + name).start(this::pump);
    }

    @Override
    public String sessionName() {
        return name;
    }

    @Override
    public int read(byte[] buf, int off, int len) throws IOException {
        return pipe.read(buf, off, len);
    }

    @Override
    public void write(byte[] bytes) throws IOException {
        send(Frame.of(FrameType.DATA_IN, bytes));
    }

    @Override
    public void resize(int columns, int rows) throws IOException {
        send(Frame.json(FrameType.RESIZE,
                Json.object().put(Protocol.F_COLUMNS, columns).put(Protocol.F_ROWS, rows).toString()));
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public OptionalInt exitCode() {
        return exitCode;
    }

    @Override
    public int waitFor() throws InterruptedException {
        ended.await();
        return exitCode.orElse(-1);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (connected) {
            try {
                codec.write(Frame.of(FrameType.DETACH, new byte[0]));
            } catch (IOException ignored) {
                // The server is gone or already closed the socket; closing below suffices.
            }
        }
        connection.close();
        pipe.close();
        finish();
    }

    private void send(Frame frame) throws IOException {
        if (!connected) {
            throw new IOException("not attached to " + name);
        }
        codec.write(frame);
    }

    private void pump() {
        try {
            while (true) {
                Frame frame = codec.read();
                if (frame == null) {
                    pipe.eof();
                    return;
                }
                switch (frame.type()) {
                    case DATA_OUT -> {
                        if (!pipe.append(frame.payload())) {
                            return;
                        }
                    }
                    case SESSION_EXITED -> {
                        JsonNode body = Json.parse(frame.payload());
                        exitCode = OptionalInt.of(body.path(Protocol.F_EXIT_CODE).asInt(-1));
                        pipe.eof();
                        return;
                    }
                    case RESPONSE -> {
                        JsonNode body = Json.parse(frame.payload());
                        if (!body.path(Protocol.F_OK).asBoolean(false)) {
                            pipe.fail(JsonDecoding.text(body, Protocol.F_ERROR));
                            return;
                        }
                    }
                    default -> {
                        // Not part of the server→client attach vocabulary; skip it.
                    }
                }
            }
        } catch (IOException e) {
            // Server gone, a reset, or our own close(): the stream simply ends.
            pipe.eof();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pipe.eof();
        } finally {
            finish();
            connection.close();
        }
    }

    private void finish() {
        connected = false;
        ended.countDown();
    }
}
