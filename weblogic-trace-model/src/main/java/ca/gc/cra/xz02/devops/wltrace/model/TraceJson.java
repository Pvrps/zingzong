package ca.gc.cra.xz02.devops.wltrace.model;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** The one mapper configuration every reader and writer of the format shares. */
public final class TraceJson {

    private static final ObjectMapper MAPPER = newMapper();

    private TraceJson() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static ObjectMapper newMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.NONE);
        mapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.PUBLIC_ONLY);
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        mapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
        // Unknown fields come from newer writers; readers keep working.
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        return mapper;
    }

    public static void write(Trace trace, OutputStream out) throws IOException {
        MAPPER.writeValue(out, trace);
    }

    public static String toJson(Trace trace) throws IOException {
        return MAPPER.writeValueAsString(trace);
    }

    public static Trace read(InputStream in) throws IOException {
        return MAPPER.readValue(in, Trace.class);
    }

    public static Trace fromJson(String json) throws IOException {
        return MAPPER.readValue(json, Trace.class);
    }

    public static <T> T read(InputStream in, Class<T> type) throws IOException {
        return MAPPER.readValue(in, type);
    }

    public static void write(Object document, OutputStream out) throws IOException {
        MAPPER.writeValue(out, document);
    }
}
