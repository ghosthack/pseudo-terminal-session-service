package io.github.ghosthack.jmux.server;

import io.github.ghosthack.jmux.session.Session;
import io.github.ghosthack.jmux.session.SessionManager;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** In-memory {@link SessionManager} over {@link FakeSession}s; the idle timer is a method
 * the test calls ({@link #fireIdle}) instead of a clock. */
final class FakeSessionManager implements SessionManager {

    private final Map<String, FakeSession> sessions = new ConcurrentHashMap<>();
    volatile boolean killAllCalled;
    volatile Listener listener;
    final List<Consumer<Session>> createdListeners = new CopyOnWriteArrayList<>();
    /** The {@code env} each session was created with, by name. */
    final Map<String, Map<String, String>> environments = new ConcurrentHashMap<>();

    FakeSession session(String name) {
        FakeSession session = sessions.get(name);
        if (session == null) {
            throw new AssertionError("no fake session " + name);
        }
        return session;
    }

    void fireIdle() {
        Listener current = listener;
        if (current != null) {
            current.onLastSessionEnded();
        }
    }

    void remove(FakeSession session) {
        sessions.remove(session.name(), session);
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
        FakeSession session = new FakeSession(this, name, workdir, columns, rows, command, labels);
        if (sessions.putIfAbsent(name, session) != null) {
            throw new IllegalStateException("session " + name + " already exists");
        }
        environments.put(name, environment == null ? Map.of() : Map.copyOf(environment));
        createdListeners.forEach(listener -> listener.accept(session));
        return session;
    }

    @Override
    public Optional<Session> find(String name) {
        return Optional.ofNullable(sessions.get(name));
    }

    @Override
    public List<Session> sessions() {
        return new ArrayList<>(sessions.values());
    }

    @Override
    public void kill(String name) {
        FakeSession session = sessions.remove(name);
        if (session != null) {
            session.kill();
        }
    }

    @Override
    public void killAll() {
        killAllCalled = true;
        for (String name : new ArrayList<>(sessions.keySet())) {
            kill(name);
        }
    }

    @Override
    public void setListener(Listener listener) {
        this.listener = listener;
    }

    @Override
    public void addCreatedListener(Consumer<Session> listener) {
        createdListeners.add(listener);
    }

    @Override
    public void removeCreatedListener(Consumer<Session> listener) {
        createdListeners.remove(listener);
    }
}
