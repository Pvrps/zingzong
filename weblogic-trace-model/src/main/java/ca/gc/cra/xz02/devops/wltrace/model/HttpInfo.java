package ca.gc.cra.xz02.devops.wltrace.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * An HTTP entry point, recorded raw: every header, the whole body on both sides. For the
 * resource-layer child spans (layer jaxrs/jaxws) only {@code resourceClass}/{@code resourceMethod}
 * are set; their arguments and result live in the span's {@code args}/{@code result}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class HttpInfo {

    public static final String LAYER_JAXRS = "jaxrs";
    public static final String LAYER_JAXWS = "jaxws";
    public static final String ENCODING_BASE64 = "base64";

    public String method;
    /** Request URI without the query string. */
    public String path;
    public String contextPath;
    public String servletPath;
    public String pathInfo;
    public String query;
    public String protocol;
    public String scheme;
    public String serverName;
    public Integer serverPort;
    public String remoteAddr;
    public Integer status;
    public String contentType;
    public String responseContentType;
    /** All request headers, multi-valued, as received. Nothing is filtered. */
    public Map<String, List<String>> requestHeaders;
    /** All response headers at the end of the request. */
    public Map<String, List<String>> responseHeaders;
    /** The body as text in {@code requestBodyEncoding}, or base64 when that is "base64". */
    public String requestBody;
    /** A charset name; with " (assumed)" when it was inferred from the content type rather than declared. */
    public String requestBodyEncoding;
    public Long requestBodyLength;
    /** Bytes beyond the safety ceiling that were not kept; absent when none. */
    public Long requestBodyDropped;
    /** False when the application left part of the body unread and it could not be drained. */
    public Boolean requestBodyComplete;
    public String responseBody;
    public String responseBodyEncoding;
    public Long responseBodyLength;
    public Long responseBodyDropped;
    public String resourceClass;
    public String resourceMethod;
    public String resourceSignature;
    /** The application called sendError(status, message): the container then writes its own error page. */
    public SentError sentError;
    /** Why {@code responseBody} is absent although the client received one. */
    public String responseBodyNote;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SentError {
        public Integer status;
        public String message;
    }
}
