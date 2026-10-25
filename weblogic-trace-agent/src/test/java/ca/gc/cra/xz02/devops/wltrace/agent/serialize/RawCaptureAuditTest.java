package ca.gc.cra.xz02.devops.wltrace.agent.serialize;

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

/** Proves the raw-capture rule mechanically: every instance field of every walked object is in the output or listed under {@code @skipped} with a reason, and every transformed value carries its marker. */
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
                Everything.class.getClassLoader(), new Class<?>[]{java.sql.Connection.class}, (proxy, method, arguments) -> method.getName().equals("toString") ? "conn" : null);
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

    private final SerializationCeilings ceilings = new SerializationCeilings()
            .withMaxStringLength(40).withMaxBytes(8).withMaxElements(5).withMaxDepth(6);

    @Test
    void everyFieldIsPresentOrListedAsSkippedAndEveryTransformationIsMarked() throws Exception {
        Everything everything = build();
        try {
            Graphs graphs = new Graphs(ceilings);
            Graphs.Result result = graphs.write(everything);
            JsonNode root = result.node;

            // Nothing dropped silently: walk the object graph and the JSON side by side.
            audit(everything, root, "$", new IdentityHashMap<>());

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
            for (ca.gc.cra.xz02.devops.wltrace.model.SerializationWarning warning : result.warnings) {
                JsonNode at = at(root, warning.path);
                assertTrue(at != null && isMarked(at), "warning without marker at " + warning.path + ": " + warning.reason);
            }
        } finally {
            everything.executor.shutdownNow();
        }
    }

    private static Everything build() {
        Everything everything = new Everything();
        everything.cycle.name = "a";
        everything.cycle.other = everything.cycle;
        everything.repeated = everything.cycle;
        everything.lazy.lines = unloadedList();
        everything.lazy._persistence_city_vh = unloadedHolder();
        everything.inner = new RawCaptureAuditTest().new Inner();
        everything.longText = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        everything.bigBytes = new byte[16];
        for (int index = 0; index < 20; index++) {
            everything.bigList.add(index);
        }
        Cycle chain = new Cycle();
        chain.name = "d0";
        Cycle cursor = chain;
        for (int index = 1; index < 12; index++) {
            Cycle nextLink = new Cycle();
            nextLink.name = "d" + index;
            cursor.other = nextLink;
            cursor = nextLink;
        }
        everything.deep = chain;
        everything.objectKeys.put(Arrays.asList(1, 2), "v");
        everything.calendar.setTimeInMillis(1_800_000_000_000L);
        return everything;
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
            for (JsonNode skippedField : node.get("@skipped")) {
                assertTrue(skippedField.has("reason") && !skippedField.get("reason").asText().isEmpty(), path + ": skipped without reason");
                skipped.add(skippedField.get("field").asText());
            }
        }
        for (Class<?> current = value.getClass(); current != null && current != Object.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                String name = field.getName();
                if (!node.has(name)) {
                    if (!skipped.contains(name)) {
                        fail(path + "." + name + " is absent without a @skipped entry: " + node);
                    }
                    continue;
                }
                field.setAccessible(true);
                audit(field.get(value), node.get(name), path + "." + name, seen);
            }
        }
    }

    private static boolean isMarked(JsonNode node) {
        if (!node.isObject()) {
            return false;
        }
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (name.equals("@ref") || name.equals("@lazy") || name.equals("@handle") || name.equals("@unserializable")
                    || name.equals("@truncated") || name.equals("@text") || name.equals("@bytes")) {
                return true;
            }
        }
        return false;
    }

    private static void assertSkipped(JsonNode node, String field, String reasonPart) {
        for (JsonNode skippedField : node.get("@skipped")) {
            if (skippedField.get("field").asText().equals(field)) {
                assertTrue(skippedField.get("reason").asText().contains(reasonPart), skippedField.toString());
                return;
            }
        }
        fail(field + " not listed under @skipped in " + node);
    }

    /** Resolves "$.a.b[2]['k']" against the tree; the marker may sit on the node or its container. */
    private static JsonNode at(JsonNode root, String path) {
        JsonNode node = root;
        String rest = path.startsWith("$") ? path.substring(1) : path;
        java.util.regex.Matcher segment = java.util.regex.Pattern.compile("\\.([^.\\[]+)|\\[(\\d+)]|\\['([^']*)']").matcher(rest);
        while (segment.find() && node != null) {
            if (segment.group(1) != null) {
                node = node.get(segment.group(1));
            } else if (segment.group(2) != null) {
                int index = Integer.parseInt(segment.group(2));
                node = index < node.size() ? node.get(index) : node.get(node.size() - 1);
            } else {
                node = node.get(segment.group(3));
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
        return Scalars.write(value, new SerializationContext(new SerializationCeilings(), "$")) != null;
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
