package io.github.ghosthack.jmux.session;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The server's table of live sessions. Thread-safe. Session names are opaque strings
 * (Harness Manager uses {@code hm-<basename>}); uniqueness is enforced here.
 *
 * <p>Auto-exit: the 2 s idle timer arms at construction (a server that boots and never
 * gets a {@code new} exits) and again whenever the session count drops to zero (process
 * exit or kill); {@link #create} cancels it. When it fires the manager notifies
 * {@link Listener#onLastSessionEnded()}. The server uses it to exit like tmux does when no
 * sessions remain; {@code serve --keep-alive} simply ignores the callback. Because only
 * {@code new} ever spawns a server, this cannot loop.
 */
public interface SessionManager {

    interface Listener {
        void onLastSessionEnded();
    }

    /** Creates and starts a session. {@code command} null/empty = the platform login
     * shell ({@link ShellCommand}). Throws {@link IllegalStateException} if the name is
     * taken, {@link java.io.UncheckedIOException} if the PTY cannot be spawned. */
    Session create(String name, Path workdir, int columns, int rows, List<String> command);

    /** As above, with the session's initial {@code labels} (null = none). The default
     * labels the session right after creating it; the real manager hands them to the
     * session before its pump starts. */
    default Session create(String name, Path workdir, int columns, int rows, List<String> command,
                           Map<String, String> labels) {
        Session session = create(name, workdir, columns, rows, command);
        if (labels != null && !labels.isEmpty()) {
            session.label(labels);
        }
        return session;
    }

    /** As above, with {@code environment} (null/empty = none) merged into the session's
     * environment after the defaults ({@link ShellCommand}). The default supports no
     * environment: it refuses a non-empty one rather than drop it. */
    default Session create(String name, Path workdir, int columns, int rows, List<String> command,
                           Map<String, String> labels, Map<String, String> environment) {
        if (environment != null && !environment.isEmpty()) {
            throw new IllegalArgumentException("this session manager does not take an environment");
        }
        return create(name, workdir, columns, rows, command, labels);
    }

    Optional<Session> find(String name);

    List<Session> sessions();

    /** Kills and removes; absent is a no-op. */
    void kill(String name);

    /** Kills every session. Server shutdown always does this: closing the PTY masters
     * would SIGHUP the shells anyway, so an explicit, ordered kill is the honest version
     * of what the OS does on exit. */
    void killAll();

    void setListener(Listener listener);

    /** Tells {@code listener} about every session created from now on, after it is listed
     * (a {@code watch} of all sessions uses it to pick up new ones). Called on the creating
     * thread, outside the manager's lock; must not block. */
    void addCreatedListener(Consumer<Session> listener);

    void removeCreatedListener(Consumer<Session> listener);
}
