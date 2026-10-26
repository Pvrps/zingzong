package ca.gc.cra.xz02.devops.wltrace.agent.bootstrap;

import ca.gc.cra.xz02.devops.wltrace.agent.http.TeeClassCache;
import ca.gc.cra.xz02.devops.wltrace.agent.instrument.Hook;
import ca.gc.cra.xz02.devops.wltrace.agent.instrument.HookInstaller;
import ca.gc.cra.xz02.devops.wltrace.agent.instrument.JdbcStatementHook;
import ca.gc.cra.xz02.devops.wltrace.agent.instrument.MessageListenerHook;
import ca.gc.cra.xz02.devops.wltrace.agent.instrument.ResourceHook;
import ca.gc.cra.xz02.devops.wltrace.agent.instrument.ServletPipelineHook;
import ca.gc.cra.xz02.devops.wltrace.agent.instrument.SessionInvokerHook;
import ca.gc.cra.xz02.devops.wltrace.agent.record.ActiveRecording;
import ca.gc.cra.xz02.devops.wltrace.agent.record.EjbRecorder;
import ca.gc.cra.xz02.devops.wltrace.agent.record.JdbcRecorder;
import ca.gc.cra.xz02.devops.wltrace.agent.record.ManifestWriter;
import ca.gc.cra.xz02.devops.wltrace.agent.record.MessageRecorder;
import ca.gc.cra.xz02.devops.wltrace.agent.record.MethodResolver;
import ca.gc.cra.xz02.devops.wltrace.agent.record.RecorderContext;
import ca.gc.cra.xz02.devops.wltrace.agent.record.RecordingControl;
import ca.gc.cra.xz02.devops.wltrace.agent.record.RecordingLifecycle;
import ca.gc.cra.xz02.devops.wltrace.agent.record.ResourceLayers;
import ca.gc.cra.xz02.devops.wltrace.agent.record.ResourceRecorder;
import ca.gc.cra.xz02.devops.wltrace.agent.record.ServletRecorder;
import ca.gc.cra.xz02.devops.wltrace.agent.record.SpanStacks;
import ca.gc.cra.xz02.devops.wltrace.agent.record.TraceAgent;
import ca.gc.cra.xz02.devops.wltrace.agent.record.TraceRuntime;
import ca.gc.cra.xz02.devops.wltrace.agent.record.TraceWriter;
import ca.gc.cra.xz02.devops.wltrace.agent.serialize.JacksonGraphSerializer;
import ca.gc.cra.xz02.devops.wltrace.agent.serialize.SerializationCeilings;
import ca.gc.cra.xz02.devops.wltrace.agent.serialize.TypeInfoCache;
import ca.gc.cra.xz02.devops.wltrace.agent.weblogic.BeanMetadataResolver;
import ca.gc.cra.xz02.devops.wltrace.agent.weblogic.DataSourceCatalog;
import ca.gc.cra.xz02.devops.wltrace.agent.weblogic.RuntimeMBeans;
import ca.gc.cra.xz02.devops.wltrace.agent.weblogic.ServerRuntime;
import ca.gc.cra.xz02.devops.wltrace.agent.weblogic.WorkContextPropagation;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Builds the agent once the jar is on the bootstrap path: every collaborator with its
 * dependencies, the five recorders and their hooks, the recording lifecycle, the control MBean.
 * Nothing is instrumented here; hooks are installed when a recording starts. In a JVM that is not
 * a WebLogic server the same objects exist but the lifecycle refuses to start.
 */
public final class AgentStartup {

    private AgentStartup() {
    }

    /** What {@link AgentMain} runs: logs the start, assembles the agent, publishes it and registers the MBean. */
    public static TraceAgent start(File outputDirectory, Instrumentation instrumentation, String mode, File jar) {
        AgentLog log = AgentLog.open(outputDirectory);
        log.info("weblogic-trace-agent " + AgentVersion.VERSION + " (" + AgentVersion.BUILD + ") " + mode
                + " (out=" + outputDirectory + ") jar=" + jar
                + " jvm=" + System.getProperty("java.version")
                + " loader=" + describe(AgentStartup.class.getClassLoader()));
        TraceAgent agent = assemble(outputDirectory, instrumentation, log);
        TraceRuntime.install(agent);
        agent.control().register();
        Optional<String> inert = agent.lifecycle().inertReason();
        log.info(inert.isPresent() ? "weblogic-trace-agent inert: " + inert.get() : "weblogic-trace-agent ready, recording off");
        return agent;
    }

    /**
     * Wires every object of the agent for the output directory. Public for tests, which install
     * the result through {@link TraceRuntime#install} themselves.
     */
    public static TraceAgent assemble(File outputDirectory, Instrumentation instrumentation, AgentLog log) {
        SerializationCeilings ceilings = new SerializationCeilings();
        JacksonGraphSerializer serializer = new JacksonGraphSerializer(ceilings, new TypeInfoCache());
        TraceWriter writer = new TraceWriter(outputDirectory, TraceWriter.DEFAULT_CAPACITY, log);
        ServerRuntime server = new ServerRuntime(log);
        WorkContextPropagation propagation = WorkContextPropagation.create(log);
        RuntimeMBeans runtimeMBeans = new RuntimeMBeans(log);
        BeanMetadataResolver beanMetadata = new BeanMetadataResolver(runtimeMBeans, log);
        DataSourceCatalog dataSources = new DataSourceCatalog(runtimeMBeans, log);
        ActiveRecording active = new ActiveRecording();
        RecorderContext context = new RecorderContext(active, new SpanStacks(), serializer, ceilings, server, runtimeMBeans, writer, propagation, log);
        MethodResolver methods = new MethodResolver();

        EjbRecorder ejb = new EjbRecorder(context, beanMetadata, methods);
        MessageRecorder message = new MessageRecorder(context, beanMetadata, methods);
        ResourceRecorder resource = new ResourceRecorder(context, methods, new ResourceLayers());
        ServletRecorder servlet = new ServletRecorder(context, new TeeClassCache(log));
        JdbcRecorder jdbc = new JdbcRecorder(context, dataSources);

        List<Hook> hooks = Arrays.asList(
                new SessionInvokerHook(ejb),
                new MessageListenerHook(message),
                new ServletPipelineHook(servlet),
                new ResourceHook(resource, log),
                new JdbcStatementHook(jdbc));
        Optional<String> inert = InertnessCheck.reason(hooks);
        List<Hook> installable = inert.isPresent() ? Collections.<Hook>emptyList() : hooks;
        HookInstaller installer = new HookInstaller(instrumentation, installable, log);
        ManifestWriter manifests = new ManifestWriter(server, log);
        RecordingLifecycle lifecycle = new RecordingLifecycle(active, installer, writer, manifests, runtimeMBeans, inert, log);
        RecordingControl control = new RecordingControl(lifecycle, writer, server, log);
        return new TraceAgent(lifecycle, control, ejb, message, resource, servlet, jdbc);
    }

    private static String describe(ClassLoader loader) {
        return loader == null ? "bootstrap" : loader.toString();
    }
}
