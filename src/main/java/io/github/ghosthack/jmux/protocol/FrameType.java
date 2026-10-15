package io.github.ghosthack.jmux.protocol;

/**
 * Frame type codes of wire protocol v1. A frame is {@code u32 big-endian length} (of type
 * byte + payload), {@code u8 type}, then the payload. See PROTOCOL.md.
 */
public enum FrameType {
    /** Client → server: a JSON object with an {@code "op"} field. */
    REQUEST(1),
    /** Server → client: JSON {@code {"ok":true,...}} or {@code {"ok":false,"error":"..."}}. */
    RESPONSE(2),
    /** Server → client, attach mode only: raw bytes the session wrote. */
    DATA_OUT(16),
    /** Client → server, attach mode only: raw bytes for the session's PTY. */
    DATA_IN(17),
    /** Client → server, attach mode only: JSON {@code {"columns":n,"rows":n}}. */
    RESIZE(18),
    /** Client → server, attach mode only: empty payload; the server closes the connection. */
    DETACH(19),
    /** Server → client, attach mode only: JSON {@code {"name":...,"exitCode":n}}; the server
     * closes the connection right after. */
    SESSION_EXITED(20);

    private final int code;

    FrameType(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static FrameType fromCode(int code) {
        for (FrameType t : values()) {
            if (t.code == code) {
                return t;
            }
        }
        throw new IllegalArgumentException("unknown frame type " + code);
    }
}
