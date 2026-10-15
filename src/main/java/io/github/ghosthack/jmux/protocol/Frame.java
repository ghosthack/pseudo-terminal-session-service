package io.github.ghosthack.jmux.protocol;

import java.nio.charset.StandardCharsets;

/** One wire frame. {@code payload} is never null (empty array for DETACH). */
public record Frame(FrameType type, byte[] payload) {

    /** Hard ceiling on a single frame's payload; larger is a protocol error, not a resize. */
    public static final int MAX_PAYLOAD = 16 * 1024 * 1024;

    public Frame {
        if (payload == null) {
            payload = new byte[0];
        }
        if (payload.length > MAX_PAYLOAD) {
            throw new IllegalArgumentException("frame payload too large: " + payload.length);
        }
    }

    public static Frame of(FrameType type, byte[] payload) {
        return new Frame(type, payload);
    }

    public static Frame json(FrameType type, String json) {
        return new Frame(type, json.getBytes(StandardCharsets.UTF_8));
    }

    public String payloadAsString() {
        return new String(payload, StandardCharsets.UTF_8);
    }
}
