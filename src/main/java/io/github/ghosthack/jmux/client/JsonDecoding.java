package io.github.ghosthack.jmux.client;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.ghosthack.jmux.protocol.Protocol;
import io.github.ghosthack.jmux.session.Session;
import io.github.ghosthack.jmux.session.SessionEvent;

import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Client-side decoding of the session records the server encodes; unknown fields are
 * ignored and missing ones get neutral defaults, as the protocol contract requires. A
 * missing {@code outputBytes} (in {@code list} and {@code capture}) decodes as -1 =
 * unknown, the daemon predates the counter: never equal to a real count by accident. */
final class JsonDecoding {

    static List<Session.Info> infos(JsonNode sessions) {
        List<Session.Info> infos = new ArrayList<>();
        if (sessions != null && sessions.isArray()) {
            for (JsonNode node : sessions) {
                infos.add(info(node));
            }
        }
        return infos;
    }

    static Session.Info info(JsonNode node) {
        return new Session.Info(
                text(node, Protocol.F_NAME),
                Path.of(text(node, Protocol.F_WORKDIR)),
                node.path(Protocol.F_COLUMNS).asInt(0),
                node.path(Protocol.F_ROWS).asInt(0),
                node.path(Protocol.F_PID).asLong(0),
                instant(text(node, Protocol.F_CREATED_AT)),
                node.path(Protocol.F_CLIENTS).asInt(0),
                node.path(Protocol.F_ALIVE).asBoolean(false),
                text(node, Protocol.F_BACKEND),
                labels(node.get(Protocol.F_LABELS)),
                outputBytes(node));
    }

    /** A labels object; anything else (an old server's missing field) is empty. */
    static Map<String, String> labels(JsonNode node) {
        Map<String, String> labels = new LinkedHashMap<>();
        if (node != null && node.isObject()) {
            node.properties().forEach(field -> {
                if (field.getValue().isTextual()) {
                    labels.put(field.getKey(), field.getValue().asText());
                }
            });
        }
        return labels;
    }

    static SessionEvent event(JsonNode node) {
        JsonNode title = node.get(Protocol.F_TITLE);
        JsonNode exitCode = node.get(Protocol.F_EXIT_CODE);
        return new SessionEvent(
                text(node, Protocol.F_NAME),
                text(node, Protocol.F_EVENT),
                node.path(Protocol.F_OUTPUT_BYTES).asLong(0),
                title != null && title.isTextual() ? title.asText() : null,
                exitCode != null && exitCode.canConvertToInt() ? exitCode.asInt() : null);
    }

    static Session.Capture capture(JsonNode node) {
        return new Session.Capture(
                text(node, Protocol.F_TEXT),
                node.path(Protocol.F_ALIVE).asBoolean(false),
                text(node, Protocol.F_FOREGROUND_COMMAND),
                node.path(Protocol.F_PROCESS_ID).asLong(0),
                text(node, Protocol.F_TITLE),
                node.path(Protocol.F_COLUMNS).asInt(0),
                node.path(Protocol.F_ROWS).asInt(0),
                outputBytes(node),
                node.path(Protocol.F_CURSOR_VISIBLE).asBoolean(true));
    }

    /** The byte counter, or -1 = unknown: the daemon predates the counter. */
    static long outputBytes(JsonNode node) {
        JsonNode value = node.get(Protocol.F_OUTPUT_BYTES);
        return value != null && value.canConvertToLong() ? value.asLong() : -1;
    }

    static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && !value.isNull() ? value.asText() : "";
    }

    private static Instant instant(String iso) {
        try {
            return Instant.parse(iso);
        } catch (DateTimeParseException e) {
            return Instant.EPOCH;
        }
    }

    private JsonDecoding() {
    }
}
