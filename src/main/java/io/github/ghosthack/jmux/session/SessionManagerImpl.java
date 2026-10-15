package io.github.ghosthack.jmux.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The real {@link SessionManager} over {@link PtySession}s. Creation, removal and the idle
 * timer are serialized on this instance; lookups read the concurrent map directly.
 *
 * <p>Idle timer (SESSION-HOST.md "Lifecycle"): armed at construction and whenever the
 * session count drops to zero — a kill, or the pump reporting an exit — and cancelled by
 * a successful {@link #create}. When it fires with no session it calls
 * {@link Listener#onLastSessionEnded()} off this lock, so the listener may call back into
 * {@link #killAll}. A timer that fires before any listener is set is re-armed by
 * {@link #setListener}, so the notification is never lost.
 */
public final class SessionManagerImpl implements SessionManager {

    public static final Duration DEFAULT_IDLE_DELAY = Duration.ofSeconds(2);

    private static final Logger log = LoggerFactory.getLogger(SessionManagerImpl.class);

    private final int historyLines;
    private final Duration idleDelay;
    private final Map<String, PtySession> sessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(
            runnable -> Thread.ofPlatform().daemon().name("hss-idle-timer").unstarted(runnable));
    private ScheduledFuture<?> idle;          // guarded by this
    private volatile Listener listener;
    private final List<Consumer<Session>> createdListeners = new CopyOnWriteArrayList<>();

    public SessionManagerImpl(int historyLines) {
        this(historyLines, DEFAULT_IDLE_DELAY);
    }

    /** {@code idleDelay}: how long the manager waits with zero sessions before notifying. */
    public SessionManagerImpl(int historyLines, Duration idleDelay) {
        if (historyLines < 0) {
            throw new IllegalArgumentException("historyLines must be >= 0");
        }
        this.historyLines = historyLines;
        this.idleDelay = idleDelay;
        armIdleTimer();
    }

    @Override
    public Session create(String name, Path workdir, int columns, int rows, List<String> command) {
        return create(name, workdir, columns, rows, command, Map.of());
    }

    @Override
    public Session create(String name, Path workdir, int columns, int rows, List<String> command,
                          Map<String, String> labels) {
        return create(name, workdir, columns, rows, command, labels, Map.of());
    }

    @Override
    public Session create(String name, Path workdir, int columns, int rows, List<String> command,
                          Map<String, String> labels, Map<String, String> environment) {
        Session session = createLocked(name, workdir, columns, rows, command, labels, environment);
        for (Consumer<Session> created : createdListeners) {
            try {
                created.accept(session);
            } catch (RuntimeException e) {
                log.warn("created-listener failed for {}", name, e);
            }
        }
        return session;
    }

    private synchronized Session createLocked(String name, Path workdir, int columns, int rows,
                                              List<String> command, Map<String, String> labels,
                                              Map<String, String> environment) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("session name is required");
        }
        if (sessions.containsKey(name)) {
            throw new IllegalStateException("session " + name + " already exists");
        }
        if (!Files.isDirectory(workdir)) {
            throw new UncheckedIOException(new NoSuchFileException(workdir.toString(), null, "not a directory"));
        }
        ShellCommand shell = ShellCommand.forSession(name, command, environment);
        PtySession session;
        try {
            session = PtySession.spawn(name, workdir, columns, rows, shell, historyLines, labels,
                    this::onSessionEnded);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot start " + shell.command() + ": " + e.getMessage(), e);
        }
        sessions.put(name, session);
        cancelIdleTimer();
        log.info("session {} started: {} (pid {}, {})", name, shell.command(), session.info().pid(),
                session.info().backend());
        return session;
    }

    @Override
    public Optional<Session> find(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(sessions.get(name));
    }

    /** Live sessions in creation order. */
    @Override
    public List<Session> sessions() {
        List<Session> list = new ArrayList<>(sessions.values());
        list.sort(Comparator.comparing((Session s) -> s.info().createdAt()).thenComparing(Session::name));
        return list;
    }

    @Override
    public void kill(String name) {
        PtySession session;
        synchronized (this) {
            session = name == null ? null : sessions.remove(name);
        }
        if (session == null) {
            return;
        }
        // Kill outside the lock: it waits up to KILL_GRACE and the pump's exit callback
        // needs the lock to run.
        session.kill();
        synchronized (this) {
            if (sessions.isEmpty()) {
                armIdleTimer();
            }
        }
    }

    @Override
    public void killAll() {
        for (Session session : sessions()) {
            kill(session.name());
        }
    }

    @Override
    public void setListener(Listener listener) {
        this.listener = listener;
        synchronized (this) {
            // The boot timer may already have fired into the void; give the listener its turn.
            if (sessions.isEmpty() && (idle == null || idle.isDone())) {
                armIdleTimer();
            }
        }
    }

    @Override
    public void addCreatedListener(Consumer<Session> listener) {
        createdListeners.add(listener);
    }

    @Override
    public void removeCreatedListener(Consumer<Session> listener) {
        createdListeners.remove(listener);
    }

    /** The pump's exit callback. A session removed by {@link #kill} is not counted twice. */
    private void onSessionEnded(PtySession session, int exitCode) {
        synchronized (this) {
            if (sessions.remove(session.name(), session) && sessions.isEmpty()) {
                armIdleTimer();
            }
        }
    }

    private synchronized void armIdleTimer() {
        cancelIdleTimer();
        idle = timer.schedule(this::fireIdle, idleDelay.toMillis(), TimeUnit.MILLISECONDS);
    }

    private synchronized void cancelIdleTimer() {
        if (idle != null) {
            idle.cancel(false);
            idle = null;
        }
    }

    private void fireIdle() {
        synchronized (this) {
            if (!sessions.isEmpty()) {
                return; // a create slipped in while the timer was already running
            }
        }
        Listener current = listener;
        if (current != null) {
            current.onLastSessionEnded();
        }
    }
}
