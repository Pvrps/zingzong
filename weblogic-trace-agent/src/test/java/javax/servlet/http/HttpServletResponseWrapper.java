package javax.servlet.http;

import javax.servlet.ServletResponseWrapper;
import java.io.IOException;
import java.util.Collection;

/** Stub of the HTTP response wrapper the tee response is generated from: delegates every member. */
public class HttpServletResponseWrapper extends ServletResponseWrapper implements HttpServletResponse {

    public HttpServletResponseWrapper(HttpServletResponse response) {
        super(response);
    }

    private HttpServletResponse http() {
        return (HttpServletResponse) getResponse();
    }

    @Override
    public void setStatus(int status) {
        http().setStatus(status);
    }

    @Override
    public int getStatus() {
        return http().getStatus();
    }

    @Override
    public Collection<String> getHeaders(String name) {
        return http().getHeaders(name);
    }

    @Override
    public Collection<String> getHeaderNames() {
        return http().getHeaderNames();
    }

    @Override
    public void sendError(int status) throws IOException {
        http().sendError(status);
    }

    @Override
    public void sendError(int status, String message) throws IOException {
        http().sendError(status, message);
    }
}
