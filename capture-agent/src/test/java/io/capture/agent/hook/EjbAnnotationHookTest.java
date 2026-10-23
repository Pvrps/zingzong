package io.capture.agent.hook;

import io.capture.agent.AgentConfig;
import io.capture.agent.AgentLog;
import com.acme.fixtures.PlainFixture;
import com.acme.fixtures.SingletonFixture;
import com.acme.fixtures.StatelessFixture;
import io.capture.agent.span.Recorder;
import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EjbAnnotationHookTest {

    @TempDir
    static File out;

    private static Instrumentation instrumentation;
    private static ResettableClassFileTransformer transformer;

    @BeforeAll
    static void installHook() {
        // Load a fixture first so the retransformation path is exercised too.
        new SingletonFixture().count(0);

        instrumentation = ByteBuddyAgent.install();
        AgentConfig config = AgentConfig.parse("out=" + out.getAbsolutePath());
        AgentLog.init(config.outDir);
        Recorder.configure(config);
        transformer = EjbAnnotationHook.install(instrumentation, config);
    }

    @AfterAll
    static void removeHook() {
        Recorder.stop();
        if (transformer != null) {
            transformer.reset(instrumentation, net.bytebuddy.agent.builder.AgentBuilder.RedefinitionStrategy.RETRANSFORMATION);
        }
    }

    @BeforeEach
    void recordingOff() {
        Recorder.stop();
    }

    @Test
    void logsNothingWhileRecordingIsOff() throws Exception {
        int before = eventLines().size();
        new StatelessFixture().greet("x");
        new SingletonFixture().count(1);
        assertEquals(before, eventLines().size());
    }

    @Test
    void logsEntryAndExitOfPublicInstanceMethodsOfEjbClasses() throws Exception {
        Recorder.start("test");
        int before = eventLines().size();

        assertEquals("hello x", new StatelessFixture().greet("x"));
        assertEquals(2, new SingletonFixture().count(1));
        assertEquals("plain", new PlainFixture().work());
        StatelessFixture.ignoredStatic();

        List<String> lines = eventLines().subList(before, eventLines().size());
        assertEquals(4, lines.size(), String.valueOf(lines));
        assertTrue(lines.get(0).contains("ENTER com.acme.fixtures.StatelessFixture.greet(java.lang.String) thread=\""));
        assertTrue(lines.get(1).contains("EXIT  com.acme.fixtures.StatelessFixture.greet "));
        assertTrue(lines.get(2).contains("ENTER com.acme.fixtures.SingletonFixture.count(int)"));
        assertTrue(lines.get(3).contains("EXIT  com.acme.fixtures.SingletonFixture.count "));
        assertFalse(String.valueOf(lines).contains("PlainFixture"));
        assertFalse(String.valueOf(lines).contains("ignoredStatic"));
    }

    @Test
    void writesOneTraceFilePerSpanWithArgsAndResult() throws Exception {
        io.capture.agent.span.Recording recording = Recorder.start("files");
        new StatelessFixture().greet("world");

        File dir = new File(out, recording.id);
        File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
        assertTrue(files != null && files.length >= 1, "expected a trace file in " + dir);
        io.capture.trace.Trace trace = io.capture.trace.TraceJson.read(new java.io.FileInputStream(files[files.length - 1]));
        io.capture.trace.Span span = trace.spans.get(0);
        assertEquals("EJB", span.kind);
        assertEquals(StatelessFixture.class.getName(), span.ejb.beanClass);
        assertEquals("greet", span.ejb.method);
        assertEquals("java.lang.String greet(java.lang.String)", span.ejb.signature);
        assertEquals("world", span.args.get(0).value.asText());
        assertEquals("java.lang.String", span.args.get(0).declaredType);
        assertEquals("hello world", span.result.value.asText());
        assertEquals(recording.id, trace.recording.id);
    }

    @Test
    void exceptionsPassThroughAndAreNoted() throws Exception {
        Recorder.start("test");
        int before = eventLines().size();

        assertThrows(IllegalStateException.class, () -> new StatelessFixture().fail());

        List<String> lines = eventLines().subList(before, eventLines().size());
        assertEquals(2, lines.size(), String.valueOf(lines));
        assertTrue(lines.get(1).contains("EXIT  com.acme.fixtures.StatelessFixture.fail "));
        assertTrue(lines.get(1).contains("threw=java.lang.IllegalStateException"));
    }

    @Test
    void reportsInstrumentedTypesInTheLog() throws Exception {
        // Loaded after the hook was installed: instrumented on load, not retransformed.
        Class.forName(StatelessFixture.class.getName());
        new PlainFixture().work();
        String log = new String(Files.readAllBytes(AgentLog.file().toPath()), StandardCharsets.UTF_8);
        assertTrue(log.contains("instrumented com.acme.fixtures.StatelessFixture"), log);
        assertTrue(log.contains("instrumented com.acme.fixtures.SingletonFixture (retransformed)"), log);
        assertFalse(log.contains("instrumented com.acme.fixtures.PlainFixture"), log);
        assertFalse(log.contains("ERROR"), log);
    }

    private static List<String> eventLines() throws Exception {
        List<String> events = new ArrayList<>();
        for (String line : Files.readAllLines(AgentLog.file().toPath(), StandardCharsets.UTF_8)) {
            if (line.contains(" EVENT ")) {
                events.add(line);
            }
        }
        return events;
    }
}
