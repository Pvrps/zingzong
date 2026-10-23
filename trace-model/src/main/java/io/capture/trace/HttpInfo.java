package io.capture.trace;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class HttpInfo {

    public String method;
    public String path;
    public String query;
    public Integer status;
    public String contentType;
    /** Allow-listed headers only; never Authorization or Cookie. */
    public Map<String, String> headers;
    public String requestBody;
    public String responseBody;
    /** Set instead of the body when it was binary or exceeded the cap. */
    public Long requestBodyLength;
    public Long responseBodyLength;
    public String resourceClass;
    public String resourceMethod;
}
