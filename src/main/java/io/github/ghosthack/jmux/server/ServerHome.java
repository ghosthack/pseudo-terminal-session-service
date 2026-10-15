package io.github.ghosthack.jmux.server;

import java.nio.file.Path;

/**
 * Where one server lives: a directory holding the socket, the lock and the log. One
 * server per home; Harness Manager and the CLI share the default home, and tests use a
 * temporary one so they never touch (or spawn) the user's real server.
 *
 * <p>Default: {@code ~/.jmux}. Override with
 * {@code -Djmux.home=DIR} or {@code JMUX_HOME}.
 * Unix-domain socket paths have a small length ceiling (104 bytes on macOS, 108 on Linux
 * and Windows), so a deeply nested override may fail to bind with a clear error; the
 * default is well inside the limit for any sane home directory.
 */
public final class ServerHome {

    public static final String HOME_PROPERTY = "jmux.home";
    public static final String HOME_ENV = "JMUX_HOME";
    public static final String SOCKET_FILE = "server.sock";
    public static final String LOCK_FILE = "server.lock";
    public static final String LOG_FILE = "server.log";

    public static Path resolve() {
        String configured = System.getProperty(HOME_PROPERTY);
        if (configured == null || configured.isBlank()) {
            configured = System.getenv(HOME_ENV);
        }
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        return Path.of(System.getProperty("user.home"), ".jmux");
    }

    public static Path socket(Path home) {
        return home.resolve(SOCKET_FILE);
    }

    public static Path lock(Path home) {
        return home.resolve(LOCK_FILE);
    }

    public static Path log(Path home) {
        return home.resolve(LOG_FILE);
    }

    private ServerHome() {
    }
}
