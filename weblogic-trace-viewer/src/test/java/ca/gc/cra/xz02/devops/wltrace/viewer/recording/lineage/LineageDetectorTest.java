package ca.gc.cra.xz02.devops.wltrace.viewer.recording.lineage;

import ca.gc.cra.xz02.devops.wltrace.viewer.recording.LoadedTrace;
import ca.gc.cra.xz02.devops.wltrace.model.LineageEntry;
import ca.gc.cra.xz02.devops.wltrace.model.LineageFile;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static ca.gc.cra.xz02.devops.wltrace.viewer.recording.lineage.Traces.span;
import static ca.gc.cra.xz02.devops.wltrace.viewer.recording.lineage.Traces.trace;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

/** Proves the lineage rules on hand-built traces: nearest earlier source wins, user-originated values are never linked, the rest is classified. */
public class LineageDetectorTest {

    private final LineageDetector detector = new LineageDetector();

    @Test
    public void orderIdInConfirmComesFromCreateResultEvenWhenSmall() {
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
        assertEquals(entry.to.trace, "t-confirm");
        assertEquals(entry.to.span, "s1");
        assertEquals(entry.to.path, "args[0].orderId");
        assertEquals(entry.from.trace, "t-create");
        assertEquals(entry.from.span, "s1");
        assertEquals(entry.from.path, "result.id");
        assertEquals(entry.value, "42");
        assertNull(entry.alternatives);
        // findOrder's bare argument "args[0]" is not an identifier-like path, so 42 there is not indexed.
        assertEquals(sourced(lineage).size(), 1, describe(lineage));
    }

    @Test
    public void commonSmallIntegerDoesNotLink() {
        LoadedTrace first = trace(0, "t-1",
                span("s1", null, 0, "Inventory", "reserve", "[\"WIDGET-1\", 3]", "{\"reserved\":3,\"warehouse\":\"WH-AMS\"}"));
        LoadedTrace second = trace(1, "t-2",
                span("s1", null, 0, "Pricing", "price", "[{\"quantity\":3,\"count\":3}]", "{\"total\":\"37.50\"}"));

        LineageFile lineage = detector.detect("rec-test", Arrays.asList(first, second));

        assertTrue(sourced(lineage).isEmpty(), describe(lineage));
        for (LineageEntry entry : lineage.entries) {
            assertTrue(!"3".equals(entry.value), "3 must not even be classified: " + describe(lineage));
        }
    }

    @Test
    public void decimalsAndLargeNumbersAndLongStringsLink() {
        LoadedTrace first = trace(0, "t-1",
                span("s1", null, 0, "Pricing", "price", "[]", "{\"subtotal\":\"123.74\",\"reference\":\"PO-2026-7781\",\"count\":12345}"));
        LoadedTrace second = trace(1, "t-2",
                span("s1", null, 0, "Tax", "computeTax", "[{\"amount\":123.74,\"ref\":\"PO-2026-7781\",\"n\":12345}]", null));

        LineageFile lineage = detector.detect("rec-test", Arrays.asList(first, second));

        List<String> paths = new ArrayList<>();
        for (LineageEntry entry : sourced(lineage)) {
            paths.add(entry.to.path + "<-" + entry.from.path);
        }
        assertEquals(paths, Arrays.asList("args[0].amount<-result.subtotal", "args[0].ref<-result.reference", "args[0].n<-result.count"));
    }

    @Test
    public void nearestEarlierSourceWinsAndOthersAreAlternatives() {
        LoadedTrace first = trace(0, "t-a", span("s1", null, 0, "Order", "createOrder", "[]", "{\"id\":7001}"));
        LoadedTrace second = trace(1, "t-b", span("s1", null, 0, "Order", "findOrder", "[]", "{\"@type\":\"Order\",\"id\":7001}"));
        LoadedTrace third = trace(2, "t-c", span("s1", null, 0, "Order", "confirmOrder", "[{\"orderId\":7001}]", null));

        LineageFile lineage = detector.detect("rec-test", Arrays.asList(first, second, third));

        LineageEntry entry = sourced(lineage).get(0);
        assertEquals(entry.from.trace, "t-b", "nearest earlier trace");
        assertNotNull(entry.alternatives);
        assertEquals(entry.alternatives.size(), 1);
        assertEquals(entry.alternatives.get(0).trace, "t-a");
        assertEquals(entry.alternatives.get(0).path, "result.id");
    }

    @Test
    public void valuesProducedByMoreTracesThanTheCapAreNotSourcesButStillClassified() {
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
        for (LineageEntry entry : lineage.entries) {
            if ("GADGET-2000".equals(entry.value)) {
                assertEquals(entry.classification, LineageEntry.CLASS_USER_INPUT);
                classified++;
            }
        }
        assertEquals(classified, 7, describe(lineage));

        // Produced by 4 traces: under the cap, so later traces link to the nearest earlier one.
        LineageFile fewer = detector.detect("rec-test", traces.subList(0, 4));
        assertEquals(sourced(fewer).size(), 3, describe(fewer));
        assertEquals(sourced(fewer).get(2).from.trace, "t-2");
    }

    @Test
    public void userOriginatedValuesTypedAgainAreNotLinkedToTheirEchoes() {
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
        assertEquals(links.size(), 1, describe(lineage));
        assertEquals(links.get(0).to.trace, "t-confirm", "confirm links to the first create");
        assertEquals(links.get(0).from.trace, "t-create-1");
        assertEquals(links.get(0).value, "65639");
        for (LineageEntry entry : lineage.entries) {
            if ("t-create-2".equals(entry.to.trace)) {
                assertNull(entry.from, "the second create links to nothing: " + describe(lineage));
                assertEquals(entry.classification, LineageEntry.CLASS_USER_INPUT);
            }
        }
    }

    @Test
    public void recomputedValuesThatMatchEarlierResultsAreLinkedButFlagged() {
        LoadedTrace first = trace(0, "t-1",
                span("s1", null, 0, "Pricing", "price", "[]", "{\"subtotal\":\"123.74\"}"),
                span("s2", "s1", 1, "Tax", "computeTax", "[{\"amount\":\"123.74\"}]", "{\"tax\":\"25.99\"}"));
        LoadedTrace second = trace(1, "t-2",
                span("s1", null, 0, "Pricing", "price", "[]", "{\"subtotal\":\"123.74\"}"),
                span("s2", "s1", 1, "Tax", "computeTax", "[{\"amount\":\"123.74\"}]", "{\"tax\":\"25.99\"}"));

        LineageFile lineage = detector.detect("rec-test", Arrays.asList(first, second));
        LineageEntry entry = sourced(lineage).get(0);
        assertEquals(entry.to.trace, "t-2");
        assertEquals(entry.from.trace, "t-1");
        assertEquals(entry.alsoProducedHere, Boolean.TRUE, "the same trace produces 123.74 itself");
    }

    @Test
    public void valuesTypedOnceAndEchoedInManyResultsStillClassifyAsUserInput() {
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

        assertEquals(classification(lineage, "Ada Lovelace"), LineageEntry.CLASS_USER_INPUT, describe(lineage));
        assertEquals(classification(lineage, "ada@example.com"), LineageEntry.CLASS_USER_INPUT, describe(lineage));
        assertEquals(entry(lineage, "Ada Lovelace").to.trace, "t-create");
        assertEquals(entry(lineage, "Ada Lovelace").to.path, "args[0].customer.name");
    }

    @Test
    public void instantsInsideTheTraceWindowAreEnvironmentEvenInRootArgs() {
        LoadedTrace create = trace(0, "t-create",
                span("s1", null, 0, "OrderService", "createOrder",
                        "[{\"requestedAt\":\"2026-10-23T18:00:00.500Z\",\"deliveryDate\":\"2026-10-30\",\"bookedAt\":\"2026-01-01T09:00:00Z\"}]",
                        "{\"id\":9001}"));
        create.trace.trace.startedAt = "2026-10-23T18:00:00.000Z";
        create.trace.trace.durationMs = 40;

        LineageFile lineage = detector.detect("rec-test", Arrays.asList(create));

        assertEquals(classification(lineage, "2026-10-23T18:00:00.500Z"), LineageEntry.CLASS_ENVIRONMENT, "stamped during the call");
        assertEquals(classification(lineage, "2026-10-30"), LineageEntry.CLASS_USER_INPUT, "a date typed by the user");
        assertEquals(classification(lineage, "2026-01-01T09:00:00Z"), LineageEntry.CLASS_USER_INPUT, "an instant outside the window");
    }

    @Test
    public void unsourcedInputsAreClassifiedByWhereTheyFirstAppear() {
        LoadedTrace create = trace(0, "t-create",
                span("s1", null, 0, "OrderService", "createOrder",
                        "[{\"customer\":{\"name\":\"Ada Lovelace\"}}]", "{\"id\":9001}"),
                span("s2", "s1", 1, "PricingService", "price",
                        "[{\"customer\":{\"name\":\"Ada Lovelace\"},\"catalogVersion\":\"2026-10-01\"}]", "{\"total\":\"10.00\"}"));

        LineageFile lineage = detector.detect("rec-test", Arrays.asList(create));

        assertEquals(classification(lineage, "Ada Lovelace"), LineageEntry.CLASS_USER_INPUT);
        assertEquals(entry(lineage, "Ada Lovelace").to.span, "s1", "classified once, at its first occurrence");
        assertEquals(classification(lineage, "2026-10-01"), LineageEntry.CLASS_ENVIRONMENT, "appears only in a nested span");
        assertNull(entry(lineage, "9001"), "results are sources, never classified inputs");
    }

    @Test
    public void markersEnumsBooleansAndShortStringsAreSkipped() {
        LoadedTrace first = trace(0, "t-1",
                span("s1", null, 0, "A", "a", "[]", "{\"@type\":\"com.acme.Thing\",\"@id\":1,\"status\":\"CONFIRMED\",\"ok\":true,\"code\":\"EUR\",\"city\":\"Amsterdam\"}"));
        LoadedTrace second = trace(1, "t-2",
                span("s1", null, 0, "B", "b", "[{\"@ref\":1,\"@type\":\"com.acme.Thing\",\"status\":\"CONFIRMED\",\"ok\":true,\"code\":\"EUR\",\"city\":\"Amsterdam\"}]", null));

        LineageFile lineage = detector.detect("rec-test", Arrays.asList(first, second));
        assertTrue(lineage.entries.isEmpty(), describe(lineage));
    }

    private static List<LineageEntry> sourced(LineageFile lineage) {
        List<LineageEntry> result = new ArrayList<>();
        for (LineageEntry entry : lineage.entries) {
            if (entry.from != null) {
                result.add(entry);
            }
        }
        return result;
    }

    private static LineageEntry entry(LineageFile lineage, String value) {
        for (LineageEntry entry : lineage.entries) {
            if (value.equals(entry.value)) {
                return entry;
            }
        }
        return null;
    }

    private static String classification(LineageFile lineage, String value) {
        LineageEntry entry = entry(lineage, value);
        return entry == null ? null : entry.classification;
    }

    private static String describe(LineageFile lineage) {
        StringBuilder text = new StringBuilder();
        for (LineageEntry entry : lineage.entries) {
            text.append(entry.to.trace).append('/').append(entry.to.path).append(" <- ")
                    .append(entry.from == null ? entry.classification : entry.from.trace + "/" + entry.from.path)
                    .append(" [").append(entry.value).append("]\n");
        }
        return text.toString();
    }
}
