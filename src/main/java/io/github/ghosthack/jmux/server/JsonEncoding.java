package io.github.ghosthack.jmux.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.ghosthack.jmux.protocol.Json;
import io.github.ghosthack.jmux.protocol.Protocol;
import io.github.ghosthack.jmux.session.CaptureFormat;
import io.github.ghosthack.jmux.session.Session;
import io.github.ghosthack.jmux.session.SessionEvent;

import java.util.List;
import java.util.Map;

/** Server-side JSON shapes of the session records; the client's {@code JsonDecoding}
 * is the mirror image. Field names come from {@link Protocol} so both sides agree. */
final class JsonEncoding {

    static ObjectNode info(Session.Info info) {
        return Json.object()
                .put(Protocol.F_NAME, info.name())
                .put(Protocol.F_WORKDIR, info.workdir().toString())
                .put(Protocol.F_COLUMNS, info.columns())
                .put(Protocol.F_ROWS, info.rows())
                .put(Protocol.F_PID, info.pid())
                .put(Protocol.F_CREATED_AT, info.createdAt().toString())
                .put(Protocol.F_CLIENTS, info.clients())
                .put(Protocol.F_ALIVE, info.alive())
                .put(Protocol.F_BACKEND, info.backend())
                .<ObjectNode>set(Protocol.F_LABELS, labels(info.labels()))
                .put(Protocol.F_OUTPUT_BYTES, info.outputBytes());
    }

    static ObjectNode labels(Map<String, String> labels) {
        ObjectNode node = Json.object();
        labels.forEach(node::put);
        return node;
    }

    /** One {@code watch} stream entry. */
    static ObjectNode event(SessionEvent event) {
        ObjectNode node = Json.ok()
                .put(Protocol.F_EVENT, event.type())
                .put(Protocol.F_NAME, event.name())
                .put(Protocol.F_OUTPUT_BYTES, event.outputBytes());
        if (event.title() != null) {
            node.put(Protocol.F_TITLE, event.title());
        }
        if (event.exitCode() != null) {
            node.put(Protocol.F_EXIT_CODE, event.exitCode());
        }
        return node;
    }

    static ArrayNode infos(List<Session> sessions) {
        ArrayNode array = Json.MAPPER.createArrayNode();
        for (Session session : sessions) {
            array.add(info(session.info()));
        }
        return array;
    }

    static ObjectNode capture(Session.Capture capture, CaptureFormat format) {
        return Json.ok()
                .put(Protocol.F_TEXT, capture.text())
                .put(Protocol.F_ALIVE, capture.alive())
                .put(Protocol.F_FOREGROUND_COMMAND, capture.foregroundCommand())
                .put(Protocol.F_PROCESS_ID, capture.processId())
                .put(Protocol.F_TITLE, capture.title())
                .put(Protocol.F_COLUMNS, capture.columns())
                .put(Protocol.F_ROWS, capture.rows())
                .put(Protocol.F_FORMAT, format.wireName())
                .put(Protocol.F_OUTPUT_BYTES, capture.outputBytes())
                .put(Protocol.F_CURSOR_VISIBLE, capture.cursorVisible());
    }

    /** An integer field with a default, tolerant of a missing or non-numeric value. */
    static int intField(JsonNode node, String field, int fallback) {
        JsonNode value = node.get(field);
        return value != null && value.canConvertToInt() ? value.asInt() : fallback;
    }

    /** A long field with a default, tolerant of a missing or non-numeric value. */
    static long longField(JsonNode node, String field, long fallback) {
        JsonNode value = node.get(field);
        return value != null && value.canConvertToLong() ? value.asLong() : fallback;
    }

    /** A boolean field with a default; anything but a JSON boolean is the default. */
    static boolean booleanField(JsonNode node, String field, boolean fallback) {
        JsonNode value = node.get(field);
        return value != null && value.isBoolean() ? value.asBoolean() : fallback;
    }

    static String textField(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private JsonEncoding() {
    }
}
