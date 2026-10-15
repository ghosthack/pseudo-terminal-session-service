package io.github.ghosthack.jmux.protocol;

/**
 * Wire-level constants shared by server, client and CLI. Request/response bodies are
 * JSON objects; the field names below are the whole vocabulary (see PROTOCOL.md).
 *
 * <p>Requests: {@code {"op": <OP_*>, "protocol": 1, ...args}}. Responses:
 * {@code {"ok": true, ...}} or {@code {"ok": false, "error": "<message>"}}.
 */
public final class Protocol {

    public static final int VERSION = 1;

    // ---- request fields
    public static final String F_OP = "op";
    public static final String F_PROTOCOL = "protocol";
    public static final String F_NAME = "name";
    public static final String F_WORKDIR = "workdir";
    public static final String F_COLUMNS = "columns";
    public static final String F_ROWS = "rows";
    public static final String F_COMMAND = "command";
    public static final String F_TEXT = "text";
    public static final String F_ENTER = "enter";
    /** {@code new} / {@code label}: a string→string object; in {@code label} a null value
     * removes the key. Also on every {@code list} entry. */
    public static final String F_LABELS = "labels";
    /** {@code new}: a string→string object of environment variables merged into the
     * session's environment after the defaults ({@code TERM} and {@code JMUX_SESSION}
     * cannot be overridden). */
    public static final String F_ENV = "env";
    /** {@code input}: named keys (see {@code vt.KeyNames}), pressed after {@code text}. */
    public static final String F_KEYS = "keys";
    /** {@code input}: send {@code text} as a paste (bracketed when the program asked). */
    public static final String F_PASTE = "paste";
    /** {@code input}: with {@code paste}, refuse (writing nothing) unless the program enabled
     * bracketed paste (DEC 2004), instead of falling back to a raw paste. */
    public static final String F_REQUIRE_BRACKETED_PASTE = "requireBracketedPaste";
    /** {@code capture}: {@code text} | {@code annotated} | {@code ansi}; echoed in the response. */
    public static final String F_FORMAT = "format";
    /** {@code wait}: first wait for output past this byte count (-1 = don't). */
    public static final String F_SINCE_BYTES = "sinceBytes";
    /** {@code wait} / {@code watch}: how long without output counts as quiet. */
    public static final String F_QUIET_MS = "quietMs";
    /** {@code wait}: the overall bound. */
    public static final String F_TIMEOUT_MS = "timeoutMs";
    /** {@code watch}: the sessions to watch; absent or empty = all, including later ones. */
    public static final String F_NAMES = "names";

    // ---- response fields
    public static final String F_OK = "ok";
    public static final String F_ERROR = "error";
    public static final String F_VERSION = "version";
    public static final String F_PID = "pid";
    public static final String F_SESSIONS = "sessions";
    public static final String F_PRESENT = "present";
    public static final String F_PATH = "path";
    public static final String F_ALIVE = "alive";
    public static final String F_FOREGROUND_COMMAND = "foregroundCommand";
    public static final String F_PROCESS_ID = "processId";
    public static final String F_TITLE = "title";
    public static final String F_CLIENTS = "clients";
    public static final String F_CREATED_AT = "createdAt";
    public static final String F_EXIT_CODE = "exitCode";
    /** {@code unix} | {@code conpty} | {@code winpty} — the pty4j backend a session got. */
    public static final String F_BACKEND = "backend";
    /** Raw PTY bytes the session has produced so far; monotonic. */
    public static final String F_OUTPUT_BYTES = "outputBytes";
    /** {@code capture}: whether the program shows its cursor. */
    public static final String F_CURSOR_VISIBLE = "cursorVisible";
    /** {@code wait}: true when the output went quiet, false on timeout. */
    public static final String F_QUIET = "quiet";
    /** A {@code watch} stream entry: one of the {@code EVENT_*} names. */
    public static final String F_EVENT = "event";

    // ---- watch event names (F_EVENT)
    /** The session became active: the first output after a quiet period (or the watch start). */
    public static final String EVENT_OUTPUT = "output";
    /** No output for the watch's {@code quietMs} after activity. */
    public static final String EVENT_QUIET = "quiet";
    /** The OSC 0/2 title changed; carries {@code title}. */
    public static final String EVENT_TITLE = "title";
    /** The program rang the bell (a BEL outside any string sequence). */
    public static final String EVENT_BELL = "bell";
    /** The session's process ended; carries {@code exitCode}. */
    public static final String EVENT_EXIT = "exit";

    /** The {@code error} text a server sends when a request's {@code protocol} differs from
     * its own; the response also carries {@code protocol}, {@code version} and {@code pid}. */
    public static final String ERROR_PROTOCOL_MISMATCH = "protocol mismatch";
    /** {@code input} with {@code paste} and {@code requireBracketedPaste} while the program has
     * not enabled bracketed paste (nothing was written). */
    public static final String ERROR_BRACKETED_PASTE_DISABLED = "bracketed paste is not enabled in the session";

    // ---- operations
    /** → {ok, protocol, version, pid}. */
    public static final String OP_PING = "ping";
    /** → {ok, sessions: [{name, workdir, columns, rows, pid, createdAt, clients, alive, backend,
     * labels, outputBytes}]}. */
    public static final String OP_LIST = "list";
    /** {name} → {ok, present}. */
    public static final String OP_HAS = "has";
    /** {name, workdir, columns, rows, command?: [..], labels?: {k: v}, env?: {k: v}} → {ok, pid,
     * backend, env?: true}. Error if the name exists. {@code env: true} acknowledges a
     * non-empty {@code env} (absent from a daemon that predates the field and dropped it). */
    public static final String OP_NEW = "new";
    /** {name} → {ok}. An absent session is not an error. */
    public static final String OP_KILL = "kill";
    /** {name, text, enter?: true} → {ok}. Text, then (when enter) a 400 ms pause and CR. */
    public static final String OP_SEND_KEYS = "send-keys";
    /** {name, format?: "text"|"annotated"|"ansi"} → {ok, text, alive, foregroundCommand,
     * processId, title, columns, rows, format, outputBytes, cursorVisible}. */
    public static final String OP_CAPTURE = "capture";
    /** {name, labels: {k: v|null}} → {ok, labels}: merges, a null value removes the key. */
    public static final String OP_LABEL = "label";
    /** {name, text?, paste?: false, requireBracketedPaste?: false, keys?: [..]} → {ok}. Text
     * first (a paste, or raw UTF-8), then the named keys; at least one of the two. An unknown
     * key name, or a paste with {@code requireBracketedPaste} while the program has not
     * enabled bracketed paste, fails the whole request before anything is written. */
    public static final String OP_INPUT = "input";
    /** {name, sinceBytes?: -1, quietMs?: 300, timeoutMs?: 5000} → {ok, quiet, outputBytes,
     * alive}. Blocks this connection until the output is quiet, the session exits, or the
     * timeout (clamped to 0..{@link #WAIT_MAX_MS}) elapses. */
    public static final String OP_WAIT = "wait";
    /** {names?: [..], quietMs?: 500} → {ok}; the connection then streams RESPONSE frames
     * {ok, event, name, outputBytes, title?, exitCode?} until DETACH or EOF. */
    public static final String OP_WATCH = "watch";
    /** {name} → {ok, path}. */
    public static final String OP_CURRENT_PATH = "current-path";
    /** {name, columns, rows} → {ok}; the connection is then in attach mode. */
    public static final String OP_ATTACH = "attach";
    /** → {ok}; the server stops accepting, kills every session (closing the PTY masters
     * would SIGHUP the shells anyway, so the kill is explicit and ordered), and exits. */
    public static final String OP_SHUTDOWN = "shutdown";

    /** Server-side pause between typed text and the Enter key on {@link #OP_SEND_KEYS}:
     * TUIs with paste detection treat a CR arriving within milliseconds of the text as a
     * literal newline, not a submit (same value tmux-based callers use today). */
    public static final long SEND_KEYS_ENTER_DELAY_MS = 400;

    /** {@link #OP_WAIT} defaults and the clamp for both of its durations. */
    public static final long WAIT_DEFAULT_QUIET_MS = 300;
    public static final long WAIT_DEFAULT_TIMEOUT_MS = 5000;
    public static final long WAIT_MAX_MS = 60_000;
    /** {@link #OP_WATCH}'s default quiet period. */
    public static final long WATCH_DEFAULT_QUIET_MS = 500;

    private Protocol() {
    }
}
