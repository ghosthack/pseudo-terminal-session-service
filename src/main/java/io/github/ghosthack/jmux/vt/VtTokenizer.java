package io.github.ghosthack.jmux.vt;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Boundary-preserving splitter for a terminal output byte stream (see SESSION-HOST.md
 * "Tokenizer contract"). Feed arbitrary chunks; get back the complete tokens they finish,
 * in order, plus the fragment of any string body still open — so clients can be streamed
 * immediately while the emulator only ever sees complete sequences.
 *
 * <p>Invariants:
 * <ul>
 *   <li>Every byte ≥ 0x80 is text. No 8-bit C1 recognition.</li>
 *   <li>Sequences start only with ESC. {@code ESC [} → CSI (parameters 0x30–0x3F,
 *       intermediates 0x20–0x2F, final 0x40–0x7E); {@code ESC ]} → OSC; {@code ESC P},
 *       {@code ESC _}, {@code ESC ^}, {@code ESC X} → STRING; both terminated by BEL or
 *       {@code ESC \} (an ESC inside a body not followed by {@code \} stays in the body);
 *       {@code ESC} + intermediate(s) 0x20–0x2F + final 0x30–0x7E, or {@code ESC} + one
 *       final 0x30–0x7E → ESCAPE. An ESC followed by a byte that fits none of those (CAN,
 *       SUB, a C0, another ESC) ends the aborted sequence as an ESCAPE token and the new
 *       byte is processed fresh.</li>
 *   <li>A C0 control other than ESC (and DEL) is its own CONTROL token, even mid-text.</li>
 *   <li>Held back until complete: a lone trailing ESC, a partial CSI, a partial ESC-
 *       intermediate sequence, a partial UTF-8 code point. A CSI longer than 64 bytes
 *       without a final byte is flushed as TEXT and the parser resets.</li>
 *   <li>String bodies are <em>not</em> held back from clients: the bytes of an open
 *       OSC/STRING that arrived in this chunk are returned as {@link Batch#openStringFragment}
 *       to be broadcast now; when the terminator arrives the whole token is returned with
 *       {@link VtToken#alreadyForwarded()} set to the number of bytes already streamed.
 *       A body longer than 64 KiB without a terminator is dropped from the emulator feed
 *       (never returned as a token — clients already have the bytes) and the parser
 *       resets.</li>
 *   <li>Concatenating, for each batch, every token's {@code bytesToForward()} followed by
 *       {@code openStringFragment}, reproduces the input byte-for-byte, minus tokens the
 *       caller chooses to drop and minus any still-held partial CSI/ESC/UTF-8 tail.</li>
 * </ul>
 */
public final class VtTokenizer {

    public static final int MAX_CSI_LENGTH = 64;
    public static final int MAX_STRING_LENGTH = 64 * 1024;

    private static final int ESC = 0x1B;
    private static final int BEL = 0x07;

    private enum State {
        /** Between tokens; {@link #text} may hold a run of printable bytes. */
        GROUND,
        /** Saw ESC; deciding what kind of sequence follows. {@link #seq} = {ESC}. */
        AFTER_ESC,
        /** In a CSI ({@code ESC [ ...}); {@link #seq} accumulates through the final byte. */
        IN_CSI,
        /** In an {@code ESC} + intermediate(s) sequence; {@link #seq} accumulates. */
        IN_ESC_INTER,
        /** In an OSC/DCS/APC/PM/SOS body; {@link #stringBuf} accumulates (already forwarded). */
        IN_STRING,
    }

    /** What one {@link #feed} produced: the tokens completed by the chunk, in order, and
     * the bytes of a still-open string body that arrived in this chunk (after the last
     * token; empty when no string is open). */
    public record Batch(List<VtToken> tokens, byte[] openStringFragment) {
    }

    private static final byte[] EMPTY = new byte[0];

    private State state = State.GROUND;

    /** Printable bytes accumulated in GROUND, flushed as a TEXT token at the next boundary. */
    private final ByteArrayOutputStream text = new ByteArrayOutputStream();
    /** The bytes of the escape/CSI sequence being parsed (starts with ESC). */
    private final ByteArrayOutputStream seq = new ByteArrayOutputStream();
    /** The bytes of the open string (introducer included). */
    private final ByteArrayOutputStream stringBuf = new ByteArrayOutputStream();
    /** OSC vs generic STRING, decided by the introducer byte. */
    private boolean stringIsOsc;
    /** Bytes of {@link #stringBuf} already returned to clients via openStringFragment. */
    private int stringForwarded;
    /** True when the previous string byte was ESC (a potential {@code ESC \} terminator). */
    private boolean stringSawEsc;

    public Batch feed(byte[] chunk, int off, int len) {
        List<VtToken> out = new ArrayList<>();

        int end = off + len;
        for (int i = off; i < end; i++) {
            int b = chunk[i] & 0xFF;
            switch (state) {
                case GROUND -> {
                    if (b == ESC) {
                        flushText(out);
                        seq.reset();
                        seq.write(ESC);
                        state = State.AFTER_ESC;
                    } else if (b < 0x20 || b == 0x7F) {
                        // C0 control (other than ESC, handled above) or DEL: its own token.
                        flushText(out);
                        out.add(new VtToken(VtToken.Kind.CONTROL, new byte[]{(byte) b}));
                    } else {
                        text.write(b);
                    }
                }
                case AFTER_ESC -> {
                    if (b == '[') {
                        seq.write(b);
                        state = State.IN_CSI;
                    } else if (b == ']' || b == 'P' || b == '_' || b == '^' || b == 'X') {
                        // OSC (]) / DCS (P) / APC (_) / PM (^) / SOS (X).
                        stringBuf.reset();
                        stringBuf.write(ESC);
                        stringBuf.write(b);
                        stringIsOsc = (b == ']');
                        stringForwarded = 0;
                        stringSawEsc = false;
                        state = State.IN_STRING;
                    } else if (b >= 0x20 && b <= 0x2F) {
                        seq.write(b);
                        state = State.IN_ESC_INTER;
                    } else if (b >= 0x30 && b <= 0x7E) {
                        seq.write(b);
                        out.add(new VtToken(VtToken.Kind.ESCAPE, seq.toByteArray()));
                        state = State.GROUND;
                    } else {
                        // Aborted: emit the lone ESC as ESCAPE and reprocess this byte fresh.
                        out.add(new VtToken(VtToken.Kind.ESCAPE, seq.toByteArray()));
                        state = State.GROUND;
                        i--; // reprocess b in GROUND
                    }
                }
                case IN_CSI -> {
                    if (b >= 0x40 && b <= 0x7E) {
                        seq.write(b);
                        out.add(new VtToken(VtToken.Kind.CSI, seq.toByteArray()));
                        state = State.GROUND;
                    } else if (seq.size() >= MAX_CSI_LENGTH) {
                        // Overlong CSI without a final byte: flush as TEXT, reprocess b fresh.
                        appendSeqAsText();
                        state = State.GROUND;
                        i--;
                    } else if (b >= 0x20 && b <= 0x3F) {
                        seq.write(b); // parameter (0x30-0x3F) or intermediate (0x20-0x2F)
                    } else {
                        // Unexpected byte inside CSI: abort to TEXT, reprocess b fresh.
                        appendSeqAsText();
                        state = State.GROUND;
                        i--;
                    }
                }
                case IN_ESC_INTER -> {
                    if (b >= 0x20 && b <= 0x2F) {
                        seq.write(b); // another intermediate
                    } else if (b >= 0x30 && b <= 0x7E) {
                        seq.write(b);
                        out.add(new VtToken(VtToken.Kind.ESCAPE, seq.toByteArray()));
                        state = State.GROUND;
                    } else {
                        // Aborted intermediate sequence: emit what we have, reprocess b fresh.
                        out.add(new VtToken(VtToken.Kind.ESCAPE, seq.toByteArray()));
                        state = State.GROUND;
                        i--;
                    }
                }
                case IN_STRING -> {
                    stringBuf.write(b);
                    if (b == BEL) {
                        emitString(out);
                        state = State.GROUND;
                    } else if (stringSawEsc) {
                        stringSawEsc = false;
                        if (b == '\\') { // ESC \ = ST terminator
                            emitString(out);
                            state = State.GROUND;
                        } else if (b == ESC) {
                            stringSawEsc = true; // the previous ESC stays in the body
                        }
                    } else if (b == ESC) {
                        stringSawEsc = true;
                    }
                }
            }
        }

        if (state == State.GROUND) {
            // Emit the completed text of this feed, holding back an incomplete UTF-8 tail.
            flushText(out);
        }

        byte[] fragment = EMPTY;
        if (state == State.IN_STRING) {
            int size = stringBuf.size();
            if (size > stringForwarded) {
                fragment = slice(stringBuf.toByteArray(), stringForwarded, size);
            }
            stringForwarded = size;
            if (size > MAX_STRING_LENGTH) {
                // Overlong unterminated string: drop from the emulator feed (clients already
                // have the bytes) and reset the parser. See "Tokenizer contract".
                stringBuf.reset();
                stringForwarded = 0;
                stringSawEsc = false;
                state = State.GROUND;
            }
        }
        return new Batch(out, fragment);
    }

    public Batch feed(byte[] chunk) {
        return feed(chunk, 0, chunk.length);
    }

    /** The whole body of the currently open OSC/STRING (introducer included), or empty:
     * attach sends this to a new client after the snapshot so it gets the sequence the
     * other clients are mid-way through. */
    public byte[] openStringBytes() {
        return state == State.IN_STRING ? stringBuf.toByteArray() : EMPTY;
    }

    /** Bytes held back as an incomplete ESC/CSI/UTF-8 tail (never a string body); empty
     * at a boundary. For diagnostics and {@link #flush}. */
    public byte[] pending() {
        return switch (state) {
            case AFTER_ESC, IN_CSI, IN_ESC_INTER -> seq.toByteArray();
            case GROUND -> incompleteUtf8Tail();
            case IN_STRING -> EMPTY;
        };
    }

    /** End of stream: emits the held tail as ESCAPE/TEXT and any open string body as a
     * STRING/OSC token (with {@code alreadyForwarded} set), resetting the parser. */
    public Batch flush() {
        List<VtToken> out = new ArrayList<>();
        switch (state) {
            case GROUND -> flushText(out);
            case AFTER_ESC, IN_ESC_INTER -> out.add(new VtToken(VtToken.Kind.ESCAPE, seq.toByteArray()));
            case IN_CSI -> out.add(new VtToken(VtToken.Kind.TEXT, seq.toByteArray()));
            case IN_STRING -> emitString(out);
        }
        state = State.GROUND;
        text.reset();
        seq.reset();
        stringBuf.reset();
        stringForwarded = 0;
        stringSawEsc = false;
        return new Batch(out, EMPTY);
    }

    /** Flush {@link #text} as one or more TEXT tokens, holding back an incomplete trailing
     * UTF-8 code point for the next feed. */
    private void flushText(List<VtToken> out) {
        int size = text.size();
        if (size == 0) {
            return;
        }
        byte[] buf = text.toByteArray();
        int tail = incompleteUtf8TailLength(buf, size);
        if (size - tail > 0) {
            out.add(new VtToken(VtToken.Kind.TEXT, slice(buf, 0, size - tail)));
        }
        text.reset();
        if (tail > 0) {
            // Re-hold the incomplete code point so the next feed can complete it.
            text.write(buf, size - tail, tail);
        }
    }

    private void appendSeqAsText() {
        byte[] s = seq.toByteArray();
        // Prepend the aborted sequence to any pending text so byte order is preserved.
        byte[] existing = text.toByteArray();
        text.reset();
        text.write(s, 0, s.length);
        text.write(existing, 0, existing.length);
    }

    private void emitString(List<VtToken> out) {
        byte[] all = stringBuf.toByteArray();
        VtToken.Kind kind = stringIsOsc ? VtToken.Kind.OSC : VtToken.Kind.STRING;
        out.add(new VtToken(kind, all, stringForwarded));
        stringBuf.reset();
        stringForwarded = 0;
        stringSawEsc = false;
    }

    /** The trailing bytes of {@link #text} that form an incomplete (completable) UTF-8
     * code point, as a fresh array; empty when the buffer ends on a boundary. */
    private byte[] incompleteUtf8Tail() {
        byte[] buf = text.toByteArray();
        int tail = incompleteUtf8TailLength(buf, buf.length);
        return tail == 0 ? EMPTY : slice(buf, buf.length - tail, buf.length);
    }

    /**
     * Number of trailing bytes of {@code b[0..len)} that form an incomplete but still
     * completable UTF-8 sequence (a lead byte with fewer continuation bytes than it needs).
     * Stray continuation bytes with no lead, and already-complete sequences, are not held.
     */
    static int incompleteUtf8TailLength(byte[] b, int len) {
        int i = len - 1;
        int cont = 0;
        while (i >= 0 && (b[i] & 0xC0) == 0x80 && cont < 3) {
            i--;
            cont++;
        }
        if (i < 0) {
            return 0; // only continuation bytes in view: not completable
        }
        int lead = b[i] & 0xFF;
        int need;
        if (lead < 0x80) {
            return 0; // ASCII lead, the trailing continuations are stray
        } else if ((lead & 0xE0) == 0xC0) {
            need = 1;
        } else if ((lead & 0xF0) == 0xE0) {
            need = 2;
        } else if ((lead & 0xF8) == 0xF0) {
            need = 3;
        } else {
            return 0; // stray continuation acting as lead, or invalid 0xF8+ byte
        }
        if (cont >= need) {
            return 0; // complete (or overlong) sequence: nothing to hold
        }
        return cont + 1; // lead byte plus the continuations seen so far
    }

    private static byte[] slice(byte[] a, int from, int to) {
        byte[] r = new byte[to - from];
        System.arraycopy(a, from, r, 0, r.length);
        return r;
    }
}
