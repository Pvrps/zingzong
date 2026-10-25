package javax.servlet.http;

import javax.servlet.ServletResponse;
import java.io.IOException;
import java.util.Collection;

/** Stub of the HTTP response: the members the agent reads by name and the tee wrapper intercepts. */
public interface HttpServletResponse extends ServletResponse {

    void setStatus(int status);

    int getStatus();

    Collection<String> getHeaders(String name);

    Collection<String> getHeaderNames();

    void sendError(int status) throws IOException;

    void sendError(int status, String message) throws IOException;
}
