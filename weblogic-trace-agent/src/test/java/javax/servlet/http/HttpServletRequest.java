package javax.servlet.http;

import javax.servlet.ServletRequest;
import java.security.Principal;
import java.util.Enumeration;

/** Stub of the HTTP request: the members the agent reads by name. */
public interface HttpServletRequest extends ServletRequest {

    String getMethod();

    String getRequestURI();

    String getContextPath();

    String getServletPath();

    String getPathInfo();

    String getQueryString();

    String getHeader(String name);

    Enumeration<String> getHeaders(String name);

    Enumeration<String> getHeaderNames();

    Principal getUserPrincipal();

    HttpSession getSession(boolean create);
}
