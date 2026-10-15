package io.github.ghosthack.jmux.protocol;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;

/** The one shared, thread-safe Jackson mapper, plus the two helpers every side uses. */
public final class Json {

    public static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public static ObjectNode object() {
        return MAPPER.createObjectNode();
    }

    public static JsonNode parse(byte[] utf8) throws IOException {
        return MAPPER.readTree(utf8);
    }

    public static byte[] bytes(JsonNode node) {
        try {
            return MAPPER.writeValueAsBytes(node);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** {@code {"ok":true}} with room for more fields. */
    public static ObjectNode ok() {
        return object().put(Protocol.F_OK, true);
    }

    public static ObjectNode error(String message) {
        return object().put(Protocol.F_OK, false).put(Protocol.F_ERROR, message);
    }

    private Json() {
    }
}
