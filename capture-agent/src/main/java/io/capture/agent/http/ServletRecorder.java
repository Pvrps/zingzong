package io.capture.agent.http;

import io.capture.agent.AgentLog;
import io.capture.agent.span.Recorder;
import io.capture.trace.HttpInfo;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Behind {@code ServletAdvice}: opens an HTTP span when a request enters the filter chain,
 * substitutes the tee wrappers, and on exit records everything the request and response
 * carried: method, URI, every header, status, both bodies in full. The servlet API is
 * reached reflectively and by name; nothing here links against javax.servlet.
 */
public final class ServletRecorder {

    private static final String HTTP_REQUEST = "javax.servlet.http.HttpServletRequest";
    private static final String REQUEST_WRAPPER = "javax.servlet.ServletRequestWrapper";
    private static final AtomicBoolean teeWarned = new AtomicBoolean();

    private ServletRecorder() {
    }

    /**
     * Called on FilterChainImpl.doFilter entry. Returns {@code [wrappedRequest, wrappedResponse, capture]}
     * when a span was opened, or null when nothing is to be done (recording off, not an HTTP
     * request, or a re-entry of the chain for a request already being recorded).
     */
    public static Object[] enter(Object request, Object response) {
        if (!Recorder.isEnabled() || request == null || response == null) {
            return null;
        }
        try {
            if (!implementsByName(request.getClass(), HTTP_REQUEST)) {
                return null;
            }
            Object original = unwrap(request);
            if (Recorder.currentServletRequest() == original) {
                return null; // next filter in the same chain
            }
            HttpCapture capture = new HttpCapture(original, unwrap(response));
            Object wrappedRequest = request;
            Object wrappedResponse = response;
            try {
                TeeClasses tees = TeeClasses.forServletApiOf(request);
                wrappedRequest = tees.wrapRequest(request, capture);
                wrappedResponse = tees.wrapResponse(response, capture);
            } catch (Throwable t) {
                if (teeWarned.compareAndSet(false, true)) {
                    AgentLog.warn("servlet tee wrappers unavailable, bodies will not be captured: " + t);
                }
            }
            capture.wrappedRequest = wrappedRequest;
            capture.wrappedResponse = wrappedResponse;
            String principal = principalOf(request);
            String sessionId = sessionIdOf(request);
            if (!Recorder.enterServlet(capture, principal, sessionId)) {
                return null;
            }
            return new Object[]{wrappedRequest, wrappedResponse, capture};
        } catch (Throwable t) {
            return null;
        }
    }

    /** Called on FilterChainImpl.doFilter exit for the call that opened the span. */
    public static void exit(Object[] context, Throwable thrown) {
        if (context == null) {
            return;
        }
        try {
            HttpCapture capture = (HttpCapture) context[2];
            flushWriter(capture);
            drainUnreadRequestBody(capture);
            HttpInfo info = describe(capture, thrown != null);
            Recorder.exitServlet(capture, info, principalOf(capture.originalRequest), sessionIdOf(capture.originalRequest), thrown);
        } catch (Throwable t) {
            try {
                AgentLog.error("servlet exit handling failed", t);
            } catch (Throwable ignored) {
                // never into the request
            }
        }
    }

    // ---- facts about the request and response --------------------------------------------

    static HttpInfo describe(HttpCapture capture, boolean thrownThroughChain) {
        Object req = capture.originalRequest;
        Object resp = capture.originalResponse;
        HttpInfo info = new HttpInfo();
        info.method = string(req, "getMethod");
        info.path = string(req, "getRequestURI");
        info.contextPath = string(req, "getContextPath");
        info.servletPath = string(req, "getServletPath");
        info.pathInfo = string(req, "getPathInfo");
        info.query = string(req, "getQueryString");
        info.protocol = string(req, "getProtocol");
        info.scheme = string(req, "getScheme");
        info.serverName = string(req, "getServerName");
        info.serverPort = integer(req, "getServerPort");
        info.remoteAddr = string(req, "getRemoteAddr");
        info.contentType = string(req, "getContentType");
        info.requestHeaders = requestHeaders(req);
        info.status = integer(resp, "getStatus");
        info.responseContentType = string(resp, "getContentType");
        info.responseHeaders = responseHeaders(resp);

        BodyBuffer requestBody = capture.requestBody;
        if (requestBody.touched() || requestBody.length() > 0) {
            String encoding = textCharset(info.contentType, string(req, "getCharacterEncoding"));
            encode(info, true, requestBody, encoding);
        }
        if (capture.responseText != null) {
            info.responseBody = capture.responseText.toString();
            info.responseBodyEncoding = capture.responseWriterCharset == null ? "chars" : capture.responseWriterCharset;
            info.responseBodyLength = (long) capture.responseText.length();
        } else if (capture.responseBody.touched() || capture.responseBody.length() > 0) {
            String encoding = textCharset(info.responseContentType, string(resp, "getCharacterEncoding"));
            encode(info, false, capture.responseBody, encoding);
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

    private static void encode(HttpInfo info, boolean request, BodyBuffer buffer, String charset) {
        byte[] bytes = buffer.bytes();
        String body;
        String encoding;
        if (charset != null) {
            String name = charset.endsWith(" (assumed)") ? charset.substring(0, charset.length() - " (assumed)".length()) : charset;
            body = new String(bytes, Charset.forName(name));
            encoding = charset;
        } else {
            body = Base64.getEncoder().encodeToString(bytes);
            encoding = HttpInfo.ENCODING_BASE64;
        }
        long dropped = buffer.dropped();
        if (request) {
            info.requestBody = body;
            info.requestBodyEncoding = encoding;
            info.requestBodyLength = buffer.length();
            info.requestBodyDropped = dropped > 0 ? dropped : null;
        } else {
            info.responseBody = body;
            info.responseBodyEncoding = encoding;
            info.responseBodyLength = buffer.length();
            info.responseBodyDropped = dropped > 0 ? dropped : null;
        }
    }

    /**
     * The charset to decode a body with, or null for binary (base64). A charset named in the
     * Content-Type is authoritative. Without one, JSON/XML/JavaScript bodies are UTF-8 by
     * their specifications and other text types use the servlet's declared encoding (the
     * container's default is ISO-8859-1); those cases are marked "(assumed)" in the recorded
     * encoding because the decoding was inferred, not declared. Anything else is binary.
     */
    static String textCharset(String contentType, String declaredCharset) {
        if (contentType == null) {
            return null;
        }
        String type = contentType.toLowerCase(Locale.ROOT);
        String charsetParam = null;
        int idx = type.indexOf("charset=");
        if (idx >= 0) {
            String rest = contentType.substring(idx + 8).trim();
            int end = rest.indexOf(';');
            charsetParam = (end >= 0 ? rest.substring(0, end) : rest).trim().replace("\"", "");
        }
        boolean structured = type.contains("json") || type.contains("xml") || type.contains("javascript") || type.contains("yaml");
        boolean text = structured || type.startsWith("text/") || type.contains("x-www-form-urlencoded");
        if (!text) {
            return null;
        }
        if (charsetParam != null && isCharset(charsetParam)) {
            return charsetParam;
        }
        if (structured) {
            return "UTF-8 (assumed)";
        }
        String declared = declaredCharset == null || declaredCharset.isEmpty() || !isCharset(declaredCharset) ? "ISO-8859-1" : declaredCharset;
        return declared + " (assumed)";
    }

    private static boolean isCharset(String name) {
        try {
            Charset.forName(name);
            return true;
        } catch (Throwable t) {
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
     * length is known (Content-Length), the rest is read now; otherwise it is left and marked
     * incomplete rather than risk blocking on an open connection.
     */
    private static void drainUnreadRequestBody(HttpCapture capture) {
        try {
            Object req = capture.originalRequest;
            Integer contentLength = integer(req, "getContentLength");
            if (contentLength == null || contentLength <= 0 || capture.requestBody.length() >= contentLength) {
                return;
            }
            Object stream = capture.requestStream != null
                    ? req.getClass().getMethod("getInputStream").invoke(req)
                    : req.getClass().getMethod("getInputStream").invoke(req);
            if (!(stream instanceof InputStream)) {
                return;
            }
            byte[] buf = new byte[8192];
            long remaining = contentLength - capture.requestBody.length();
            while (remaining > 0) {
                int n = ((InputStream) stream).read(buf, 0, (int) Math.min(buf.length, remaining));
                if (n < 0) {
                    break;
                }
                capture.requestBody.write(buf, 0, n);
                remaining -= n;
            }
        } catch (Throwable t) {
            // leave what we have; requestBodyComplete says so
        }
    }

    static String principalOf(Object request) {
        try {
            Object principal = request.getClass().getMethod("getUserPrincipal").invoke(request);
            return principal instanceof Principal ? ((Principal) principal).getName() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    static String sessionIdOf(Object request) {
        try {
            Method getSession = request.getClass().getMethod("getSession", boolean.class);
            Object session = getSession.invoke(request, false);
            return session == null ? null : (String) session.getClass().getMethod("getId").invoke(session);
        } catch (Throwable t) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, List<String>> requestHeaders(Object req) {
        Map<String, List<String>> headers = new LinkedHashMap<String, List<String>>();
        try {
            Enumeration<String> names = (Enumeration<String>) req.getClass().getMethod("getHeaderNames").invoke(req);
            Method getHeaders = req.getClass().getMethod("getHeaders", String.class);
            while (names != null && names.hasMoreElements()) {
                String name = names.nextElement();
                List<String> values = new ArrayList<String>();
                Enumeration<String> e = (Enumeration<String>) getHeaders.invoke(req, name);
                while (e != null && e.hasMoreElements()) {
                    values.add(e.nextElement());
                }
                headers.put(name, values);
            }
        } catch (Throwable t) {
            headers.put("@unavailable", java.util.Collections.singletonList(String.valueOf(t)));
        }
        return headers;
    }

    /**
     * WebLogic's getHeaderNames() lists the headers it sets itself (Date, Content-Length,
     * Content-Type) as "Name: " and only answers getHeaders() for the bare name, so both
     * forms are tried and the bare name is recorded.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, List<String>> responseHeaders(Object resp) {
        Map<String, List<String>> headers = new LinkedHashMap<String, List<String>>();
        try {
            Collection<String> names = (Collection<String>) resp.getClass().getMethod("getHeaderNames").invoke(resp);
            Method getHeaders = resp.getClass().getMethod("getHeaders", String.class);
            for (String rawName : names) {
                String name = rawName;
                while (name.endsWith(" ") || name.endsWith(":")) {
                    name = name.substring(0, name.length() - 1);
                }
                Collection<String> values = (Collection<String>) getHeaders.invoke(resp, rawName);
                if ((values == null || values.isEmpty()) && !name.equals(rawName)) {
                    values = (Collection<String>) getHeaders.invoke(resp, name);
                }
                headers.put(name, values == null ? new ArrayList<String>() : new ArrayList<String>(values));
            }
        } catch (Throwable t) {
            headers.put("@unavailable", java.util.Collections.singletonList(String.valueOf(t)));
        }
        return headers;
    }

    private static String string(Object target, String getter) {
        try {
            Object v = target.getClass().getMethod(getter).invoke(target);
            return v == null ? null : v.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static Integer integer(Object target, String getter) {
        try {
            Object v = target.getClass().getMethod(getter).invoke(target);
            return v instanceof Number ? ((Number) v).intValue() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** The container's request behind any ServletRequestWrapper layers. */
    static Object unwrap(Object request) {
        Object current = request;
        for (int i = 0; i < 32 && current != null && implementsByName(current.getClass(), REQUEST_WRAPPER); i++) {
            try {
                Object inner = current.getClass().getMethod("getRequest").invoke(current);
                if (inner == null) {
                    break;
                }
                current = inner;
            } catch (Throwable t) {
                break;
            }
        }
        return current;
    }

    static boolean implementsByName(Class<?> type, String name) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            if (c.getName().equals(name)) {
                return true;
            }
            for (Class<?> i : c.getInterfaces()) {
                if (implementsByName(i, name)) {
                    return true;
                }
            }
        }
        return false;
    }
}
