package io.github.ghosthack.jmux.session;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * A {@link Session.Client} that keeps every {@code onOutput} chunk (in order, as separate
 * arrays, so a test can pick out "the first bytes after attach") and the exit code.
 */
final class RecordingClient implements Session.Client {

    private final List<byte[]> chunks = new ArrayList<>();
    private final CountDownLatch exited = new CountDownLatch(1);
    private volatile Integer exitCode;

    @Override
    public void onOutput(byte[] bytes) {
        synchronized (chunks) {
            chunks.add(bytes.clone());
        }
    }

    @Override
    public void onExit(int exitCode) {
        this.exitCode = exitCode;
        exited.countDown();
    }

    List<byte[]> chunks() {
        synchronized (chunks) {
            return List.copyOf(chunks);
        }
    }

    byte[] bytes() {
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        for (byte[] chunk : chunks()) {
            all.writeBytes(chunk);
        }
        return all.toByteArray();
    }

    String text() {
        return new String(bytes(), StandardCharsets.UTF_8);
    }

    /** Everything received after the first {@code skip} bytes. */
    String textAfter(int skip) {
        byte[] all = bytes();
        return skip >= all.length ? "" : new String(all, skip, all.length - skip, StandardCharsets.UTF_8);
    }

    int byteCount() {
        return bytes().length;
    }

    boolean hasExited() {
        return exited.getCount() == 0;
    }

    Integer exitCode() {
        return exitCode;
    }
}
