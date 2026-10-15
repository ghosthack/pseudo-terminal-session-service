package io.github.ghosthack.jmux.server;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/**
 * Everything {@code serve} is told on its command line, plus the one tunable the design
 * fixes at 30 s ({@link #behindTimeout}) that tests shorten so a stuck client can be
 * observed being dropped without waiting half a minute.
 *
 * @param home             the server home ({@link ServerHome})
 * @param daemon           redirect stdio to {@code server.log} and ignore SIGHUP/SIGINT
 * @param keepAlive        ignore the idle timer (never auto-exit)
 * @param historyLines     scrollback lines a redraw-on-attach replays
 * @param clientBufferBytes per-client outbound queue bound before the BEHIND policy kicks in
 * @param behindTimeout    how long a BEHIND client may go without a completed write
 */
public record ServeOptions(Path home, boolean daemon, boolean keepAlive, int historyLines,
                           int clientBufferBytes, Duration behindTimeout) {

    public static final int DEFAULT_HISTORY_LINES = 2000;
    public static final int DEFAULT_CLIENT_BUFFER_BYTES = 4 * 1024 * 1024;
    public static final Duration DEFAULT_BEHIND_TIMEOUT = Duration.ofSeconds(30);

    public ServeOptions {
        Objects.requireNonNull(home, "home");
        Objects.requireNonNull(behindTimeout, "behindTimeout");
        if (historyLines < 0) {
            throw new IllegalArgumentException("history lines must be zero or greater");
        }
        if (clientBufferBytes <= 0) {
            throw new IllegalArgumentException("client buffer must be positive");
        }
    }

    /** Foreground, auto-exiting, default sizes: what tests and plain {@code serve} use. */
    public static ServeOptions defaults(Path home) {
        return new ServeOptions(home, false, false, DEFAULT_HISTORY_LINES,
                DEFAULT_CLIENT_BUFFER_BYTES, DEFAULT_BEHIND_TIMEOUT);
    }

    public ServeOptions withClientBufferBytes(int bytes) {
        return new ServeOptions(home, daemon, keepAlive, historyLines, bytes, behindTimeout);
    }

    public ServeOptions withBehindTimeout(Duration timeout) {
        return new ServeOptions(home, daemon, keepAlive, historyLines, clientBufferBytes, timeout);
    }

    public ServeOptions withKeepAlive(boolean value) {
        return new ServeOptions(home, daemon, value, historyLines, clientBufferBytes, behindTimeout);
    }
}
