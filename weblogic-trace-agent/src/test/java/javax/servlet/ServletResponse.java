package javax.servlet;

import java.io.IOException;
import java.io.PrintWriter;

/** Stub of the servlet response: the members the tee wrapper and the agent's readers reach. */
public interface ServletResponse {

    String getCharacterEncoding();

    String getContentType();

    ServletOutputStream getOutputStream() throws IOException;

    PrintWriter getWriter() throws IOException;
}
