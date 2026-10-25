package javax.servlet;

import java.io.IOException;
import java.io.PrintWriter;

/** Stub of the servlet response wrapper: delegates every member. */
public class ServletResponseWrapper implements ServletResponse {

    private final ServletResponse response;

    public ServletResponseWrapper(ServletResponse response) {
        this.response = response;
    }

    public ServletResponse getResponse() {
        return response;
    }

    @Override
    public String getCharacterEncoding() {
        return response.getCharacterEncoding();
    }

    @Override
    public String getContentType() {
        return response.getContentType();
    }

    @Override
    public ServletOutputStream getOutputStream() throws IOException {
        return response.getOutputStream();
    }

    @Override
    public PrintWriter getWriter() throws IOException {
        return response.getWriter();
    }
}
