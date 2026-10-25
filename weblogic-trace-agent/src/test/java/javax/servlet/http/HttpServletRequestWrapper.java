package javax.servlet.http;

import javax.servlet.ServletRequestWrapper;
import java.security.Principal;
import java.util.Enumeration;

/** Stub of the HTTP request wrapper the tee request is generated from: delegates every member. */
public class HttpServletRequestWrapper extends ServletRequestWrapper implements HttpServletRequest {

    public HttpServletRequestWrapper(HttpServletRequest request) {
        super(request);
    }

    private HttpServletRequest http() {
        return (HttpServletRequest) getRequest();
    }

    @Override
    public String getMethod() {
        return http().getMethod();
    }

    @Override
    public String getRequestURI() {
        return http().getRequestURI();
    }

    @Override
    public String getContextPath() {
        return http().getContextPath();
    }

    @Override
    public String getServletPath() {
        return http().getServletPath();
    }

    @Override
    public String getPathInfo() {
        return http().getPathInfo();
    }

    @Override
    public String getQueryString() {
        return http().getQueryString();
    }

    @Override
    public String getHeader(String name) {
        return http().getHeader(name);
    }

    @Override
    public Enumeration<String> getHeaders(String name) {
        return http().getHeaders(name);
    }

    @Override
    public Enumeration<String> getHeaderNames() {
        return http().getHeaderNames();
    }

    @Override
    public Principal getUserPrincipal() {
        return http().getUserPrincipal();
    }

    @Override
    public HttpSession getSession(boolean create) {
        return http().getSession(create);
    }
}
