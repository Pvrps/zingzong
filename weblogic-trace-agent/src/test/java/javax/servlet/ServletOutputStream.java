package javax.servlet;

import java.io.OutputStream;

/** Stub of the servlet output stream the tee stream is generated from. */
public abstract class ServletOutputStream extends OutputStream {

    protected ServletOutputStream() {
    }

    public abstract boolean isReady();

    public abstract void setWriteListener(WriteListener listener);
}
