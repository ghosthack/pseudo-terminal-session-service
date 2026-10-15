package io.github.ghosthack.jmux.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.ghosthack.jmux.protocol.Json;
import io.github.ghosthack.jmux.protocol.Protocol;
import io.github.ghosthack.jmux.session.CaptureFormat;
import io.github.ghosthack.jmux.session.Session;
import io.github.ghosthack.jmux.session.SessionManager;
import io.github.ghosthack.jmux.vt.KeyNames;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * The request/response operations that do not change the connection's mode. A missing
 * session is always the error {@value #NO_SUCH_SESSION_PREFIX}{@code <name>} so the
 * client can map it onto Harness Manager's "no such session" semantics.
 */
final class RequestDispatcher {

    static final String NO_SUCH_SESSION_PREFIX = "no such session: ";

    private final SessionManager manager;
    private final SessionServer server;

    RequestDispatcher(SessionManager manager, SessionServer server) {
        this.manager = manager;
        this.server = server;
    }

    /** Null when {@code op} is unknown here (attach and shutdown belong to the connection). */
    ObjectNode handle(String op, JsonNode request) {
        switch (op) {
            case Protocol.OP_PING:
                return server.identity(Json.ok());
            case Protocol.OP_LIST:
                return Json.ok().set(Protocol.F_SESSIONS, JsonEncoding.infos(manager.sessions()));
            case Protocol.OP_HAS:
                return Json.ok().put(Protocol.F_PRESENT, manager.find(name(request)).isPresent());
            case Protocol.OP_NEW:
                return create(request);
            case Protocol.OP_KILL:
                manager.kill(name(request));
                return Json.ok();
            case Protocol.OP_SEND_KEYS:
                return withSession(request, session -> {
                    String text = JsonEncoding.textField(request, Protocol.F_TEXT);
                    JsonNode enter = request.get(Protocol.F_ENTER);
                    session.sendKeys(text == null ? "" : text, enter == null || enter.asBoolean(true));
                    return Json.ok();
                });
            case Protocol.OP_CAPTURE:
                return capture(request);
            case Protocol.OP_LABEL:
                return label(request);
            case Protocol.OP_INPUT:
                return input(request);
            case Protocol.OP_WAIT:
                return await(request);
            case Protocol.OP_CURRENT_PATH:
                return withSession(request, session ->
                        Json.ok().put(Protocol.F_PATH, session.currentPath().toString()));
            default:
                return null;
        }
    }

    SessionManager manager() {
        return manager;
    }

    Optional<Session> find(JsonNode request) {
        String name = name(request);
        return name.isEmpty() ? Optional.empty() : manager.find(name);
    }

    static ObjectNode noSuchSession(String name) {
        return Json.error(NO_SUCH_SESSION_PREFIX + name);
    }

    /** Bounds for a requested terminal size; jediterm clamps upward to 5×2 itself, but a
     * zero or absurd size must never reach the PTY spawn (the shell would be started and
     * then orphaned by the emulator's constructor failing). */
    static final int MAX_COLUMNS = 4000;
    static final int MAX_ROWS = 2000;

    /** The error text for an unusable size, or empty when it is acceptable. */
    static Optional<String> invalidSize(int columns, int rows) {
        if (columns < 1 || columns > MAX_COLUMNS || rows < 1 || rows > MAX_ROWS) {
            return Optional.of("invalid size " + columns + "x" + rows + " (1.." + MAX_COLUMNS
                    + " columns, 1.." + MAX_ROWS + " rows)");
        }
        return Optional.empty();
    }

    private ObjectNode create(JsonNode request) {
        String name = name(request);
        if (name.isEmpty()) {
            return Json.error("session name is required");
        }
        int columns = JsonEncoding.intField(request, Protocol.F_COLUMNS, 80);
        int rows = JsonEncoding.intField(request, Protocol.F_ROWS, 24);
        Optional<String> badSize = invalidSize(columns, rows);
        if (badSize.isPresent()) {
            return Json.error(badSize.get());
        }
        String workdir = JsonEncoding.textField(request, Protocol.F_WORKDIR);
        if (workdir == null || workdir.isBlank()) {
            return Json.error("workdir is required");
        }
        Map<String, String> labels;
        Map<String, String> env;
        try {
            labels = labels(request.get(Protocol.F_LABELS), false);
            env = environment(request.get(Protocol.F_ENV));
        } catch (IllegalArgumentException e) {
            return Json.error(e.getMessage());
        }
        List<String> command = new ArrayList<>();
        JsonNode commandNode = request.get(Protocol.F_COMMAND);
        if (commandNode != null && commandNode.isArray()) {
            commandNode.forEach(arg -> command.add(arg.asText()));
        }
        try {
            Session session = manager.create(name, Path.of(workdir), columns, rows, command, labels, env);
            Session.Info info = session.info();
            ObjectNode reply = Json.ok().put(Protocol.F_PID, info.pid()).put(Protocol.F_BACKEND, info.backend());
            if (!env.isEmpty()) {
                // The ack: a daemon that predates env ignores the field and omits this, which
                // is how a client tells "applied" from "silently dropped".
                reply.put(Protocol.F_ENV, true);
            }
            return reply;
        } catch (IllegalStateException | IllegalArgumentException | UncheckedIOException e) {
            return Json.error(e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private ObjectNode capture(JsonNode request) {
        String formatName = JsonEncoding.textField(request, Protocol.F_FORMAT);
        Optional<CaptureFormat> format = formatName == null
                ? Optional.of(CaptureFormat.TEXT) : CaptureFormat.fromWireName(formatName);
        if (format.isEmpty()) {
            return Json.error("unknown capture format: " + formatName + " (text, annotated or ansi)");
        }
        return withSession(request, session ->
                JsonEncoding.capture(session.capture(format.get()), format.get()));
    }

    private ObjectNode label(JsonNode request) {
        Map<String, String> changes;
        try {
            changes = labels(request.get(Protocol.F_LABELS), true);
        } catch (IllegalArgumentException e) {
            return Json.error(e.getMessage());
        }
        return withSession(request, session -> {
            session.label(changes);
            return Json.ok().set(Protocol.F_LABELS, JsonEncoding.labels(session.labels()));
        });
    }

    /** Validates everything (key names included) before writing anything, so a bad key
     * never leaves half an input in the PTY. */
    private ObjectNode input(JsonNode request) {
        JsonNode textNode = request.get(Protocol.F_TEXT);
        if (textNode != null && !textNode.isNull() && !textNode.isTextual()) {
            return Json.error("text must be a string");
        }
        String text = textNode == null || textNode.isNull() ? null : textNode.asText();
        JsonNode keysNode = request.get(Protocol.F_KEYS);
        List<String> keys = new ArrayList<>();
        if (keysNode != null && !keysNode.isNull()) {
            if (!keysNode.isArray()) {
                return Json.error("keys must be an array of key names");
            }
            for (JsonNode key : keysNode) {
                if (!key.isTextual()) {
                    return Json.error("keys must be an array of key names");
                }
                keys.add(key.asText());
            }
        }
        if (text == null && keys.isEmpty()) {
            return Json.error("input needs text or keys");
        }
        try {
            KeyNames.encode(keys, false);
        } catch (IllegalArgumentException e) {
            return Json.error(e.getMessage());
        }
        boolean paste = JsonEncoding.booleanField(request, Protocol.F_PASTE, false);
        boolean requireBracketed = JsonEncoding.booleanField(request, Protocol.F_REQUIRE_BRACKETED_PASTE, false);
        return withSession(request, session -> {
            if (text != null) {
                if (paste && requireBracketed) {
                    // Checked and written in one step by the session; refused = nothing
                    // written, keys included.
                    if (!session.pasteIfBracketed(text)) {
                        return Json.error(Protocol.ERROR_BRACKETED_PASTE_DISABLED);
                    }
                } else if (paste) {
                    session.paste(text);
                } else {
                    session.write(text.getBytes(StandardCharsets.UTF_8));
                }
            }
            if (!keys.isEmpty()) {
                session.input(keys);
            }
            return Json.ok();
        });
    }

    /** Inline on the connection's thread: only this connection waits. */
    private ObjectNode await(JsonNode request) {
        long sinceBytes = JsonEncoding.longField(request, Protocol.F_SINCE_BYTES, -1);
        long quietMs = clampMs(JsonEncoding.longField(request, Protocol.F_QUIET_MS, Protocol.WAIT_DEFAULT_QUIET_MS));
        long timeoutMs = clampMs(JsonEncoding.longField(request, Protocol.F_TIMEOUT_MS,
                Protocol.WAIT_DEFAULT_TIMEOUT_MS));
        return withSession(request, session -> {
            try {
                Session.WaitResult result = session.awaitQuiet(sinceBytes, quietMs, timeoutMs);
                return Json.ok()
                        .put(Protocol.F_QUIET, result.quiet())
                        .put(Protocol.F_OUTPUT_BYTES, result.outputBytes())
                        .put(Protocol.F_ALIVE, result.alive());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Json.error("wait interrupted (server shutting down)");
            }
        });
    }

    /** A duration request field clamped to 0..{@link Protocol#WAIT_MAX_MS}. */
    static long clampMs(long ms) {
        return Math.max(0, Math.min(Protocol.WAIT_MAX_MS, ms));
    }

    /** A {@code labels} object as a map. With {@code allowNull} a JSON null value is kept as
     * a null (remove) entry; without, it is an error. Absent/null object = empty. */
    static Map<String, String> labels(JsonNode node, boolean allowNull) {
        Map<String, String> labels = new LinkedHashMap<>();
        if (node == null || node.isNull()) {
            return labels;
        }
        if (!node.isObject()) {
            throw new IllegalArgumentException("labels must be an object of strings");
        }
        for (Map.Entry<String, JsonNode> field : node.properties()) {
            JsonNode value = field.getValue();
            if (value.isTextual()) {
                labels.put(field.getKey(), value.asText());
            } else if (value.isNull() && allowNull) {
                labels.put(field.getKey(), null);
            } else {
                throw new IllegalArgumentException("label " + field.getKey() + " must be a string"
                        + (allowNull ? " or null" : ""));
            }
        }
        return labels;
    }

    /** An {@code env} object as a map: string values only, names non-empty and free of
     * {@code =} and NUL, values free of NUL (neither can reach a process environment).
     * Absent/null object = empty. */
    static Map<String, String> environment(JsonNode node) {
        Map<String, String> env = new LinkedHashMap<>();
        if (node == null || node.isNull()) {
            return env;
        }
        if (!node.isObject()) {
            throw new IllegalArgumentException("env must be an object of strings");
        }
        for (Map.Entry<String, JsonNode> field : node.properties()) {
            String key = field.getKey();
            JsonNode value = field.getValue();
            if (key.isEmpty() || key.indexOf('=') >= 0 || key.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("invalid environment variable name: '" + key + "'");
            }
            if (!value.isTextual()) {
                throw new IllegalArgumentException("env " + key + " must be a string");
            }
            if (value.asText().indexOf('\0') >= 0) {
                throw new IllegalArgumentException("env " + key + " must not contain NUL");
            }
            env.put(key, value.asText());
        }
        return env;
    }

    private ObjectNode withSession(JsonNode request, Function<Session, ObjectNode> body) {
        String name = name(request);
        Optional<Session> session = name.isEmpty() ? Optional.empty() : manager.find(name);
        return session.map(body).orElseGet(() -> noSuchSession(name));
    }

    private static String name(JsonNode request) {
        String name = JsonEncoding.textField(request, Protocol.F_NAME);
        return name == null ? "" : name;
    }
}
