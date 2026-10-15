package io.github.ghosthack.jmux.cli;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.ghosthack.jmux.client.SessionServerClient;
import io.github.ghosthack.jmux.client.SessionServerException;
import io.github.ghosthack.jmux.protocol.Json;
import io.github.ghosthack.jmux.protocol.Protocol;
import io.github.ghosthack.jmux.server.DaemonMode;
import io.github.ghosthack.jmux.server.ServeOptions;
import io.github.ghosthack.jmux.server.ServerHome;
import io.github.ghosthack.jmux.server.ServerVersion;
import io.github.ghosthack.jmux.server.SessionManagerFactory;
import io.github.ghosthack.jmux.server.SessionServer;
import io.github.ghosthack.jmux.session.CaptureFormat;
import io.github.ghosthack.jmux.session.Session;
import io.github.ghosthack.jmux.session.SessionEvent;
import io.github.ghosthack.jmux.session.SessionManager;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The command line: one subcommand per server operation plus {@code serve} and
 * {@code version}. Exit code 0 on success, 1 on any error (usage errors included), errors
 * on stderr, JSON with {@code --json}. The dead-server rules are the client library's:
 * {@code list} prints nothing, {@code has} prints false, {@code kill}/{@code shutdown}
 * succeed, everything else reports "no such session" — and only {@code new} spawns.
 * Dependency-free on purpose: this is the debugging surface, not the product UI.
 */
public final class Cli {

    static final String USAGE = """
            usage: jmux <command> [options]

            commands:
              serve [--daemon] [--keep-alive] [--history-lines N] [--client-buffer BYTES]
              list [--json]
              has NAME
              new NAME [--dir DIR] [--size CxR]
              kill NAME
              send-keys NAME TEXT... [--no-enter]
              input NAME [--text T] [--paste] [KEY...]
              label NAME [KEY=VALUE...]   (KEY= removes the label)
              wait NAME [--quiet MS] [--timeout MS] [--since BYTES]
              watch [NAME...] [--quiet MS]
              capture NAME [--format text|annotated|ansi] [--json]
              path NAME
              attach NAME [--size CxR]
              shutdown
              version [--json]

            options:
              --home DIR   server home (default ~/.jmux,
                           or $JMUX_HOME / -Djmux.home)
              --json       machine-readable output

            keys (input): a single character, Enter, Tab, BTab, Esc, Space, Backspace,
              Delete, Insert, Up, Down, Left, Right, Home, End, PageUp, PageDown,
              F1..F12, C-x (control), M-key (meta/ESC prefix)
            """;

    private static final Set<String> VALUE_OPTIONS = Set.of("--home", "--dir", "--size", "--history-lines",
            "--client-buffer", "--text", "--format", "--quiet", "--timeout", "--since");
    private static final Set<String> FLAG_OPTIONS = Set.of("--json", "--daemon", "--keep-alive", "--no-enter",
            "--help", "--paste");

    private final InputStream stdin;
    private final PrintStream out;
    /** Reassigned once by {@code serve --daemon}: after the stdio redirect, errors belong
     * in {@code server.log}, not on the stderr the process was born with. */
    private PrintStream err;

    public Cli(InputStream stdin, OutputStream out, PrintStream err) {
        this.stdin = stdin;
        this.out = out instanceof PrintStream ps ? ps : new PrintStream(out, true);
        this.err = err;
    }

    /** Runs one command line and returns the process exit code. */
    public int run(String... argv) {
        List<String> all = Arrays.asList(argv);
        if (all.isEmpty() || all.contains("--help") || all.contains("-h")) {
            err.print(USAGE);
            return all.isEmpty() ? 1 : 0;
        }
        String command = all.get(0);
        try {
            Args args = Args.parse(all.subList(1, all.size()), VALUE_OPTIONS, FLAG_OPTIONS);
            Path home = args.value("--home").map(Path::of).orElseGet(ServerHome::resolve);
            return dispatch(command, args, home);
        } catch (Args.UsageException e) {
            err.println("error: " + e.getMessage());
            err.print(USAGE);
            return 1;
        } catch (SessionServerException e) {
            err.println("error: " + e.getMessage());
            return 1;
        } catch (IOException | RuntimeException e) {
            err.println("error: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
            return 1;
        }
    }

    private int dispatch(String command, Args args, Path home) throws IOException {
        SessionServerClient client = new SessionServerClient(home, true);
        boolean json = args.flag("--json");
        switch (command) {
            case "serve":
                return serve(args, home);
            case "version":
                return version(client, json);
            case "list":
                return list(client, json);
            case "has":
                out.println(client.has(args.positional(0, "NAME")));
                return 0;
            case "new":
                return newSession(client, args);
            case "kill":
                client.kill(args.positional(0, "NAME"));
                return 0;
            case "send-keys": {
                String name = args.positional(0, "NAME");
                List<String> words = args.positionals().subList(1, args.positionals().size());
                if (words.isEmpty()) {
                    throw new Args.UsageException("TEXT is required");
                }
                client.sendKeys(name, String.join(" ", words), !args.flag("--no-enter"));
                return 0;
            }
            case "input":
                return input(client, args);
            case "label":
                return label(client, args);
            case "wait":
                return await(client, args);
            case "watch":
                return watch(client, args);
            case "capture":
                return capture(client, args.positional(0, "NAME"), captureFormat(args), json);
            case "path":
                out.println(client.currentPath(args.positional(0, "NAME")));
                return 0;
            case "attach":
                return attach(client, args);
            case "shutdown":
                client.shutdown();
                return 0;
            default:
                throw new Args.UsageException("unknown command '" + command + "'");
        }
    }

    private int serve(Args args, Path home) throws IOException {
        ServeOptions options = new ServeOptions(home, args.flag("--daemon"), args.flag("--keep-alive"),
                args.intValue("--history-lines", ServeOptions.DEFAULT_HISTORY_LINES),
                args.intValue("--client-buffer", ServeOptions.DEFAULT_CLIENT_BUFFER_BYTES),
                ServeOptions.DEFAULT_BEHIND_TIMEOUT);
        if (options.daemon()) {
            DaemonMode.enter(ServerHome.log(home));
            err = System.err;
        }
        SessionManager manager = SessionManagerFactory.createSessionManager(options);
        SessionServer server = new SessionServer(options, manager);
        if (!server.start()) {
            return 0; // another server owns the home: that one serves, we are redundant
        }
        Runtime.getRuntime().addShutdownHook(new Thread(server::shutdown, "hss-shutdown-hook"));
        try {
            server.awaitExit();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            server.shutdown();
        }
        return 0;
    }

    private int version(SessionServerClient client, boolean json) {
        String version = ServerVersion.current();
        Optional<SessionServerClient.ServerInfo> running = client.ping();
        if (json) {
            ObjectNode node = Json.object().put(Protocol.F_VERSION, version).put(Protocol.F_PROTOCOL, Protocol.VERSION);
            running.ifPresent(info -> node.set("server", Json.object()
                    .put(Protocol.F_VERSION, info.version())
                    .put(Protocol.F_PROTOCOL, info.protocol())
                    .put(Protocol.F_PID, info.pid())));
            out.println(node);
        } else {
            out.println("jmux " + version + " (protocol " + Protocol.VERSION + ")");
            running.ifPresent(info -> out.println("server running: " + info.version()
                    + " (protocol " + info.protocol() + ", pid " + info.pid() + ")"));
        }
        return 0;
    }

    private int list(SessionServerClient client, boolean json) {
        List<Session.Info> sessions = client.list();
        if (json) {
            ArrayNode array = Json.MAPPER.createArrayNode();
            for (Session.Info info : sessions) {
                array.add(Json.object()
                        .put(Protocol.F_NAME, info.name())
                        .put(Protocol.F_WORKDIR, info.workdir().toString())
                        .put(Protocol.F_COLUMNS, info.columns())
                        .put(Protocol.F_ROWS, info.rows())
                        .put(Protocol.F_PID, info.pid())
                        .put(Protocol.F_CREATED_AT, info.createdAt().toString())
                        .put(Protocol.F_CLIENTS, info.clients())
                        .put(Protocol.F_ALIVE, info.alive())
                        .put(Protocol.F_BACKEND, info.backend())
                        .<ObjectNode>set(Protocol.F_LABELS, labelsNode(info.labels()))
                        .put(Protocol.F_OUTPUT_BYTES, info.outputBytes()));
            }
            out.println(array);
        } else {
            for (Session.Info info : sessions) {
                out.println(info.name() + ": " + info.columns() + "x" + info.rows() + " pid " + info.pid()
                        + ", " + info.clients() + " client" + (info.clients() == 1 ? "" : "s")
                        + (info.alive() ? "" : ", exited") + " [" + info.backend() + "] " + info.workdir());
            }
        }
        return 0;
    }

    private int newSession(SessionServerClient client, Args args) {
        String name = args.positional(0, "NAME");
        Path dir = args.value("--dir").map(Path::of).orElseGet(() -> Path.of(System.getProperty("user.dir")));
        int[] size = args.value("--size").map(Args::size).orElse(new int[]{80, 24});
        client.newSession(name, dir, size[0], size[1], null);
        return 0;
    }

    private int input(SessionServerClient client, Args args) {
        String name = args.positional(0, "NAME");
        List<String> keys = args.positionals().subList(1, args.positionals().size());
        Optional<String> text = args.value("--text");
        if (text.isEmpty() && keys.isEmpty()) {
            throw new Args.UsageException("--text or a KEY is required");
        }
        client.input(name, text.orElse(null), args.flag("--paste"), keys);
        return 0;
    }

    private int label(SessionServerClient client, Args args) {
        String name = args.positional(0, "NAME");
        Map<String, String> changes = parseLabels(args.positionals().subList(1, args.positionals().size()));
        Map<String, String> labels = client.label(name, changes);
        if (args.flag("--json")) {
            out.println(labelsNode(labels));
        } else {
            labels.forEach((key, value) -> out.println(key + "=" + value));
        }
        return 0;
    }

    /** {@code KEY=VALUE} words as label changes; {@code KEY=} (empty value) removes. */
    static Map<String, String> parseLabels(List<String> words) {
        Map<String, String> changes = new LinkedHashMap<>();
        for (String word : words) {
            int eq = word.indexOf('=');
            if (eq <= 0) {
                throw new Args.UsageException("labels are KEY=VALUE (KEY= removes), not '" + word + "'");
            }
            String value = word.substring(eq + 1);
            changes.put(word.substring(0, eq), value.isEmpty() ? null : value);
        }
        return changes;
    }

    private int await(SessionServerClient client, Args args) {
        String name = args.positional(0, "NAME");
        Session.WaitResult result = client.awaitQuiet(name,
                args.longValue("--since", -1),
                args.longValue("--quiet", Protocol.WAIT_DEFAULT_QUIET_MS),
                args.longValue("--timeout", Protocol.WAIT_DEFAULT_TIMEOUT_MS));
        out.println(Json.object()
                .put(Protocol.F_QUIET, result.quiet())
                .put(Protocol.F_OUTPUT_BYTES, result.outputBytes())
                .put(Protocol.F_ALIVE, result.alive()));
        return 0;
    }

    /** One JSON line per event until the server ends the stream (or the process is
     * interrupted). A stream the server dropped with an error (the watcher fell behind)
     * prints that error and exits 1, so a script can tell it from a normal end. */
    private int watch(SessionServerClient client, Args args) {
        long quietMs = args.longValue("--quiet", Protocol.WATCH_DEFAULT_QUIET_MS);
        try (SessionServerClient.Watch watch = client.watch(args.positionals(), quietMs, this::printEvent)) {
            while (watch.isOpen()) {
                Thread.sleep(100);
            }
            Optional<String> error = watch.error();
            if (error.isPresent()) {
                err.println("error: " + error.get());
                return 1;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return 0;
    }

    private synchronized void printEvent(SessionEvent event) {
        ObjectNode node = Json.object()
                .put(Protocol.F_EVENT, event.type())
                .put(Protocol.F_NAME, event.name())
                .put(Protocol.F_OUTPUT_BYTES, event.outputBytes());
        if (event.title() != null) {
            node.put(Protocol.F_TITLE, event.title());
        }
        if (event.exitCode() != null) {
            node.put(Protocol.F_EXIT_CODE, event.exitCode());
        }
        out.println(node);
        out.flush();
    }

    static CaptureFormat captureFormat(Args args) {
        return args.value("--format")
                .map(name -> CaptureFormat.fromWireName(name).orElseThrow(() ->
                        new Args.UsageException("--format must be text, annotated or ansi, not '" + name + "'")))
                .orElse(CaptureFormat.TEXT);
    }

    private static ObjectNode labelsNode(Map<String, String> labels) {
        ObjectNode node = Json.object();
        labels.forEach(node::put);
        return node;
    }

    private int capture(SessionServerClient client, String name, CaptureFormat format, boolean json) {
        // The client folds the server's "no such session" into empty (Harness Manager maps
        // it to a dead pane); only blame a missing server when none answers.
        SessionServerClient.FormattedCapture captured = client.captureWithFormat(name, format)
                .orElseThrow(() -> client.isServerRunning()
                        ? new SessionServerException("no such session: " + name)
                        : SessionServerException.noServer(name));
        Session.Capture capture = captured.capture();
        if (json) {
            out.println(Json.object()
                    .put(Protocol.F_TEXT, capture.text())
                    .put(Protocol.F_ALIVE, capture.alive())
                    .put(Protocol.F_FOREGROUND_COMMAND, capture.foregroundCommand())
                    .put(Protocol.F_PROCESS_ID, capture.processId())
                    .put(Protocol.F_TITLE, capture.title())
                    .put(Protocol.F_COLUMNS, capture.columns())
                    .put(Protocol.F_ROWS, capture.rows())
                    .put(Protocol.F_FORMAT, captured.format().wireName())
                    .put(Protocol.F_OUTPUT_BYTES, capture.outputBytes())
                    .put(Protocol.F_CURSOR_VISIBLE, capture.cursorVisible()));
        } else {
            out.print(capture.text());
            out.flush();
        }
        return 0;
    }

    private int attach(SessionServerClient client, Args args) throws IOException {
        String name = args.positional(0, "NAME");
        // Same existence check as capture(): a missing name or dead server reports "no such
        // session" like every other command, before the Windows-unsupported message applies.
        if (!client.has(name)) {
            throw client.isServerRunning()
                    ? new SessionServerException("no such session: " + name)
                    : SessionServerException.noServer(name);
        }
        if (System.getProperty("os.name", "").startsWith("Windows")) {
            err.println("error: console attach is not supported on Windows yet; use Harness Manager to attach");
            return 1;
        }
        int[] size = args.value("--size").map(Args::size).orElse(null);
        return new AttachCommand(client, stdin, out, err).run(name, size);
    }
}
