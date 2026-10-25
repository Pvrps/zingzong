package io.capture.agent.serialize;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.persistence.indirection.IndirectList;
import org.eclipse.persistence.indirection.ValueHolderInterface;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The raw-capture rule, checked mechanically: an object containing every category the rule
 * names is serialized, then every instance field of every walked object is looked up in the
 * output. A field may be absent only if the object's {@code @skipped} list names it with a
 * reason, and every value that is not written as it was carries a marker saying why.
 */
class RawCaptureAuditTest {

    enum Color { RED, GREEN }

    static class Cycle implements Serializable {
        private static final long serialVersionUID = 1L;
        String name;
        Cycle other;
    }

    static class LazyOwner implements Serializable {
        private static final long serialVersionUID = 1L;
        List<String> lines;
        // EclipseLink weaving shape: the holder backs the field next to it.
        String city;
        ValueHolderInterface _persistence_city_vh;
        Object _persistence_fetchGroup = "fg";
    }

    class Inner implements Serializable {
        private static final long serialVersionUID = 1L;
        String tag = "inner";   // the compiler adds this$0
    }

    static class NotSerializable {
        String secret = "kept, not masked";
        transient String cache = "transient fields are walked too";
    }

    static class Everything implements Serializable {
        private static final long serialVersionUID = 1L;
        Cycle cycle = new Cycle();
        Cycle repeated;
        LazyOwner lazy = new LazyOwner();
        Thread thread = Thread.currentThread();
        ClassLoader loader = Everything.class.getClassLoader();
        java.io.InputStream stream = new java.io.ByteArrayInputStream(new byte[]{1});
        java.sql.Connection connection = (java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(
                Everything.class.getClassLoader(), new Class<?>[]{java.sql.Connection.class}, (p, m, a) -> m.getName().equals("toString") ? "conn" : null);
        ReentrantLock lock = new ReentrantLock();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Logger logger = Logger.getLogger("audit");
        NotSerializable notSerializable = new NotSerializable();
        Inner inner;
        String password = "hunter2";
        String authorizationHeader = "Bearer abc.def.ghi";
        String longText;
        byte[] bigBytes;
        List<Integer> bigList = new ArrayList<>();
        Cycle deep;
        Optional<String> maybe = Optional.of("x");
        Optional<String> none = Optional.empty();
        RuntimeException failure = new IllegalStateException("boom", new IllegalArgumentException("root"));
        Color color = Color.GREEN;
        BigDecimal amount = new BigDecimal("12.500");
        Timestamp timestamp = Timestamp.from(Instant.parse("2026-10-23T18:00:00.123456789Z"));
        Calendar calendar = new GregorianCalendar(TimeZone.getTimeZone("Europe/Amsterdam"));
        Map<Object, String> objectKeys = new java.util.HashMap<>();
        int[] ints = {1, 2, 3};
        Object monitor = new Object();
    }

    private final SerializerConfig config = new SerializerConfig()
            .withMaxStringLength(40).withMaxBytes(8).withMaxElements(5).withMaxDepth(6);

    @Test
    void everyFieldIsPresentOrListedAsSkippedAndEveryTransformationIsMarked() throws Exception {
        Everything e = build();
        try {
            Graphs graphs = new Graphs(config);
            Graphs.Result r = graphs.write(e);
            JsonNode root = r.node;

            // Nothing dropped silently: walk the object graph and the JSON side by side.
            audit(e, root, "$", new IdentityHashMap<>());

            // Each category produced its marker.
            assertEquals(root.get("cycle").get("@id").asInt(), root.get("cycle").get("other").get("@ref").asInt(), "cycle -> @ref");
            assertEquals(root.get("cycle").get("@id").asInt(), root.get("repeated").get("@ref").asInt(), "repeated -> @ref");
            assertTrue(root.get("lazy").get("lines").get("@lazy").asBoolean(), "lazy collection");
            assertTrue(root.get("lazy").get("city").get("@lazy").asBoolean(), "lazy weaving holder");
            for (String handle : new String[]{"thread", "loader", "stream", "connection", "lock", "executor"}) {
                assertTrue(root.get(handle).has("@handle"), handle + " is a handle: " + root.get(handle));
                assertTrue(root.get(handle).has("identity"), handle);
            }
            assertEquals("java.util.logging.Logger", root.get("logger").get("@type").asText(), "loggers are walked");
            assertEquals("hunter2", root.get("password").asText(), "no masking");
            assertEquals("Bearer abc.def.ghi", root.get("authorizationHeader").asText(), "no redaction");
            assertEquals("kept, not masked", root.get("notSerializable").get("secret").asText());
            assertFalse(root.get("notSerializable").get("@serializable").asBoolean());
            assertEquals("transient fields are walked too", root.get("notSerializable").get("cache").asText());
            assertSkipped(root.get("inner"), "this$0", "compiler-injected");
            assertSkipped(root.get("lazy"), "_persistence_city_vh", "weaver-injected");
            assertSkipped(root.get("lazy"), "_persistence_fetchGroup", "weaver-injected");
            assertEquals(24, root.get("longText").get("@truncated").asInt(), "string ceiling counted");
            assertEquals(64, root.get("longText").get("length").asInt());
            assertEquals(8, root.get("bigBytes").get("@truncated").asInt(), "byte ceiling counted");
            assertEquals(16, root.get("bigBytes").get("length").asInt());
            assertEquals(15, root.get("bigList").get(5).get("@truncated").asInt(), "element ceiling counted");
            JsonNode deepest = root.get("deep");
            int depth = 0;
            while (deepest.has("other") && deepest.get("other").isObject() && !deepest.get("other").has("@unserializable")) {
                deepest = deepest.get("other");
                depth++;
            }
            assertTrue(deepest.get("other").get("reason").asText().startsWith("depth ceiling"), "depth ceiling marked at " + depth + ": " + deepest);
            assertTrue(root.get("maybe").get("present").asBoolean());
            assertFalse(root.get("none").get("present").asBoolean());
            assertEquals("boom", root.get("failure").get("detailMessage").asText());
            assertEquals("root", root.get("failure").get("cause").get("detailMessage").asText());
            assertTrue(root.get("failure").get("stackTrace").size() > 0);
            assertEquals("GREEN", root.get("color").asText());
            assertEquals("12.500", root.get("amount").asText(), "scale kept");
            assertEquals("2026-10-23T18:00:00.123456789Z", root.get("timestamp").asText(), "nanoseconds kept");
            assertTrue(root.get("calendar").asText().endsWith("[Europe/Amsterdam]"), "zone kept: " + root.get("calendar"));
            assertEquals("java.util.HashMap", root.get("objectKeys").get("@type").asText());
            assertEquals(3, root.get("ints").size());
            assertEquals("java.lang.Object", root.get("monitor").get("@type").asText());

            // Every warning corresponds to a marker in the output, i.e. nothing was dropped quietly.
            for (io.capture.trace.SerializationWarning w : r.warnings) {
                JsonNode at = at(root, w.path);
                assertTrue(at != null && isMarked(at), "warning without marker at " + w.path + ": " + w.reason);
            }
        } finally {
            e.executor.shutdownNow();
        }
    }

    private static Everything build() {
        Everything e = new Everything();
        e.cycle.name = "a";
        e.cycle.other = e.cycle;
        e.repeated = e.cycle;
        e.lazy.lines = unloadedList();
        e.lazy._persistence_city_vh = unloadedHolder();
        e.inner = new RawCaptureAuditTest().new Inner();
        e.longText = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        e.bigBytes = new byte[16];
        for (int i = 0; i < 20; i++) {
            e.bigList.add(i);
        }
        Cycle chain = new Cycle();
        chain.name = "d0";
        Cycle cursor = chain;
        for (int i = 1; i < 12; i++) {
            Cycle next = new Cycle();
            next.name = "d" + i;
            cursor.other = next;
            cursor = next;
        }
        e.deep = chain;
        e.objectKeys.put(Arrays.asList(1, 2), "v");
        e.calendar.setTimeInMillis(1_800_000_000_000L);
        return e;
    }

    /** Every non-static declared field of {@code value}'s class hierarchy must be a key or a @skipped entry. */
    private static void audit(Object value, JsonNode node, String path, IdentityHashMap<Object, Boolean> seen) throws Exception {
        if (value == null || node == null || node.isNull() || isMarked(node) || seen.containsKey(value)) {
            return;
        }
        if (isScalar(value) || value instanceof Optional || value instanceof Map
                || value instanceof Iterable || value.getClass().isArray()) {
            return;
        }
        if (!value.getClass().getName().startsWith(RawCaptureAuditTest.class.getName())) {
            return; // JDK and library objects are walked by the same code; the fixture classes are the audit subjects
        }
        seen.put(value, Boolean.TRUE);
        assertTrue(node.isObject() && node.has("@type"), path + " should be a walked object: " + node);
        Set<String> skipped = new HashSet<>();
        if (node.has("@skipped")) {
            for (JsonNode s : node.get("@skipped")) {
                assertTrue(s.has("reason") && !s.get("reason").asText().isEmpty(), path + ": skipped without reason");
                skipped.add(s.get("field").asText());
            }
        }
        for (Class<?> c = value.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                String name = f.getName();
                if (!node.has(name)) {
                    if (!skipped.contains(name)) {
                        fail(path + "." + name + " is absent without a @skipped entry: " + node);
                    }
                    continue;
                }
                f.setAccessible(true);
                audit(f.get(value), node.get(name), path + "." + name, seen);
            }
        }
    }

    private static boolean isMarked(JsonNode node) {
        if (!node.isObject()) {
            return false;
        }
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            String n = names.next();
            if (n.equals("@ref") || n.equals("@lazy") || n.equals("@handle") || n.equals("@unserializable")
                    || n.equals("@truncated") || n.equals("@text") || n.equals("@bytes")) {
                return true;
            }
        }
        return false;
    }

    private static void assertSkipped(JsonNode node, String field, String reasonPart) {
        for (JsonNode s : node.get("@skipped")) {
            if (s.get("field").asText().equals(field)) {
                assertTrue(s.get("reason").asText().contains(reasonPart), s.toString());
                return;
            }
        }
        fail(field + " not listed under @skipped in " + node);
    }

    /** Resolves "$.a.b[2]['k']" against the tree; the marker may sit on the node or its container. */
    private static JsonNode at(JsonNode root, String path) {
        JsonNode node = root;
        String rest = path.startsWith("$") ? path.substring(1) : path;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\.([^.\\[]+)|\\[(\\d+)]|\\['([^']*)']").matcher(rest);
        while (m.find() && node != null) {
            if (m.group(1) != null) {
                node = node.get(m.group(1));
            } else if (m.group(2) != null) {
                int i = Integer.parseInt(m.group(2));
                node = i < node.size() ? node.get(i) : node.get(node.size() - 1);
            } else {
                node = node.get(m.group(3));
            }
        }
        if (node != null && node.isArray()) {
            return node.get(node.size() - 1);
        }
        if (node != null && node.isObject() && node.has("@truncated")) {
            return node;
        }
        return node;
    }

    private static void assertFalse(boolean condition) {
        assertTrue(!condition);
    }

    /** A value the serializer writes as one JSON scalar, so it has no fields to audit. */
    private static boolean isScalar(Object value) {
        return Scalars.write(value, new SerializationContext(new SerializerConfig(), "$")) != null;
    }

    static IndirectList<String> unloadedList() {
        IndirectList<String> list = new IndirectList<>();
        list.setValueHolder(unloadedHolder());
        return list;
    }

    static ValueHolderInterface unloadedHolder() {
        return new ValueHolderInterface() {
            @Override
            public Object getValue() {
                throw new IllegalStateException("instantiated by the serializer");
            }

            @Override
            public void setValue(Object value) {
            }

            @Override
            public boolean isInstantiated() {
                return false;
            }

            @Override
            public Object clone() {
                return this;
            }
        };
    }
}
