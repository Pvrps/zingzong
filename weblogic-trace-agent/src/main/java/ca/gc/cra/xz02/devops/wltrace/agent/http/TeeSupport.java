package ca.gc.cra.xz02.devops.wltrace.agent.http;

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
import java.io.PrintWriter;
import java.io.Writer;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.util.concurrent.Callable;

/**
 * The code behind the classes {@link TeeClasses} generates. Those classes extend the servlet API,
 * which the agent cannot link against, and delegate here. Every byte the application reads or
 * writes passes through unchanged; a copy lands in the {@link HttpCapture} buffers. Static because
 * Byte Buddy binds the generated methods to these; the state is the capture they are handed.
 */
public final class TeeSupport {

    private TeeSupport() {
    }

    /** {@code getInputStream()}: the container's stream behind a generated tee stream, created once. */
    @RuntimeType
    public static Object getInputStream(@SuperCall Callable<?> container, @FieldValue("capture") Object captureField) throws Exception {
        HttpCapture capture = (HttpCapture) captureField;
        synchronized (capture) {
            if (capture.requestStream == null) {
                capture.requestStream = capture.tees.newInputStream(container.call(), capture.requestBody);
            }
            return capture.requestStream;
        }
    }

    /** {@code getReader()}: a reader over the tee stream in the request's declared charset (ISO-8859-1 by the servlet default). */
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

    /** {@code getOutputStream()}: the container's stream behind a generated tee stream, created once. */
    @RuntimeType
    public static Object getOutputStream(@SuperCall Callable<?> container, @FieldValue("capture") Object captureField) throws Exception {
        HttpCapture capture = (HttpCapture) captureField;
        synchronized (capture) {
            if (capture.responseStream == null) {
                capture.responseStream = capture.tees.newOutputStream(container.call(), capture.responseBody);
            }
            return capture.responseStream;
        }
    }

    /** {@code getWriter()}: the container's own writer, so its encoding and commit rules apply, behind a writer that keeps every character. */
    @RuntimeType
    public static Object getWriter(@This Object self, @SuperCall Callable<?> container, @FieldValue("capture") Object captureField) throws Exception {
        HttpCapture capture = (HttpCapture) captureField;
        synchronized (capture) {
            if (capture.responseWriter == null) {
                PrintWriter containerWriter = (PrintWriter) container.call();
                capture.responseText = new StringBuilder();
                capture.responseWriterCharset = (String) self.getClass().getMethod("getCharacterEncoding").invoke(self);
                capture.responseWriter = new PrintWriter(new TeeWriter(containerWriter, capture.responseText), false);
            }
            return capture.responseWriter;
        }
    }

    /**
     * {@code sendError(status[, message])}: the application asked the container for its error
     * page. The page is written by the container through its own stream, not through the
     * wrapper, so what is recorded is the request: status and message.
     */
    @RuntimeType
    public static Object sendError(@SuperCall Callable<?> container, @AllArguments Object[] arguments, @FieldValue("capture") Object captureField) throws Exception {
        HttpCapture capture = (HttpCapture) captureField;
        synchronized (capture) {
            capture.sentErrorStatus = arguments.length > 0 && arguments[0] instanceof Integer ? (Integer) arguments[0] : null;
            capture.sentErrorMessage = arguments.length > 1 && arguments[1] != null ? String.valueOf(arguments[1]) : null;
        }
        return container.call();
    }

    /**
     * Every intercepted method of a generated stream lands here: read and write go through the
     * buffer, everything else is forwarded to the container's stream.
     */
    @RuntimeType
    public static Object forward(@Origin Method method, @AllArguments Object[] arguments,
                                 @FieldValue("delegate") Object delegate, @FieldValue("buffer") Object bufferField) throws Throwable {
        BodyBuffer buffer = (BodyBuffer) bufferField;
        String name = method.getName();
        Class<?>[] parameterTypes = method.getParameterTypes();
        if (name.equals("read") && delegate instanceof InputStream) {
            return read((InputStream) delegate, parameterTypes, arguments, buffer);
        }
        if (name.equals("write") && delegate instanceof OutputStream) {
            write((OutputStream) delegate, parameterTypes, arguments, buffer);
            return null;
        }
        Method target = delegate.getClass().getMethod(name, parameterTypes);
        try {
            target.setAccessible(true);
        } catch (Throwable ignored) {
            // a public method on a public class works without
        }
        try {
            return target.invoke(delegate, arguments);
        } catch (InvocationTargetException wrapped) {
            throw wrapped.getCause();
        }
    }

    private static Object read(InputStream in, Class<?>[] parameterTypes, Object[] arguments, BodyBuffer buffer) throws IOException {
        if (parameterTypes.length == 0) {
            int value = in.read();
            buffer.touch();
            if (value >= 0) {
                buffer.write(value);
            }
            return value;
        }
        byte[] bytes = (byte[]) arguments[0];
        int offset = parameterTypes.length == 3 ? (Integer) arguments[1] : 0;
        int count = parameterTypes.length == 3 ? (Integer) arguments[2] : bytes.length;
        int read = in.read(bytes, offset, count);
        buffer.touch();
        if (read > 0) {
            buffer.write(bytes, offset, read);
        }
        return read;
    }

    private static void write(OutputStream out, Class<?>[] parameterTypes, Object[] arguments, BodyBuffer buffer) throws IOException {
        if (parameterTypes.length == 1 && parameterTypes[0] == int.class) {
            out.write((Integer) arguments[0]);
            buffer.write((Integer) arguments[0]);
            return;
        }
        byte[] bytes = (byte[]) arguments[0];
        int offset = parameterTypes.length == 3 ? (Integer) arguments[1] : 0;
        int count = parameterTypes.length == 3 ? (Integer) arguments[2] : bytes.length;
        out.write(bytes, offset, count);
        buffer.write(bytes, offset, count);
    }

    static Charset charset(String name, String fallback) {
        try {
            return Charset.forName(name == null || name.isEmpty() ? fallback : name);
        } catch (Throwable failure) {
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
        public void write(char[] characters, int offset, int count) throws IOException {
            delegate.write(characters, offset, count);
            synchronized (copy) {
                copy.append(characters, offset, count);
            }
        }

        @Override
        public void write(String text, int offset, int count) throws IOException {
            delegate.write(text, offset, count);
            synchronized (copy) {
                copy.append(text, offset, offset + count);
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
}
