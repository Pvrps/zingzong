package io.capture.reader;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import io.capture.trace.TraceJson;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Validates trace files against {@code trace.schema.json} from trace-model. */
public final class TraceValidator {

    public static final String SCHEMA_RESOURCE = "/trace.schema.json";

    private final JsonSchema schema;

    public TraceValidator() throws IOException {
        try (InputStream in = TraceValidator.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            if (in == null) {
                throw new IOException("schema not on classpath: " + SCHEMA_RESOURCE);
            }
            JsonNode schemaNode = TraceJson.mapper().readTree(in);
            schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7).getSchema(schemaNode);
        }
    }

    /** Problems for one file; empty when valid. Parse errors count as problems. */
    public List<String> validate(Path file) {
        List<String> problems = new ArrayList<>();
        JsonNode node;
        try (InputStream in = Files.newInputStream(file)) {
            node = TraceJson.mapper().readTree(in);
        } catch (IOException e) {
            problems.add("cannot parse: " + e.getMessage());
            return problems;
        }
        Set<ValidationMessage> messages = schema.validate(node);
        for (ValidationMessage m : messages) {
            problems.add(m.getMessage());
        }
        return problems;
    }

    /** Validates every trace file in the directory; keys are file names, only files with problems appear. */
    public java.util.Map<String, List<String>> validateAll(Path dir) throws IOException {
        java.util.Map<String, List<String>> result = new java.util.LinkedHashMap<>();
        for (Path file : RecordingDir.traceFiles(dir)) {
            List<String> problems = validate(file);
            if (!problems.isEmpty()) {
                result.put(file.getFileName().toString(), problems);
            }
        }
        return result;
    }
}
