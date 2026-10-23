package io.capture.reader;

import io.capture.trace.RecordingIndex;
import io.capture.trace.RecordingRef;
import io.capture.trace.Trace;
import io.capture.trace.TraceInfo;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SessionGrouperTest {

    @Test
    void httpSessionThenPrincipalThenWholeRecording() {
        List<RecordingIndex.Session> sessions = SessionGrouper.group(Arrays.asList(
                trace(0, "t1", "S1", "ada"),
                trace(1, "t2", null, "bob"),
                trace(2, "t3", "S1", "ada"),
                trace(3, "t4", null, null),
                trace(4, "t5", "S2", null),
                trace(5, "t6", null, "bob")));

        assertEquals(4, sessions.size());
        assertEquals("httpSession:S1", sessions.get(0).id);
        assertEquals(Arrays.asList("t1", "t3"), sessions.get(0).traceIds);
        assertEquals("httpSession:S2", sessions.get(1).id);
        assertEquals("principal:bob", sessions.get(2).id);
        assertEquals(Arrays.asList("t2", "t6"), sessions.get(2).traceIds);
        assertEquals(RecordingIndex.Session.KIND_RECORDING, sessions.get(3).kind);
        assertEquals("recording", sessions.get(3).id);
        assertNull(sessions.get(3).key);
        assertEquals(Arrays.asList("t4"), sessions.get(3).traceIds);
    }

    @Test
    void noSessionInformationMeansOneLane() {
        List<RecordingIndex.Session> sessions = SessionGrouper.group(Arrays.asList(
                trace(0, "t1", null, null), trace(1, "t2", null, null)));
        assertEquals(1, sessions.size());
        assertEquals(Arrays.asList("t1", "t2"), sessions.get(0).traceIds);
    }

    private static LoadedTrace trace(int index, String id, String session, String principal) {
        Trace trace = new Trace();
        trace.recording = new RecordingRef();
        trace.recording.seq = index + 1;
        trace.trace = new TraceInfo();
        trace.trace.id = id;
        trace.trace.httpSessionId = session;
        trace.trace.principal = principal;
        return new LoadedTrace(Paths.get(id + ".json"), trace, index);
    }
}
