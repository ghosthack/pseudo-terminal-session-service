package io.github.ghosthack.jmux.session;

import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import com.pty4j.WinSize;
import com.pty4j.unix.UnixPtyProcess;
import io.github.ghosthack.jmux.protocol.Protocol;
import io.github.ghosthack.jmux.vt.HeadlessTerminal;
import io.github.ghosthack.jmux.vt.KeyNames;
import io.github.ghosthack.jmux.vt.QueryFilter;
import io.github.ghosthack.jmux.vt.SgrNormalizer;
import io.github.ghosthack.jmux.vt.VtToken;
import io.github.ghosthack.jmux.vt.VtTokenizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * The PTY-backed {@link Session}: a pty4j process, the {@link HeadlessTerminal} shadowing
 * its output, and the attached clients — see SESSION-HOST.md "Session model" and the
 * {@link Session} contract.
 *
 * <p>Threads: the <b>pump</b> reads the PTY and, under the one session lock, tokenizes,
 * normalizes, applies to the emulator and fans out to clients; the <b>writer</b> drains
 * the PTY input queue ({@link #write}, {@link #sendKeys}, emulator replies) so nothing ever
 * writes to the PTY from under the lock. Tokenizing happens under the lock too (not only
 * the apply/fan-out), which is what makes {@code tokenizer.openStringBytes()} at attach
 * time exactly "what the others have been sent so far and the emulator has not": the pump
 * is between batches whenever anyone else holds the lock.
 *
 * <p>Activity: the pump counts every raw byte it reads ({@link #outputBytes}) and stamps
 * the time of the last read under the lock, then wakes {@link #awaitQuiet} waiters with
 * {@code notifyAll}. Watchers' {@code quiet} events come from one shared scheduled timer
 * that re-checks the stamp under the lock, so the pump never waits for anyone.
 */
public final class PtySession implements Session {

    /** How the manager learns that the PTY process ended; called once, on the pump thread,
     * after every attached client got {@link Client#onExit}. */
    interface ExitListener {
        void onSessionEnded(PtySession session, int exitCode);
    }

    private static final Logger log = LoggerFactory.getLogger(PtySession.class);
    private static final int READ_BUFFER_BYTES = 16 * 1024;
    /** How long the hung-up shell and its descendants get before survivors are SIGKILLed. */
    static final Duration KILL_GRACE = Duration.ofSeconds(2);
    /** How often {@link #kill} re-checks the snapshot descendants while the grace runs. */
    private static final long KILL_POLL_MS = 20;
    private static final boolean WINDOWS = ShellCommand.isWindows(System.getProperty("os.name", ""));
    private static final byte[] CR = {'\r'};
    /** String terminator, prefixed to a BEHIND re-sync snapshot (see {@link #handSnapshot}). */
    private static final byte[] ST = {0x1B, '\\'};
    /** Writer-queue sentinel: the PTY is gone, stop draining. */
    private static final byte[] END_OF_INPUT = new byte[0];
    private static final String PASTE_START = "\u001b[200~";
    private static final String PASTE_END = "\u001b[201~";
    /** Fires watchers' quiet checks for every session; its tasks only take a session lock
     * briefly and enqueue. */
    private static final ScheduledExecutorService QUIET_TIMER = Executors.newSingleThreadScheduledExecutor(
            runnable -> Thread.ofPlatform().daemon().name("hss-quiet-timer").unstarted(runnable));

    /** A watcher's activity state; guarded by the session lock. */
    private static final class WatchState {
        boolean active;
        ScheduledFuture<?> quietCheck;
    }

    private final String name;
    private final Path workdir;
    private final Instant createdAt = Instant.now();
    private final PtyProcess process;
    private final PtyBackend backend;
    private final int historyLines;
    private final ExitListener exitListener;

    private final Object lock = new Object();
    private final VtTokenizer tokenizer = new VtTokenizer();   // guarded by lock
    private final HeadlessTerminal terminal;                    // apply/resize/serialize under lock
    private final List<Client> clients = new ArrayList<>();     // guarded by lock
    private int columns;                                        // guarded by lock
    private int rows;                                           // guarded by lock
    private boolean exited;                                     // guarded by lock
    private int exitCode;                                       // guarded by lock, valid once exited
    private final Map<String, String> labels = new TreeMap<>(); // guarded by lock
    private long outputBytes;                                   // guarded by lock
    private long lastOutputNanos = System.nanoTime();           // guarded by lock
    private final Map<Watcher, WatchState> watchers = new LinkedHashMap<>(); // guarded by lock

    private final BlockingQueue<byte[]> ptyInput = new LinkedBlockingQueue<>();
    private final Thread pump;
    private final Thread writer;

    private PtySession(String name, Path workdir, PtyProcess process, int columns, int rows,
                       int historyLines, Map<String, String> labels, ExitListener exitListener) {
        this.name = name;
        this.labels.putAll(Session.immutableLabels(labels));
        this.workdir = workdir;
        this.process = process;
        this.backend = PtyBackend.of(process);
        this.historyLines = historyLines;
        this.exitListener = exitListener;
        this.columns = columns;
        this.rows = rows;
        // Emulator replies (DA, DSR, CPR) are enqueued for the writer thread, never written
        // from under the lock the pump holds while the emulator produces them.
        this.terminal = new HeadlessTerminal(columns, rows, historyLines, this::write);
        this.pump = Thread.ofPlatform().daemon().name("hss-pump-" + name).unstarted(this::pump);
        this.writer = Thread.ofPlatform().daemon().name("hss-pty-writer-" + name).unstarted(this::drainPtyInput);
    }

    /** Spawns {@code shell} in a PTY of {@code columns}×{@code rows} at {@code workdir} and
     * starts the pump and writer threads. */
    static PtySession spawn(String name, Path workdir, int columns, int rows, ShellCommand shell,
                            int historyLines, ExitListener exitListener) throws IOException {
        return spawn(name, workdir, columns, rows, shell, historyLines, Map.of(), exitListener);
    }

    /** As above, with the session's initial {@code labels}. */
    static PtySession spawn(String name, Path workdir, int columns, int rows, ShellCommand shell,
                            int historyLines, Map<String, String> labels, ExitListener exitListener)
            throws IOException {
        PtyProcess process = new PtyProcessBuilder(shell.command().toArray(String[]::new))
                .setEnvironment(shell.environment())
                .setDirectory(workdir.toString())
                .setInitialColumns(columns)
                .setInitialRows(rows)
                .setUseWinConPty(true)
                .setConsole(false)
                .start();
        PtySession session;
        try {
            session = new PtySession(name, workdir, process, columns, rows, historyLines, labels, exitListener);
        } catch (RuntimeException e) {
            // The shell is already running; without this it would outlive the failed
            // create as an untracked orphan holding its PTY.
            process.destroyForcibly();
            throw e;
        }
        session.writer.start();
        session.pump.start();
        return session;
    }

    // --- Session ---------------------------------------------------------------------

    @Override
    public String name() {
        return name;
    }

    @Override
    public Info info() {
        synchronized (lock) {
            return new Info(name, workdir, columns, rows, process.pid(), createdAt, clients.size(),
                    isAlive(), backend.id(), labels, outputBytes);
        }
    }

    @Override
    public boolean isAlive() {
        synchronized (lock) {
            if (exited) {
                return false;
            }
        }
        // kill() may have reaped the process before the pump noticed EOF.
        return process.isAlive();
    }

    @Override
    public void write(byte[] bytes) {
        if (bytes.length == 0) {
            return;
        }
        ptyInput.offer(bytes);
    }

    @Override
    public void sendKeys(String text, boolean enter) {
        write(text.getBytes(StandardCharsets.UTF_8));
        if (!enter) {
            return;
        }
        // tmux parity: the pause lives here, off every lock, so a TUI with paste detection
        // sees the CR as a submit and not as a pasted newline.
        try {
            Thread.sleep(Protocol.SEND_KEYS_ENTER_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        write(CR);
    }

    @Override
    public void resize(int columns, int rows, Client except) {
        synchronized (lock) {
            if (exited) {
                return;
            }
            try {
                process.setWinSize(new WinSize(columns, rows));
            } catch (RuntimeException e) {
                // A PTY whose slave side is already gone; the emulator still follows the client.
                log.debug("setWinSize({}x{}) on {} failed: {}", columns, rows, name, e.toString());
            }
            terminal.resize(columns, rows);
            // jediterm clamps to a minimum size; announce what the emulator really is.
            this.columns = terminal.columns();
            this.rows = terminal.rows();
            byte[] announcement = ("[8;" + this.rows + ";" + this.columns + "t")
                    .getBytes(StandardCharsets.US_ASCII);
            for (Client client : clients) {
                if (client != except) {
                    deliver(client, announcement);
                }
            }
        }
    }

    @Override
    public Capture capture(CaptureFormat format) {
        String text;
        String title;
        int cols;
        int rws;
        boolean alive;
        long bytes;
        boolean cursorVisible;
        synchronized (lock) {
            // Under the session lock so the text is never a half-applied batch.
            text = switch (format) {
                case TEXT -> terminal.screenText();
                case ANNOTATED -> terminal.annotatedText();
                case ANSI -> new String(terminal.serialize(0), StandardCharsets.UTF_8);
            };
            title = terminal.title();
            cols = columns;
            rws = rows;
            alive = !exited;
            bytes = outputBytes;
            cursorVisible = terminal.modes().cursorVisible();
        }
        alive = alive && process.isAlive();
        String foreground = alive ? ForegroundProbe.probe(process.pid()).command() : "";
        return new Capture(text, alive, foreground, process.pid(), title, cols, rws, bytes, cursorVisible);
    }

    @Override
    public long outputBytes() {
        synchronized (lock) {
            return outputBytes;
        }
    }

    @Override
    public Map<String, String> labels() {
        synchronized (lock) {
            return Session.immutableLabels(labels);
        }
    }

    @Override
    public void label(Map<String, String> changes) {
        synchronized (lock) {
            changes.forEach((key, value) -> {
                if (key == null) {
                    return;
                }
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
        write(KeyNames.encode(keys, terminal.modes().applicationCursorKeys()));
    }

    @Override
    public void paste(String text) {
        if (text.isEmpty()) {
            return;
        }
        if (terminal.modes().bracketedPaste()) {
            writeBracketed(text);
        } else {
            write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    @Override
    public boolean pasteIfBracketed(String text) {
        if (!terminal.modes().bracketedPaste()) {
            return false;
        }
        if (!text.isEmpty()) {
            writeBracketed(text);
        }
        return true;
    }

    private void writeBracketed(String text) {
        write((PASTE_START + pasteSafe(text) + PASTE_END).getBytes(StandardCharsets.UTF_8));
    }

    /** {@code text} without any ESC (0x1b) or C1 CSI (U+009B) character: with neither, no
     * escape sequence can be formed inside the bracket, so no end marker either — however
     * it is nested ({@code ESC[201ESC[201~~} survives a one-pass removal of the marker). */
    static String pasteSafe(String text) {
        StringBuilder safe = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '\u001b' && c != '\u009b') {
                safe.append(c);
            }
        }
        return safe.toString();
    }

    @Override
    public WaitResult awaitQuiet(long sinceBytes, long quietMs, long timeoutMs) throws InterruptedException {
        long quietNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(0, quietMs));
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0, timeoutMs));
        synchronized (lock) {
            if (sinceBytes >= 0) {
                // Phase 1: the output the caller's input is expected to cause. If none comes
                // the screen is taken as settled and the quiet check below answers at once.
                while (!exited && outputBytes <= sinceBytes) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) {
                        break;
                    }
                    waitNanos(remaining);
                }
            }
            while (true) {
                if (exited) {
                    return new WaitResult(true, outputBytes, false);
                }
                long now = System.nanoTime();
                long idle = now - lastOutputNanos;
                if (idle >= quietNanos) {
                    return new WaitResult(true, outputBytes, true);
                }
                long remaining = deadline - now;
                if (remaining <= 0) {
                    return new WaitResult(false, outputBytes, true);
                }
                waitNanos(Math.min(quietNanos - idle, remaining));
            }
        }
    }

    /** {@code lock.wait} for about {@code nanos} (rounded up to a whole millisecond); the
     * caller holds the lock and re-checks its condition. */
    private void waitNanos(long nanos) throws InterruptedException {
        lock.wait(Math.max(1, TimeUnit.NANOSECONDS.toMillis(nanos + 999_999)));
    }

    @Override
    public void addWatcher(Watcher watcher) {
        synchronized (lock) {
            if (exited) {
                // Raced with the exit, as attach can: the watcher still learns the end.
                emit(watcher, new SessionEvent(name, Protocol.EVENT_EXIT, outputBytes, null, exitCode));
                return;
            }
            watchers.putIfAbsent(watcher, new WatchState());
        }
    }

    @Override
    public void removeWatcher(Watcher watcher) {
        synchronized (lock) {
            WatchState state = watchers.remove(watcher);
            if (state != null && state.quietCheck != null) {
                state.quietCheck.cancel(false);
            }
        }
    }

    @Override
    public Path currentPath() {
        String hint = terminal.reportedCwd();
        if (hint != null && !hint.isBlank()) {
            try {
                return Path.of(hint);
            } catch (InvalidPathException e) {
                log.debug("ignoring unusable OSC cwd hint {} on {}", hint, name);
            }
        }
        if (isAlive()) {
            long shellPid = process.pid();
            long foregroundPid = ForegroundProbe.probe(shellPid).pid();
            Optional<Path> cwd = CwdProbe.cwdOf(foregroundPid);
            if (cwd.isEmpty() && foregroundPid != shellPid) {
                cwd = CwdProbe.cwdOf(shellPid);
            }
            if (cwd.isPresent()) {
                return cwd.get();
            }
        }
        return workdir;
    }

    @Override
    public void attach(Client client, int columns, int rows) {
        synchronized (lock) {
            if (!exited && (columns != this.columns || rows != this.rows)) {
                resize(columns, rows, client);
            }
            handSnapshot(client, false);
            if (exited) {
                // Raced with the exit: the client gets the final screen, then the end.
                client.onExit(exitCode);
                return;
            }
            clients.add(client);
        }
    }

    @Override
    public void snapshotFor(Client client) {
        synchronized (lock) {
            handSnapshot(client, true);
        }
    }

    /** Under the lock: the redraw, then the open OSC/DCS body the others are mid-way through.
     * With {@code closeOpenString} the redraw is prefixed by ST, joined into the same chunk
     * (a separate two-byte chunk ahead of an oversized snapshot would trip the client's
     * overflow check and loop it through BEHIND). */
    private void handSnapshot(Client client, boolean closeOpenString) {
        byte[] screen = terminal.serialize(historyLines);
        if (closeOpenString) {
            // The client may be inside an OSC/DCS body: its last delivered batch ended
            // in-string and the terminator was dropped with its queue, or a batch landed
            // between its BEHIND clear and this lock. Close it so the redraw is not
            // consumed as string body; a stray ST in ground state is ignored.
            byte[] joined = new byte[ST.length + screen.length];
            System.arraycopy(ST, 0, joined, 0, ST.length);
            System.arraycopy(screen, 0, joined, ST.length, screen.length);
            screen = joined;
        }
        deliverSnapshot(client, screen);
        byte[] open = tokenizer.openStringBytes();
        if (open.length > 0) {
            deliver(client, open);
        }
    }

    @Override
    public void detach(Client client) {
        synchronized (lock) {
            clients.remove(client);
        }
    }

    @Override
    public List<Client> clients() {
        synchronized (lock) {
            return List.copyOf(clients);
        }
    }

    @Override
    public void kill() {
        long pid = process.pid();
        // Snapshot before signalling: once the shell dies its children are reparented and
        // descendants() is empty.
        List<ProcessHandle> descendants = ProcessHandle.of(pid)
                .map(handle -> handle.descendants().toList())
                .orElse(List.of());
        if (process.isAlive()) {
            if (!WINDOWS && process instanceof UnixPtyProcess unix) {
                // SIGHUP, what tmux delivers by closing the master: interactive bash/zsh
                // ignore SIGTERM but exit on SIGHUP and forward it to their jobs.
                unix.hangup();
            } else {
                // TerminateProcess on the shell; pty4j closes the pseudoconsole once the
                // shell exits, taking attached children down.
                process.destroy();
            }
        }
        try {
            // One deadline for the whole tree: an interactive shell exits at once after
            // forwarding SIGHUP, so waiting on it alone would SIGKILL its jobs (a harness
            // flushing state, vim writing its recovery file) milliseconds after the HUP.
            long deadline = System.nanoTime() + KILL_GRACE.toNanos();
            process.waitFor(KILL_GRACE.toMillis(), TimeUnit.MILLISECONDS);
            while (System.nanoTime() < deadline && descendants.stream().anyMatch(ProcessHandle::isAlive)) {
                Thread.sleep(KILL_POLL_MS);
            }
            if (process.isAlive()) {
                process.destroyForcibly();
            }
            for (ProcessHandle survivor : descendants) {
                if (survivor.isAlive()) {
                    survivor.destroyForcibly();
                }
            }
            process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public String toString() {
        return "PtySession[" + name + ", pid " + process.pid() + ", " + backend.id() + "]";
    }

    // --- pump ------------------------------------------------------------------------

    private void pump() {
        InputStream in = process.getInputStream();
        byte[] buffer = new byte[READ_BUFFER_BYTES];
        try {
            while (true) {
                int n;
                try {
                    n = in.read(buffer);
                } catch (IOException e) {
                    // EIO on Linux once the slave side is closed: the PTY's way of saying EOF.
                    break;
                }
                if (n < 0) {
                    break;
                }
                if (n == 0) {
                    continue;
                }
                synchronized (lock) {
                    outputBytes += n;
                    lastOutputNanos = System.nanoTime();
                    markActive();
                    applyAndBroadcast(tokenizer.feed(buffer, 0, n));
                    lock.notifyAll(); // awaitQuiet waiters re-check the counter and the stamp
                }
            }
        } finally {
            finish(in);
        }
    }

    /** EOF: flush the tokenizer, apply the last batch, end the clients, tell the manager. */
    private void finish(InputStream in) {
        int code = awaitExit();
        List<Client> ended;
        synchronized (lock) {
            applyAndBroadcast(tokenizer.flush());
            exited = true;
            exitCode = code;
            ended = List.copyOf(clients);
            clients.clear();
            terminal.close();
            SessionEvent exit = new SessionEvent(name, Protocol.EVENT_EXIT, outputBytes, null, code);
            for (Map.Entry<Watcher, WatchState> entry : List.copyOf(watchers.entrySet())) {
                if (entry.getValue().quietCheck != null) {
                    entry.getValue().quietCheck.cancel(false);
                }
                emit(entry.getKey(), exit);
            }
            watchers.clear();
            lock.notifyAll(); // awaitQuiet returns at once for an exited session
        }
        ptyInput.offer(END_OF_INPUT);
        closeQuietly(in); // closes the master Pty (shared with the writer's stream)
        for (Client client : ended) {
            try {
                client.onExit(code);
            } catch (RuntimeException e) {
                log.warn("client of {} failed on exit", name, e);
            }
        }
        log.info("session {} (pid {}) exited with {}", name, process.pid(), code);
        exitListener.onSessionEnded(this, code);
    }

    private int awaitExit() {
        try {
            return process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    /** Under the lock: emulator first, then the same bytes to every client — everything
     * {@link QueryFilter} does not strip, followed by the open string fragment. */
    private void applyAndBroadcast(VtTokenizer.Batch batch) {
        List<VtToken> tokens = batch.tokens();
        List<VtToken> normalized = new ArrayList<>(tokens.size());
        for (VtToken token : tokens) {
            normalized.add(SgrNormalizer.normalize(token));
        }
        String titleBefore = terminal.title();
        terminal.apply(normalized);
        if (!watchers.isEmpty()) {
            notifyTitleAndBell(titleBefore, normalized);
        }
        if (clients.isEmpty()) {
            return;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (VtToken token : normalized) {
            if (!QueryFilter.isServerAnswered(token)) {
                out.writeBytes(token.bytesToForward());
            }
        }
        out.writeBytes(batch.openStringFragment());
        if (out.size() == 0) {
            return;
        }
        byte[] bytes = out.toByteArray();
        for (Client client : clients) {
            deliver(client, bytes);
        }
    }

    /** Under the lock, on every read: each idle watcher turns active ({@code output}) and
     * gets a quiet check scheduled if it has none pending. */
    private void markActive() {
        // Over a copy: a watcher may (re-entrantly, on this thread) remove itself.
        for (Map.Entry<Watcher, WatchState> entry : List.copyOf(watchers.entrySet())) {
            Watcher watcher = entry.getKey();
            WatchState state = entry.getValue();
            if (!state.active) {
                state.active = true;
                emit(watcher, new SessionEvent(name, Protocol.EVENT_OUTPUT, outputBytes, null, null));
            }
            if (state.quietCheck == null) {
                scheduleQuietCheck(watcher, state, watcher.quietMs());
            }
        }
    }

    /** Must hold the lock. */
    private void scheduleQuietCheck(Watcher watcher, WatchState state, long delayMs) {
        state.quietCheck = QUIET_TIMER.schedule(() -> quietCheck(watcher), Math.max(1, delayMs),
                TimeUnit.MILLISECONDS);
    }

    /** On the timer: {@code quiet} if the watcher's period passed since the last read,
     * otherwise a re-check when it would have. One pending check per watcher, so a busy
     * session costs one timer task per quiet period, not one per read. */
    private void quietCheck(Watcher watcher) {
        synchronized (lock) {
            WatchState state = watchers.get(watcher);
            if (state == null || exited) {
                return;
            }
            state.quietCheck = null;
            if (!state.active) {
                return;
            }
            long quietNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(0, watcher.quietMs()));
            long idle = System.nanoTime() - lastOutputNanos;
            if (idle >= quietNanos) {
                state.active = false;
                emit(watcher, new SessionEvent(name, Protocol.EVENT_QUIET, outputBytes, null, null));
            } else {
                scheduleQuietCheck(watcher, state, TimeUnit.NANOSECONDS.toMillis(quietNanos - idle + 999_999));
            }
        }
    }

    /** Under the lock, after an apply: a changed title, and one {@code bell} for a batch
     * holding any BEL control (an OSC's BEL terminator is inside its OSC token, so a
     * CONTROL BEL is always a real bell). */
    private void notifyTitleAndBell(String titleBefore, List<VtToken> tokens) {
        String title = terminal.title();
        if (!Objects.equals(titleBefore, title)) {
            SessionEvent event = new SessionEvent(name, Protocol.EVENT_TITLE, outputBytes, title, null);
            List.copyOf(watchers.keySet()).forEach(watcher -> emit(watcher, event));
        }
        for (VtToken token : tokens) {
            if (token.kind() == VtToken.Kind.CONTROL && token.bytes().length == 1 && token.bytes()[0] == 0x07) {
                SessionEvent event = new SessionEvent(name, Protocol.EVENT_BELL, outputBytes, null, null);
                List.copyOf(watchers.keySet()).forEach(watcher -> emit(watcher, event));
                break;
            }
        }
    }

    /** A watcher that throws must not poison the pump or the other watchers. */
    private void emit(Watcher watcher, SessionEvent event) {
        try {
            watcher.onEvent(event);
        } catch (RuntimeException e) {
            log.warn("watcher of {} rejected {}", name, event.type(), e);
        }
    }

    /** {@link Client#onOutput} only enqueues; a client that throws must not poison the pump
     * or the other clients. */
    private void deliver(Client client, byte[] bytes) {
        try {
            client.onOutput(bytes);
        } catch (RuntimeException e) {
            log.warn("client of {} rejected output", name, e);
        }
    }

    /** Under the lock: a re-sync snapshot goes through {@link Client#onSnapshot} so a
     * BEHIND client can atomically resume delivery with it (see the interface contract). */
    private void deliverSnapshot(Client client, byte[] bytes) {
        try {
            client.onSnapshot(bytes);
        } catch (RuntimeException e) {
            log.warn("client of {} rejected a snapshot", name, e);
        }
    }

    // --- writer ----------------------------------------------------------------------

    private void drainPtyInput() {
        OutputStream out = process.getOutputStream();
        try {
            while (true) {
                byte[] bytes = ptyInput.take();
                if (bytes == END_OF_INPUT) {
                    break;
                }
                out.write(bytes);
                out.flush();
            }
        } catch (IOException e) {
            // The PTY closed underneath us: the pump is about to report the exit.
            log.debug("writing to {} failed: {}", name, e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // Deliberately no close here: pty4j's PTYOutputStream.close() closes the shared
        // master Pty, which the pump may still be reading. The pump closes it once at EOF.
    }

    private static void closeQuietly(Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException ignored) {
            // The process is gone; there is nothing left to release.
        }
    }
}
