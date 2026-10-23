package io.capture.agent.hook;

import com.acme.fixtures.OuterFixture;
import com.acme.fixtures.PlainFixture;
import com.acme.fixtures.SingletonFixture;
import com.acme.fixtures.StatelessFixture;
import io.capture.agent.AgentConfig;
import io.capture.agent.AgentLog;
import io.capture.agent.span.Recorder;
import io.capture.agent.span.Recording;
import io.capture.trace.Span;
import io.capture.trace.Trace;
import io.capture.trace.TraceJson;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileInputStream;
import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaptureHookTest {

    @TempDir
    static File out;

    private static Instrumentation instrumentation;
    private static CaptureHook hook;

    @BeforeAll
    static void installHook() {
        // Load fixtures first so start() has to retransform already-loaded classes.
        new SingletonFixture().count(0);
        new StatelessFixture().greet("warm-up");
        new OuterFixture().run("warm-up");
        new PlainFixture().work();

        instrumentation = ByteBuddyAgent.install();
        AgentConfig config = AgentConfig.parse("out=" + out.getAbsolutePath());
        AgentLog.init(config.outDir);
        Recorder.configure(config);
        hook = new CaptureHook(instrumentation, config, CaptureHook.EjbStrategy.ANNOTATION);
        Recorder.hook(hook);
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
    void recordsNothingWhileRecordingIsOffAndNoAdviceIsInstalled() throws Exception {
        assertFalse(hook.isInstalled(), "stop() restores the original bytecode");
        long logBefore = AgentLog.file().length();
        File[] dirsBefore = recordingDirs();
        new StatelessFixture().greet("x");
        new SingletonFixture().count(1);
        new OuterFixture().run("x");
        assertEquals(logBefore, AgentLog.file().length(), "agent.log must not grow");
        assertTrue(Arrays.equals(dirsBefore, recordingDirs()), "no recording directory appears");
    }

    @Test
    void startInstallsAndStopRemovesTheHook() throws Exception {
        Recording first = Recorder.start("lifecycle-1");
        assertTrue(hook.isInstalled());
        new StatelessFixture().greet("a");
        Recorder.stop();
        assertFalse(hook.isInstalled());
        new StatelessFixture().greet("not recorded");

        Recording second = Recorder.start("lifecycle-2");
        new StatelessFixture().greet("b");
        Recorder.stop();
        assertTrue(Recorder.flush(5000));

        assertEquals(1, traces(first).size());
        assertEquals("a", traces(first).get(0).spans.get(0).args.get(0).value.asText());
        assertEquals(1, traces(second).size());
        assertEquals("b", traces(second).get(0).spans.get(0).args.get(0).value.asText());

        io.capture.trace.RecordingManifest manifest;
        try (FileInputStream in = new FileInputStream(new File(new File(out, second.id), "recording.json"))) {
            manifest = TraceJson.read(in, io.capture.trace.RecordingManifest.class);
        }
        assertEquals(second.id, manifest.id);
        assertEquals("lifecycle-2", manifest.name);
        assertTrue(manifest.startedAt != null && manifest.endedAt != null, "start and end time");
        assertEquals(Long.valueOf(1), manifest.traceCount);
        assertEquals(Long.valueOf(0), manifest.droppedTraces);
        assertEquals("annotation", manifest.agent.hook);
        assertFalse("unknown".equals(manifest.agent.version), "version comes from build.properties: " + manifest.agent.version);
        assertNull(manifest.filters, "no filters unless given");
    }

    @Test
    void principalFilterKeepsOtherTrafficOut() throws Exception {
        // Outside WebLogic the principal resolves to null, so a filter for "weblogic" rejects every root span.
        Recording filtered = Recorder.start("filtered", "weblogic", null);
        new StatelessFixture().greet("ignored");
        Recorder.stop();
        Recording open = Recorder.start("open", null, null);
        new StatelessFixture().greet("kept");
        Recorder.stop();
        assertTrue(Recorder.flush(5000));

        assertEquals(0, traces(filtered).size());
        assertEquals(1, traces(open).size());
        io.capture.trace.RecordingManifest manifest;
        try (FileInputStream in = new FileInputStream(new File(new File(out, filtered.id), "recording.json"))) {
            manifest = TraceJson.read(in, io.capture.trace.RecordingManifest.class);
        }
        assertEquals("weblogic", manifest.filters.principal);
        assertNull(manifest.filters.httpSessionId);
    }

    @Test
    void oneTraceFilePerRootCallWithArgsResultAndSignature() throws Exception {
        Recording recording = Recorder.start("files");
        assertEquals("hello world", new StatelessFixture().greet("world"));
        assertEquals(2, new SingletonFixture().count(1));
        assertEquals("plain", new PlainFixture().work());
        StatelessFixture.ignoredStatic();
        assertTrue(Recorder.flush(5000));

        List<Trace> traces = traces(recording);
        assertEquals(2, traces.size(), "one trace per root call: greet and count");
        Span greet = traces.get(0).spans.get(0);
        assertEquals("EJB", greet.kind);
        assertEquals(StatelessFixture.class.getName(), greet.ejb.beanClass);
        assertEquals("greet", greet.ejb.method);
        assertEquals("java.lang.String greet(java.lang.String)", greet.ejb.signature);
        assertEquals("world", greet.args.get(0).value.asText());
        assertEquals("java.lang.String", greet.args.get(0).declaredType);
        assertEquals("hello world", greet.result.value.asText());
        assertTrue(greet.thread != null && !greet.thread.isEmpty());
        assertEquals(1, traces.get(0).recording.seq);
        assertEquals(2, traces.get(1).recording.seq);
        assertEquals("count", traces.get(1).spans.get(0).ejb.method);
        assertNull(traces.get(1).spans.get(0).selfInvocation);
    }

    @Test
    void nestedCallsFormOneTreeWithSelfInvocationsFlagged() throws Exception {
        Recording recording = Recorder.start("tree");
        assertEquals("x+!!+", new OuterFixture().run("x"));
        assertTrue(Recorder.flush(5000));

        List<Trace> traces = traces(recording);
        assertEquals(1, traces.size(), "nested calls produce one file, got: " + describe(traces));
        List<Span> spans = traces.get(0).spans;
        assertEquals(5, spans.size());

        Span run = spans.get(0);
        assertEquals("run", run.ejb.method);
        assertNull(run.parentId);
        assertEquals(0, run.depth);
        assertNull(run.selfInvocation);
        assertEquals("x+!!+", run.result.value.asText());

        assertEquals(Arrays.asList("run", "helper", "work", "work", "helper"), methods(spans));
        for (Span child : spans.subList(1, 5)) {
            assertEquals(run.id, child.parentId);
            assertEquals(1, child.depth);
            assertTrue(child.startNs >= run.startNs);
            assertTrue(child.startNs + child.durationNs <= run.startNs + run.durationNs + 1_000_000L);
        }
        assertEquals(Boolean.TRUE, spans.get(1).selfInvocation, "helper is a self-invocation of OuterFixture");
        assertNull(spans.get(2).selfInvocation, "InnerFixture.work is a different bean");
        assertEquals(Boolean.TRUE, spans.get(4).selfInvocation);
        for (int i = 0; i < spans.size(); i++) {
            assertEquals(i, spans.get(i).order);
            assertEquals("s" + (i + 1), spans.get(i).id);
        }
    }

    @Test
    void exceptionsPassThroughAndCompleteTheTrace() throws Exception {
        Recording recording = Recorder.start("exceptions");
        assertThrows(IllegalStateException.class, () -> new OuterFixture().explode());
        assertTrue(Recorder.flush(5000));

        List<Trace> traces = traces(recording);
        assertEquals(1, traces.size());
        Span root = traces.get(0).spans.get(0);
        assertEquals("explode", root.ejb.method);
        assertEquals("java.lang.IllegalStateException", root.exception.className);
        assertEquals("outer exploded", root.exception.message);
        assertNull(root.result);
        assertEquals("work", traces.get(0).spans.get(1).ejb.method);
        assertEquals(0, Recorder.droppedTraces());
    }

    @Test
    void reportsInstrumentedTypesInTheLog() throws Exception {
        Recording recording = Recorder.start("log");
        Class<?> late = Class.forName("com.acme.fixtures.LateFixture");
        assertEquals(4, late.getMethod("twice", int.class).invoke(late.getDeclaredConstructor().newInstance(), 2));
        new PlainFixture().work();
        assertTrue(Recorder.flush(5000));

        String log = new String(Files.readAllBytes(AgentLog.file().toPath()), StandardCharsets.UTF_8);
        assertTrue(log.contains("instrumented com.acme.fixtures.StatelessFixture (retransformed)"), log);
        assertTrue(log.contains("instrumented com.acme.fixtures.SingletonFixture (retransformed)"), log);
        assertTrue(log.contains("instrumented com.acme.fixtures.LateFixture loader="), "transformed on load: " + log);
        assertFalse(log.contains("instrumented com.acme.fixtures.PlainFixture"), log);
        assertFalse(log.contains("ERROR"), log);
        assertEquals("twice", traces(recording).get(0).spans.get(0).ejb.method);
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
        assertTrue(files != null, "recording dir missing");
        Arrays.sort(files);
        List<Trace> traces = new ArrayList<>();
        for (File f : files) {
            try (FileInputStream in = new FileInputStream(f)) {
                traces.add(TraceJson.read(in));
            }
        }
        return traces;
    }

    private static String describe(List<Trace> traces) {
        StringBuilder sb = new StringBuilder();
        for (Trace t : traces) {
            sb.append(t.trace.id).append(methods(t.spans)).append(' ');
        }
        return sb.toString();
    }

    private static List<String> methods(List<Span> spans) {
        List<String> names = new ArrayList<>();
        for (Span s : spans) {
            names.add(s.ejb.method);
        }
        return names;
    }
}
