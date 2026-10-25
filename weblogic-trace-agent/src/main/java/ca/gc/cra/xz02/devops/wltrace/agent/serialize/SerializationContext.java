package ca.gc.cra.xz02.devops.wltrace.agent.serialize;

import com.fasterxml.jackson.databind.node.ObjectNode;
import ca.gc.cra.xz02.devops.wltrace.model.SerializationWarning;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * State for serializing one value: the JSON path for warnings, the depth, and the
 * identity table that turns repeated references into {@code @ref}. One context per
 * captured value, so ids never cross an argument boundary.
 */
public final class SerializationContext {

    public final SerializerConfig config;
    private final List<SerializationWarning> warnings = new ArrayList<SerializationWarning>();
    private final Deque<String> path = new ArrayDeque<String>();
    private final Map<Object, Tracked> seen = new IdentityHashMap<Object, Tracked>();
    private int nextId = 1;
    private int depth;

    public SerializationContext(SerializerConfig config, String rootPath) {
        this.config = config;
        this.path.push(rootPath == null ? "$" : rootPath);
    }

    public List<SerializationWarning> warnings() {
        return warnings;
    }

    public void warn(String reason, String typeName) {
        warnings.add(new SerializationWarning(currentPath(), reason, typeName));
    }

    public String currentPath() {
        StringBuilder sb = new StringBuilder();
        // ArrayDeque.push adds first, so iterate from the tail to rebuild root-first.
        for (java.util.Iterator<String> it = path.descendingIterator(); it.hasNext(); ) {
            sb.append(it.next());
        }
        return sb.toString();
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

    /** Returns the existing record for an object seen before, or null. */
    Tracked seen(Object value) {
        return seen.get(value);
    }

    Tracked track(Object value, ObjectNode node) {
        Tracked tracked = new Tracked(node);
        seen.put(value, tracked);
        return tracked;
    }

    /** Assigns (once) and returns the id used for {@code @id}/{@code @ref}. */
    int idFor(Tracked tracked) {
        if (tracked.id == 0) {
            tracked.id = nextId++;
        }
        return tracked.id;
    }

    static final class Tracked {
        /** The node for objects; null for collections, maps and arrays (no @id possible). */
        final ObjectNode node;
        int id;
        boolean inProgress = true;

        Tracked(ObjectNode node) {
            this.node = node;
        }
    }
}
