package ca.gc.cra.xz02.devops.wltrace.agent.instrument.stubs;

import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A request and a response of the servlet API, as dynamic proxies over plain state. */
public final class Http {

    public final Map<String, List<String>> requestHeaders = new LinkedHashMap<String, List<String>>();
    public final StringWriter written = new StringWriter();
    public final ByteArrayOutputStream streamed = new ByteArrayOutputStream();
    public int status = 200;
    private final String httpMethod;
    private final String uri;
    private final PrintWriter writer = new PrintWriter(written);
    private final ServletOutputStream output = new ServletOutputStream() {
        @Override
        public void write(int b) {
            streamed.write(b);
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setWriteListener(WriteListener listener) {
        }
    };

    public Http(String method, String uri) {
        this.httpMethod = method;
        this.uri = uri;
        requestHeaders.put("Accept", Collections.singletonList("application/json"));
    }

    public HttpServletRequest request() {
        return (HttpServletRequest) Proxy.newProxyInstance(Http.class.getClassLoader(), new Class<?>[]{HttpServletRequest.class}, (proxy, method, arguments) -> {
            switch (method.getName()) {
                case "getMethod": return httpMethod;
                case "getRequestURI": return uri;
                case "getContextPath": return "/orders";
                case "getProtocol": return "HTTP/1.1";
                case "getScheme": return "http";
                case "getServerName": return "localhost";
                case "getServerPort": return 7001;
                case "getRemoteAddr": return "127.0.0.1";
                case "getContentLength": return -1;
                case "getHeaderNames": return Collections.enumeration(requestHeaders.keySet());
                case "getHeaders": return Collections.enumeration(requestHeaders.getOrDefault(arguments[0], Collections.<String>emptyList()));
                case "getHeader": { List<String> values = requestHeaders.get(arguments[0]); return values == null ? null : values.get(0); }
                case "getCharacterEncoding": return "UTF-8";
                case "hashCode": return System.identityHashCode(proxy);
                case "equals": return proxy == arguments[0];
                case "toString": return "request " + httpMethod + " " + uri;
                default: return defaultValue(method.getReturnType());
            }
        });
    }

    public HttpServletResponse response() {
        return (HttpServletResponse) Proxy.newProxyInstance(Http.class.getClassLoader(), new Class<?>[]{HttpServletResponse.class}, (proxy, method, arguments) -> {
            switch (method.getName()) {
                case "setStatus": status = (Integer) arguments[0]; return null;
                case "getStatus": return status;
                case "getWriter": return writer;
                case "getOutputStream": return output;
                case "getContentType": return "application/json";
                case "getCharacterEncoding": return "UTF-8";
                case "getHeaderNames": return new ArrayList<String>(Collections.singletonList("Content-Type"));
                case "getHeaders": return Collections.singletonList("application/json");
                case "hashCode": return System.identityHashCode(proxy);
                case "equals": return proxy == arguments[0];
                case "toString": return "response";
                default: return defaultValue(method.getReturnType());
            }
        });
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        return null;
    }
}
