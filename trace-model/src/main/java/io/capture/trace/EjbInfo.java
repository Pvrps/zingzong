package io.capture.trace;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class EjbInfo {

    public static final String INVOCATION_LOCAL = "LOCAL";
    public static final String INVOCATION_REMOTE = "REMOTE";

    public String app;
    public String module;
    public String beanName;
    /** The user's bean class, never a container-generated subclass. */
    public String beanClass;
    /** The business interface the call came in through, when known. JSON key: "interface". */
    @JsonProperty("interface")
    public String interfaceName;
    /** LOCAL or REMOTE, when known. */
    public String invocation;
    public List<String> jndiNames;
    public String method;
    /** Java-style signature: "ReturnType name(ParamType, ...)" with fully qualified names. */
    public String signature;
    /** Transaction attribute or status, e.g. REQUIRED, when known. */
    public String transaction;
}
