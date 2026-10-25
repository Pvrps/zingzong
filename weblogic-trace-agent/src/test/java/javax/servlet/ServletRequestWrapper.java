package javax.servlet;

import java.io.BufferedReader;
import java.io.IOException;

/** Stub of the servlet request wrapper: delegates every member; {@link #getRequest()} is what the agent unwraps through. */
public class ServletRequestWrapper implements ServletRequest {

    private final ServletRequest request;

    public ServletRequestWrapper(ServletRequest request) {
        this.request = request;
    }

    public ServletRequest getRequest() {
        return request;
    }

    @Override
    public String getCharacterEncoding() {
        return request.getCharacterEncoding();
    }

    @Override
    public int getContentLength() {
        return request.getContentLength();
    }

    @Override
    public String getContentType() {
        return request.getContentType();
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
        return request.getInputStream();
    }

    @Override
    public BufferedReader getReader() throws IOException {
        return request.getReader();
    }

    @Override
    public String getProtocol() {
        return request.getProtocol();
    }

    @Override
    public String getScheme() {
        return request.getScheme();
    }

    @Override
    public String getServerName() {
        return request.getServerName();
    }

    @Override
    public int getServerPort() {
        return request.getServerPort();
    }

    @Override
    public String getRemoteAddr() {
        return request.getRemoteAddr();
    }
}
