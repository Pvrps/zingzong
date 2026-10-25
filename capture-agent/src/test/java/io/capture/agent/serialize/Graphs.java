package io.capture.agent.serialize;

import com.fasterxml.jackson.databind.JsonNode;
import io.capture.trace.SerializationWarning;
import io.capture.trace.TraceJson;

import java.util.List;

/** Serializes a value with a fresh context and keeps the warnings next to the result. */
final class Graphs {

    final JacksonGraphSerializer serializer;

    Graphs() {
        this(new SerializerConfig());
    }

    Graphs(SerializerConfig config) {
        this.serializer = new JacksonGraphSerializer(config);
    }

    Result write(Object value) {
        SerializationContext ctx = new SerializationContext(serializer.config(), "$");
        JsonNode node = serializer.serialize(value, value == null ? Object.class : value.getClass(), ctx);
        return new Result(node, ctx.warnings());
    }

    static final class Result {
        final JsonNode node;
        final List<SerializationWarning> warnings;

        Result(JsonNode node, List<SerializationWarning> warnings) {
            this.node = node;
            this.warnings = warnings;
        }

        String json() {
            try {
                return TraceJson.mapper().writeValueAsString(node);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }

        String warnings() {
            StringBuilder sb = new StringBuilder();
            for (SerializationWarning w : warnings) {
                sb.append(w.path).append(" : ").append(w.reason).append(" (").append(w.typeName).append(")\n");
            }
            return sb.toString();
        }

        boolean hasWarning(String pathPart, String reasonPart) {
            for (SerializationWarning w : warnings) {
                if (w.path.contains(pathPart) && w.reason.contains(reasonPart)) {
                    return true;
                }
            }
            return false;
        }
    }
}
