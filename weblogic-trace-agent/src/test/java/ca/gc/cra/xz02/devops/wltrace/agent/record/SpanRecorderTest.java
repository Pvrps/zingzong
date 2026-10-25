package ca.gc.cra.xz02.devops.wltrace.agent.record;

import ca.gc.cra.xz02.devops.wltrace.agent.bootstrap.AgentLog;
import ca.gc.cra.xz02.devops.wltrace.agent.serialize.JacksonGraphSerializer;
import ca.gc.cra.xz02.devops.wltrace.agent.serialize.SerializationCeilings;
import ca.gc.cra.xz02.devops.wltrace.agent.serialize.TypeInfoCache;
import ca.gc.cra.xz02.devops.wltrace.agent.weblogic.RuntimeMBeans;
import ca.gc.cra.xz02.devops.wltrace.agent.weblogic.ServerRuntime;
import ca.gc.cra.xz02.devops.wltrace.agent.weblogic.WorkContextPropagation;
import ca.gc.cra.xz02.devops.wltrace.model.Span;
import ca.gc.cra.xz02.devops.wltrace.model.Trace;
import ca.gc.cra.xz02.devops.wltrace.model.TraceJson;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

/**
 * Proves the shape every recorder shares, once, through a stub subclass: the guard on the active
 * recording, nesting into one trace, serialization of arguments, result and exception under the
 * facts' signature, and a stack abandoned when exit does not match enter.
 */
public class SpanRecorderTest {

    /** The facts of a stub call: a name and the method whose signature types the values. */
    static final class StubFacts extends SpanFacts {
        final String name;
        final MethodSignature signature;

        StubFacts(String name, MethodSignature signature) {
            this.name = name;
            this.signature = signature;
        }

        @Override
        public MethodSignature signature() {
            return signature;
        }

        @Override
        public void describe(Span span) {
            span.app = name;
        }

        @Override
        public String summary() {
            return name;
        }
    }

    /** A recorder whose facts are the first enter argument's text; a null first argument is an unrecognised call. */
    static final class StubRecorder extends SpanRecorder<StubFacts> {
        StubRecorder(RecorderContext context) {
            super(context);
        }

        @Override
        public String kind() {
            return "STUB";
        }

        @Override
        protected StubFacts readFacts(Object first, Object second, Object third) {
            if (first == null) {
                return null;
            }
            MethodSignature signature = second instanceof MethodSignature ? (MethodSignature) second : MethodSignature.unknown(first.toString(), "");
            return new StubFacts(first.toString(), signature);
        }
    }

    private File out;
    private ActiveRecording active;
    private TraceWriter writer;
    private StubRecorder recorder;

    @BeforeClass
    public void assemble() throws Exception {
        out = Files.createTempDirectory("span-recorder-test").toFile();
        AgentLog log = AgentLog.open(out);
        active = new ActiveRecording();
        writer = new TraceWriter(out, 100, log);
        ServerRuntime server = new ServerRuntime(log);
        RecorderContext context = new RecorderContext(active, new SpanStacks(),
                new JacksonGraphSerializer(new SerializationCeilings(), new TypeInfoCache()), new SerializationCeilings(),
                server, new RuntimeMBeans(log), writer, WorkContextPropagation.create(log), log);
        recorder = new StubRecorder(context);
    }

    @BeforeMethod
    public void noRecording() {
        active.replace(null);
    }

    @Test
    public void entersNothingWhileNoRecordingIsActive() {
        long writtenBefore = writer.written();
        assertEquals(recorder.enter("call", null, null), SpanRecorder.NOT_RECORDED);
        recorder.exit(SpanRecorder.NOT_RECORDED, null, null, null);
        assertTrue(writer.flush(5000));
        assertEquals(writer.written(), writtenBefore);
    }

    @Test
    public void entersNothingWhenTheFactsAreNotRecognised() {
        active.replace(new Recording("rec-unrecognised", "unrecognised"));
        assertEquals(recorder.enter(null, null, null), SpanRecorder.NOT_RECORDED);
    }

    @Test
    public void nestedEntersFormOneTraceWithArgumentsAndResultsSerializedUnderTheSignature() throws Exception {
        Recording recording = new Recording("rec-nested", "nested");
        active.replace(recording);
        MethodSignature signature = MethodSignature.of(Greeting.class.getMethod("greet", String.class, int.class));
        long outer = recorder.enter("outer", signature, null);
        long inner = recorder.enter("inner", signature, null);
        assertTrue(outer != SpanRecorder.NOT_RECORDED && inner != SpanRecorder.NOT_RECORDED && outer != inner);
        recorder.exit(inner, new Object[]{"world", 2}, "hello world", null);
        recorder.exit(outer, new Object[]{"all", 1}, "hello all", null);
        assertTrue(writer.flush(5000));

        Trace trace = onlyTrace(recording);
        assertEquals(trace.spans.size(), 2);
        Span root = trace.spans.get(0);
        Span child = trace.spans.get(1);
        assertEquals(root.kind, "STUB");
        assertEquals(root.app, "outer");
        assertNull(root.parentId);
        assertEquals(child.parentId, root.id);
        assertEquals(child.depth, 1);
        assertEquals(child.args.get(0).declaredType, "java.lang.String");
        assertEquals(child.args.get(1).declaredType, "int");
        assertEquals(child.args.get(1).value.asInt(), 2);
        assertEquals(child.result.declaredType, "java.lang.String");
        assertEquals(child.result.value.asText(), "hello world");
        assertNull(child.exception);
        assertNull(child.serializationWarnings, "plain values produce no warnings");
    }

    @Test
    public void exceptionsReplaceTheResultAndVoidMethodsHaveNone() throws Exception {
        Recording recording = new Recording("rec-exception", "exception");
        active.replace(recording);
        long failing = recorder.enter("failing", MethodSignature.of(Greeting.class.getMethod("greet", String.class, int.class)), null);
        recorder.exit(failing, new Object[]{"x", 1}, null, new IllegalStateException("boom"));
        long silent = recorder.enter("silent", MethodSignature.of(Greeting.class.getMethod("wave")), null);
        recorder.exit(silent, new Object[0], null, null);
        assertTrue(writer.flush(5000));

        List<Trace> traces = traces(recording);
        assertEquals(traces.size(), 2, "each root call is its own trace");
        Span failed = traces.get(0).spans.get(0);
        assertEquals(failed.exception.className, "java.lang.IllegalStateException");
        assertEquals(failed.exception.message, "boom");
        assertEquals(failed.exception.value.get("detailMessage").asText(), "boom");
        assertNull(failed.result);
        assertNull(traces.get(1).spans.get(0).result, "void: no result");
    }

    @Test
    public void anExitThatDoesNotMatchTheOpenSpanAbandonsTheThreadsStack() throws Exception {
        Recording recording = new Recording("rec-mismatch", "mismatch");
        active.replace(recording);
        long opened = recorder.enter("opened", null, null);
        recorder.exit(opened + 1000, null, null, null);
        assertTrue(writer.flush(5000));
        assertEquals(traces(recording).size(), 0, "the mismatched exit writes nothing");

        long fresh = recorder.enter("fresh", null, null);
        recorder.exit(fresh, null, "ok", null);
        assertTrue(writer.flush(5000));
        Trace trace = onlyTrace(recording);
        assertEquals(trace.spans.size(), 1, "the abandoned span is gone; the next root starts clean");
        assertNull(trace.spans.get(0).parentId);
    }

    @Test
    public void aSpanEnteredBeforeStopIsStillWrittenWhenItExits() throws Exception {
        Recording first = new Recording("rec-first", "first");
        active.replace(first);
        long token = recorder.enter("during-first", null, null);
        active.replace(null);
        recorder.exit(token, null, "late", null);
        assertTrue(writer.flush(5000));
        assertEquals(traces(first).size(), 1, "a span entered under the recording is still written when it exits");
        assertEquals(onlyTrace(first).spans.get(0).app, "during-first");
    }

    /** The method whose signature types the stub's values. */
    interface Greeting {
        String greet(String name, int times);

        void wave();
    }

    private Trace onlyTrace(Recording recording) throws Exception {
        List<Trace> traces = traces(recording);
        assertEquals(traces.size(), 1, "exactly one trace expected");
        return traces.get(0);
    }

    private List<Trace> traces(Recording recording) throws Exception {
        File[] files = new File(out, recording.id).listFiles((directory, name) -> name.matches("\\d{6}-t-.*\\.json"));
        if (files == null) {
            return new ArrayList<>();
        }
        Arrays.sort(files);
        List<Trace> traces = new ArrayList<>();
        for (File file : files) {
            try (InputStream in = Files.newInputStream(file.toPath())) {
                traces.add(TraceJson.read(in));
            }
        }
        assertNotNull(traces);
        return traces;
    }
}
