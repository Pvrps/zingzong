package ca.gc.cra.xz02.devops.wltrace.agent.instrument;

import ca.gc.cra.xz02.devops.wltrace.agent.bootstrap.AgentLog;
import ca.gc.cra.xz02.devops.wltrace.agent.bootstrap.AgentStartup;
import ca.gc.cra.xz02.devops.wltrace.agent.instrument.stubs.Calls;
import ca.gc.cra.xz02.devops.wltrace.agent.instrument.stubs.EventListenerBean;
import ca.gc.cra.xz02.devops.wltrace.agent.instrument.stubs.GreeterBean;
import ca.gc.cra.xz02.devops.wltrace.agent.instrument.stubs.Http;
import ca.gc.cra.xz02.devops.wltrace.agent.instrument.stubs.MessageDrivenBeanInfoStub;
import ca.gc.cra.xz02.devops.wltrace.agent.record.Recording;
import ca.gc.cra.xz02.devops.wltrace.agent.record.RecordingLifecycle;
import ca.gc.cra.xz02.devops.wltrace.agent.record.TraceAgent;
import ca.gc.cra.xz02.devops.wltrace.agent.record.TraceRuntime;
import ca.gc.cra.xz02.devops.wltrace.model.EjbInfo;
import ca.gc.cra.xz02.devops.wltrace.model.RecordingManifest;
import ca.gc.cra.xz02.devops.wltrace.model.Span;
import ca.gc.cra.xz02.devops.wltrace.model.Trace;
import ca.gc.cra.xz02.devops.wltrace.model.TraceJson;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.example.web.OrdersResource;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
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
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

/**
 * Proves that the hooks land on classes named like the WebLogic targets, that each advice yields
 * the expected span through its recorder, and that nothing is instrumented while no recording is active.
 */
public class HookInstallerTest {

    private File out;
    private TraceAgent agent;
    private RecordingLifecycle lifecycle;
    private AgentLog log;

    @BeforeClass
    public void assembleAgent() throws Exception {
        out = Files.createTempDirectory("weblogic-trace-test").toFile();
        // Load the targets first so start() has to retransform classes that are already loaded.
        Calls.local(new GreeterBean(), "greet", new Object[]{"warm-up"});
        new FilterChainImpl(200).doFilter(new Http("GET", "/warm-up").request(), new Http("GET", "/warm-up").response());
        new ServletStubImpl().execute(new Http("GET", "/warm-up").request(), new Http("GET", "/warm-up").response());

        System.setProperty("weblogic.home", "stubs");
        log = AgentLog.open(out);
        agent = AgentStartup.assemble(out, ByteBuddyAgent.install(), log);
        TraceRuntime.install(agent);
        lifecycle = agent.lifecycle();
        assertFalse(lifecycle.inertReason().isPresent(), "the test class path carries stubs of every container target");
    }

    @AfterClass
    public void removeHooks() {
        lifecycle.stop();
        System.clearProperty("weblogic.home");
        assertFalse(lifecycle.hooksInstalled());
    }

    @BeforeMethod
    public void recordingOff() {
        lifecycle.stop();
    }

    @Test
    public void nothingIsInstrumentedWhileNoRecordingIsActive() throws Exception {
        assertFalse(lifecycle.hooksInstalled(), "stop() restores the original bytecode");
        long logBefore = log.file().length();
        File[] directoriesBefore = recordingDirectories();
        Calls.local(new GreeterBean(), "greet", new Object[]{"x"});
        new FilterChainImpl(200).doFilter(new Http("GET", "/x").request(), new Http("GET", "/x").response());
        assertEquals(log.file().length(), logBefore, "agent.log must not grow");
        assertTrue(Arrays.equals(directoriesBefore, recordingDirectories()), "no recording directory appears");
    }

    @Test
    public void startInstallsAndStopRemovesTheHooksAndWritesTheManifest() throws Exception {
        Recording first = lifecycle.start("rec-lifecycle-1", "lifecycle-1");
        assertTrue(lifecycle.hooksInstalled());
        Calls.local(new GreeterBean(), "greet", new Object[]{"a"});
        lifecycle.stop();
        assertFalse(lifecycle.hooksInstalled());
        Calls.local(new GreeterBean(), "greet", new Object[]{"not recorded"});

        Recording second = lifecycle.start("rec-lifecycle-2", "lifecycle-2");
        Calls.local(new GreeterBean(), "greet", new Object[]{"b"});
        lifecycle.stop();

        assertEquals(traces(first).size(), 1);
        assertEquals(traces(first).get(0).spans.get(0).args.get(0).value.asText(), "a");
        assertEquals(traces(second).size(), 1);
        assertEquals(traces(second).get(0).spans.get(0).args.get(0).value.asText(), "b");

        RecordingManifest manifest;
        try (FileInputStream in = new FileInputStream(new File(new File(out, second.id), "recording.json"))) {
            manifest = TraceJson.read(in, RecordingManifest.class);
        }
        assertEquals(manifest.id, second.id);
        assertEquals(manifest.name, "lifecycle-2");
        assertTrue(manifest.startedAt != null && manifest.endedAt != null, "start and end time");
        assertEquals(manifest.traceCount, Long.valueOf(1));
        assertEquals(manifest.droppedTraces, Long.valueOf(0));
        assertFalse("unknown".equals(manifest.agent.version), "version comes from build.properties: " + manifest.agent.version);
    }

    @Test
    public void localInvokerProducesAnEjbSpanFromTheContainerObjects() throws Exception {
        Recording recording = lifecycle.start("rec-local", "local");
        assertEquals(Calls.local(new GreeterBean(), "greet", new Object[]{"world"}), "hello world");
        lifecycle.stop();

        List<Trace> traces = traces(recording);
        assertEquals(traces.size(), 1);
        Span span = traces.get(0).spans.get(0);
        assertEquals(span.kind, Span.KIND_EJB);
        assertEquals(span.ejb.beanClass, GreeterBean.class.getName());
        assertEquals(span.ejb.method, "greet");
        assertEquals(span.ejb.signature, "java.lang.String greet(java.lang.String)");
        assertEquals(span.ejb.beanName, "Greeter");
        assertEquals(span.ejb.interfaceName, "ca.gc.cra.xz02.devops.wltrace.agent.instrument.stubs.Greeter");
        assertEquals(span.ejb.invocation, EjbInfo.INVOCATION_LOCAL);
        assertEquals(span.ejb.transactionAttribute, "REQUIRED");
        assertEquals(span.ejb.beanType, "STATELESS");
        assertTrue(span.ejb.jndiNames.contains("stubs/Greeter"), "names from BeanInfo.getBusinessJNDINames: " + span.ejb.jndiNames);
        assertEquals(span.args.get(0).value.asText(), "world");
        assertEquals(span.args.get(0).declaredType, "java.lang.String");
        assertEquals(span.result.value.asText(), "hello world");
        assertTrue(span.thread != null && !span.thread.isEmpty());
        assertEquals(traces.get(0).recording.seq, 1);
    }

    @Test
    public void remoteInvokerResolvesTheInterfaceThroughTheGeneratedRemoteInterface() throws Exception {
        Recording recording = lifecycle.start("rec-remote", "remote");
        assertEquals(Calls.remote(new GreeterBean(), "greet", new Object[]{"t3"}), "hello t3");
        lifecycle.stop();

        Span span = traces(recording).get(0).spans.get(0);
        assertEquals(span.ejb.invocation, EjbInfo.INVOCATION_REMOTE);
        assertEquals(span.ejb.interfaceName, "ca.gc.cra.xz02.devops.wltrace.agent.instrument.stubs.Greeter");
    }

    @Test
    public void nestedInvocationsFormOneTraceAndExceptionsCompleteIt() throws Exception {
        Recording recording = lifecycle.start("rec-tree", "tree");
        assertEquals(Calls.local(new GreeterBean(), "run", new Object[]{"x"}), "hello x!");
        assertThrows(IllegalStateException.class, () -> Calls.local(new GreeterBean(), "fail", new Object[0]));
        lifecycle.stop();

        List<Trace> traces = traces(recording);
        assertEquals(traces.size(), 2, "one file per root call");
        List<Span> spans = traces.get(0).spans;
        assertEquals(methods(spans), Arrays.asList("run", "greet"));
        assertNull(spans.get(0).parentId);
        assertEquals(spans.get(1).parentId, spans.get(0).id);
        assertEquals(spans.get(1).depth, 1);
        assertEquals(Arrays.asList(spans.get(0).id, spans.get(1).id), Arrays.asList("s1", "s2"));

        Span failed = traces.get(1).spans.get(0);
        assertEquals(failed.ejb.method, "fail");
        assertEquals(failed.exception.className, "java.lang.IllegalStateException");
        assertEquals(failed.exception.message, "greeter exploded");
        assertNull(failed.result);
        assertEquals(agent.control().getDroppedTraces(), 0);
    }

    @Test
    public void messageListenerProducesAnMdbSpanWithTheMessageAsArgument() throws Exception {
        Recording recording = lifecycle.start("rec-mdb", "mdb");
        EventListenerBean bean = new EventListenerBean();
        MDListener listener = new MDListener(new MessageDrivenBeanInfoStub(EventListenerBean.class, "OrderEventListener", "jms/OrderEvents"), bean);
        listener.onMessage(textMessage("order 42 created"));
        assertEquals(bean.lastText, "order 42 created");
        lifecycle.stop();

        Span span = traces(recording).get(0).spans.get(0);
        assertEquals(span.kind, Span.KIND_EJB);
        assertEquals(span.ejb.beanClass, EventListenerBean.class.getName());
        assertEquals(span.ejb.method, "onMessage");
        assertEquals(span.ejb.beanName, "OrderEventListener");
        assertEquals(span.ejb.invocation, EjbInfo.INVOCATION_MESSAGE);
        assertEquals(span.ejb.interfaceName, "javax.jms.MessageListener");
        assertEquals(span.ejb.destination, "jms/OrderEvents");
        assertEquals(span.ejb.beanType, "MESSAGE_DRIVEN");
        assertEquals(span.ejb.transactionAttribute, "REQUIRED");
        assertEquals(span.args.get(0).value.get("@jms").asText(), "TextMessage");
        assertEquals(span.args.get(0).value.get("text").asText(), "order 42 created");
        assertNull(span.result, "onMessage is void");
    }

    @Test
    public void filterChainAndServletStubProduceHttpRootsWithBothBodies() throws Exception {
        Recording recording = lifecycle.start("rec-http", "http");
        Http filtered = new Http("POST", "/orders/api/orders");
        new FilterChainImpl(201).doFilter(filtered.request(), filtered.response());
        Http direct = new Http("GET", "/tax/TaxWebService");
        new ServletStubImpl().execute(direct.request(), direct.response());
        lifecycle.stop();

        List<Trace> traces = traces(recording);
        assertEquals(traces.size(), 2);
        Span post = traces.get(0).spans.get(0);
        assertEquals(post.kind, Span.KIND_HTTP);
        assertNull(post.layer);
        assertEquals(post.http.method, "POST");
        assertEquals(post.http.path, "/orders/api/orders");
        assertEquals(post.http.status, Integer.valueOf(201));
        assertEquals(post.http.requestHeaders.get("Accept"), Arrays.asList("application/json"));
        assertEquals(post.http.responseBody, "{\"ok\":true}", "written through the tee writer");
        assertEquals(filtered.written.toString(), "{\"ok\":true}", "and delivered to the container");

        Span get = traces.get(1).spans.get(0);
        assertEquals(get.http.path, "/tax/TaxWebService");
        assertEquals(get.http.responseBody, "plain", "written through the tee stream");
        assertEquals(new String(direct.streamed.toByteArray(), StandardCharsets.UTF_8), "plain");
    }

    @Test
    public void statementWrappersProduceSqlSpansWithParametersAndCounts() throws Exception {
        Recording recording = lifecycle.start("rec-sql", "sql");
        Connection connection = new Connection("OrdersDS");
        PreparedStatement select = new PreparedStatement(connection, "SELECT ID, STATUS FROM ORDERS WHERE ID = ? AND STATUS <> ?");
        select.rowsToYield = 2;
        select.setLong(1, 5L);
        select.setString(2, "CANCELLED");
        java.sql.ResultSet rows = select.executeQuery();
        int read = 0;
        while (rows.next()) {
            read++;
        }
        rows.close();
        assertEquals(read, 2);
        PreparedStatement update = new PreparedStatement(connection, "UPDATE ORDERS SET STATUS = ? WHERE ID = ?");
        update.setString(1, "CONFIRMED");
        update.setLong(2, 5L);
        assertEquals(update.executeUpdate(), 1);
        Statement broken = new Statement(connection);
        broken.failure = new java.sql.SQLException("Table \"NOPE\" not found");
        assertThrows(java.sql.SQLException.class, () -> broken.executeUpdate("DELETE FROM NOPE"));
        lifecycle.stop();

        List<Trace> traces = traces(recording);
        assertEquals(traces.size(), 3, "every statement outside a request is its own trace");
        Span query = traces.get(0).spans.get(0);
        assertEquals(query.kind, Span.KIND_SQL);
        assertEquals(query.sql.dataSource, "OrdersDS");
        assertEquals(query.sql.statementType, "prepared");
        assertEquals(query.sql.operation, "executeQuery");
        assertEquals(query.sql.sql, "SELECT ID, STATUS FROM ORDERS WHERE ID = ? AND STATUS <> ?");
        assertEquals(query.sql.parameters.get(0).asLong(), 5L);
        assertEquals(query.sql.parameters.get(1).asText(), "CANCELLED");
        assertEquals(query.sql.resultSet, Boolean.TRUE);
        assertEquals(query.sql.rows, Long.valueOf(2), "rows counted as the application stepped through them");
        assertNull(query.sql.updateCount);

        Span updated = traces.get(1).spans.get(0);
        assertEquals(updated.sql.operation, "executeUpdate");
        assertEquals(updated.sql.updateCount, Integer.valueOf(1));
        assertEquals(Arrays.asList(updated.sql.parameters.get(0).asText(), updated.sql.parameters.get(1).asText()), Arrays.asList("CONFIRMED", "5"));

        Span failed = traces.get(2).spans.get(0);
        assertEquals(failed.sql.statementType, "statement");
        assertEquals(failed.sql.sql, "DELETE FROM NOPE");
        assertEquals(failed.exception.className, "java.sql.SQLException");
        assertTrue(failed.exception.message.contains("NOPE"));
    }

    @Test
    public void resourceClassesAreInstrumentedOnlyWhenAnApplicationLoaderDefinesThem() throws Exception {
        Recording recording = lifecycle.start("rec-resource", "resource");
        Class<?> owned = new GenericClassLoader(OrdersResource.class.getName()).loadClass(OrdersResource.class.getName());
        assertTrue(owned.getClassLoader() instanceof GenericClassLoader);
        assertEquals(owned.getMethod("create", String.class).invoke(owned.getDeclaredConstructor().newInstance(), "body"), "created body");
        assertEquals(new OrdersResource().create("system"), "created system");
        lifecycle.stop();

        List<Trace> traces = traces(recording);
        assertEquals(traces.size(), 1, "only the application loader's class is a span");
        Span span = traces.get(0).spans.get(0);
        assertEquals(span.kind, Span.KIND_HTTP);
        assertEquals(span.layer, "jaxrs");
        assertEquals(span.http.resourceClass, OrdersResource.class.getName());
        assertEquals(span.http.resourceMethod, "create");
        assertEquals(span.http.resourceSignature, "java.lang.String create(java.lang.String)");
        assertEquals(span.args.get(0).value.asText(), "body");
        assertEquals(span.result.value.asText(), "created body");

        String logText = new String(Files.readAllBytes(log.file().toPath()), StandardCharsets.UTF_8);
        assertTrue(logText.contains("not instrumented (resource annotation, but not an application class): " + OrdersResource.class.getName()), logText);
        assertTrue(logText.contains("instrumented weblogic.ejb.container.internal.SessionLocalMethodInvoker (retransformed)"), logText);
        assertTrue(logText.contains("instrumented weblogic.servlet.internal.FilterChainImpl (retransformed)"), logText);
        assertFalse(logText.contains("ERROR"), logText);
    }

    private static TextMessage textMessage(String text) {
        return (TextMessage) Proxy.newProxyInstance(HookInstallerTest.class.getClassLoader(), new Class<?>[]{TextMessage.class}, (proxy, method, arguments) -> {
            switch (method.getName()) {
                case "getText": return text;
                case "getJMSMessageID": return "ID:1";
                case "hashCode": return System.identityHashCode(proxy);
                case "equals": return proxy == arguments[0];
                case "toString": return "TextMessage(" + text + ")";
                default: return method.getReturnType() == boolean.class ? false : method.getReturnType().isPrimitive() ? 0 : null;
            }
        });
    }

    private File[] recordingDirectories() {
        File[] directories = out.listFiles(File::isDirectory);
        if (directories == null) {
            return new File[0];
        }
        Arrays.sort(directories);
        return directories;
    }

    private List<Trace> traces(Recording recording) throws Exception {
        File[] files = new File(out, recording.id).listFiles((directory, name) -> name.matches("\\d{6}-t-.*\\.json"));
        assertNotNull(files, "recording dir missing");
        Arrays.sort(files);
        List<Trace> traces = new ArrayList<>();
        for (File file : files) {
            try (FileInputStream in = new FileInputStream(file)) {
                traces.add(TraceJson.read(in));
            }
        }
        return traces;
    }

    private static List<String> methods(List<Span> spans) {
        return spans.stream().map(span -> span.ejb.method).collect(Collectors.toList());
    }
}
