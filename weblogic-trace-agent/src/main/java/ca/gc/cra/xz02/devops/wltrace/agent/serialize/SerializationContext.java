package ca.gc.cra.xz02.devops.wltrace.agent.serialize;

import ca.gc.cra.xz02.devops.wltrace.model.SerializationWarning;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * The state of serializing one value: the JSON path for warnings, the depth, and the identity
 * table that turns repeated references into {@code @ref}. One context per recorded value, so
 * ids never cross an argument boundary.
 */
public final class SerializationContext {

    /** An object seen during this walk: its node when it is an object, and whether it is still being written. */
    static final class Tracked {
        /** The node for objects; null for collections, maps and arrays, which carry no {@code @id}. */
        final ObjectNode node;
        int id;
        boolean inProgress = true;

        Tracked(ObjectNode node) {
            this.node = node;
        }
    }

    public final SerializationCeilings ceilings;
    private final List<SerializationWarning> warnings = new ArrayList<>();
    private final Deque<String> path = new ArrayDeque<>();
    private final Map<Object, Tracked> seen = new IdentityHashMap<>();
    private int nextId = 1;
    private int depth;

    public SerializationContext(SerializationCeilings ceilings, String rootPath) {
        this.ceilings = ceilings;
        this.path.push(rootPath == null ? "$" : rootPath);
    }

    public List<SerializationWarning> warnings() {
        return warnings;
    }

    public void warn(String reason, String typeName) {
        warnings.add(new SerializationWarning(currentPath(), reason, typeName));
    }

    public String currentPath() {
        StringBuilder builder = new StringBuilder();
        // ArrayDeque.push adds first, so iterate from the tail to rebuild root-first.
        for (Iterator<String> segments = path.descendingIterator(); segments.hasNext(); ) {
            builder.append(segments.next());
        }
        return builder.toString();
    }

    void pushField(String name) {
        path.push("." + name);
    }

    void pushIndex(int index) {
        path.push("[" + index + "]");
    }

    void pushKey(String key) {
        path.push("['" + key + "']");
    }

    void pop() {
        path.pop();
    }

    int depth() {
        return depth;
    }

    void enter() {
        depth++;
    }

    void leave() {
        depth--;
    }

    /** The record of an object seen before in this walk, or null. */
    Tracked seen(Object value) {
        return seen.get(value);
    }

    Tracked track(Object value, ObjectNode node) {
        Tracked tracked = new Tracked(node);
        seen.put(value, tracked);
        return tracked;
    }

    /** Assigns once and returns the id used for {@code @id} and {@code @ref}. */
    int idFor(Tracked tracked) {
        if (tracked.id == 0) {
            tracked.id = nextId++;
        }
        return tracked.id;
    }
}
