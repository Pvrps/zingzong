package io.capture.reader;

import io.capture.trace.RecordingIndex;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lanes for the viewer: traces with an HTTP session id are grouped by it, the rest with a
 * principal by principal, and whatever remains forms one session for the whole recording.
 * A trace another trace caused ({@code parentTraceId}: an {@code @Asynchronous} call, a JMS
 * message to an MDB) joins its parent's lane, right after the parent, however it arrived.
 * Otherwise order within a session follows seq.
 */
public final class SessionGrouper {

    private SessionGrouper() {
    }

    public static List<RecordingIndex.Session> group(List<LoadedTrace> traces) {
        Map<String, RecordingIndex.Session> bySession = new LinkedHashMap<>();
        Map<String, RecordingIndex.Session> byPrincipal = new LinkedHashMap<>();
        Map<String, RecordingIndex.Session> laneOf = new HashMap<>();
        Map<String, LoadedTrace> byId = new HashMap<>();
        RecordingIndex.Session rest = null;
        List<LoadedTrace> children = new ArrayList<>();

        for (LoadedTrace loaded : traces) {
            byId.put(loaded.id(), loaded);
            if (loaded.trace.trace.parentTraceId != null) {
                children.add(loaded);
                continue;
            }
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
            laneOf.put(loaded.id(), session);
        }

        // Children after their parents; a chain (child of a child) resolves in passes.
        List<LoadedTrace> pending = new ArrayList<>(children);
        boolean progress = true;
        while (!pending.isEmpty() && progress) {
            progress = false;
            for (LoadedTrace child : new ArrayList<>(pending)) {
                RecordingIndex.Session lane = laneOf.get(child.trace.trace.parentTraceId);
                if (lane == null) {
                    continue;
                }
                int at = lane.traceIds.indexOf(child.trace.trace.parentTraceId);
                int insert = at + 1;
                while (insert < lane.traceIds.size() && isDescendantOf(byId, lane.traceIds.get(insert), child.trace.trace.parentTraceId)) {
                    insert++;
                }
                lane.traceIds.add(insert, child.id());
                laneOf.put(child.id(), lane);
                pending.remove(child);
                progress = true;
            }
        }
        // A child whose parent is not in this recording (it was caused before the recording started) stands alone.
        for (LoadedTrace orphan : pending) {
            if (rest == null) {
                rest = newSession(RecordingIndex.Session.KIND_RECORDING, null);
            }
            rest.traceIds.add(orphan.id());
        }

        List<RecordingIndex.Session> all = new ArrayList<>(bySession.values());
        all.addAll(byPrincipal.values());
        if (rest != null) {
            all.add(rest);
        }
        return all;
    }

    private static boolean isDescendantOf(Map<String, LoadedTrace> byId, String id, String ancestor) {
        LoadedTrace t = byId.get(id);
        while (t != null && t.trace.trace.parentTraceId != null) {
            if (t.trace.trace.parentTraceId.equals(ancestor)) {
                return true;
            }
            t = byId.get(t.trace.trace.parentTraceId);
        }
        return false;
    }

    private static RecordingIndex.Session newSession(String kind, String key) {
        RecordingIndex.Session session = new RecordingIndex.Session();
        session.kind = kind;
        session.key = key;
        session.id = key == null ? kind : kind + ":" + key;
        return session;
    }
}
