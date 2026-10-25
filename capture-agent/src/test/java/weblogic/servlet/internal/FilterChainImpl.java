package weblogic.servlet.internal;

import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/** Stub of WebLogic's filter chain: the {@code doFilter} the hook advises, ending in a servlet that writes a body. */
public final class FilterChainImpl {

    private final int status;

    public FilterChainImpl(int status) {
        this.status = status;
    }

    public void doFilter(ServletRequest request, ServletResponse response) throws IOException {
        ((HttpServletResponse) response).setStatus(status);
        response.getWriter().write("{\"ok\":true}");
    }
}
