package io.github.ghosthack.jmux.client;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.ghosthack.jmux.protocol.Frame;
import io.github.ghosthack.jmux.protocol.FrameCodec;
import io.github.ghosthack.jmux.protocol.FrameType;
import io.github.ghosthack.jmux.protocol.Json;
import io.github.ghosthack.jmux.protocol.Protocol;
import io.github.ghosthack.jmux.session.SessionEvent;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * {@link SessionServerClient.Watch} over one watch-mode connection: a virtual reader thread
 * hands each event RESPONSE to the consumer, in order. The stream ends on EOF, on an
 * {@code ok:false} RESPONSE (the server dropping a watcher that fell behind), or on
 * {@link #close}, which sends DETACH first.
 */
final class SocketWatch implements SessionServerClient.Watch {

    private final ServerConnection connection;
    private final FrameCodec codec;
    private final Consumer<SessionEvent> onEvent;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile boolean open = true;
    private volatile String error;

    SocketWatch(ServerConnection connection, Consumer<SessionEvent> onEvent) {
        this.connection = connection;
        this.codec = connection.codec();
        this.onEvent = onEvent;
        Thread.ofVirtual().name("hss-watch-reader").start(this::pump);
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public Optional<String> error() {
        return Optional.ofNullable(error);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (open) {
            try {
                codec.write(Frame.of(FrameType.DETACH, new byte[0]));
            } catch (IOException ignored) {
                // The server is gone or already closed the socket; closing below suffices.
            }
        }
        open = false;
        connection.close();
    }

    private void pump() {
        try {
            while (true) {
                Frame frame = codec.read();
                if (frame == null || frame.type() != FrameType.RESPONSE) {
                    if (frame == null) {
                        return;
                    }
                    continue; // not part of the watch vocabulary
                }
                JsonNode body = Json.parse(frame.payload());
                if (!body.path(Protocol.F_OK).asBoolean(false)) {
                    error = JsonDecoding.text(body, Protocol.F_ERROR);
                    return;
                }
                if (body.has(Protocol.F_EVENT)) {
                    try {
                        onEvent.accept(JsonDecoding.event(body));
                    } catch (RuntimeException ignored) {
                        // A failing consumer must not end the stream for the events after it.
                    }
                }
            }
        } catch (IOException e) {
            // Server gone, a reset, or our own close(): the stream simply ends.
        } finally {
            open = false;
            connection.close();
        }
    }
}
