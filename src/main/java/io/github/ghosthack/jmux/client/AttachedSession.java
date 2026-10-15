package io.github.ghosthack.jmux.client;

import java.io.IOException;

/**
 * A live attachment: the byte stream of one session plus resize, over a dedicated
 * connection. Shape mirrors what a terminal widget needs from a PTY, so Harness Manager's
 * {@code SessionConnection} adapter is a thin wrapper. Reading and writing may happen on
 * different threads; {@link #close()} may be called from any thread and unblocks a
 * pending {@link #read}.
 */
public interface AttachedSession extends AutoCloseable {

    String sessionName();

    /** Blocks for the next chunk of session output (DATA_OUT payloads, delivered whole or
     * in part). Returns -1 once the server has sent SESSION_EXITED or closed the
     * connection, or after {@link #close()}. */
    int read(byte[] buf, int off, int len) throws IOException;

    /** Sends keystrokes/bytes to the session (DATA_IN). */
    void write(byte[] bytes) throws IOException;

    void resize(int columns, int rows) throws IOException;

    /** True until SESSION_EXITED / EOF / close. */
    boolean isConnected();

    /** The session's exit code once SESSION_EXITED arrived; empty while attached, or when
     * the attachment ended for another reason (detach, server gone). */
    java.util.OptionalInt exitCode();

    /** Blocks until the attachment ends; returns the exit code or -1. */
    int waitFor() throws InterruptedException;

    /** Detaches (DETACH frame, then closes the socket). Never kills the session. */
    @Override
    void close();
}
