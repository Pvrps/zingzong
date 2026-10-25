package weblogic.servlet.internal;

import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import java.io.IOException;

/** Stub of WebLogic's servlet stub: the {@code execute} the hook advises for web applications without filters. */
public final class ServletStubImpl {

    public void execute(ServletRequest request, ServletResponse response) throws IOException {
        response.getOutputStream().write("plain".getBytes("UTF-8"));
    }
}
