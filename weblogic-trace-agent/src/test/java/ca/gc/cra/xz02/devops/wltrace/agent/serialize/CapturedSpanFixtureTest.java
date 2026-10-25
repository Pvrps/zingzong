package ca.gc.cra.xz02.devops.wltrace.agent.serialize;

import com.fasterxml.jackson.databind.JsonNode;
import ca.gc.cra.xz02.devops.wltrace.model.EjbInfo;
import ca.gc.cra.xz02.devops.wltrace.model.Span;
import ca.gc.cra.xz02.devops.wltrace.model.Trace;
import ca.gc.cra.xz02.devops.wltrace.model.TraceJson;
import org.testng.annotations.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

/** Proves that recordings captured on WebLogic 14.1.2 carry every marker of the format where the raw-capture rule puts it, and no {@code @truncated} under the production ceilings. */
public class CapturedSpanFixtureTest {

    private static final Set<String> KNOWN_MARKERS = new HashSet<>(Arrays.asList(
            "@type", "@id", "@ref", "@lazy", "@handle", "@skipped", "@serializable", "@entries", "@jms",
            "@truncated", "@text", "@bytes", "@unserializable"));

    @Test
    public void createOrderTraceHasTheExpectedTreeAndMarkers() throws Exception {
        Trace trace = load("captures/create-order-trace.json");
        assertEquals(trace.trace.serverName, "AdminServer");
        assertNull(trace.trace.principal, "anonymous REST call");
        assertEquals(trace.trace.deployments.get(0).moduleType, "ear");
        List<Span> spans = trace.spans;
        assertEquals(spans.size(), 17);
        assertEquals(spans.get(0).http.method, "POST");
        assertEquals(spans.get(1).http.resourceMethod, "create");
        assertEquals(methods(spans.subList(2, 10)),
                Arrays.asList("createOrder", "reserve", "reserve", "price", "computeTax", "rateFor", "unitPrice", "unitPrice"));
        assertEquals(spans.get(2).parentId, "s2");
        assertEquals(spans.get(6).parentId, "s6", "computeTax is a child of price despite living in tax-ear");
        assertEquals(spans.get(6).ejb.invocation, EjbInfo.INVOCATION_REMOTE);
        assertEquals(spans.get(7).ejb.ejb2x, Boolean.TRUE, "rateFor is an EJB 2.x component-interface call");
        for (Span span : spans) {
            if (span == spans.get(1)) {
                assertEquals(span.serializationWarnings.size(), 12, "one warning per runtime handle inside the JAX-RS Response");
                assertTrue(span.serializationWarnings.get(0).reason.startsWith("runtime handle"), span.serializationWarnings.get(0).reason);
            } else {
                assertNull(span.serializationWarnings, "no warnings expected in " + span.id);
            }
        }

        Span createOrder = spans.get(2);
        assertEquals(createOrder.ejb.beanClass, "com.acme.orders.OrderServiceBean");
        assertEquals(createOrder.ejb.app, "orders-ear");
        assertEquals(createOrder.ejb.jndiNames.get(0), "java:global/orders-ear/orders-ejb/OrderService!com.acme.orders.api.OrderServiceLocal", "the invoked view first");
        JsonNode request = createOrder.args.get(0).value;
        assertEquals(request.get("@type").asText(), "com.acme.common.dto.CreateOrderRequest");
        assertEquals(request.get("paymentMethod").get("@type").asText(), "com.acme.common.dto.CardPayment", "the runtime type of a polymorphic field");
        JsonNode order = createOrder.result.value;
        assertEquals(order.get("@type").asText(), "com.acme.common.model.Order");
        assertTrue(order.get("lines").get("@lazy").asBoolean(), "an unloaded JPA collection is marked, not loaded");
        assertFalse(order.get("lines").get("loaded").asBoolean());
        assertFalse(order.get("audit").get("@serializable").asBoolean(), "a non-Serializable value is walked and flagged");
        assertEquals(order.get("status").asText(), "CREATED");
        assertTrue(fieldsSkipped(order).contains("_persistence_fetchGroup"), "weaver-injected fields are listed as skipped: " + fieldsSkipped(order));

        JsonNode response = spans.get(1).result.value;
        assertEquals(spans.get(1).result.declaredType, "javax.ws.rs.core.Response");
        Set<String> kinds = new TreeSet<>();
        for (JsonNode handle : nodesWith(response, "@handle")) {
            kinds.add(handle.get("kind").asText());
        }
        assertEquals(kinds, new TreeSet<>(Arrays.asList("class reference", "stream")), "JAX-RS runtime handles inside the Response");
        List<JsonNode> refs = nodesWith(response, "@ref");
        assertFalse(refs.isEmpty(), "shared objects inside the Response become @ref");
        for (JsonNode ref : refs) {
            assertTrue(idsIn(response).contains(ref.get("@ref").asInt()), "every @ref points at an @id in the same value");
        }

        List<Span> sql = new ArrayList<>();
        for (Span span : spans) {
            if (Span.KIND_SQL.equals(span.kind)) {
                sql.add(span);
            }
        }
        assertEquals(sql.size(), 7);
        assertEquals(sql.get(0).sql.dataSource, "OrdersDS");
        assertTrue(sql.get(0).sql.sql.startsWith("INSERT INTO ORDERS"), sql.get(0).sql.sql);
        assertEquals(sql.get(0).sql.updateCount, Integer.valueOf(1));
        assertEquals(sql.get(0).sql.parameters.get(1).asText(), "EUR");
        assertEquals(sql.get(1).sql.rows, Long.valueOf(1), "CALL IDENTITY() yields one row");

        assertMarkersKnownAndUntruncated(trace);
    }

    @Test
    public void failedConfirmTraceCarriesTheExceptionWithJvmFieldsSkipped() throws Exception {
        Trace trace = load("captures/confirm-unknown-order-trace.json");
        assertEquals(methods(trace.spans.subList(2, 3)), Arrays.asList("confirmOrder"));
        Span confirm = trace.spans.get(2);
        assertEquals(confirm.exception.className, "com.acme.orders.api.OrderNotFoundException");
        assertNull(confirm.result, "a call that threw has no result");
        JsonNode exception = confirm.exception.value;
        assertEquals(exception.get("@type").asText(), "com.acme.orders.api.OrderNotFoundException");
        assertEquals(exception.get("orderId").asLong(), 999999, "the exception's own fields are data");
        assertTrue(exception.get("stackTrace").size() > 10, "the full stack");
        assertEquals(new TreeSet<>(fieldsSkipped(exception)), new TreeSet<>(Arrays.asList("backtrace", "depth")));
        assertEquals(trace.spans.get(0).http.status, Integer.valueOf(404));
        assertMarkersKnownAndUntruncated(trace);
    }

    @Test
    public void messageDrivenTraceRecordsTheMessageAsItsApiView() throws Exception {
        Trace trace = load("captures/order-event-message-trace.json");
        assertNotNull(trace.trace.parentTraceId, "caused by the request that sent the message");
        Span onMessage = trace.spans.get(0);
        assertEquals(onMessage.ejb.invocation, EjbInfo.INVOCATION_MESSAGE);
        assertEquals(onMessage.ejb.beanType, "MESSAGE_DRIVEN");
        JsonNode message = onMessage.args.get(0).value;
        assertEquals(message.get("@jms").asText(), "TextMessage");
        assertTrue(message.get("headers").has("JMSMessageID"));
        assertTrue(message.get("text").asText().contains("orderId"), message.get("text").asText());
        assertEquals(trace.spans.get(1).ejb.method, "record");
        assertMarkersKnownAndUntruncated(trace);
    }

    private static void assertMarkersKnownAndUntruncated(Trace trace) throws Exception {
        JsonNode root = TraceJson.mapper().readTree(TraceJson.toJson(trace));
        Set<String> markers = new TreeSet<>();
        collectMarkers(root, markers);
        for (String marker : markers) {
            assertTrue(KNOWN_MARKERS.contains(marker), "unknown marker " + marker);
        }
        assertFalse(markers.contains("@truncated"), "production ceilings are never hit by the sample payloads");
        assertFalse(markers.contains("@unserializable"));
        assertTrue(markers.contains("@type"));
    }

    private static void collectMarkers(JsonNode node, Set<String> into) {
        if (node.isObject()) {
            for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                if (e.getKey().startsWith("@")) {
                    into.add(e.getKey());
                }
                collectMarkers(e.getValue(), into);
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                collectMarkers(child, into);
            }
        }
    }

    private static List<JsonNode> nodesWith(JsonNode node, String marker) {
        List<JsonNode> found = new ArrayList<>();
        if (node.isObject()) {
            if (node.has(marker)) {
                found.add(node);
            }
            for (JsonNode child : node) {
                found.addAll(nodesWith(child, marker));
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                found.addAll(nodesWith(child, marker));
            }
        }
        return found;
    }

    private static Set<Integer> idsIn(JsonNode node) {
        Set<Integer> ids = new HashSet<>();
        for (JsonNode n : nodesWith(node, "@id")) {
            ids.add(n.get("@id").asInt());
        }
        return ids;
    }

    private static List<String> fieldsSkipped(JsonNode object) {
        List<String> names = new ArrayList<>();
        if (object.has("@skipped")) {
            for (JsonNode s : object.get("@skipped")) {
                names.add(s.get("field").asText());
            }
        }
        return names;
    }

    private static Trace load(String resource) throws Exception {
        try (InputStream in = CapturedSpanFixtureTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, resource);
            Trace trace = TraceJson.read(in);
            assertEquals(trace.formatVersion, 1);
            return trace;
        }
    }

    private static List<String> methods(List<Span> spans) {
        List<String> names = new ArrayList<>();
        for (Span span : spans) {
            names.add(span.ejb.method);
        }
        return names;
    }
}
