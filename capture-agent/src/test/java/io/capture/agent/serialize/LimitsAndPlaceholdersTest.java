package io.capture.agent.serialize;

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
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void depthLimitProducesPlaceholderAndWarning() {
        Graphs graphs = new Graphs(new SerializerConfig().withMaxDepth(3));
        Node chain = new Node("d0", new Node("d1", new Node("d2", new Node("d3", new Node("d4", null)))));

        Graphs.Result r = graphs.write(chain);
        JsonNode cut = r.node.get("next").get("next").get("next");
        assertEquals(Node.class.getName(), cut.get("@unserializable").asText(), r.json());
        assertTrue(r.hasWarning("$.next.next.next", "depth limit 3"), r.warnings());
    }

    @Test
    void collectionAndMapAndArrayCapsTruncateWithMarker() {
        Graphs graphs = new Graphs(new SerializerConfig().withMaxElements(3));
        List<Integer> ten = Arrays.asList(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);

        Graphs.Result r = graphs.write(ten);
        assertEquals(4, r.node.size(), r.json());
        assertEquals(7, r.node.get(3).get("@truncated").asInt());
        assertTrue(r.hasWarning("$", "truncated at 3 of 10"), r.warnings());

        Graphs.Result arr = graphs.write(new String[]{"a", "b", "c", "d", "e"});
        assertEquals(2, arr.node.get(3).get("@truncated").asInt());

        Map<String, Integer> map = new java.util.LinkedHashMap<>();
        for (int i = 0; i < 5; i++) {
            map.put("k" + i, i);
        }
        Graphs.Result m = graphs.write(map);
        assertEquals(2, m.node.get("@truncated").asInt(), m.json());
        assertFalse(m.node.has("k3"));
    }

    static class PlainData {
        String label = "not serializable but plain data";
        int count = 3;
    }

    static class WithInfrastructure implements Serializable {
        private static final long serialVersionUID = 1L;
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        java.util.concurrent.locks.Lock lock = new java.util.concurrent.locks.ReentrantLock();
        Thread thread = Thread.currentThread();
        ClassLoader loader = getClass().getClassLoader();
        java.io.InputStream stream = new java.io.ByteArrayInputStream(new byte[0]);
        PlainData data = new PlainData();
    }

    @Test
    void loggersBecomePlaceholdersByTypeHeuristic() {
        Graphs graphs = new Graphs();
        Graphs.Result r = graphs.write(new WithLogger());
        assertEquals("java.util.logging.Logger", r.node.get("logger").get("@unserializable").asText(), r.json());
        assertEquals("x", r.node.get("name").asText());
        assertTrue(r.hasWarning("$.logger", "infrastructure type"), r.warnings());
    }

    @Test
    void infrastructureTypesBecomePlaceholdersAndNonSerializableDataIsCapturedFlagged() throws Exception {
        WithInfrastructure value = new WithInfrastructure();
        try {
            Graphs.Result r = new Graphs().write(value);
            for (String field : new String[]{"executor", "lock", "thread", "loader", "stream"}) {
                assertTrue(r.node.get(field).has("@unserializable"), field + ": " + r.json());
                assertTrue(r.hasWarning("$." + field, "infrastructure type"), r.warnings());
            }
            JsonNode data = r.node.get("data");
            assertEquals(PlainData.class.getName(), data.get("@type").asText(), r.json());
            assertFalse(data.get("@serializable").asBoolean());
            assertEquals("not serializable but plain data", data.get("label").asText());
            assertEquals(3, data.get("count").asInt());
            assertEquals(5, r.warnings.size(), r.warnings());

            WithInfrastructure back = new Graphs().read(r.node, WithInfrastructure.class);
            assertEquals(3, back.data.count);
            assertNotNull(back.executor, "placeholder keeps the constructor default");
        } finally {
            value.executor.shutdownNow();
        }
    }

    @Test
    void vendorInternalsBecomePlaceholdersByPackage() {
        Holder holder = new Holder();
        holder.payload = new org.eclipse.persistence.internal.helper.DatabaseField("X");
        Graphs.Result r = new Graphs().write(holder);
        assertEquals("org.eclipse.persistence.internal.helper.DatabaseField",
                r.node.get("payload").get("@unserializable").asText(), r.json());
        assertTrue(r.hasWarning("$.payload", "infrastructure type"), r.warnings());
    }

    @Test
    void throwablesCarryMessageTopFramesAndCause() throws Exception {
        IllegalStateException ex = new IllegalStateException("outer", new IllegalArgumentException("inner"));
        Graphs graphs = new Graphs();

        Graphs.Result r = graphs.write(ex);
        assertEquals("java.lang.IllegalStateException", r.node.get("@type").asText());
        assertEquals("outer", r.node.get("message").asText());
        assertTrue(r.node.get("stackTrace").size() <= 10 && r.node.get("stackTrace").size() > 0);
        assertTrue(r.node.get("stackTrace").get(0).asText().contains("LimitsAndPlaceholdersTest"));
        assertEquals("inner", r.node.get("cause").get("message").asText());
        assertTrue(r.warnings.isEmpty(), r.warnings());

        Throwable back = graphs.read(r.node, Throwable.class);
        assertEquals(IllegalStateException.class, back.getClass());
        assertEquals("outer", back.getMessage());
        assertEquals(IllegalArgumentException.class, back.getCause().getClass());
    }

    @Test
    void byteArraysAreBase64AndCapped() throws Exception {
        Holder holder = new Holder();
        holder.bytes = new byte[]{1, 2, 3, 4, 5, 6};
        Graphs graphs = new Graphs(new SerializerConfig().withMaxBytes(4));

        Graphs.Result r = graphs.write(holder);
        JsonNode bytes = r.node.get("bytes");
        assertEquals("AQIDBA==", bytes.get("@bytes").asText(), r.json());
        assertEquals(6, bytes.get("length").asInt());
        assertTrue(bytes.get("@truncated").asBoolean());
        assertTrue(r.hasWarning("$.bytes", "truncated"));

        holder.bytes = new byte[]{1, 2};
        Holder back = new Graphs().roundTrip(holder, Holder.class);
        assertTrue(Arrays.equals(new byte[]{1, 2}, back.bytes));
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

        Holder back = graphs.read(r.node, Holder.class);
        assertEquals("list-key", back.byObjectKey.get(Arrays.asList(1, 2)));
    }

    @Test
    void failuresInsideTheGraphDegradeToPlaceholdersAndNeverThrow() {
        Graphs graphs = new Graphs();
        Graphs.Result r = graphs.write(new HostileToString());

        JsonNode items = r.node.get("items");
        assertNotNull(items, r.json());
        assertEquals(HostileCollection.class.getName(), items.get("@unserializable").asText(), r.json());
        assertTrue(r.hasWarning("$.items", "IllegalStateException: iterator exploded"), r.warnings());
        assertEquals(1, r.warnings.size(), r.warnings());
    }

    @Test
    void placeholdersLeaveFieldsAtConstructorDefaultsAndReadAsNullAtTopLevel() throws Exception {
        Graphs graphs = new Graphs();
        String type = Node.class.getName();

        Node lazy = graphs.read(io.capture.trace.TraceJson.mapper().readTree(
                "{\"@type\":\"" + type + "\",\"label\":\"a\",\"next\":{\"@lazy\":true,\"loaded\":false}}"), Node.class);
        assertEquals("a", lazy.label);
        assertNull(lazy.next);

        Node unserializable = graphs.read(io.capture.trace.TraceJson.mapper().readTree(
                "{\"@type\":\"" + type + "\",\"label\":\"b\",\"next\":{\"@unserializable\":\"x\",\"toString\":\"x\"}}"), Node.class);
        assertEquals("b", unserializable.label);
        assertNull(unserializable.next);

        assertNull(graphs.read(io.capture.trace.TraceJson.mapper().readTree("{\"@unserializable\":\"x\"}"), Node.class));
    }
}
