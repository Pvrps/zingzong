package ca.gc.cra.xz02.devops.wltrace.agent.instrument;

import ca.gc.cra.xz02.devops.wltrace.agent.bootstrap.AgentLog;
import ca.gc.cra.xz02.devops.wltrace.agent.instrument.stubs.Calls;
import ca.gc.cra.xz02.devops.wltrace.agent.instrument.stubs.EventListenerBean;
import ca.gc.cra.xz02.devops.wltrace.agent.instrument.stubs.GreeterBean;
import ca.gc.cra.xz02.devops.wltrace.agent.instrument.stubs.Http;
import ca.gc.cra.xz02.devops.wltrace.agent.instrument.stubs.MessageDrivenBeanInfoStub;
import org.example.web.OrdersResource;
import ca.gc.cra.xz02.devops.wltrace.agent.record.Recorder;
import ca.gc.cra.xz02.devops.wltrace.agent.record.Recording;
import ca.gc.cra.xz02.devops.wltrace.agent.weblogic.WorkContextPropagation;
import ca.gc.cra.xz02.devops.wltrace.model.EjbInfo;
import ca.gc.cra.xz02.devops.wltrace.model.RecordingManifest;
import ca.gc.cra.xz02.devops.wltrace.model.Span;
import ca.gc.cra.xz02.devops.wltrace.model.Trace;
import ca.gc.cra.xz02.devops.wltrace.model.TraceJson;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import weblogic.ejb.container.internal.MDListener;
import weblogic.jdbc.wrapper.Connection;
import weblogic.jdbc.wrapper.PreparedStatement;
import weblogic.jdbc.wrapper.Statement;
import weblogic.servlet.internal.FilterChainImpl;
import weblogic.servlet.internal.ServletStubImpl;
import weblogic.utils.classloaders.GenericClassLoader;

import javax.jms.TextMessage;
import java.io.File;
import java.io.FileInputStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Proves that the hook lands on classes named like the WebLogic targets, that each advice yields the expected span, and that nothing is instrumented while no recording is active. */
class CaptureHookTest {

    @TempDir
    static File out;

    private static CaptureHook hook;

    @BeforeAll
    static void installHook() throws Exception {
        // Load the targets first so start() has to retransform classes that are already loaded.
        Calls.local(new GreeterBean(), "greet", new Object[]{"warm-up"});
        new FilterChainImpl(200).doFilter(new Http("GET", "/warm-up").request(), new Http("GET", "/warm-up").response());
        new ServletStubImpl().execute(new Http("GET", "/warm-up").request(), new Http("GET", "/warm-up").response());

        Instrumentation instrumentation = ByteBuddyAgent.install();
        AgentLog.init(out);
        Recorder.configure(out);
        hook = new CaptureHook(instrumentation);
        Recorder.hook(hook, WorkContextPropagation.create());
    }

    @AfterAll
    static void removeHook() {
        Recorder.stop();
        assertFalse(hook.isInstalled());
    }

    @BeforeEach
    void recordingOff() {
        Recorder.stop();
    }

    @Test
    void allTargetsArePresentAndNothingIsInstrumentedWhileOff() throws Exception {
        assertTrue(ContainerTargets.missing().isEmpty(), ContainerTargets.missing().toString());
        assertTrue(JdbcTargets.present());
        assertFalse(hook.isInstalled(), "stop() restores the original bytecode");
        long logBefore = AgentLog.file().length();
        File[] dirsBefore = recordingDirs();
        Calls.local(new GreeterBean(), "greet", new Object[]{"x"});
        new FilterChainImpl(200).doFilter(new Http("GET", "/x").request(), new Http("GET", "/x").response());
        assertEquals(logBefore, AgentLog.file().length(), "agent.log must not grow");
        assertTrue(Arrays.equals(dirsBefore, recordingDirs()), "no recording directory appears");
    }

    @Test
    void startInstallsAndStopRemovesTheHookAndWritesTheManifest() throws Exception {
        Recording first = Recorder.start("rec-lifecycle-1", "lifecycle-1");
        assertTrue(hook.isInstalled());
        Calls.local(new GreeterBean(), "greet", new Object[]{"a"});
        Recorder.stop();
        assertFalse(hook.isInstalled());
        Calls.local(new GreeterBean(), "greet", new Object[]{"not recorded"});

        Recording second = Recorder.start("rec-lifecycle-2", "lifecycle-2");
        Calls.local(new GreeterBean(), "greet", new Object[]{"b"});
        Recorder.stop();
        assertTrue(Recorder.flush(5000));

        assertEquals(1, traces(first).size());
        assertEquals("a", traces(first).get(0).spans.get(0).args.get(0).value.asText());
        assertEquals(1, traces(second).size());
        assertEquals("b", traces(second).get(0).spans.get(0).args.get(0).value.asText());

        RecordingManifest manifest;
        try (FileInputStream in = new FileInputStream(new File(new File(out, second.id), "recording.json"))) {
            manifest = TraceJson.read(in, RecordingManifest.class);
        }
        assertEquals(second.id, manifest.id);
        assertEquals("lifecycle-2", manifest.name);
        assertTrue(manifest.startedAt != null && manifest.endedAt != null, "start and end time");
        assertEquals(Long.valueOf(1), manifest.traceCount);
        assertEquals(Long.valueOf(0), manifest.droppedTraces);
        assertFalse("unknown".equals(manifest.agent.version), "version comes from build.properties: " + manifest.agent.version);
    }

    @Test
    void localInvokerProducesAnEjbSpanFromTheContainerObjects() throws Exception {
        Recording recording = Recorder.start("rec-local", "local");
        assertEquals("hello world", Calls.local(new GreeterBean(), "greet", new Object[]{"world"}));
        assertTrue(Recorder.flush(5000));

        List<Trace> traces = traces(recording);
        assertEquals(1, traces.size());
        Span span = traces.get(0).spans.get(0);
        assertEquals(Span.KIND_EJB, span.kind);
        assertEquals(GreeterBean.class.getName(), span.ejb.beanClass);
        assertEquals("greet", span.ejb.method);
        assertEquals("java.lang.String greet(java.lang.String)", span.ejb.signature);
        assertEquals("Greeter", span.ejb.beanName);
        assertEquals("ca.gc.cra.xz02.devops.wltrace.agent.instrument.stubs.Greeter", span.ejb.interfaceName);
        assertEquals(EjbInfo.INVOCATION_LOCAL, span.ejb.invocation);
        assertEquals("REQUIRED", span.ejb.transactionAttribute);
        assertEquals("STATELESS", span.ejb.beanType);
        assertTrue(span.ejb.jndiNames.contains("stubs/Greeter"), "names from BeanInfo.getBusinessJNDINames: " + span.ejb.jndiNames);
        assertEquals("world", span.args.get(0).value.asText());
        assertEquals("java.lang.String", span.args.get(0).declaredType);
        assertEquals("hello world", span.result.value.asText());
        assertTrue(span.thread != null && !span.thread.isEmpty());
        assertEquals(1, traces.get(0).recording.seq);
    }

    @Test
    void remoteInvokerResolvesTheInterfaceThroughTheGeneratedRemoteInterface() throws Exception {
        Recording recording = Recorder.start("rec-remote", "remote");
        assertEquals("hello t3", Calls.remote(new GreeterBean(), "greet", new Object[]{"t3"}));
        assertTrue(Recorder.flush(5000));

        Span span = traces(recording).get(0).spans.get(0);
        assertEquals(EjbInfo.INVOCATION_REMOTE, span.ejb.invocation);
        assertEquals("ca.gc.cra.xz02.devops.wltrace.agent.instrument.stubs.Greeter", span.ejb.interfaceName);
    }

    @Test
    void nestedInvocationsFormOneTraceAndExceptionsCompleteIt() throws Exception {
        Recording recording = Recorder.start("rec-tree", "tree");
        assertEquals("hello x!", Calls.local(new GreeterBean(), "run", new Object[]{"x"}));
        assertThrows(IllegalStateException.class, () -> Calls.local(new GreeterBean(), "fail", new Object[0]));
        assertTrue(Recorder.flush(5000));

        List<Trace> traces = traces(recording);
        assertEquals(2, traces.size(), "one file per root call");
        List<Span> spans = traces.get(0).spans;
        assertEquals(Arrays.asList("run", "greet"), methods(spans));
        assertNull(spans.get(0).parentId);
        assertEquals(spans.get(0).id, spans.get(1).parentId);
        assertEquals(1, spans.get(1).depth);
        assertEquals(Arrays.asList("s1", "s2"), Arrays.asList(spans.get(0).id, spans.get(1).id));

        Span failed = traces.get(1).spans.get(0);
        assertEquals("fail", failed.ejb.method);
        assertEquals("java.lang.IllegalStateException", failed.exception.className);
        assertEquals("greeter exploded", failed.exception.message);
        assertNull(failed.result);
        assertEquals(0, Recorder.droppedTraces());
    }

    @Test
    void messageListenerProducesAnMdbSpanWithTheMessageAsArgument() throws Exception {
        Recording recording = Recorder.start("rec-mdb", "mdb");
        EventListenerBean bean = new EventListenerBean();
        MDListener listener = new MDListener(new MessageDrivenBeanInfoStub(EventListenerBean.class, "OrderEventListener", "jms/OrderEvents"), bean);
        listener.onMessage(textMessage("order 42 created"));
        assertEquals("order 42 created", bean.lastText);
        assertTrue(Recorder.flush(5000));

        Span span = traces(recording).get(0).spans.get(0);
        assertEquals(Span.KIND_EJB, span.kind);
        assertEquals(EventListenerBean.class.getName(), span.ejb.beanClass);
        assertEquals("onMessage", span.ejb.method);
        assertEquals("OrderEventListener", span.ejb.beanName);
        assertEquals(EjbInfo.INVOCATION_MESSAGE, span.ejb.invocation);
        assertEquals("javax.jms.MessageListener", span.ejb.interfaceName);
        assertEquals("jms/OrderEvents", span.ejb.destination);
        assertEquals("MESSAGE_DRIVEN", span.ejb.beanType);
        assertEquals("REQUIRED", span.ejb.transactionAttribute);
        assertEquals("TextMessage", span.args.get(0).value.get("@jms").asText());
        assertEquals("order 42 created", span.args.get(0).value.get("text").asText());
        assertNull(span.result, "onMessage is void");
    }

    @Test
    void filterChainAndServletStubProduceHttpRootsWithBothBodies() throws Exception {
        Recording recording = Recorder.start("rec-http", "http");
        Http filtered = new Http("POST", "/orders/api/orders");
        new FilterChainImpl(201).doFilter(filtered.request(), filtered.response());
        Http direct = new Http("GET", "/tax/TaxWebService");
        new ServletStubImpl().execute(direct.request(), direct.response());
        assertTrue(Recorder.flush(5000));

        List<Trace> traces = traces(recording);
        assertEquals(2, traces.size());
        Span post = traces.get(0).spans.get(0);
        assertEquals(Span.KIND_HTTP, post.kind);
        assertNull(post.layer);
        assertEquals("POST", post.http.method);
        assertEquals("/orders/api/orders", post.http.path);
        assertEquals(Integer.valueOf(201), post.http.status);
        assertEquals(Arrays.asList("application/json"), post.http.requestHeaders.get("Accept"));
        assertEquals("{\"ok\":true}", post.http.responseBody, "written through the tee writer");
        assertEquals("{\"ok\":true}", filtered.written.toString(), "and delivered to the container");

        Span get = traces.get(1).spans.get(0);
        assertEquals("/tax/TaxWebService", get.http.path);
        assertEquals("plain", get.http.responseBody, "written through the tee stream");
        assertEquals("plain", new String(direct.streamed.toByteArray(), StandardCharsets.UTF_8));
    }

    @Test
    void statementWrappersProduceSqlSpansWithParametersAndCounts() throws Exception {
        Recording recording = Recorder.start("rec-sql", "sql");
        Connection conn = new Connection("OrdersDS");
        PreparedStatement select = new PreparedStatement(conn, "SELECT ID, STATUS FROM ORDERS WHERE ID = ? AND STATUS <> ?");
        select.rowsToYield = 2;
        select.setLong(1, 5L);
        select.setString(2, "CANCELLED");
        java.sql.ResultSet rows = select.executeQuery();
        int read = 0;
        while (rows.next()) {
            read++;
        }
        rows.close();
        assertEquals(2, read);
        PreparedStatement update = new PreparedStatement(conn, "UPDATE ORDERS SET STATUS = ? WHERE ID = ?");
        update.setString(1, "CONFIRMED");
        update.setLong(2, 5L);
        assertEquals(1, update.executeUpdate());
        Statement broken = new Statement(conn);
        broken.failure = new java.sql.SQLException("Table \"NOPE\" not found");
        assertThrows(java.sql.SQLException.class, () -> broken.executeUpdate("DELETE FROM NOPE"));
        assertTrue(Recorder.flush(5000));

        List<Trace> traces = traces(recording);
        assertEquals(3, traces.size(), "every statement outside a request is its own trace");
        Span query = traces.get(0).spans.get(0);
        assertEquals(Span.KIND_SQL, query.kind);
        assertEquals("OrdersDS", query.sql.dataSource);
        assertEquals("prepared", query.sql.statementType);
        assertEquals("executeQuery", query.sql.operation);
        assertEquals("SELECT ID, STATUS FROM ORDERS WHERE ID = ? AND STATUS <> ?", query.sql.sql);
        assertEquals(5L, query.sql.parameters.get(0).asLong());
        assertEquals("CANCELLED", query.sql.parameters.get(1).asText());
        assertEquals(Boolean.TRUE, query.sql.resultSet);
        assertEquals(Long.valueOf(2), query.sql.rows, "rows counted as the application stepped through them");
        assertNull(query.sql.updateCount);

        Span updated = traces.get(1).spans.get(0);
        assertEquals("executeUpdate", updated.sql.operation);
        assertEquals(Integer.valueOf(1), updated.sql.updateCount);
        assertEquals(Arrays.asList("CONFIRMED", "5"), Arrays.asList(updated.sql.parameters.get(0).asText(), updated.sql.parameters.get(1).asText()));

        Span failed = traces.get(2).spans.get(0);
        assertEquals("statement", failed.sql.statementType);
        assertEquals("DELETE FROM NOPE", failed.sql.sql);
        assertEquals("java.sql.SQLException", failed.exception.className);
        assertTrue(failed.exception.message.contains("NOPE"));
    }

    @Test
    void resourceClassesAreInstrumentedOnlyWhenAnApplicationLoaderDefinesThem() throws Exception {
        Recording recording = Recorder.start("rec-resource", "resource");
        Class<?> owned = new GenericClassLoader(OrdersResource.class.getName()).loadClass(OrdersResource.class.getName());
        assertTrue(owned.getClassLoader() instanceof GenericClassLoader);
        assertEquals("created body", owned.getMethod("create", String.class).invoke(owned.getDeclaredConstructor().newInstance(), "body"));
        assertEquals("created system", new OrdersResource().create("system"));
        assertTrue(Recorder.flush(5000));

        List<Trace> traces = traces(recording);
        assertEquals(1, traces.size(), "only the application loader's class is a span");
        Span span = traces.get(0).spans.get(0);
        assertEquals(Span.KIND_HTTP, span.kind);
        assertEquals("jaxrs", span.layer);
        assertEquals(OrdersResource.class.getName(), span.http.resourceClass);
        assertEquals("create", span.http.resourceMethod);
        assertEquals("body", span.args.get(0).value.asText());
        assertEquals("created body", span.result.value.asText());

        String log = new String(Files.readAllBytes(AgentLog.file().toPath()), StandardCharsets.UTF_8);
        assertTrue(log.contains("not instrumented (resource annotation, but not an application class): " + OrdersResource.class.getName()), log);
        assertTrue(log.contains("instrumented weblogic.ejb.container.internal.SessionLocalMethodInvoker (retransformed)"), log);
        assertTrue(log.contains("instrumented weblogic.servlet.internal.FilterChainImpl (retransformed)"), log);
        assertFalse(log.contains("ERROR"), log);
    }

    private static TextMessage textMessage(String text) {
        return (TextMessage) Proxy.newProxyInstance(CaptureHookTest.class.getClassLoader(), new Class<?>[]{TextMessage.class}, (p, m, a) -> {
            switch (m.getName()) {
                case "getText": return text;
                case "getJMSMessageID": return "ID:1";
                case "hashCode": return System.identityHashCode(p);
                case "equals": return p == a[0];
                case "toString": return "TextMessage(" + text + ")";
                default: return m.getReturnType() == boolean.class ? false : m.getReturnType().isPrimitive() ? 0 : null;
            }
        });
    }

    private static File[] recordingDirs() {
        File[] dirs = out.listFiles(File::isDirectory);
        if (dirs == null) {
            return new File[0];
        }
        Arrays.sort(dirs);
        return dirs;
    }

    private static List<Trace> traces(Recording recording) throws Exception {
        File[] files = new File(out, recording.id).listFiles((d, name) -> name.matches("\\d{6}-t-.*\\.json"));
        assertNotNull(files, "recording dir missing");
        Arrays.sort(files);
        List<Trace> traces = new ArrayList<>();
        for (File f : files) {
            try (FileInputStream in = new FileInputStream(f)) {
                traces.add(TraceJson.read(in));
            }
        }
        return traces;
    }

    private static List<String> methods(List<Span> spans) {
        List<String> names = new ArrayList<>();
        for (Span s : spans) {
            names.add(s.ejb.method);
        }
        return names;
    }
}
