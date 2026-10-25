package javax.servlet;

import java.io.BufferedReader;
import java.io.IOException;

/** Stub of the servlet request: the members the tee wrapper and the agent's readers reach. */
public interface ServletRequest {

    String getCharacterEncoding();

    int getContentLength();

    String getContentType();

    ServletInputStream getInputStream() throws IOException;

    BufferedReader getReader() throws IOException;

    String getProtocol();

    String getScheme();

    String getServerName();

    int getServerPort();

    String getRemoteAddr();
}
