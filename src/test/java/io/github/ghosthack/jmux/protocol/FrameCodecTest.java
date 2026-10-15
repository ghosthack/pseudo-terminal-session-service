package io.github.ghosthack.jmux.protocol;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrameCodecTest {

    @Test
    void roundTripsEveryFrameTypeInOrder() throws IOException {
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        FrameCodec writer = new FrameCodec(new ByteArrayInputStream(new byte[0]), wire);
        writer.write(Frame.json(FrameType.REQUEST, "{\"op\":\"ping\"}"));
        writer.write(Frame.of(FrameType.DATA_OUT, new byte[]{0, 1, 2, (byte) 0xff}));
        writer.write(Frame.of(FrameType.DETACH, null));
        writer.write(Frame.json(FrameType.SESSION_EXITED, "{\"exitCode\":3}"));

        FrameCodec reader = new FrameCodec(new ByteArrayInputStream(wire.toByteArray()), new ByteArrayOutputStream());
        Frame first = reader.read();
        assertEquals(FrameType.REQUEST, first.type());
        assertEquals("{\"op\":\"ping\"}", first.payloadAsString());
        Frame second = reader.read();
        assertEquals(FrameType.DATA_OUT, second.type());
        assertArrayEquals(new byte[]{0, 1, 2, (byte) 0xff}, second.payload());
        Frame third = reader.read();
        assertEquals(FrameType.DETACH, third.type());
        assertEquals(0, third.payload().length);
        assertEquals(FrameType.SESSION_EXITED, reader.read().type());
        assertNull(reader.read(), "clean EOF at a frame boundary is null");
    }

    @Test
    void eofAtBoundaryIsNullAndStaysNull() throws IOException {
        FrameCodec reader = new FrameCodec(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream());
        assertNull(reader.read());
        assertNull(reader.read());
    }

    @Test
    void truncatedFrameIsAnError() throws IOException {
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        new FrameCodec(new ByteArrayInputStream(new byte[0]), wire)
                .write(Frame.of(FrameType.DATA_IN, "hello world".getBytes()));
        byte[] full = wire.toByteArray();
        byte[] cutInPayload = Arrays.copyOf(full, full.length - 3);
        assertThrows(IOException.class, () ->
                new FrameCodec(new ByteArrayInputStream(cutInPayload), new ByteArrayOutputStream()).read());
        byte[] cutAfterLength = Arrays.copyOf(full, 4);
        assertThrows(IOException.class, () ->
                new FrameCodec(new ByteArrayInputStream(cutAfterLength), new ByteArrayOutputStream()).read());
        // Fewer than four bytes cannot be told from a clean EOF: the codec reports null.
        byte[] cutInLength = Arrays.copyOf(full, 2);
        assertNull(new FrameCodec(new ByteArrayInputStream(cutInLength), new ByteArrayOutputStream()).read());
    }

    @Test
    void oversizedLengthIsAnErrorWithoutAllocating() throws IOException {
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(wire);
        out.writeInt(Frame.MAX_PAYLOAD + 2);
        out.writeByte(FrameType.DATA_OUT.code());
        IOException e = assertThrows(IOException.class, () ->
                new FrameCodec(new ByteArrayInputStream(wire.toByteArray()), new ByteArrayOutputStream()).read());
        assertTrue(e.getMessage().contains("bad frame length"), e.getMessage());
    }

    @Test
    void unknownTypeAndZeroLengthAreErrors() throws IOException {
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(wire);
        out.writeInt(1);
        out.writeByte(99);
        assertThrows(IOException.class, () ->
                new FrameCodec(new ByteArrayInputStream(wire.toByteArray()), new ByteArrayOutputStream()).read());

        ByteArrayOutputStream zero = new ByteArrayOutputStream();
        new DataOutputStream(zero).writeInt(0);
        assertThrows(IOException.class, () ->
                new FrameCodec(new ByteArrayInputStream(zero.toByteArray()), new ByteArrayOutputStream()).read());
    }
}
