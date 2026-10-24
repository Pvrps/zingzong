package io.capture.agent.http;

import io.capture.agent.AgentLog;
import net.bytebuddy.implementation.bind.annotation.AllArguments;
import net.bytebuddy.implementation.bind.annotation.FieldValue;
import net.bytebuddy.implementation.bind.annotation.Origin;
import net.bytebuddy.implementation.bind.annotation.RuntimeType;
import net.bytebuddy.implementation.bind.annotation.SuperCall;
import net.bytebuddy.implementation.bind.annotation.This;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.Writer;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.util.concurrent.Callable;

/**
 * The code behind the wrapper classes {@link TeeClasses} generates at runtime. Those classes
 * extend the servlet API (which the agent cannot link against) and delegate here. Every
 * byte the application reads or writes passes through unchanged; a copy lands in the
 * {@link HttpCapture} buffers.
 */
public final class TeeSupport {

    private TeeSupport() {
    }

    // ---- request wrapper ----------------------------------------------------------------

    /** getInputStream(): the container's stream wrapped in a generated tee stream, created once. */
    @RuntimeType
    public static Object getInputStream(@This Object self, @SuperCall Callable<?> zuper, @FieldValue("capture") Object captureField) throws Exception {
        HttpCapture capture = (HttpCapture) captureField;
        synchronized (capture) {
            if (capture.requestStream == null) {
                Object delegate = zuper.call();
                capture.requestStream = TeeClasses.forServletApiOf(self).newInputStream(delegate, capture.requestBody);
            }
            return capture.requestStream;
        }
    }

    /** getReader(): a reader over our tee stream in the request's declared charset (ISO-8859-1 by the spec's default). */
    @RuntimeType
    public static Object getReader(@This Object self, @FieldValue("capture") Object captureField) throws Exception {
        HttpCapture capture = (HttpCapture) captureField;
        synchronized (capture) {
            if (capture.requestReader == null) {
                InputStream stream = (InputStream) self.getClass().getMethod("getInputStream").invoke(self);
                String encoding = (String) self.getClass().getMethod("getCharacterEncoding").invoke(self);
                capture.requestReader = new BufferedReader(new InputStreamReader(stream, charset(encoding, "ISO-8859-1")));
            }
            return capture.requestReader;
        }
    }

    // ---- response wrapper ---------------------------------------------------------------

    @RuntimeType
    public static Object getOutputStream(@This Object self, @SuperCall Callable<?> zuper, @FieldValue("capture") Object captureField) throws Exception {
        HttpCapture capture = (HttpCapture) captureField;
        synchronized (capture) {
            if (capture.responseStream == null) {
                Object delegate = zuper.call();
                capture.responseStream = TeeClasses.forServletApiOf(self).newOutputStream(delegate, capture.responseBody);
            }
            return capture.responseStream;
        }
    }

    /**
     * getWriter(): the container's own writer (so its encoding and commit rules apply) behind a
     * PrintWriter that also keeps every character written.
     */
    @RuntimeType
    public static Object getWriter(@This Object self, @SuperCall Callable<?> zuper, @FieldValue("capture") Object captureField) throws Exception {
        HttpCapture capture = (HttpCapture) captureField;
        synchronized (capture) {
            if (capture.responseWriter == null) {
                PrintWriter container = (PrintWriter) zuper.call();
                capture.responseText = new StringBuilder();
                capture.responseWriterCharset = (String) self.getClass().getMethod("getCharacterEncoding").invoke(self);
                capture.responseWriter = new PrintWriter(new TeeWriter(container, capture.responseText), false);
            }
            return capture.responseWriter;
        }
    }


    /**
     * sendError(status[, message]): the application asked the container for its error page.
     * The page itself is written by the container through its own stream, not through the
     * wrapper, so what is recorded is the request: status and message.
     */
    @RuntimeType
    public static Object sendError(@SuperCall Callable<?> zuper, @AllArguments Object[] args, @FieldValue("capture") Object captureField) throws Exception {
        HttpCapture capture = (HttpCapture) captureField;
        synchronized (capture) {
            capture.sentErrorStatus = args.length > 0 && args[0] instanceof Integer ? (Integer) args[0] : null;
            capture.sentErrorMessage = args.length > 1 && args[1] != null ? String.valueOf(args[1]) : null;
        }
        return zuper.call();
    }
    // ---- generated stream subclasses -----------------------------------------------------

    /**
     * Every method of a generated ServletInputStream/ServletOutputStream subclass lands here:
     * read/write go through the buffer, everything else is forwarded to the container's stream.
     */
    @RuntimeType
    public static Object forward(@Origin Method method, @AllArguments Object[] args,
                                 @FieldValue("delegate") Object delegate, @FieldValue("buffer") Object bufferField) throws Throwable {
        BodyBuffer buffer = (BodyBuffer) bufferField;
        String name = method.getName();
        Class<?>[] types = method.getParameterTypes();
        if (name.equals("read") && delegate instanceof InputStream) {
            InputStream in = (InputStream) delegate;
            if (types.length == 0) {
                int b = in.read();
                buffer.touch();
                if (b >= 0) {
                    buffer.write(b);
                }
                return b;
            }
            if (types.length == 3) {
                byte[] b = (byte[]) args[0];
                int off = (Integer) args[1];
                int len = (Integer) args[2];
                int n = in.read(b, off, len);
                buffer.touch();
                if (n > 0) {
                    buffer.write(b, off, n);
                }
                return n;
            }
            if (types.length == 1) {
                byte[] b = (byte[]) args[0];
                int n = in.read(b, 0, b.length);
                buffer.touch();
                if (n > 0) {
                    buffer.write(b, 0, n);
                }
                return n;
            }
        }
        if (name.equals("write") && delegate instanceof OutputStream) {
            OutputStream out = (OutputStream) delegate;
            if (types.length == 1 && types[0] == int.class) {
                out.write((Integer) args[0]);
                buffer.write((Integer) args[0]);
                return null;
            }
            if (types.length == 3) {
                byte[] b = (byte[]) args[0];
                int off = (Integer) args[1];
                int len = (Integer) args[2];
                out.write(b, off, len);
                buffer.write(b, off, len);
                return null;
            }
            if (types.length == 1) {
                byte[] b = (byte[]) args[0];
                out.write(b);
                buffer.write(b, 0, b.length);
                return null;
            }
        }
        Method target = delegate.getClass().getMethod(name, types);
        try {
            target.setAccessible(true);
        } catch (Throwable ignored) {
            // public method on a public class: fine without
        }
        try {
            return target.invoke(delegate, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw e.getCause();
        }
    }

    // ---- helpers ------------------------------------------------------------------------

    static Charset charset(String name, String fallback) {
        try {
            return Charset.forName(name == null || name.isEmpty() ? fallback : name);
        } catch (Throwable t) {
            return Charset.forName(fallback);
        }
    }

    /** Forwards to the container's writer and keeps a copy of every character. */
    static final class TeeWriter extends Writer {
        private final Writer delegate;
        private final StringBuilder copy;

        TeeWriter(Writer delegate, StringBuilder copy) {
            this.delegate = delegate;
            this.copy = copy;
        }

        @Override
        public void write(char[] cbuf, int off, int len) throws IOException {
            delegate.write(cbuf, off, len);
            synchronized (copy) {
                copy.append(cbuf, off, len);
            }
        }

        @Override
        public void write(String str, int off, int len) throws IOException {
            delegate.write(str, off, len);
            synchronized (copy) {
                copy.append(str, off, off + len);
            }
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }

    static void warnOnce(java.util.concurrent.atomic.AtomicBoolean flag, String message, Throwable t) {
        if (flag.compareAndSet(false, true)) {
            AgentLog.warn(message + ": " + t);
        }
    }

    static Writer writerOver(OutputStream out, String charset) {
        return new OutputStreamWriter(out, charset(charset, "ISO-8859-1"));
    }
}
