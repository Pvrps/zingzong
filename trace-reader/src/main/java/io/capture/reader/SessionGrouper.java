package io.capture.reader;

import io.capture.trace.RecordingIndex;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lanes for the viewer: traces with an HTTP session id are grouped by it, the rest with a
 * principal by principal, and whatever remains forms one session for the whole recording.
 * Order within a session follows seq.
 */
public final class SessionGrouper {

    private SessionGrouper() {
    }

    public static List<RecordingIndex.Session> group(List<LoadedTrace> traces) {
        Map<String, RecordingIndex.Session> bySession = new LinkedHashMap<>();
        Map<String, RecordingIndex.Session> byPrincipal = new LinkedHashMap<>();
        RecordingIndex.Session rest = null;

        for (LoadedTrace loaded : traces) {
            String httpSessionId = loaded.trace.trace.httpSessionId;
            String principal = loaded.trace.trace.principal;
            RecordingIndex.Session session;
            if (httpSessionId != null && !httpSessionId.isEmpty()) {
                session = bySession.computeIfAbsent(httpSessionId,
                        key -> newSession(RecordingIndex.Session.KIND_HTTP_SESSION, key));
            } else if (principal != null && !principal.isEmpty()) {
                session = byPrincipal.computeIfAbsent(principal,
                        key -> newSession(RecordingIndex.Session.KIND_PRINCIPAL, key));
            } else {
                if (rest == null) {
                    rest = newSession(RecordingIndex.Session.KIND_RECORDING, null);
                }
                session = rest;
            }
            session.traceIds.add(loaded.id());
        }

        List<RecordingIndex.Session> all = new ArrayList<>(bySession.values());
        all.addAll(byPrincipal.values());
        if (rest != null) {
            all.add(rest);
        }
        return all;
    }

    private static RecordingIndex.Session newSession(String kind, String key) {
        RecordingIndex.Session session = new RecordingIndex.Session();
        session.kind = kind;
        session.key = key;
        session.id = key == null ? kind : kind + ":" + key;
        return session;
    }
}
