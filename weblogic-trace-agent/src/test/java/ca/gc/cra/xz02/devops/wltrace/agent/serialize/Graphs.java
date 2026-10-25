package ca.gc.cra.xz02.devops.wltrace.agent.serialize;

import ca.gc.cra.xz02.devops.wltrace.model.SerializationWarning;
import ca.gc.cra.xz02.devops.wltrace.model.TraceJson;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/** Serializes a value with a fresh context and keeps the warnings next to the result. */
final class Graphs {

    final JacksonGraphSerializer serializer;

    Graphs() {
        this(new SerializationCeilings());
    }

    Graphs(SerializationCeilings ceilings) {
        this.serializer = new JacksonGraphSerializer(ceilings, new TypeInfoCache());
    }

    Result write(Object value) {
        SerializationContext context = new SerializationContext(serializer.ceilings(), "$");
        JsonNode node = serializer.serialize(value, value == null ? Object.class : value.getClass(), context);
        return new Result(node, context.warnings());
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
            StringBuilder builder = new StringBuilder();
            for (SerializationWarning warning : warnings) {
                builder.append(warning.path).append(" : ").append(warning.reason).append(" (").append(warning.typeName).append(")\n");
            }
            return builder.toString();
        }

        boolean hasWarning(String pathPart, String reasonPart) {
            return warnings.stream().anyMatch(warning -> warning.path.contains(pathPart) && warning.reason.contains(reasonPart));
        }
    }
}
