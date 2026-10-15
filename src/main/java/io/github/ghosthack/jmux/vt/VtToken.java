package io.github.ghosthack.jmux.vt;

import java.nio.charset.StandardCharsets;

/**
 * One complete unit of a terminal output stream, as split by {@link VtTokenizer}. The
 * bytes are the exact original bytes, so concatenating a token sequence reproduces the
 * input verbatim (minus any incomplete tail the tokenizer is still holding).
 *
 * <p>{@code alreadyForwarded} is non-zero only for STRING/OSC tokens whose body was
 * streamed to clients in fragments before the terminator arrived (see
 * {@link VtTokenizer.Batch}): the broadcast for this token is {@code bytes[alreadyForwarded..]}.
 * The emulator and the mode tracker always get the whole token.
 */
public record VtToken(Kind kind, byte[] bytes, int alreadyForwarded) {

    public VtToken(Kind kind, byte[] bytes) {
        this(kind, bytes, 0);
    }

    public enum Kind {
        /** Printable text: one or more complete UTF-8 code points, no C0 controls. */
        TEXT,
        /** A single C0 control byte (BEL, BS, HT, LF, CR, SO, SI, ...) other than ESC, or DEL. */
        CONTROL,
        /** {@code ESC [ params intermediates final} — final byte 0x40..0x7E. */
        CSI,
        /** {@code ESC ] ... (BEL | ESC \)} — includes the terminator. */
        OSC,
        /** {@code ESC P ... ESC \} (DCS), and likewise APC ({@code ESC _}), PM ({@code ESC ^}),
         * SOS ({@code ESC X}) — includes the terminator. */
        STRING,
        /** Any other {@code ESC x} sequence: two-byte escapes (ESC c, ESC 7, ESC =, ...) and
         * the intermediate forms ({@code ESC ( B}, {@code ESC # 8}). */
        ESCAPE,
    }

    public String asString() {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** The bytes not yet streamed to clients: all of them, except for a string token whose
     * body went out in fragments. */
    public byte[] bytesToForward() {
        if (alreadyForwarded == 0) {
            return bytes;
        }
        byte[] rest = new byte[bytes.length - alreadyForwarded];
        System.arraycopy(bytes, alreadyForwarded, rest, 0, rest.length);
        return rest;
    }

    /** For CSI tokens: the parameter/intermediate bytes between {@code ESC [} and the
     * final byte (e.g. {@code "?996"} for {@code CSI ?996n}); empty for other kinds. */
    public String csiBody() {
        if (kind != Kind.CSI || bytes.length < 3) {
            return "";
        }
        return new String(bytes, 2, bytes.length - 3, StandardCharsets.US_ASCII);
    }

    /** For CSI tokens: the final byte; 0 for other kinds. */
    public char csiFinal() {
        return kind == Kind.CSI ? (char) (bytes[bytes.length - 1] & 0xff) : 0;
    }
}
