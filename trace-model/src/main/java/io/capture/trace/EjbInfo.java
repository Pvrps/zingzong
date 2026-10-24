package io.capture.trace;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class EjbInfo {

    public static final String INVOCATION_LOCAL = "LOCAL";
    public static final String INVOCATION_REMOTE = "REMOTE";
    /** A message-driven bean: delivered by the container from a destination, no client view. */
    public static final String INVOCATION_MESSAGE = "MESSAGE";

    public String app;
    public String module;
    public String beanName;
    /** The user's bean class, never a container-generated subclass. */
    public String beanClass;
    /** The business interface the call came in through, when known. JSON key: "interface". */
    @JsonProperty("interface")
    public String interfaceName;
    /** LOCAL, REMOTE or MESSAGE, when known. */
    public String invocation;
    public List<String> jndiNames;
    /** For a message-driven bean: the destination it listens on, as configured. */
    public String destination;
    public String method;
    /** Java-style signature: "ReturnType name(ParamType, ...)" with fully qualified names. */
    public String signature;
    /** The declared transaction attribute (REQUIRED, REQUIRES_NEW, ..., BEAN for BMT), when known. */
    public String transactionAttribute;
}
