package io.capture.export;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.capture.trace.ExceptionInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One exported step: everything a replaying client needs to make the same call again, taken
 * from the span exactly as captured. Nothing is summarized: {@link #payload} is the span's
 * {@code args} (or the HTTP request body), {@link #expectedResult} its {@code result}, both
 * with their type markers; the variables and substitutions say which fields carry a value
 * from one selected step to a later one, as detected by trace-reader's lineage.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Step {

    public static final String KIND_EJB = "EJB";
    public static final String KIND_HTTP = "HTTP";

    /** 1-based position among the exported steps. */
    public int index;
    /** "step1", "step2", ...; what substitutions refer to. */
    public String id;
    /** "OrderService.createOrder", "POST /orders/api/orders". */
    public String name;
    public String kind;
    public String traceId;
    public String spanId;
    public Lookup lookup;
    /** EJB: the captured {@code args} list ({@code declaredType} + {@code value} each). HTTP: the request body as captured. */
    public JsonNode payload;
    /** The captured {@code result} ({@code declaredType} + {@code value}); absent for void or when the call threw. */
    public JsonNode expectedResult;
    public ExceptionInfo expectedException;
    /** Fields of this step's result that later selected steps consume. */
    public List<Variable> variables = new ArrayList<>();
    /** Fields of this step's args that come from earlier selected steps. */
    public List<Substitution> substitutions = new ArrayList<>();

    /** How to reach the callee. EJB fields or HTTP fields are set, never both. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Lookup {
        public List<String> jndiNames;
        public String interfaceName;
        public String method;
        public String signature;
        public String invocation;

        public String httpMethod;
        public String path;
        public String query;
        public Map<String, List<String>> headers;
        public String contentType;
        public String bodyEncoding;
    }

    /** A value produced by this step that a later step uses. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Variable {
        /** The {@code ${name}} a substitution refers to. */
        public String name;
        /** Lineage path within the span, e.g. "result.id" or "http.responseBody.id". */
        public String path;
        /** The captured value, as text. */
        public String capturedValue;
        /** Steps that consume it. */
        public List<String> usedBy = new ArrayList<>();
    }

    /** A value in this step's payload that an earlier step produced. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Substitution {
        /** Lineage path within the span, e.g. "args[0].orderId" or "http.requestBody.orderId". */
        public String path;
        public String variable;
        public String fromStep;
        public String fromPath;
        public String capturedValue;
        /** Other selected steps that produced the same value, nearest first. */
        public List<String> alternatives;
        /** The same value also appears among this step's own results: possibly recomputed. */
        public Boolean alsoProducedHere;
    }
}
