package io.capture.reader.lineage;

import io.capture.reader.LoadedTrace;
import io.capture.trace.LineageEntry;
import io.capture.trace.LineageFile;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static io.capture.reader.lineage.Traces.span;
import static io.capture.reader.lineage.Traces.trace;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LineageDetectorTest {

    private final LineageDetector detector = new LineageDetector();

    @Test
    void orderIdInConfirmComesFromCreateResultEvenWhenSmall() {
        LoadedTrace create = trace(0, "t-create",
                span("s1", null, 0, "OrderService", "createOrder",
                        "[{\"@type\":\"Req\",\"customer\":{\"name\":\"Ada Lovelace\"},\"lines\":[{\"sku\":\"WIDGET-1\",\"quantity\":3}]}]",
                        "{\"@type\":\"Order\",\"@id\":1,\"id\":42,\"status\":\"CREATED\",\"total\":\"149.73\"}"));
        LoadedTrace confirm = trace(1, "t-confirm",
                span("s1", null, 0, "OrderService", "confirmOrder",
                        "[{\"@type\":\"ConfirmRequest\",\"orderId\":42,\"confirmedBy\":\"ada\"}]",
                        "{\"orderId\":42,\"status\":\"CONFIRMED\"}"),
                span("s2", "s1", 1, "OrderService", "findOrder", "[42]", "{\"id\":42}"));

        LineageFile lineage = detector.detect("rec-test", Arrays.asList(create, confirm));

        LineageEntry entry = sourced(lineage).get(0);
        assertEquals("t-confirm", entry.to.trace);
        assertEquals("s1", entry.to.span);
        assertEquals("args[0].orderId", entry.to.path);
        assertEquals("t-create", entry.from.trace);
        assertEquals("s1", entry.from.span);
        assertEquals("result.id", entry.from.path);
        assertEquals("42", entry.value);
        assertNull(entry.alternatives);
        // findOrder's bare argument "args[0]" is not an identifier-like path, so 42 there is not indexed.
        assertEquals(1, sourced(lineage).size(), describe(lineage));
    }

    @Test
    void commonSmallIntegerDoesNotLink() {
        LoadedTrace first = trace(0, "t-1",
                span("s1", null, 0, "Inventory", "reserve", "[\"WIDGET-1\", 3]", "{\"reserved\":3,\"warehouse\":\"WH-AMS\"}"));
        LoadedTrace second = trace(1, "t-2",
                span("s1", null, 0, "Pricing", "price", "[{\"quantity\":3,\"count\":3}]", "{\"total\":\"37.50\"}"));

        LineageFile lineage = detector.detect("rec-test", Arrays.asList(first, second));

        assertTrue(sourced(lineage).isEmpty(), describe(lineage));
        for (LineageEntry e : lineage.entries) {
            assertTrue(!"3".equals(e.value), "3 must not even be classified: " + describe(lineage));
        }
    }

    @Test
    void decimalsAndLargeNumbersAndLongStringsLink() {
        LoadedTrace first = trace(0, "t-1",
                span("s1", null, 0, "Pricing", "price", "[]", "{\"subtotal\":\"123.74\",\"reference\":\"PO-2026-7781\",\"count\":12345}"));
        LoadedTrace second = trace(1, "t-2",
                span("s1", null, 0, "Tax", "computeTax", "[{\"amount\":123.74,\"ref\":\"PO-2026-7781\",\"n\":12345}]", null));

        LineageFile lineage = detector.detect("rec-test", Arrays.asList(first, second));

        List<String> paths = new ArrayList<>();
        for (LineageEntry e : sourced(lineage)) {
            paths.add(e.to.path + "<-" + e.from.path);
        }
        assertEquals(Arrays.asList("args[0].amount<-result.subtotal", "args[0].ref<-result.reference", "args[0].n<-result.count"), paths);
    }

    @Test
    void nearestEarlierSourceWinsAndOthersAreAlternatives() {
        LoadedTrace a = trace(0, "t-a", span("s1", null, 0, "Order", "createOrder", "[]", "{\"id\":7001}"));
        LoadedTrace b = trace(1, "t-b", span("s1", null, 0, "Order", "findOrder", "[]", "{\"@type\":\"Order\",\"id\":7001}"));
        LoadedTrace c = trace(2, "t-c", span("s1", null, 0, "Order", "confirmOrder", "[{\"orderId\":7001}]", null));

        LineageFile lineage = detector.detect("rec-test", Arrays.asList(a, b, c));

        LineageEntry entry = sourced(lineage).get(0);
        assertEquals("t-b", entry.from.trace, "nearest earlier trace");
        assertNotNull(entry.alternatives);
        assertEquals(1, entry.alternatives.size());
        assertEquals("t-a", entry.alternatives.get(0).trace);
        assertEquals("result.id", entry.alternatives.get(0).path);
    }

    @Test
    void valuesProducedByMoreTracesThanTheCapAreNotSourcesButStillClassified() {
        List<LoadedTrace> traces = new ArrayList<>();
        // The value is system-originated: it first appears in a result, then flows into later inputs.
        traces.add(trace(0, "t-0", span("s1", null, 0, "Catalog", "pick", "[]", "{\"sku\":\"GADGET-2000\"}")));
        for (int i = 1; i < 8; i++) {
            traces.add(trace(i, "t-" + i,
                    span("s1", null, 0, "Catalog", "lookup", "[{\"sku\":\"GADGET-2000\"}]", "{\"sku\":\"GADGET-2000\",\"price\":\"99.99\"}")));
        }
        LineageFile lineage = detector.detect("rec-test", traces);

        // Produced by 8 traces: too common to be a source anywhere in the recording.
        assertTrue(sourced(lineage).isEmpty(), describe(lineage));
        // Classification is never capped: every trace's input is classified regardless.
        int classified = 0;
        for (LineageEntry e : lineage.entries) {
            if ("GADGET-2000".equals(e.value)) {
                assertEquals(LineageEntry.CLASS_USER_INPUT, e.classification);
                classified++;
            }
        }
        assertEquals(7, classified, describe(lineage));

        // Produced by 4 traces: under the cap, so later traces link to the nearest earlier one.
        LineageFile fewer = detector.detect("rec-test", traces.subList(0, 4));
        assertEquals(3, sourced(fewer).size(), describe(fewer));
        assertEquals("t-2", sourced(fewer).get(2).from.trace);
    }

    @Test
    void userOriginatedValuesTypedAgainAreNotLinkedToTheirEchoes() {
        LoadedTrace create1 = trace(0, "t-create-1",
                span("s1", null, 0, "OrderService", "createOrder",
                        "[{\"customer\":{\"name\":\"Ada Lovelace\"},\"lines\":[{\"sku\":\"WIDGET-1\"}]}]",
                        "{\"id\":65639,\"customerName\":\"Ada Lovelace\"}"),
                span("s2", "s1", 1, "InventoryService", "reserve", "[\"WIDGET-1\"]", "{\"sku\":\"WIDGET-1\"}"));
        LoadedTrace confirm = trace(1, "t-confirm",
                span("s1", null, 0, "OrderService", "confirmOrder", "[{\"orderId\":65639}]", "{\"orderId\":65639,\"customerName\":\"Ada Lovelace\"}"));
        LoadedTrace create2 = trace(2, "t-create-2",
                span("s1", null, 0, "OrderService", "createOrder",
                        "[{\"customer\":{\"name\":\"Ada Lovelace\"},\"lines\":[{\"sku\":\"WIDGET-1\"}]}]",
                        "{\"id\":65640,\"customerName\":\"Ada Lovelace\"}"),
                span("s2", "s1", 1, "InventoryService", "reserve", "[\"WIDGET-1\"]", "{\"sku\":\"WIDGET-1\"}"));

        LineageFile lineage = detector.detect("rec-test", Arrays.asList(create1, confirm, create2));

        List<LineageEntry> links = sourced(lineage);
        assertEquals(1, links.size(), describe(lineage));
        assertEquals("t-confirm", links.get(0).to.trace, "confirm links to the first create");
        assertEquals("t-create-1", links.get(0).from.trace);
        assertEquals("65639", links.get(0).value);
        for (LineageEntry e : lineage.entries) {
            if ("t-create-2".equals(e.to.trace)) {
                assertNull(e.from, "the second create links to nothing: " + describe(lineage));
                assertEquals(LineageEntry.CLASS_USER_INPUT, e.classification);
            }
        }
    }

    @Test
    void recomputedValuesThatMatchEarlierResultsAreLinkedButFlagged() {
        LoadedTrace first = trace(0, "t-1",
                span("s1", null, 0, "Pricing", "price", "[]", "{\"subtotal\":\"123.74\"}"),
                span("s2", "s1", 1, "Tax", "computeTax", "[{\"amount\":\"123.74\"}]", "{\"tax\":\"25.99\"}"));
        LoadedTrace second = trace(1, "t-2",
                span("s1", null, 0, "Pricing", "price", "[]", "{\"subtotal\":\"123.74\"}"),
                span("s2", "s1", 1, "Tax", "computeTax", "[{\"amount\":\"123.74\"}]", "{\"tax\":\"25.99\"}"));

        LineageFile lineage = detector.detect("rec-test", Arrays.asList(first, second));
        LineageEntry entry = sourced(lineage).get(0);
        assertEquals("t-2", entry.to.trace);
        assertEquals("t-1", entry.from.trace);
        assertEquals(Boolean.TRUE, entry.alsoProducedHere, "the same trace produces 123.74 itself");
    }

    @Test
    void valuesTypedOnceAndEchoedInManyResultsStillClassifyAsUserInput() {
        List<LoadedTrace> traces = new ArrayList<>();
        traces.add(trace(0, "t-create",
                span("s1", null, 0, "OrderService", "createOrder",
                        "[{\"customer\":{\"name\":\"Ada Lovelace\",\"email\":\"ada@example.com\"}}]",
                        "{\"id\":9001,\"customerName\":\"Ada Lovelace\",\"customerEmail\":\"ada@example.com\"}"),
                span("s2", "s1", 1, "PricingService", "price",
                        "[{\"customer\":{\"name\":\"Ada Lovelace\",\"email\":\"ada@example.com\"}}]",
                        "{\"customerName\":\"Ada Lovelace\",\"customerEmail\":\"ada@example.com\",\"total\":\"10.00\"}")));
        for (int i = 1; i <= 6; i++) {
            traces.add(trace(i, "t-get-" + i,
                    span("s1", null, 0, "OrderService", "findOrder", "[9001]",
                            "{\"id\":9001,\"customerName\":\"Ada Lovelace\",\"customerEmail\":\"ada@example.com\"}")));
        }

        LineageFile lineage = detector.detect("rec-test", traces);

        assertEquals(LineageEntry.CLASS_USER_INPUT, classification(lineage, "Ada Lovelace"), describe(lineage));
        assertEquals(LineageEntry.CLASS_USER_INPUT, classification(lineage, "ada@example.com"), describe(lineage));
        assertEquals("t-create", entry(lineage, "Ada Lovelace").to.trace);
        assertEquals("args[0].customer.name", entry(lineage, "Ada Lovelace").to.path);
    }

    @Test
    void instantsInsideTheTraceWindowAreEnvironmentEvenInRootArgs() {
        LoadedTrace create = trace(0, "t-create",
                span("s1", null, 0, "OrderService", "createOrder",
                        "[{\"requestedAt\":\"2026-10-23T18:00:00.500Z\",\"deliveryDate\":\"2026-10-30\",\"bookedAt\":\"2026-01-01T09:00:00Z\"}]",
                        "{\"id\":9001}"));
        create.trace.trace.startedAt = "2026-10-23T18:00:00.000Z";
        create.trace.trace.durationMs = 40;

        LineageFile lineage = detector.detect("rec-test", Arrays.asList(create));

        assertEquals(LineageEntry.CLASS_ENVIRONMENT, classification(lineage, "2026-10-23T18:00:00.500Z"), "stamped during the call");
        assertEquals(LineageEntry.CLASS_USER_INPUT, classification(lineage, "2026-10-30"), "a date typed by the user");
        assertEquals(LineageEntry.CLASS_USER_INPUT, classification(lineage, "2026-01-01T09:00:00Z"), "an instant outside the window");
    }

    @Test
    void unsourcedInputsAreClassifiedByWhereTheyFirstAppear() {
        LoadedTrace create = trace(0, "t-create",
                span("s1", null, 0, "OrderService", "createOrder",
                        "[{\"customer\":{\"name\":\"Ada Lovelace\"}}]", "{\"id\":9001}"),
                span("s2", "s1", 1, "PricingService", "price",
                        "[{\"customer\":{\"name\":\"Ada Lovelace\"},\"catalogVersion\":\"2026-10-01\"}]", "{\"total\":\"10.00\"}"));

        LineageFile lineage = detector.detect("rec-test", Arrays.asList(create));

        assertEquals(LineageEntry.CLASS_USER_INPUT, classification(lineage, "Ada Lovelace"));
        assertEquals("s1", entry(lineage, "Ada Lovelace").to.span, "classified once, at its first occurrence");
        assertEquals(LineageEntry.CLASS_ENVIRONMENT, classification(lineage, "2026-10-01"), "appears only in a nested span");
        assertNull(entry(lineage, "9001"), "results are sources, never classified inputs");
    }

    @Test
    void markersEnumsBooleansAndShortStringsAreSkipped() {
        LoadedTrace first = trace(0, "t-1",
                span("s1", null, 0, "A", "a", "[]", "{\"@type\":\"com.acme.Thing\",\"@id\":1,\"status\":\"CONFIRMED\",\"ok\":true,\"code\":\"EUR\",\"city\":\"Amsterdam\"}"));
        LoadedTrace second = trace(1, "t-2",
                span("s1", null, 0, "B", "b", "[{\"@ref\":1,\"@type\":\"com.acme.Thing\",\"status\":\"CONFIRMED\",\"ok\":true,\"code\":\"EUR\",\"city\":\"Amsterdam\"}]", null));

        LineageFile lineage = detector.detect("rec-test", Arrays.asList(first, second));
        assertTrue(lineage.entries.isEmpty(), describe(lineage));
    }

    private static List<LineageEntry> sourced(LineageFile lineage) {
        List<LineageEntry> result = new ArrayList<>();
        for (LineageEntry e : lineage.entries) {
            if (e.from != null) {
                result.add(e);
            }
        }
        return result;
    }

    private static LineageEntry entry(LineageFile lineage, String value) {
        for (LineageEntry e : lineage.entries) {
            if (value.equals(e.value)) {
                return e;
            }
        }
        return null;
    }

    private static String classification(LineageFile lineage, String value) {
        LineageEntry e = entry(lineage, value);
        return e == null ? null : e.classification;
    }

    private static String describe(LineageFile lineage) {
        StringBuilder sb = new StringBuilder();
        for (LineageEntry e : lineage.entries) {
            sb.append(e.to.trace).append('/').append(e.to.path).append(" <- ")
                    .append(e.from == null ? e.classification : e.from.trace + "/" + e.from.path)
                    .append(" [").append(e.value).append("]\n");
        }
        return sb.toString();
    }
}
