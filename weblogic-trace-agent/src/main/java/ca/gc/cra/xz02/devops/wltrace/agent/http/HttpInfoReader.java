package ca.gc.cra.xz02.devops.wltrace.agent.http;

import ca.gc.cra.xz02.devops.wltrace.model.HttpInfo;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns a completed {@link HttpCapture} into the {@link HttpInfo} of the span: method, URI, every
 * header, status, both bodies in full, decoded with the charset the content type declares or
 * implies. Nothing is filtered or masked.
 */
public final class HttpInfoReader {

    private static final String ASSUMED = " (assumed)";
    private static final String UNREADABLE = "@unreadable";

    private HttpInfoReader() {
    }

    /**
     * Completes the capture and describes the exchange. The writer is flushed so buffered
     * characters reach the container; a request body the application left unread is drained when
     * its length is known.
     */
    public static HttpInfo describe(HttpCapture capture, boolean thrownThroughChain) {
        flushWriter(capture);
        drainUnreadRequestBody(capture);
        Object request = capture.originalRequest;
        Object response = capture.originalResponse;
        HttpInfo info = new HttpInfo();
        info.method = ServletApi.string(request, "getMethod");
        info.path = ServletApi.string(request, "getRequestURI");
        info.contextPath = ServletApi.string(request, "getContextPath");
        info.servletPath = ServletApi.string(request, "getServletPath");
        info.pathInfo = ServletApi.string(request, "getPathInfo");
        info.query = ServletApi.string(request, "getQueryString");
        info.protocol = ServletApi.string(request, "getProtocol");
        info.scheme = ServletApi.string(request, "getScheme");
        info.serverName = ServletApi.string(request, "getServerName");
        info.serverPort = ServletApi.integer(request, "getServerPort");
        info.remoteAddr = ServletApi.string(request, "getRemoteAddr");
        info.contentType = ServletApi.string(request, "getContentType");
        info.requestHeaders = requestHeaders(request);
        info.status = ServletApi.integer(response, "getStatus");
        info.responseContentType = ServletApi.string(response, "getContentType");
        info.responseHeaders = responseHeaders(response);

        if (capture.requestBody.touched() || capture.requestBody.length() > 0) {
            encodeRequestBody(info, capture.requestBody, textCharset(info.contentType, ServletApi.string(request, "getCharacterEncoding")));
        }
        if (capture.responseText != null) {
            info.responseBody = capture.responseText.toString();
            info.responseBodyEncoding = capture.responseWriterCharset == null ? "chars" : capture.responseWriterCharset;
            info.responseBodyLength = (long) capture.responseText.length();
        } else if (capture.responseBody.touched() || capture.responseBody.length() > 0) {
            encodeResponseBody(info, capture.responseBody, textCharset(info.responseContentType, ServletApi.string(response, "getCharacterEncoding")));
        }
        if (capture.sentErrorStatus != null) {
            info.sentError = new HttpInfo.SentError();
            info.sentError.status = capture.sentErrorStatus;
            info.sentError.message = capture.sentErrorMessage;
        }
        if (info.responseBody == null && (capture.sentErrorStatus != null || thrownThroughChain)) {
            info.responseBodyNote = "no body was written inside the servlet pipeline; the container produced its error page for this status afterwards, through its own stream, which the agent does not see";
        }
        return info;
    }

    private static void encodeRequestBody(HttpInfo info, BodyBuffer buffer, String charset) {
        String[] bodyAndEncoding = decode(buffer, charset);
        info.requestBody = bodyAndEncoding[0];
        info.requestBodyEncoding = bodyAndEncoding[1];
        info.requestBodyLength = buffer.length();
        info.requestBodyDropped = buffer.dropped() > 0 ? buffer.dropped() : null;
    }

    private static void encodeResponseBody(HttpInfo info, BodyBuffer buffer, String charset) {
        String[] bodyAndEncoding = decode(buffer, charset);
        info.responseBody = bodyAndEncoding[0];
        info.responseBodyEncoding = bodyAndEncoding[1];
        info.responseBodyLength = buffer.length();
        info.responseBodyDropped = buffer.dropped() > 0 ? buffer.dropped() : null;
    }

    /** The body as text in the charset, or as base64 when there is none; and the encoding to record. */
    private static String[] decode(BodyBuffer buffer, String charset) {
        byte[] bytes = buffer.bytes();
        if (charset == null) {
            return new String[]{Base64.getEncoder().encodeToString(bytes), HttpInfo.ENCODING_BASE64};
        }
        String name = charset.endsWith(ASSUMED) ? charset.substring(0, charset.length() - ASSUMED.length()) : charset;
        return new String[]{new String(bytes, Charset.forName(name)), charset};
    }

    /**
     * The charset to decode a body with, or null for binary. A charset named in the content type
     * is authoritative. Without one, JSON, XML, JavaScript and YAML bodies are UTF-8 by their
     * specifications and other text types use the declared encoding (the servlet default is
     * ISO-8859-1); those are marked "(assumed)" because the decoding was inferred, not declared.
     * Anything else is binary.
     */
    public static String textCharset(String contentType, String declaredCharset) {
        if (contentType == null) {
            return null;
        }
        String type = contentType.toLowerCase(Locale.ROOT);
        String charsetParameter = null;
        int index = type.indexOf("charset=");
        if (index >= 0) {
            String rest = contentType.substring(index + "charset=".length()).trim();
            int end = rest.indexOf(';');
            charsetParameter = (end >= 0 ? rest.substring(0, end) : rest).trim().replace("\"", "");
        }
        boolean structured = type.contains("json") || type.contains("xml") || type.contains("javascript") || type.contains("yaml");
        boolean text = structured || type.startsWith("text/") || type.contains("x-www-form-urlencoded");
        if (!text) {
            return null;
        }
        if (charsetParameter != null && isCharset(charsetParameter)) {
            return charsetParameter;
        }
        if (structured) {
            return "UTF-8" + ASSUMED;
        }
        boolean declaredUsable = declaredCharset != null && !declaredCharset.isEmpty() && isCharset(declaredCharset);
        return (declaredUsable ? declaredCharset : "ISO-8859-1") + ASSUMED;
    }

    private static boolean isCharset(String name) {
        try {
            Charset.forName(name);
            return true;
        } catch (Throwable failure) {
            return false;
        }
    }

    private static void flushWriter(HttpCapture capture) {
        if (capture.responseWriter != null) {
            try {
                capture.responseWriter.flush();
            } catch (Throwable ignored) {
                // the container closes its own writer
            }
        }
    }

    /**
     * A body the application never read is still part of what crossed the boundary. When its
     * length is known (Content-Length) the rest is read now; otherwise it is left alone rather
     * than risk blocking on an open connection.
     */
    private static void drainUnreadRequestBody(HttpCapture capture) {
        try {
            Object request = capture.originalRequest;
            Integer contentLength = ServletApi.integer(request, "getContentLength");
            if (contentLength == null || contentLength <= 0 || capture.requestBody.length() >= contentLength) {
                return;
            }
            Object stream = request.getClass().getMethod("getInputStream").invoke(request);
            if (!(stream instanceof InputStream)) {
                return;
            }
            byte[] chunk = new byte[8192];
            long remaining = contentLength - capture.requestBody.length();
            while (remaining > 0) {
                int read = ((InputStream) stream).read(chunk, 0, (int) Math.min(chunk.length, remaining));
                if (read < 0) {
                    break;
                }
                capture.requestBody.write(chunk, 0, read);
                remaining -= read;
            }
        } catch (Throwable failure) {
            // what was read stays; the recorded length says the rest is missing
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, List<String>> requestHeaders(Object request) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        try {
            Enumeration<String> names = (Enumeration<String>) request.getClass().getMethod("getHeaderNames").invoke(request);
            Method getHeaders = request.getClass().getMethod("getHeaders", String.class);
            while (names != null && names.hasMoreElements()) {
                String name = names.nextElement();
                List<String> values = new ArrayList<>();
                Enumeration<String> headerValues = (Enumeration<String>) getHeaders.invoke(request, name);
                while (headerValues != null && headerValues.hasMoreElements()) {
                    values.add(headerValues.nextElement());
                }
                headers.put(name, values);
            }
        } catch (Throwable failure) {
            headers.put(UNREADABLE, Collections.singletonList(String.valueOf(failure)));
        }
        return headers;
    }

    /**
     * WebLogic's {@code getHeaderNames()} lists the headers it sets itself (Date, Content-Length,
     * Content-Type) as {@code "Name: "} and answers {@code getHeaders()} only for the bare name, so
     * both forms are tried and the bare name is recorded.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, List<String>> responseHeaders(Object response) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        try {
            Collection<String> names = (Collection<String>) response.getClass().getMethod("getHeaderNames").invoke(response);
            Method getHeaders = response.getClass().getMethod("getHeaders", String.class);
            for (String rawName : names) {
                String name = rawName;
                while (name.endsWith(" ") || name.endsWith(":")) {
                    name = name.substring(0, name.length() - 1);
                }
                Collection<String> values = (Collection<String>) getHeaders.invoke(response, rawName);
                if ((values == null || values.isEmpty()) && !name.equals(rawName)) {
                    values = (Collection<String>) getHeaders.invoke(response, name);
                }
                headers.put(name, values == null ? new ArrayList<>() : new ArrayList<>(values));
            }
        } catch (Throwable failure) {
            headers.put(UNREADABLE, Collections.singletonList(String.valueOf(failure)));
        }
        return headers;
    }
}
