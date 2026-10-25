package javax.servlet;

import java.io.InputStream;

/** Stub of the servlet input stream the tee stream is generated from. */
public abstract class ServletInputStream extends InputStream {

    protected ServletInputStream() {
    }

    public abstract boolean isFinished();

    public abstract boolean isReady();

    public abstract void setReadListener(ReadListener listener);
}
