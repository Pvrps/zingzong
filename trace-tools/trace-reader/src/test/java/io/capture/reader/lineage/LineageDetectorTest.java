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
    void valuesAppearingMoreThanFiveTimesAreIgnored() {
        List<LoadedTrace> traces = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            traces.add(trace(i, "t-" + i,
                    span("s1", null, 0, "Catalog", "lookup", "[{\"sku\":\"GADGET-2000\"}]", "{\"sku\":\"GADGET-2000\",\"price\":\"99.99\"}")));
        }
        LineageFile lineage = detector.detect("rec-test", traces);
        for (LineageEntry e : lineage.entries) {
            assertTrue(!"GADGET-2000".equals(e.value), "8 occurrences > cap: " + describe(lineage));
        }
        // 99.99 occurs 4 times, only in results: no entries at all for it, and nothing links.
        assertTrue(sourced(lineage).isEmpty(), describe(lineage));
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
