package io.github.ghosthack.jmux.server;

import io.github.ghosthack.jmux.protocol.Protocol;
import io.github.ghosthack.jmux.session.CaptureFormat;
import io.github.ghosthack.jmux.session.Session;
import io.github.ghosthack.jmux.session.SessionEvent;
import io.github.ghosthack.jmux.vt.KeyNames;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A {@link Session} without a PTY or emulator: records every call the server makes and
 * lets a test play the pump (push bytes, exit). {@link #attach} hands the client a canned
 * snapshot exactly as the real session would, and {@link #snapshotFor} a distinguishable
 * one so a re-snapshot after BEHIND can be spotted in the client's byte stream.
 */
final class FakeSession implements Session {

    static final byte[] SNAPSHOT = "<<SNAPSHOT>>".getBytes(StandardCharsets.UTF_8);
    static final byte[] RESNAPSHOT = "<<RESNAPSHOT>>".getBytes(StandardCharsets.UTF_8);
    static final String CAPTURE_TEXT = "captured screen\n";
    static final String ANNOTATED_TEXT = "▶ captured screen▮\n";
    static final String ANSI_TEXT = "\u001b[0mcaptured screen\n";

    /** One {@link #awaitQuiet} call as the server made it. */
    record Wait(long sinceBytes, long quietMs, long timeoutMs) {
    }

    record Resize(int columns, int rows, Client except) {
    }

    private final FakeSessionManager manager;
    private final String name;
    private final Path workdir;
    private final List<String> command;
    private final Instant createdAt = Instant.now();
    private final Object lock = new Object();

    final List<Client> clients = new CopyOnWriteArrayList<>();
    final List<Client> attached = new CopyOnWriteArrayList<>();
    final List<Client> detached = new CopyOnWriteArrayList<>();
    final List<Resize> resizes = new CopyOnWriteArrayList<>();
    final List<String> sentKeys = new CopyOnWriteArrayList<>();
    final AtomicInteger snapshotForCalls = new AtomicInteger();
    final List<String> pastes = new CopyOnWriteArrayList<>();
    final List<List<String>> inputs = new CopyOnWriteArrayList<>();
    final List<Wait> waits = new CopyOnWriteArrayList<>();
    final List<CaptureFormat> captureFormats = new CopyOnWriteArrayList<>();
    final List<Watcher> watchers = new CopyOnWriteArrayList<>();
    final AtomicLong outputBytes = new AtomicLong();
    private final Map<String, String> labels = new TreeMap<>(); // guarded by lock
    /** What {@link #awaitQuiet} answers; null = quiet, alive, at the current byte count. */
    volatile WaitResult waitResult;
    private final ByteArrayOutputStream written = new ByteArrayOutputStream();
    volatile int columns;
    volatile int rows;
    volatile boolean alive = true;
    volatile boolean killed;

    FakeSession(FakeSessionManager manager, String name, Path workdir, int columns, int rows, List<String> command,
                Map<String, String> labels) {
        this.manager = manager;
        if (labels != null) {
            this.labels.putAll(labels);
        }
        this.name = name;
        this.workdir = workdir;
        this.columns = columns;
        this.rows = rows;
        this.command = command == null ? List.of() : List.copyOf(command);
    }

    List<String> command() {
        return command;
    }

    /** What the pump would deliver: the same bytes to every attached client, under the lock. */
    void push(byte[] bytes) {
        synchronized (lock) {
            outputBytes.addAndGet(bytes.length);
            for (Client client : clients) {
                client.onOutput(bytes);
            }
        }
    }

    void push(String text) {
        push(text.getBytes(StandardCharsets.UTF_8));
    }

    /** The shell exited: clients learn the code, the session leaves the manager. */
    void exit(int exitCode) {
        synchronized (lock) {
            alive = false;
            for (Client client : clients) {
                client.onExit(exitCode);
            }
            SessionEvent exit = new SessionEvent(name, Protocol.EVENT_EXIT, outputBytes.get(), null, exitCode);
            for (Watcher watcher : watchers) {
                watcher.onEvent(exit);
            }
            watchers.clear();
        }
        manager.remove(this);
    }

    /** What the pump would tell watchers, under the lock. */
    void event(String type, String title) {
        synchronized (lock) {
            SessionEvent event = new SessionEvent(name, type, outputBytes.get(), title, null);
            for (Watcher watcher : watchers) {
                watcher.onEvent(event);
            }
        }
    }

    String writtenText() {
        synchronized (written) {
            return written.toString(StandardCharsets.UTF_8);
        }
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Info info() {
        return new Info(name, workdir, columns, rows, 4242, createdAt, clients.size(), alive, "fake",
                labels(), outputBytes.get());
    }

    @Override
    public boolean isAlive() {
        return alive;
    }

    @Override
    public void write(byte[] bytes) {
        synchronized (written) {
            written.writeBytes(bytes);
        }
    }

    @Override
    public void sendKeys(String text, boolean enter) {
        sentKeys.add(text + (enter ? "<CR>" : ""));
    }

    @Override
    public void resize(int columns, int rows, Client except) {
        synchronized (lock) {
            this.columns = columns;
            this.rows = rows;
            resizes.add(new Resize(columns, rows, except));
            byte[] announcement = ("[8;" + rows + ";" + columns + "t").getBytes(StandardCharsets.US_ASCII);
            for (Client client : clients) {
                if (client != except) {
                    client.onOutput(announcement);
                }
            }
        }
    }

    @Override
    public Capture capture(CaptureFormat format) {
        captureFormats.add(format);
        String text = switch (format) {
            case TEXT -> CAPTURE_TEXT;
            case ANNOTATED -> ANNOTATED_TEXT;
            case ANSI -> ANSI_TEXT;
        };
        return new Capture(text, alive, "sh", 4242, "the title", columns, rows, outputBytes.get(), false);
    }

    @Override
    public long outputBytes() {
        return outputBytes.get();
    }

    @Override
    public Map<String, String> labels() {
        synchronized (lock) {
            return Map.copyOf(labels);
        }
    }

    @Override
    public void label(Map<String, String> changes) {
        synchronized (lock) {
            changes.forEach((key, value) -> {
                if (value == null) {
                    labels.remove(key);
                } else {
                    labels.put(key, value);
                }
            });
        }
    }

    @Override
    public void input(List<String> keys) {
        inputs.add(List.copyOf(keys));
        write(KeyNames.encode(keys, false));
    }

    @Override
    public void paste(String text) {
        pastes.add(text);
        write(("<paste>" + text + "</paste>").getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public boolean pasteIfBracketed(String text) {
        if (!bracketedPaste) {
            return false;
        }
        paste(text);
        return true;
    }

    /** What {@link #pasteIfBracketed} sees as the program's DEC 2004 mode. */
    volatile boolean bracketedPaste;

    @Override
    public WaitResult awaitQuiet(long sinceBytes, long quietMs, long timeoutMs) {
        waits.add(new Wait(sinceBytes, quietMs, timeoutMs));
        WaitResult result = waitResult;
        return result != null ? result : new WaitResult(true, outputBytes.get(), alive);
    }

    @Override
    public void addWatcher(Watcher watcher) {
        synchronized (lock) {
            if (!alive) {
                watcher.onEvent(new SessionEvent(name, Protocol.EVENT_EXIT, outputBytes.get(), null, 0));
                return;
            }
            if (!watchers.contains(watcher)) {
                watchers.add(watcher);
            }
        }
    }

    @Override
    public void removeWatcher(Watcher watcher) {
        watchers.remove(watcher);
    }

    @Override
    public Path currentPath() {
        return workdir;
    }

    @Override
    public void attach(Client client, int columns, int rows) {
        synchronized (lock) {
            if (columns != this.columns || rows != this.rows) {
                resize(columns, rows, client);
            }
            client.onOutput(SNAPSHOT);
            clients.add(client);
            attached.add(client);
        }
    }

    @Override
    public void snapshotFor(Client client) {
        synchronized (lock) {
            snapshotForCalls.incrementAndGet();
            // Optional live bytes on either side of the snapshot, all under the session
            // lock: a test uses them to pin that a BEHIND client drops what precedes the
            // redraw and keeps what follows it.
            if (liveBeforeSnapshot != null) {
                client.onOutput(liveBeforeSnapshot);
            }
            client.onSnapshot(RESNAPSHOT);
            if (liveAfterSnapshot != null) {
                client.onOutput(liveAfterSnapshot);
            }
        }
    }

    volatile byte[] liveBeforeSnapshot;
    volatile byte[] liveAfterSnapshot;

    @Override
    public void detach(Client client) {
        synchronized (lock) {
            if (clients.remove(client)) {
                detached.add(client);
            }
        }
    }

    @Override
    public List<Client> clients() {
        return new ArrayList<>(clients);
    }

    @Override
    public void kill() {
        killed = true;
        alive = false;
    }
}
