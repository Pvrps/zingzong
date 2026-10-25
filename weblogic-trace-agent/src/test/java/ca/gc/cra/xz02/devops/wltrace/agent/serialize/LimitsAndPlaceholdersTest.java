package ca.gc.cra.xz02.devops.wltrace.agent.serialize;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Proves that every safety ceiling and every non-walkable value leaves a counted, named marker and never throws. */
class LimitsAndPlaceholdersTest {

    static class Node implements Serializable {
        private static final long serialVersionUID = 1L;
        String label;
        Node next;

        Node(String label, Node next) {
            this.label = label;
            this.next = next;
        }
    }

    static class WithLogger implements Serializable {
        private static final long serialVersionUID = 1L;
        Logger logger = Logger.getLogger("test");
        String name = "x";
    }

    static class Holder implements Serializable {
        private static final long serialVersionUID = 1L;
        Object payload;
        Map<Object, String> byObjectKey = new HashMap<>();
        byte[] bytes;
        Optional<String> maybe = Optional.empty();
    }

    static class HostileCollection extends ArrayList<String> {
        private static final long serialVersionUID = 1L;

        @Override
        public Iterator<String> iterator() {
            throw new IllegalStateException("iterator exploded");
        }
    }

    static class HostileToString implements Serializable {
        private static final long serialVersionUID = 1L;
        Collection<String> items = new HostileCollection();

        @Override
        public String toString() {
            throw new IllegalStateException("toString exploded");
        }
    }

    static class PlainData {
        String label = "not serializable but plain data";
        int count = 3;
    }

    static class WithHandles implements Serializable {
        private static final long serialVersionUID = 1L;
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        java.util.concurrent.locks.Lock lock = new java.util.concurrent.locks.ReentrantLock();
        Thread thread = Thread.currentThread();
        ClassLoader loader = getClass().getClassLoader();
        java.io.InputStream stream = new java.io.ByteArrayInputStream(new byte[0]);
        PlainData data = new PlainData();
    }

    static class CustomException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final String sku;
        final int requested;

        CustomException(String message, String sku, int requested, Throwable cause) {
            super(message, cause);
            this.sku = sku;
            this.requested = requested;
        }
    }

    @Test
    void depthCeilingLeavesAMarkedSubtree() {
        Graphs graphs = new Graphs(new SerializationCeilings().withMaxDepth(3));
        Node chain = new Node("d0", new Node("d1", new Node("d2", new Node("d3", new Node("d4", null)))));

        Graphs.Result r = graphs.write(chain);
        JsonNode cut = r.node.get("next").get("next").get("next");
        assertEquals(Node.class.getName(), cut.get("@unserializable").asText(), r.json());
        assertTrue(cut.get("reason").asText().startsWith("depth ceiling 3 reached"), r.json());
        assertTrue(cut.has("identity"));
        assertTrue(r.hasWarning("$.next.next.next", "depth ceiling 3"), r.warnings());
    }

    @Test
    void elementCeilingsLeaveCountedMarkers() {
        Graphs graphs = new Graphs(new SerializationCeilings().withMaxElements(3));
        List<Integer> ten = Arrays.asList(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);

        Graphs.Result r = graphs.write(ten);
        assertEquals(4, r.node.size(), r.json());
        assertEquals(7, r.node.get(3).get("@truncated").asInt());
        assertEquals("elements", r.node.get(3).get("what").asText());
        assertTrue(r.hasWarning("$", "7 of 10 elements dropped"), r.warnings());

        Graphs.Result arr = graphs.write(new String[]{"a", "b", "c", "d", "e"});
        assertEquals(2, arr.node.get(3).get("@truncated").asInt());

        Map<String, Integer> map = new java.util.LinkedHashMap<>();
        for (int i = 0; i < 5; i++) {
            map.put("k" + i, i);
        }
        Graphs.Result m = graphs.write(map);
        assertEquals(2, m.node.get("@truncated").asInt(), m.json());
        assertEquals("entries", m.node.get("what").asText());
        assertFalse(m.node.has("k3"));
    }

    @Test
    void stringCeilingLeavesPrefixLengthAndDroppedCount() throws Exception {
        Graphs graphs = new Graphs(new SerializationCeilings().withMaxStringLength(5));
        Graphs.Result r = graphs.write(new Node("abcdefghij", null));
        JsonNode label = r.node.get("label");
        assertEquals("abcde", label.get("@text").asText(), r.json());
        assertEquals(10, label.get("length").asInt());
        assertEquals(5, label.get("@truncated").asInt());
        assertEquals("chars", label.get("what").asText());
        assertTrue(r.hasWarning("$.label", "5 of 10 chars dropped"), r.warnings());
    }

    @Test
    void loggersAreWalkedLikeAnyObject() {
        Graphs graphs = new Graphs();
        Graphs.Result r = graphs.write(new WithLogger());
        JsonNode logger = r.node.get("logger");
        assertEquals("java.util.logging.Logger", logger.get("@type").asText(), r.json());
        assertEquals("test", logger.get("name").asText());
        assertFalse(logger.has("@unserializable"));
        assertEquals("x", r.node.get("name").asText());
    }

    @Test
    void runtimeHandlesAreTypedReferencesAndNonSerializableDataIsWalkedFlagged() throws Exception {
        WithHandles value = new WithHandles();
        try {
            Graphs.Result r = new Graphs().write(value);
            String[][] expected = {{"executor", "executor"}, {"lock", "lock"}, {"thread", "thread"}, {"loader", "class loader"}, {"stream", "stream"}};
            for (String[] e : expected) {
                JsonNode handle = r.node.get(e[0]);
                assertTrue(handle.has("@handle"), e[0] + ": " + r.json());
                assertEquals(e[1], handle.get("kind").asText(), e[0]);
                assertTrue(handle.get("identity").asText().contains("@"), e[0]);
                assertTrue(handle.has("toString"), e[0]);
                assertTrue(r.hasWarning("$." + e[0], "runtime handle"), r.warnings());
            }
            JsonNode data = r.node.get("data");
            assertEquals(PlainData.class.getName(), data.get("@type").asText(), r.json());
            assertFalse(data.get("@serializable").asBoolean());
            assertEquals("not serializable but plain data", data.get("label").asText());
            assertEquals(3, data.get("count").asInt());
            assertEquals(5, r.warnings.size(), r.warnings());
        } finally {
            value.executor.shutdownNow();
        }
    }

    @Test
    void vendorInternalsAreWalkedTooWithWeaverFieldsListedAsSkipped() {
        Holder holder = new Holder();
        holder.payload = new org.eclipse.persistence.internal.helper.DatabaseField("X");
        Graphs.Result r = new Graphs().write(holder);
        JsonNode field = r.node.get("payload");
        assertEquals("org.eclipse.persistence.internal.helper.DatabaseField", field.get("@type").asText(), r.json());
        assertEquals("X", field.get("name").asText());
        assertFalse(field.has("@unserializable"));
    }

    @Test
    void throwablesAreWalkedInFullWithStackCauseSuppressedAndOwnFields() throws Exception {
        CustomException ex = new CustomException("outer", "GIZMO-3", 9, new IllegalArgumentException("inner"));
        ex.addSuppressed(new IllegalStateException("while cleaning up"));
        Graphs graphs = new Graphs();

        Graphs.Result r = graphs.write(ex);
        assertEquals(CustomException.class.getName(), r.node.get("@type").asText(), r.json());
        assertEquals("outer", r.node.get("detailMessage").asText());
        assertEquals("GIZMO-3", r.node.get("sku").asText(), "an exception's own fields are data");
        assertEquals(9, r.node.get("requested").asInt());
        assertEquals(ex.getStackTrace().length, r.node.get("stackTrace").size(), "full stack, no top-N");
        assertTrue(r.node.get("stackTrace").get(0).asText().contains("LimitsAndPlaceholdersTest"));
        assertEquals("inner", r.node.get("cause").get("detailMessage").asText());
        assertTrue(r.node.get("cause").get("cause").isNull(), "cause == this sentinel reads as no cause");
        assertEquals("while cleaning up", r.node.get("suppressedExceptions").get(0).get("detailMessage").asText());
        List<String> skipped = new ArrayList<>();
        if (r.node.has("@skipped")) {
            for (JsonNode s : r.node.get("@skipped")) {
                skipped.add(s.get("field").asText());
            }
        }
        if (System.getProperty("java.specification.version").startsWith("1.")) {
            // JDK 8 hides Throwable.backtrace from reflection and has no depth field: nothing to list.
            assertTrue(skipped.isEmpty(), "JDK 8: no JVM-internal Throwable fields visible: " + skipped);
        } else {
            assertTrue(skipped.contains("backtrace") && skipped.contains("depth"), "JVM-internal fields listed: " + skipped);
        }
        assertTrue(r.warnings.isEmpty(), r.warnings());
    }

    @Test
    void byteArraysAreBase64AndTheCeilingIsCounted() throws Exception {
        Holder holder = new Holder();
        holder.bytes = new byte[]{1, 2, 3, 4, 5, 6};
        Graphs graphs = new Graphs(new SerializationCeilings().withMaxBytes(4));

        Graphs.Result r = graphs.write(holder);
        JsonNode bytes = r.node.get("bytes");
        assertEquals("AQIDBA==", bytes.get("@bytes").asText(), r.json());
        assertEquals(6, bytes.get("length").asInt());
        assertEquals(2, bytes.get("@truncated").asInt());
        assertEquals("bytes", bytes.get("what").asText());
        assertTrue(r.hasWarning("$.bytes", "2 of 6 bytes dropped"), r.warnings());

        holder.bytes = new byte[]{1, 2};
        assertEquals("AQI=", new Graphs().write(holder).node.get("bytes").get("@bytes").asText(), "under the ceiling: no truncation marker");
        assertFalse(new Graphs().write(holder).node.get("bytes").has("@truncated"));
    }

    @Test
    void optionalsKeepPresenceAndValue() throws Exception {
        Holder holder = new Holder();
        Graphs graphs = new Graphs();
        JsonNode empty = graphs.write(holder).node.get("maybe");
        assertEquals("java.util.Optional", empty.get("@type").asText());
        assertFalse(empty.get("present").asBoolean());
        assertFalse(empty.has("value"));

        holder.maybe = Optional.of("there");
        JsonNode present = graphs.write(holder).node.get("maybe");
        assertTrue(present.get("present").asBoolean());
        assertEquals("there", present.get("value").asText());
    }

    @Test
    void mapsWithObjectKeysUseEntries() throws Exception {
        Holder holder = new Holder();
        holder.byObjectKey.put(Arrays.asList(1, 2), "list-key");
        Graphs graphs = new Graphs();

        Graphs.Result r = graphs.write(holder);
        JsonNode map = r.node.get("byObjectKey");
        assertEquals("java.util.HashMap", map.get("@type").asText(), r.json());
        assertEquals("list-key", map.get("@entries").get(0).get("value").asText());
        assertEquals(2, map.get("@entries").get(0).get("key").size());
    }

    @Test
    void failuresInsideTheGraphBecomeMarkedNodesAndNeverThrow() {
        Graphs graphs = new Graphs();
        Graphs.Result r = graphs.write(new HostileToString());

        JsonNode items = r.node.get("items");
        assertNotNull(items, r.json());
        assertEquals(HostileCollection.class.getName(), items.get("@unserializable").asText(), r.json());
        assertEquals("java.lang.IllegalStateException: iterator exploded", items.get("reason").asText());
        assertTrue(r.hasWarning("$.items", "IllegalStateException: iterator exploded"), r.warnings());
        assertEquals(1, r.warnings.size(), r.warnings());
    }
}
