package io.github.ghosthack.jmux.client;

/** A request the server refused, a protocol mismatch, a server that could not be started,
 * (with {@link #isNoServer()}) no server running where one is required, or (with
 * {@link #isUnsupported()}) an operation or option the running daemon predates. The message
 * is meant for the user. */
public class SessionServerException extends RuntimeException {

    /** How every {@link #isUnsupported()} message starts. */
    public static final String UNSUPPORTED_PREFIX = "unsupported by this jmux daemon: ";

    private final boolean noServer;
    private final boolean unsupported;

    public SessionServerException(String message) {
        this(message, null, false);
    }

    public SessionServerException(String message, Throwable cause) {
        this(message, cause, false);
    }

    public SessionServerException(String message, Throwable cause, boolean noServer) {
        this(message, cause, noServer, false);
    }

    private SessionServerException(String message, Throwable cause, boolean noServer, boolean unsupported) {
        super(message, cause);
        this.noServer = noServer;
        this.unsupported = unsupported;
    }

    public static SessionServerException noServer(String what) {
        return new SessionServerException("no such session: " + what + " (no session server is running)",
                null, true);
    }

    /** The running daemon (same protocol, older build) lacks {@code what}; restarting it on
     * the current build is the remedy. */
    public static SessionServerException unsupported(String what) {
        return new SessionServerException(UNSUPPORTED_PREFIX + what + " (the running daemon predates it;"
                + " finish its sessions or run `jmux shutdown`, then retry)", null, false, true);
    }

    /** True when the failure is "no server is listening" rather than a refused request. */
    public boolean isNoServer() {
        return noServer;
    }

    /** True when the running daemon is older than this client and lacks the operation or
     * option the request needed; the message starts with {@link #UNSUPPORTED_PREFIX}. */
    public boolean isUnsupported() {
        return unsupported;
    }
}
