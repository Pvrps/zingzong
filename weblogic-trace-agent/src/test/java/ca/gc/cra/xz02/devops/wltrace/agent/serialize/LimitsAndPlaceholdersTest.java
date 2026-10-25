package ca.gc.cra.xz02.devops.wltrace.agent.serialize;

import com.fasterxml.jackson.databind.JsonNode;
import org.testng.annotations.Test;

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

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

/** Proves that every safety ceiling and every non-walkable value leaves a counted, named marker and never throws. */
public class LimitsAndPlaceholdersTest {

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
    public void depthCeilingLeavesAMarkedSubtree() {
        Graphs graphs = new Graphs(new SerializationCeilings().withMaxDepth(3));
        Node chain = new Node("d0", new Node("d1", new Node("d2", new Node("d3", new Node("d4", null)))));

        Graphs.Result result = graphs.write(chain);
        JsonNode cut = result.node.get("next").get("next").get("next");
        assertEquals(cut.get("@unserializable").asText(), Node.class.getName(), result.json());
        assertTrue(cut.get("reason").asText().startsWith("depth ceiling 3 reached"), result.json());
        assertTrue(cut.has("identity"));
        assertTrue(result.hasWarning("$.next.next.next", "depth ceiling 3"), result.warnings());
    }

    @Test
    public void elementCeilingsLeaveCountedMarkers() {
        Graphs graphs = new Graphs(new SerializationCeilings().withMaxElements(3));
        List<Integer> ten = Arrays.asList(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);

        Graphs.Result result = graphs.write(ten);
        assertEquals(result.node.size(), 4, result.json());
        assertEquals(result.node.get(3).get("@truncated").asInt(), 7);
        assertEquals(result.node.get(3).get("what").asText(), "elements");
        assertTrue(result.hasWarning("$", "7 of 10 elements dropped"), result.warnings());

        Graphs.Result arrayResult = graphs.write(new String[]{"a", "b", "c", "d", "e"});
        assertEquals(arrayResult.node.get(3).get("@truncated").asInt(), 2);

        Map<String, Integer> map = new java.util.LinkedHashMap<>();
        for (int i = 0; i < 5; i++) {
            map.put("k" + i, i);
        }
        Graphs.Result mapResult = graphs.write(map);
        assertEquals(mapResult.node.get("@truncated").asInt(), 2, mapResult.json());
        assertEquals(mapResult.node.get("what").asText(), "entries");
        assertFalse(mapResult.node.has("k3"));
    }

    @Test
    public void stringCeilingLeavesPrefixLengthAndDroppedCount() throws Exception {
        Graphs graphs = new Graphs(new SerializationCeilings().withMaxStringLength(5));
        Graphs.Result result = graphs.write(new Node("abcdefghij", null));
        JsonNode label = result.node.get("label");
        assertEquals(label.get("@text").asText(), "abcde", result.json());
        assertEquals(label.get("length").asInt(), 10);
        assertEquals(label.get("@truncated").asInt(), 5);
        assertEquals(label.get("what").asText(), "chars");
        assertTrue(result.hasWarning("$.label", "5 of 10 chars dropped"), result.warnings());
    }

    @Test
    public void loggersAreWalkedLikeAnyObject() {
        Graphs graphs = new Graphs();
        Graphs.Result result = graphs.write(new WithLogger());
        JsonNode logger = result.node.get("logger");
        assertEquals(logger.get("@type").asText(), "java.util.logging.Logger", result.json());
        assertEquals(logger.get("name").asText(), "test");
        assertFalse(logger.has("@unserializable"));
        assertEquals(result.node.get("name").asText(), "x");
    }

    @Test
    public void runtimeHandlesAreTypedReferencesAndNonSerializableDataIsWalkedFlagged() throws Exception {
        WithHandles value = new WithHandles();
        try {
            Graphs.Result result = new Graphs().write(value);
            String[][] expectedHandles = {{"executor", "executor"}, {"lock", "lock"}, {"thread", "thread"}, {"loader", "class loader"}, {"stream", "stream"}};
            for (String[] fieldAndKind : expectedHandles) {
                JsonNode handle = result.node.get(fieldAndKind[0]);
                assertTrue(handle.has("@handle"), fieldAndKind[0] + ": " + result.json());
                assertEquals(handle.get("kind").asText(), fieldAndKind[1], fieldAndKind[0]);
                assertTrue(handle.get("identity").asText().contains("@"), fieldAndKind[0]);
                assertTrue(handle.has("toString"), fieldAndKind[0]);
                assertTrue(result.hasWarning("$." + fieldAndKind[0], "runtime handle"), result.warnings());
            }
            JsonNode data = result.node.get("data");
            assertEquals(data.get("@type").asText(), PlainData.class.getName(), result.json());
            assertFalse(data.get("@serializable").asBoolean());
            assertEquals(data.get("label").asText(), "not serializable but plain data");
            assertEquals(data.get("count").asInt(), 3);
            assertEquals(result.warnings.size(), 5, result.warnings());
        } finally {
            value.executor.shutdownNow();
        }
    }

    @Test
    public void vendorInternalsAreWalkedTooWithWeaverFieldsListedAsSkipped() {
        Holder holder = new Holder();
        holder.payload = new org.eclipse.persistence.internal.helper.DatabaseField("X");
        Graphs.Result result = new Graphs().write(holder);
        JsonNode field = result.node.get("payload");
        assertEquals(field.get("@type").asText(), "org.eclipse.persistence.internal.helper.DatabaseField", result.json());
        assertEquals(field.get("name").asText(), "X");
        assertFalse(field.has("@unserializable"));
    }

    @Test
    public void throwablesAreWalkedInFullWithStackCauseSuppressedAndOwnFields() throws Exception {
        CustomException ex = new CustomException("outer", "GIZMO-3", 9, new IllegalArgumentException("inner"));
        ex.addSuppressed(new IllegalStateException("while cleaning up"));
        Graphs graphs = new Graphs();

        Graphs.Result result = graphs.write(ex);
        assertEquals(result.node.get("@type").asText(), CustomException.class.getName(), result.json());
        assertEquals(result.node.get("detailMessage").asText(), "outer");
        assertEquals(result.node.get("sku").asText(), "GIZMO-3", "an exception's own fields are data");
        assertEquals(result.node.get("requested").asInt(), 9);
        assertEquals(result.node.get("stackTrace").size(), ex.getStackTrace().length, "full stack, no top-N");
        assertTrue(result.node.get("stackTrace").get(0).asText().contains("LimitsAndPlaceholdersTest"));
        assertEquals(result.node.get("cause").get("detailMessage").asText(), "inner");
        assertTrue(result.node.get("cause").get("cause").isNull(), "cause == this sentinel reads as no cause");
        assertEquals(result.node.get("suppressedExceptions").get(0).get("detailMessage").asText(), "while cleaning up");
        List<String> skipped = new ArrayList<>();
        if (result.node.has("@skipped")) {
            for (JsonNode s : result.node.get("@skipped")) {
                skipped.add(s.get("field").asText());
            }
        }
        if (System.getProperty("java.specification.version").startsWith("1.")) {
            // JDK 8 hides Throwable.backtrace from reflection and has no depth field: nothing to list.
            assertTrue(skipped.isEmpty(), "JDK 8: no JVM-internal Throwable fields visible: " + skipped);
        } else {
            assertTrue(skipped.contains("backtrace") && skipped.contains("depth"), "JVM-internal fields listed: " + skipped);
        }
        assertTrue(result.warnings.isEmpty(), result.warnings());
    }

    @Test
    public void byteArraysAreBase64AndTheCeilingIsCounted() throws Exception {
        Holder holder = new Holder();
        holder.bytes = new byte[]{1, 2, 3, 4, 5, 6};
        Graphs graphs = new Graphs(new SerializationCeilings().withMaxBytes(4));

        Graphs.Result result = graphs.write(holder);
        JsonNode bytes = result.node.get("bytes");
        assertEquals(bytes.get("@bytes").asText(), "AQIDBA==", result.json());
        assertEquals(bytes.get("length").asInt(), 6);
        assertEquals(bytes.get("@truncated").asInt(), 2);
        assertEquals(bytes.get("what").asText(), "bytes");
        assertTrue(result.hasWarning("$.bytes", "2 of 6 bytes dropped"), result.warnings());

        holder.bytes = new byte[]{1, 2};
        assertEquals(new Graphs().write(holder).node.get("bytes").get("@bytes").asText(), "AQI=", "under the ceiling: no truncation marker");
        assertFalse(new Graphs().write(holder).node.get("bytes").has("@truncated"));
    }

    @Test
    public void optionalsKeepPresenceAndValue() throws Exception {
        Holder holder = new Holder();
        Graphs graphs = new Graphs();
        JsonNode empty = graphs.write(holder).node.get("maybe");
        assertEquals(empty.get("@type").asText(), "java.util.Optional");
        assertFalse(empty.get("present").asBoolean());
        assertFalse(empty.has("value"));

        holder.maybe = Optional.of("there");
        JsonNode present = graphs.write(holder).node.get("maybe");
        assertTrue(present.get("present").asBoolean());
        assertEquals(present.get("value").asText(), "there");
    }

    @Test
    public void mapsWithObjectKeysUseEntries() throws Exception {
        Holder holder = new Holder();
        holder.byObjectKey.put(Arrays.asList(1, 2), "list-key");
        Graphs graphs = new Graphs();

        Graphs.Result result = graphs.write(holder);
        JsonNode map = result.node.get("byObjectKey");
        assertEquals(map.get("@type").asText(), "java.util.HashMap", result.json());
        assertEquals(map.get("@entries").get(0).get("value").asText(), "list-key");
        assertEquals(map.get("@entries").get(0).get("key").size(), 2);
    }

    @Test
    public void failuresInsideTheGraphBecomeMarkedNodesAndNeverThrow() {
        Graphs graphs = new Graphs();
        Graphs.Result result = graphs.write(new HostileToString());

        JsonNode items = result.node.get("items");
        assertNotNull(items, result.json());
        assertEquals(items.get("@unserializable").asText(), HostileCollection.class.getName(), result.json());
        assertEquals(items.get("reason").asText(), "java.lang.IllegalStateException: iterator exploded");
        assertTrue(result.hasWarning("$.items", "IllegalStateException: iterator exploded"), result.warnings());
        assertEquals(result.warnings.size(), 1, result.warnings());
    }
}
