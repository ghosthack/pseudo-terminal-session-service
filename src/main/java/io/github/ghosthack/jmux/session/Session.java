package io.github.ghosthack.jmux.session;

import io.github.ghosthack.jmux.protocol.Protocol;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * One hosted session: a shell (or explicit command) in a pty4j PTY, the headless terminal
 * that shadows its output, and the set of attached clients. See SESSION-HOST.md
 * "Session model" for the full contract; the load-bearing rules:
 *
 * <p><b>One pump thread and one lock.</b> The pump reads PTY bytes → {@code VtTokenizer}
 * → {@code SgrNormalizer} → under the session lock: apply the complete tokens to the
 * {@code HeadlessTerminal}, then for every attached client enqueue the bytes of every
 * token {@code QueryFilter} does not strip (via {@code VtToken.bytesToForward()}) followed
 * by the batch's open-string fragment. Emulator state and broadcast position are identical
 * at every lock release.
 *
 * <p><b>Per-client outbound queue.</b> A client is handed bytes through
 * {@link Client#onOutput}, which must only enqueue; the connection's own writer thread
 * drains it. The client implementation applies the overflow policy (drop, mark BEHIND,
 * re-snapshot via {@link #snapshotFor}) — the session never blocks on a client.
 *
 * <p><b>PTY input</b> ({@link #write}, {@link #sendKeys}, emulator replies) goes through a
 * per-session writer queue and thread, never from under the session lock.
 *
 * <p><b>Attach</b> takes the session lock between pump batches, resizes to the client's
 * size if it differs (announcing {@code CSI 8 ; rows ; columns t} to the others), serializes,
 * hands the new client the snapshot followed by {@code tokenizer.openStringBytes()}, registers
 * it, and releases.
 *
 * <p><b>Watchers</b> ({@link #addWatcher}) are told about activity, quiet, title, bell and
 * exit; like clients they are called under the session lock (or the quiet timer's hold of
 * it) and must only enqueue. {@link #awaitQuiet} blocks the caller, never the pump.
 */
public interface Session {

    /** How the server sees a client: output bytes and the end of the session. */
    interface Client {
        /** Called under the session lock; must be non-blocking (enqueue only). */
        void onOutput(byte[] bytes);

        /** The PTY process ended; the client connection should be closed after this. */
        void onExit(int exitCode);

        /** A re-sync snapshot requested through {@link Session#snapshotFor}, handed over
         * under the session lock. A client that discards live output while BEHIND must
         * re-enable delivery <em>inside</em> this call: nothing else can enqueue while the
         * session lock is held, so the snapshot is guaranteed to precede every later live
         * batch. The default treats it like ordinary output. */
        default void onSnapshot(byte[] bytes) {
            onOutput(bytes);
        }
    }

    /** A watch on one session's activity: see {@link #addWatcher}. */
    interface Watcher {
        /** Called under the session lock (from the pump, the quiet timer or the exit
         * path); must be non-blocking (enqueue only). */
        void onEvent(SessionEvent event);

        /** How long without output after activity raises {@code quiet} for this watcher. */
        default long quietMs() {
            return Protocol.WATCH_DEFAULT_QUIET_MS;
        }
    }

    /** {@code backend}: {@code unix} | {@code conpty} | {@code winpty}. {@code labels} is an
     * immutable, key-sorted copy (never null); {@code outputBytes} the raw PTY byte count
     * (-1 = unknown: decoded from a daemon that predates the counter). */
    record Info(String name, Path workdir, int columns, int rows, long pid, Instant createdAt,
                int clients, boolean alive, String backend, Map<String, String> labels,
                long outputBytes) {

        public Info {
            labels = immutableLabels(labels);
        }

        /** Without labels or a byte count (callers that predate them). */
        public Info(String name, Path workdir, int columns, int rows, long pid, Instant createdAt,
                    int clients, boolean alive, String backend) {
            this(name, workdir, columns, rows, pid, createdAt, clients, alive, backend, Map.of(), 0);
        }
    }

    /** Foreground command, PID and title as {@code capture} reports them, plus the byte
     * counter the text corresponds to (-1 = unknown: the daemon predates the counter) and
     * whether the cursor is shown. */
    record Capture(String text, boolean alive, String foregroundCommand, long processId,
                   String title, int columns, int rows, long outputBytes, boolean cursorVisible) {

        /** Without the byte counter and cursor visibility (callers that predate them). */
        public Capture(String text, boolean alive, String foregroundCommand, long processId,
                       String title, int columns, int rows) {
            this(text, alive, foregroundCommand, processId, title, columns, rows, 0, true);
        }
    }

    /** What {@link #awaitQuiet} ended with: {@code quiet} false means the timeout elapsed
     * while output was still flowing. */
    record WaitResult(boolean quiet, long outputBytes, boolean alive) {
    }

    /** An immutable key-sorted copy without null keys or values; null → empty. */
    static Map<String, String> immutableLabels(Map<String, String> labels) {
        if (labels == null || labels.isEmpty()) {
            return Map.of();
        }
        TreeMap<String, String> copy = new TreeMap<>();
        labels.forEach((k, v) -> {
            if (k != null && v != null) {
                copy.put(k, v);
            }
        });
        return Collections.unmodifiableMap(copy);
    }

    String name();

    Info info();

    boolean isAlive();

    /** Enqueues raw bytes for the PTY (client keystrokes, or the emulator's query replies). */
    void write(byte[] bytes);

    /** {@code text}, then when {@code enter} a {@code Protocol.SEND_KEYS_ENTER_DELAY_MS}
     * pause and CR. The pause happens on the calling thread, never under a lock. */
    void sendKeys(String text, boolean enter);

    /** Resizes the PTY and the headless terminal under the session lock and announces the
     * new size in-band to every attached client except {@code except} (null = all). */
    void resize(int columns, int rows, Client except);

    /** The visible screen as {@link CaptureFormat#TEXT}. */
    default Capture capture() {
        return capture(CaptureFormat.TEXT);
    }

    /** The visible screen in {@code format}, taken under the session lock. */
    Capture capture(CaptureFormat format);

    /** Raw PTY bytes read so far (before any filtering); monotonic. */
    long outputBytes();

    /** The session's labels: an immutable copy, never null. */
    Map<String, String> labels();

    /** Merges {@code changes} into the labels; a null value removes its key. */
    void label(Map<String, String> changes);

    /** Presses named keys ({@code vt.KeyNames}), encoded for the current cursor-key mode.
     * Throws {@link IllegalArgumentException} on an unknown name, before writing anything. */
    void input(List<String> keys);

    /** Writes {@code text} as a paste: wrapped in {@code CSI 200~ … CSI 201~} when the
     * program enabled bracketed paste, raw otherwise. Inside the bracket every ESC (0x1b)
     * and C1 CSI (U+009B) character is removed, so the text cannot close the bracket early
     * by any nesting of end markers (and carries no escape sequences at all). */
    void paste(String text);

    /** {@link #paste} only when the program enabled bracketed paste (DEC 2004): returns false,
     * having written nothing, when it has not (a caller that must not have the text typed
     * as raw keystrokes, where a newline would submit it line by line). */
    boolean pasteIfBracketed(String text);

    /**
     * Blocks until the output is quiet. With {@code sinceBytes >= 0} it first waits for the
     * byte counter to pass {@code sinceBytes} (the output a just-sent input causes); if that
     * never happens within the timeout the screen is considered settled and the quiet check
     * runs at once. Then it waits until no output arrived for {@code quietMs}. Returns
     * {@code quiet=false} when {@code timeoutMs} (the bound for both phases) elapses first,
     * and promptly with {@code quiet=true, alive=false} once the session has exited.
     */
    WaitResult awaitQuiet(long sinceBytes, long quietMs, long timeoutMs) throws InterruptedException;

    /** Registers a watcher (idempotent). From now on it gets {@code output} on the first
     * output after a quiet period (or after registration), {@code quiet} once
     * {@link Watcher#quietMs()} pass without output after that, {@code title}, {@code bell},
     * and finally {@code exit} — immediately, when the session has already exited. */
    void addWatcher(Watcher watcher);

    void removeWatcher(Watcher watcher);

    /** OSC 7 / 9;9 hint → platform probe → launch workdir. Never null. */
    Path currentPath();

    /** Registers a client: the redraw-on-attach handshake described above. Returns once
     * the client has been handed the snapshot via {@link Client#onOutput}. */
    void attach(Client client, int columns, int rows);

    /** A fresh snapshot at the session's current size for a client that fell BEHIND, taken
     * under the session lock so it is positionally consistent with what the client will
     * receive next (the caller must clear its queue before calling and re-enable live
     * delivery under the same call). The snapshot is prefixed with ST ({@code ESC \}): the
     * bytes the client last received may have ended inside an OSC/DCS body whose terminator
     * went down with its queue, and a redraw fed into an open string would be swallowed. */
    void snapshotFor(Client client);

    void detach(Client client);

    List<Client> clients();

    /** Snapshot descendants; Unix {@code hangup()} (SIGHUP) / Windows {@code destroy()}; wait
     * up to 2 s for the shell <em>and</em> every snapshot pid; then {@code destroyForcibly}
     * on whatever survives. Returns once the process is gone. */
    void kill();
}
