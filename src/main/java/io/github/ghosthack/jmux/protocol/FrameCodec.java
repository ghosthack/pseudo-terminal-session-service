package io.github.ghosthack.jmux.protocol;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Reads and writes {@link Frame}s over blocking streams. Not thread-safe per direction:
 * one reader thread and one writer thread per connection; the writer must be externally
 * serialized when several threads can send (attach mode: session output and
 * SESSION_EXITED race) — callers synchronize on the codec instance for writes.
 */
public final class FrameCodec {

    private final DataInputStream in;
    private final DataOutputStream out;

    public FrameCodec(InputStream in, OutputStream out) {
        this.in = new DataInputStream(in);
        this.out = new DataOutputStream(out);
    }

    /** Blocks until one full frame has arrived. Returns null on a clean EOF at a frame
     * boundary; throws on a truncated frame or an oversized/unknown one. */
    public Frame read() throws IOException {
        int length;
        try {
            length = in.readInt();
        } catch (EOFException e) {
            return null;
        }
        if (length < 1 || length - 1 > Frame.MAX_PAYLOAD) {
            throw new IOException("bad frame length " + length);
        }
        int typeCode = in.readUnsignedByte();
        FrameType type;
        try {
            type = FrameType.fromCode(typeCode);
        } catch (IllegalArgumentException e) {
            throw new IOException(e.getMessage());
        }
        byte[] payload = new byte[length - 1];
        in.readFully(payload);
        return new Frame(type, payload);
    }

    /** Writes one frame and flushes. Synchronize on this codec when several threads write. */
    public synchronized void write(Frame frame) throws IOException {
        out.writeInt(frame.payload().length + 1);
        out.writeByte(frame.type().code());
        out.write(frame.payload());
        out.flush();
    }
}
