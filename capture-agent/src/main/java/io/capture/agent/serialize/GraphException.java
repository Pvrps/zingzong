package io.capture.agent.serialize;

/** Deserialization failure. Serialization never throws. */
public class GraphException extends Exception {

    private static final long serialVersionUID = 1L;

    public GraphException(String message) {
        super(message);
    }

    public GraphException(String message, Throwable cause) {
        super(message, cause);
    }
}
