package io.github.ghosthack.jmux.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.ghosthack.jmux.protocol.Frame;
import io.github.ghosthack.jmux.protocol.FrameCodec;
import io.github.ghosthack.jmux.protocol.FrameType;
import io.github.ghosthack.jmux.protocol.Json;
import io.github.ghosthack.jmux.protocol.Protocol;

import java.io.EOFException;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.util.Optional;

/**
 * One socket to the server with its codec. {@link #open} folds "socket file missing" and
 * "connect refused" into an empty result — both mean no server — and {@link #request}
 * stamps every request with the protocol version and turns EOF-before-response into
 * {@link NoServerException}, so callers see exactly the three no-server cases the design
 * names and nothing else in that shape.
 */
final class ServerConnection implements AutoCloseable {

    /** The connection died before the response: the server is not (or no longer) there. */
    static final class NoServerException extends IOException {
        NoServerException(String message) {
            super(message);
        }
    }

    private final SocketChannel channel;
    private final FrameCodec codec;

    private ServerConnection(SocketChannel channel) {
        this.channel = channel;
        this.codec = new FrameCodec(Channels.newInputStream(channel), Channels.newOutputStream(channel));
    }

    static Optional<ServerConnection> open(Path socket) {
        SocketChannel channel = null;
        try {
            channel = SocketChannel.open(StandardProtocolFamily.UNIX);
            channel.connect(UnixDomainSocketAddress.of(socket));
            return Optional.of(new ServerConnection(channel));
        } catch (IOException e) {
            if (channel != null) {
                try {
                    channel.close();
                } catch (IOException ignored) {
                    // Never connected; nothing to release.
                }
            }
            return Optional.empty();
        }
    }

    SocketChannel channel() {
        return channel;
    }

    FrameCodec codec() {
        return codec;
    }

    /** Sends {@code op} with {@code args} (plus the protocol stamp) and returns the
     * response body, which may be {@code ok:false}. */
    JsonNode request(String op, ObjectNode args) throws IOException {
        ObjectNode body = args == null ? Json.object() : args;
        body.put(Protocol.F_OP, op).put(Protocol.F_PROTOCOL, Protocol.VERSION);
        codec.write(Frame.of(FrameType.REQUEST, Json.bytes(body)));
        Frame reply;
        try {
            reply = codec.read();
        } catch (EOFException e) {
            throw new NoServerException("connection closed before the response");
        }
        if (reply == null) {
            throw new NoServerException("connection closed before the response");
        }
        if (reply.type() != FrameType.RESPONSE) {
            throw new IOException("expected a RESPONSE frame, got " + reply.type());
        }
        JsonNode node = Json.parse(reply.payload());
        if (!node.isObject()) {
            throw new IOException("response is not a JSON object");
        }
        return node;
    }

    /** Reads until the server closes the socket (what {@code shutdown} waits for). */
    void drainToEof() {
        try {
            while (codec.read() != null) {
                // discard
            }
        } catch (IOException ignored) {
            // A reset counts as closed too.
        }
    }

    @Override
    public void close() {
        try {
            channel.close();
        } catch (IOException ignored) {
            // Nothing further to release.
        }
    }
}
