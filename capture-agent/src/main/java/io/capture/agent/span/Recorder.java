package io.capture.agent.span;

import com.fasterxml.jackson.databind.JsonNode;
import io.capture.agent.AgentConfig;
import io.capture.agent.AgentLog;
import io.capture.agent.hook.CaptureHook;
import io.capture.agent.http.HttpCapture;
import io.capture.agent.meta.BeanMeta;
import io.capture.agent.meta.ContainerInvocation;
import io.capture.agent.meta.WlsDataSources;
import io.capture.agent.meta.WlsMetadata;
import io.capture.trace.HttpInfo;
import io.capture.agent.serialize.GraphSerializer;
import io.capture.agent.serialize.JacksonGraphSerializer;
import io.capture.agent.serialize.SerializationContext;
import io.capture.agent.serialize.SerializerConfig;
import io.capture.agent.serialize.TypeAllowList;
import io.capture.trace.ExceptionInfo;
import io.capture.trace.SerializationWarning;
import io.capture.trace.Span;
import io.capture.trace.Trace;
import io.capture.trace.ValueCapture;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Static entry points called from inlined advice. Must be reachable from every application
 * class loader (the agent jar is on the bootstrap path) and must never throw.
 *
 * Enter pushes a span on the thread's {@link SpanStack}; exit serializes arguments and
 * result (or exception) on the calling thread, pops, and when the stack empties hands the
 * finished {@link Trace} to the {@link TraceWriter}'s queue.
 */
public final class Recorder {

    private static final AtomicReference<Recording> ACTIVE = new AtomicReference<Recording>();
    private static volatile AgentConfig config;
    private static volatile SerializerConfig serializerConfig = new SerializerConfig();
    private static volatile GraphSerializer serializer = new JacksonGraphSerializer(serializerConfig);
    private static volatile TraceWriter writer;
    private static volatile WorkContextPropagation propagation = WorkContextPropagation.create();
    private static volatile CaptureHook hook;
    private static volatile String inertReason;
    private static final AtomicBoolean containerWarned = new AtomicBoolean();

    private Recorder() {
    }

    /** The hook to install while recording, and the propagation that links traces across this server's threads. */
    public static void hook(CaptureHook captureHook, WorkContextPropagation tracePropagation) {
        hook = captureHook;
        propagation = tracePropagation;
    }

    /** Marks the agent as inert: no recording can start, and {@link #state()} says why. */
    public static void inert(String reason) {
        inertReason = reason;
    }

    /** "ready", "recording <id>" or "inert: <reason>". */
    public static String state() {
        if (inertReason != null) {
            return "inert: " + inertReason;
        }
        Recording recording = ACTIVE.get();
        return recording == null ? "ready" : "recording " + recording.id;
    }

    /** The serializer runs with the documented ceilings of {@link SerializerConfig}; only the output directory is configurable. */
    public static void configure(AgentConfig agentConfig) {
        config = agentConfig;
        serializerConfig = new SerializerConfig();
        serializer = new JacksonGraphSerializer(serializerConfig);
        writer = new TraceWriter(agentConfig.outDir, TraceWriter.DEFAULT_CAPACITY);
    }

    public static AgentConfig config() {
        return config;
    }

    public static GraphSerializer serializer() {
        return serializer;
    }

    public static boolean isEnabled() {
        return ACTIVE.get() != null;
    }

    public static Recording active() {
        return ACTIVE.get();
    }

    public static long droppedTraces() {
        TraceWriter w = writer;
        return w == null ? 0L : w.dropped();
    }

    public static long writtenTraces() {
        TraceWriter w = writer;
        return w == null ? 0L : w.written();
    }

    public static int queuedTraces() {
        TraceWriter w = writer;
        return w == null ? 0 : w.queued();
    }

    /**
     * Instruments the classes, writes this server's manifest, then opens the recording window
     * under the caller's id. A recording already active is replaced (and logged).
     */
    public static synchronized Recording start(String recordingId, String name) {
        if (inertReason != null) {
            throw new IllegalStateException("agent is inert: " + inertReason);
        }
        Recording recording = new Recording(recordingId, name);
        WlsMetadata.reset();
        WlsMetadata.deployments();
        File dir = writer == null ? null : writer.recordingDir(recording);
        try {
            if (hook != null) {
                hook.install();
            }
        } catch (Throwable t) {
            AgentLog.error("hook installation failed; recording " + recording.id + " will see no calls", t);
        }
        if (dir != null) {
            ManifestWriter.write(dir, recording, null, null, null);
        }
        Recording previous = ACTIVE.getAndSet(recording);
        if (previous != null) {
            AgentLog.warn("recording " + previous.id + " replaced by " + recording.id);
        }
        AgentLog.info("RECORDING START id=" + recording.id + " name=" + recording.name + " dir=" + dir);
        return recording;
    }

    /** Closes the window, waits (bounded) for queued traces, completes recording.json, restores the bytecode. */
    public static synchronized Recording stop() {
        Recording recording = ACTIVE.getAndSet(null);
        if (recording != null) {
            TraceWriter w = writer;
            boolean flushed = w == null || w.flush(5000);
            if (w != null) {
                ManifestWriter.write(w.recordingDir(recording), recording, System.currentTimeMillis(),
                        recording.traceCount(), droppedTraces());
            }
            AgentLog.info("RECORDING STOP id=" + recording.id + " name=" + recording.name
                    + " traces=" + recording.traceCount() + " dropped=" + droppedTraces()
                    + (flushed ? "" : " (writer still busy after 5s)"));
            try {
                if (hook != null) {
                    hook.uninstall();
                }
            } catch (Throwable t) {
                AgentLog.error("hook removal failed; advice stays in place until the next stop", t);
            }
        }
        return recording;
    }

    /** Waits for the writer queue to drain; for tests and the stop path. */
    public static boolean flush(long timeoutMillis) {
        TraceWriter w = writer;
        return w == null || w.flush(timeoutMillis);
    }

    // ---- container hook --------------------------------------------------------------------

    /**
     * Entry of a session-bean invoker: everything comes from the container's invocation wrapper
     * and the generated client object. Returns 0 when nothing is recorded, else the token
     * {@link #exitContainer} needs.
     */
    public static long enterContainer(Object invokable, Object clientObject, Object wrapper) {
        Recording recording = ACTIVE.get();
        if (recording == null) {
            return 0L;
        }
        try {
            ContainerInvocation ci = ContainerInvocation.read(invokable, clientObject, wrapper);
            if (ci == null || ci.beanClass == null) {
                if (containerWarned.compareAndSet(false, true)) {
                    AgentLog.warn("container hook: invocation objects not recognised (" + describe(invokable) + ", "
                            + describe(clientObject) + ", " + describe(wrapper) + "); call not recorded");
                }
                return 0L;
            }
            SpanStack stack = SpanStack.current();
            boolean root = stack.isEmpty();
            String principal = null;
            String httpSessionId = null;
            if (root) {
                principal = WlsRuntime.principal();
                httpSessionId = WlsRuntime.httpSessionId();
            }
            BeanMeta meta = WlsMetadata.of(ci.beanClass, new WlsMetadata.Hint(ci.ejbName, ci.beanInfo));
            String txAttribute = ci.transactionAttribute;
            String invocation = ci.local ? BeanMeta.LOCAL : BeanMeta.REMOTE;
            String methodName = ci.method == null ? "?" : ci.method.getName();
            String iface = ci.businessInterface(meta == null ? null : meta.interfaces);

            long now = System.nanoTime();
            stack.push(recording, Span.KIND_EJB, null, ci.beanClass.getName(), methodName, now, null, ci, meta,
                    iface, invocation, txAttribute, propagation);
            if (root) {
                stack.trace().principal(principal);
                stack.trace().httpSessionId(httpSessionId);
            }
            return now == 0L ? 1L : now;
        } catch (Throwable t) {
            return 0L;
        }
    }

    public static void exitContainer(long token, Object[] args, Object result, Throwable thrown) {
        if (token == 0L) {
            return;
        }
        try {
            SpanStack stack = SpanStack.current();
            OpenSpan open = stack.top();
            if (open == null || !(open.container instanceof ContainerInvocation)) {
                stack.reset();
                return;
            }
            ContainerInvocation ci = (ContainerInvocation) open.container;
            long durationNs = System.nanoTime() - open.enteredAtNanos;
            Span span = open.toSpan(durationNs);
            MethodSignatures.MethodInfo info = ci.method == null
                    ? MethodSignatures.MethodInfo.unknown(open.method, "")
                    : MethodSignatures.resolve(ci.beanClass, ci.method);
            fill(span, info, args, result, thrown);
            complete(stack, span);
        } catch (Throwable t) {
            try {
                SpanStack.current().reset();
                AgentLog.error("container exit handling failed", t);
            } catch (Throwable ignored) {
                // never into the business call
            }
        }
    }


    // ---- message-driven beans ----------------------------------------------------------------

    /**
     * Container hook, {@code MDListener.onMessage}: the listener's {@code MessageDrivenBeanInfo}
     * names the bean class (in its own loader) and the destination; the message is the argument.
     */
    public static long enterMessage(Object listener, Object message) {
        Recording recording = ACTIVE.get();
        if (recording == null) {
            return 0L;
        }
        try {
            Object info = ContainerInvocation.fieldUp(listener, "info");
            String className = info == null ? null : String.valueOf(ContainerInvocation.call(info, "getBeanClassName"));
            ClassLoader loader = info == null ? null : (ClassLoader) ContainerInvocation.call(info, "getClassLoader");
            if (className == null || loader == null) {
                if (containerWarned.compareAndSet(false, true)) {
                    AgentLog.warn("message hook: listener not recognised (" + describe(listener) + "); delivery not recorded");
                }
                return 0L;
            }
            Class<?> bean = Class.forName(className, false, loader);
            SpanStack stack = SpanStack.current();
            boolean root = stack.isEmpty();
            String principal = root ? WlsRuntime.principal() : null;
            BeanMeta meta = WlsMetadata.of(bean, new WlsMetadata.Hint(null, info));
            Object destination = ContainerInvocation.call(info, "getDestinationName");
            MethodSignatures.MethodInfo onMessage = MethodSignatures.resolve(bean, "onMessage", "(Ljavax/jms/Message;)V");
            String txAttribute = ContainerInvocation.transactionAttribute(ContainerInvocation.call(info, "getOnMessageMethodDescriptor"), info);
            long now = System.nanoTime();
            OpenSpan span = stack.push(recording, Span.KIND_EJB, null, bean.getName(), "onMessage", now, null, message, meta,
                    "javax.jms.MessageListener", io.capture.trace.EjbInfo.INVOCATION_MESSAGE, txAttribute, propagation);
            span.destination = destination == null ? null : destination.toString();
            span.methodInfo = onMessage;
            if (root) {
                stack.trace().principal(principal);
            }
            return now == 0L ? 1L : now;
        } catch (Throwable t) {
            return 0L;
        }
    }

    public static void exitMessage(long token, Object message, Throwable thrown) {
        if (token == 0L) {
            return;
        }
        try {
            SpanStack stack = SpanStack.current();
            OpenSpan open = stack.top();
            if (open == null || open.container != message) {
                stack.reset();
                return;
            }
            long durationNs = System.nanoTime() - open.enteredAtNanos;
            Span span = open.toSpan(durationNs);
            fill(span, open.methodInfo, new Object[]{message}, null, thrown);
            complete(stack, span);
        } catch (Throwable t) {
            try {
                SpanStack.current().reset();
                AgentLog.error("message exit handling failed", t);
            } catch (Throwable ignored) {
                // never into the delivery
            }
        }
    }


    // ---- JDBC through data sources ------------------------------------------------------------

    /** The parameters bound on a prepared statement wrapper so far, and the batches added. */
    private static final class Binds {
        final java.util.TreeMap<Integer, Object> current = new java.util.TreeMap<Integer, Object>();
        final List<java.util.TreeMap<Integer, Object>> batches = new ArrayList<java.util.TreeMap<Integer, Object>>();
        final List<String> sqlBatch = new ArrayList<String>();
    }

    private static final Map<Object, Binds> BINDS = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<Object, Binds>());
    /** The SQL record of each statement's last execution that produced a result set, while its rows are being read. */
    private static final Map<Object, io.capture.trace.SqlInfo> OPEN_RESULTS = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<Object, io.capture.trace.SqlInfo>());

    /** PreparedStatement wrapper: set*(index, value), setNull, addBatch, clearParameters. */
    public static void bindJdbc(Object statement, String method, Object[] args) {
        if (ACTIVE.get() == null) {
            return;
        }
        try {
            Binds binds = BINDS.get(statement);
            if (binds == null) {
                binds = new Binds();
                BINDS.put(statement, binds);
            }
            synchronized (binds) {
                if (method.equals("clearParameters")) {
                    binds.current.clear();
                } else if (method.equals("addBatch")) {
                    if (args.length == 1 && args[0] instanceof String) {
                        binds.sqlBatch.add((String) args[0]);
                    } else {
                        binds.batches.add(new java.util.TreeMap<Integer, Object>(binds.current));
                    }
                } else if (args.length >= 1 && args[0] instanceof Integer) {
                    binds.current.put((Integer) args[0], method.equals("setNull") || args.length < 2 ? null : args[1]);
                }
            }
        } catch (Throwable ignored) {
            // never into the statement
        }
    }

    /** Statement wrapper, {@code execute*}: opens the SQL span with what the wrapper knows before the driver runs. */
    public static long enterJdbc(Object statement, String operation, Object[] args) {
        Recording recording = ACTIVE.get();
        if (recording == null) {
            return 0L;
        }
        try {
            Object conn = ContainerInvocation.fieldUp(statement, "conn");
            Object pool = conn == null ? null : ContainerInvocation.call(conn, "getPoolName");
            String poolName = pool == null ? "?" : pool.toString();
            io.capture.trace.SqlInfo info = new io.capture.trace.SqlInfo();
            info.dataSource = poolName;
            WlsDataSources.DataSource ds = WlsDataSources.describe(poolName);
            if (ds != null) {
                info.jndiNames = ds.jndiNames.isEmpty() ? null : ds.jndiNames;
                info.url = ds.url;
                info.database = ds.database;
            }
            info.statementType = statementType(statement);
            info.operation = operation;
            Object sql = ContainerInvocation.fieldUp(statement, "sql");
            info.sql = sql != null ? sql.toString() : args.length > 0 && args[0] instanceof String ? (String) args[0] : null;
            List<SerializationWarning> warnings = new ArrayList<SerializationWarning>();
            Binds binds = BINDS.get(statement);
            if (binds != null) {
                synchronized (binds) {
                    if (operation.equals("executeBatch")) {
                        if (!binds.batches.isEmpty()) {
                            info.batches = new ArrayList<List<JsonNode>>();
                            for (java.util.TreeMap<Integer, Object> batch : binds.batches) {
                                info.batches.add(parameters(batch, "$.sql.batches[" + info.batches.size() + "]", warnings));
                            }
                        } else if (!binds.sqlBatch.isEmpty()) {
                            info.batches = new ArrayList<List<JsonNode>>();
                            for (String s : binds.sqlBatch) {
                                info.batches.add(java.util.Collections.singletonList((JsonNode) com.fasterxml.jackson.databind.node.TextNode.valueOf(s)));
                            }
                        }
                        binds.batches.clear();
                        binds.sqlBatch.clear();
                    } else if (!binds.current.isEmpty()) {
                        info.parameters = parameters(binds.current, "$.sql.parameters", warnings);
                    }
                }
            }
            SpanStack stack = SpanStack.current();
            long now = System.nanoTime();
            OpenSpan span = stack.push(recording, Span.KIND_SQL, null, poolName, operation, now, null, statement, null, null, null, null, propagation);
            span.sql = info;
            span.sqlWarnings = warnings;
            return now == 0L ? 1L : now;
        } catch (Throwable t) {
            return 0L;
        }
    }

    public static void exitJdbc(long token, Object statement, Object result, Throwable thrown) {
        if (token == 0L) {
            return;
        }
        try {
            SpanStack stack = SpanStack.current();
            OpenSpan open = stack.top();
            if (open == null || open.container != statement) {
                stack.reset();
                return;
            }
            long durationNs = System.nanoTime() - open.enteredAtNanos;
            Span span = open.toSpan(durationNs);
            List<SerializationWarning> warnings = open.sqlWarnings == null ? new ArrayList<SerializationWarning>() : open.sqlWarnings;
            if (thrown != null) {
                span.exception = new ExceptionInfo();
                span.exception.className = thrown.getClass().getName();
                span.exception.message = thrown.getMessage();
                span.exception.value = serialize(thrown, Throwable.class, "$.exception", warnings);
            } else if (result instanceof Integer) {
                span.sql.updateCount = (Integer) result;
            } else if (result instanceof int[]) {
                span.sql.updateCounts = (int[]) result;
            } else if (result instanceof Boolean) {
                span.sql.resultSet = (Boolean) result;
                if ((Boolean) result) {
                    span.sql.rows = 0L;
                    OPEN_RESULTS.put(statement, span.sql);
                }
            } else if (result != null && implementsNamed(result.getClass(), "java.sql.ResultSet")) {
                span.sql.resultSet = Boolean.TRUE;
                span.sql.rows = 0L;
                OPEN_RESULTS.put(statement, span.sql);
            }
            if (!warnings.isEmpty()) {
                span.serializationWarnings = warnings;
            }
            complete(stack, span);
        } catch (Throwable t) {
            try {
                SpanStack.current().reset();
                AgentLog.error("jdbc exit handling failed", t);
            } catch (Throwable ignored) {
                // never into the statement
            }
        }
    }

    /** ResultSet wrapper, every call through its handler: a {@code next()} that returned true is one more row the application read. */
    public static void rowRead(Object resultSet, String method, Object returned) {
        if (ACTIVE.get() == null || !method.equals("next") || !Boolean.TRUE.equals(returned)) {
            return;
        }
        try {
            Object statement = ContainerInvocation.fieldUp(resultSet, "stmt");
            io.capture.trace.SqlInfo info = statement == null ? null : OPEN_RESULTS.get(statement);
            if (info != null && info.rows != null) {
                info.rows = info.rows + 1;
            }
        } catch (Throwable ignored) {
            // observing only
        }
    }

    private static List<JsonNode> parameters(java.util.TreeMap<Integer, Object> bound, String path, List<SerializationWarning> warnings) {
        int size = bound.isEmpty() ? 0 : bound.lastKey();
        List<JsonNode> out = new ArrayList<JsonNode>(size);
        for (int i = 1; i <= size; i++) {
            Object v = bound.get(i);
            out.add(bound.containsKey(i) ? serialize(v, v == null ? Object.class : v.getClass(), path + "[" + (i - 1) + "]", warnings) : com.fasterxml.jackson.databind.node.NullNode.getInstance());
        }
        return out;
    }

    private static String statementType(Object statement) {
        for (Class<?> c = statement.getClass(); c != null; c = c.getSuperclass()) {
            if (c.getName().equals("weblogic.jdbc.wrapper.CallableStatement")) {
                return "callable";
            }
            if (c.getName().equals("weblogic.jdbc.wrapper.PreparedStatement")) {
                return "prepared";
            }
        }
        return "statement";
    }

    private static boolean implementsNamed(Class<?> type, String interfaceName) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            for (Class<?> i : c.getInterfaces()) {
                if (i.getName().equals(interfaceName) || implementsNamed(i, interfaceName)) {
                    return true;
                }
            }
        }
        return false;
    }
    private static String describe(Object o) {
        return o == null ? "null" : o.getClass().getName();
    }

    // ---- HTTP entry points -----------------------------------------------------------------

    /** The container request of the servlet span open on this thread, or null. */
    public static Object currentServletRequest() {
        try {
            Object capture = SpanStack.current().servletCapture();
            return capture instanceof HttpCapture ? ((HttpCapture) capture).originalRequest : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Opens a servlet span (root, or a child for a nested dispatch). False when the recording's filters reject it. */
    public static boolean enterServlet(HttpCapture capture, String principal, String httpSessionId) {
        Recording recording = ACTIVE.get();
        if (recording == null) {
            return false;
        }
        try {
            SpanStack stack = SpanStack.current();
            boolean root = stack.isEmpty();
            long now = System.nanoTime();
            stack.push(recording, Span.KIND_HTTP, null, null, null, now, capture, propagation);
            if (root) {
                stack.trace().principal(principal);
                stack.trace().httpSessionId(httpSessionId);
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Completes the servlet span opened by {@link #enterServlet}. */
    public static void exitServlet(HttpCapture capture, HttpInfo info, String principal, String httpSessionId, Throwable thrown) {
        try {
            SpanStack stack = SpanStack.current();
            OpenSpan open = stack.top();
            if (open == null || open.http != capture) {
                stack.reset();
                return;
            }
            long durationNs = System.nanoTime() - open.enteredAtNanos;
            Span span = open.toSpan(durationNs);
            span.http = info;
            if (thrown != null) {
                List<SerializationWarning> warnings = new ArrayList<SerializationWarning>();
                span.exception = new ExceptionInfo();
                span.exception.className = thrown.getClass().getName();
                span.exception.message = thrown.getMessage();
                span.exception.value = serialize(thrown, Throwable.class, "$.exception", warnings);
                if (!warnings.isEmpty()) {
                    span.serializationWarnings = warnings;
                }
            }
            // A session created during the request, or a login, shows up now.
            stack.trace().principal(principal);
            stack.trace().httpSessionId(httpSessionId);

            Recording recording = stack.trace().recording;
            Trace finished = stack.pop(span, propagation);
            if (finished != null) {
                TraceWriter w = writer;
                if (w != null) {
                    w.offer(recording, finished);
                }
            }
        } catch (Throwable t) {
            try {
                SpanStack.current().reset();
                AgentLog.error("servlet exit handling failed", t);
            } catch (Throwable ignored) {
                // never into the request
            }
        }
    }

    /** JAX-RS resource / JAX-WS endpoint method entry: a child HTTP span with its layer. */
    public static long enterResource(Object self, String resourceClass, String method, String signature) {
        Recording recording = ACTIVE.get();
        if (recording == null) {
            return 0L;
        }
        try {
            SpanStack stack = SpanStack.current();
            boolean root = stack.isEmpty();
            String principal = null;
            String httpSessionId = null;
            if (root) {
                principal = WlsRuntime.principal();
                httpSessionId = WlsRuntime.httpSessionId();
            }
            String layer = ResourceLayers.layerOf(self == null ? null : self.getClass());
            long now = System.nanoTime();
            stack.push(recording, Span.KIND_HTTP, layer, resourceClass, method, now, null, propagation);
            if (root) {
                stack.trace().principal(principal);
                stack.trace().httpSessionId(httpSessionId);
            }
            return now == 0L ? 1L : now;
        } catch (Throwable t) {
            return 0L;
        }
    }

    /** Resource method exit, normal or exceptional. Token 0 means {@link #enterResource} recorded nothing. */
    public static void exit(long token, Object self, String beanClass, String method, String descriptor,
                            Object[] args, Object result, Throwable thrown) {
        if (token == 0L) {
            return;
        }
        try {
            SpanStack stack = SpanStack.current();
            OpenSpan open = stack.top();
            if (open == null || !open.beanClass.equals(beanClass) || !open.method.equals(method)) {
                // The recording boundary was crossed mid-trace: abandon this thread's state.
                stack.reset();
                return;
            }
            long durationNs = System.nanoTime() - open.enteredAtNanos;
            Span span = open.toSpan(durationNs);
            Class<?> bean = MethodSignatures.beanClass(self, beanClass);
            fill(span, MethodSignatures.resolve(bean, method, descriptor), args, result, thrown);
            complete(stack, span);
        } catch (Throwable t) {
            try {
                SpanStack.current().reset();
                AgentLog.error("resource exit handling failed for " + beanClass + "." + method, t);
            } catch (Throwable ignored) {
                // never into the business call
            }
        }
    }

    /** Pops the finished span; when it was the root, hands the trace to the writer. */
    private static void complete(SpanStack stack, Span span) {
        Recording recording = stack.trace().recording;
        Trace finished = stack.pop(span, propagation);
        if (finished != null) {
            TraceWriter w = writer;
            if (w != null) {
                w.offer(recording, finished);
            }
        }
    }

    private static void fill(Span span, MethodSignatures.MethodInfo info, Object[] args, Object result, Throwable thrown) {
        if (span.ejb != null) {
            span.ejb.signature = info.signature;
        } else if (span.http != null) {
            span.http.resourceSignature = info.signature;
        }

        List<SerializationWarning> warnings = new ArrayList<SerializationWarning>();
        span.args = new ArrayList<ValueCapture>();
        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                span.args.add(capture(args[i], info.parameterType(i), "$.args[" + i + "]", warnings));
            }
        }
        if (thrown != null) {
            span.exception = new ExceptionInfo();
            span.exception.className = thrown.getClass().getName();
            span.exception.message = thrown.getMessage();
            span.exception.value = serialize(thrown, Throwable.class, "$.exception", warnings);
        } else if (!info.returnsVoid()) {
            span.result = capture(result, info.returnType == null ? Object.class : info.returnType, "$.result", warnings);
        }
        if (!warnings.isEmpty()) {
            span.serializationWarnings = warnings;
        }
    }

    private static ValueCapture capture(Object value, Class<?> declaredType, String path, List<SerializationWarning> warnings) {
        ValueCapture capture = new ValueCapture();
        capture.declaredType = declaredType.getName();
        capture.value = serialize(value, declaredType, path, warnings);
        return capture;
    }

    private static JsonNode serialize(Object value, Class<?> declaredType, String path, List<SerializationWarning> warnings) {
        SerializationContext ctx = new SerializationContext(serializerConfig, path);
        JsonNode node = serializer.serialize(value, declaredType, ctx);
        warnings.addAll(ctx.warnings());
        return node;
    }
}
