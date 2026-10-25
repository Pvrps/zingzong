package io.capture.agent.serialize;

import com.fasterxml.jackson.databind.JsonNode;
import io.capture.trace.EjbInfo;
import io.capture.trace.Span;
import io.capture.trace.Trace;
import io.capture.trace.TraceJson;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves that recordings captured on WebLogic 14.1.2 carry the format's markers where the
 * raw-capture rule puts them: {@code @type} on every object, {@code @ref} for a repeated object,
 * {@code @lazy} for an unloaded JPA collection, {@code @serializable: false} for a non-Serializable
 * value, {@code @skipped} for weaver- and JVM-injected fields, {@code @handle} for runtime
 * handles, {@code @jms} for a message, and no {@code @truncated} under the production ceilings.
 */
class CapturedSpanFixtureTest {

    private static final Set<String> KNOWN_MARKERS = new HashSet<>(Arrays.asList(
            "@type", "@id", "@ref", "@lazy", "@handle", "@skipped", "@serializable", "@entries", "@jms",
            "@truncated", "@text", "@bytes", "@unserializable"));

    @Test
    void createOrderTraceHasTheExpectedTreeAndMarkers() throws Exception {
        Trace trace = load("captures/create-order-trace.json");
        assertEquals("AdminServer", trace.trace.serverName);
        assertNull(trace.trace.principal, "anonymous REST call");
        assertEquals("ear", trace.trace.deployments.get(0).moduleType);
        List<Span> spans = trace.spans;
        assertEquals(17, spans.size());
        assertEquals("POST", spans.get(0).http.method);
        assertEquals("create", spans.get(1).http.resourceMethod);
        assertEquals(Arrays.asList("createOrder", "reserve", "reserve", "price", "computeTax", "rateFor", "unitPrice", "unitPrice"),
                methods(spans.subList(2, 10)));
        assertEquals("s2", spans.get(2).parentId);
        assertEquals("s6", spans.get(6).parentId, "computeTax is a child of price despite living in tax-ear");
        assertEquals(EjbInfo.INVOCATION_REMOTE, spans.get(6).ejb.invocation);
        assertEquals(Boolean.TRUE, spans.get(7).ejb.ejb2x, "rateFor is an EJB 2.x component-interface call");
        for (Span span : spans) {
            if (span == spans.get(1)) {
                assertEquals(12, span.serializationWarnings.size(), "one warning per runtime handle inside the JAX-RS Response");
                assertTrue(span.serializationWarnings.get(0).reason.startsWith("runtime handle"), span.serializationWarnings.get(0).reason);
            } else {
                assertNull(span.serializationWarnings, "no warnings expected in " + span.id);
            }
        }

        Span createOrder = spans.get(2);
        assertEquals("com.acme.orders.OrderServiceBean", createOrder.ejb.beanClass);
        assertEquals("orders-ear", createOrder.ejb.app);
        assertEquals("java:global/orders-ear/orders-ejb/OrderService!com.acme.orders.api.OrderServiceLocal", createOrder.ejb.jndiNames.get(0), "the invoked view first");
        JsonNode request = createOrder.args.get(0).value;
        assertEquals("com.acme.common.dto.CreateOrderRequest", request.get("@type").asText());
        assertEquals("com.acme.common.dto.CardPayment", request.get("paymentMethod").get("@type").asText(), "the runtime type of a polymorphic field");
        JsonNode order = createOrder.result.value;
        assertEquals("com.acme.common.model.Order", order.get("@type").asText());
        assertTrue(order.get("lines").get("@lazy").asBoolean(), "an unloaded JPA collection is marked, not loaded");
        assertFalse(order.get("lines").get("loaded").asBoolean());
        assertFalse(order.get("audit").get("@serializable").asBoolean(), "a non-Serializable value is walked and flagged");
        assertEquals("CREATED", order.get("status").asText());
        assertTrue(fieldsSkipped(order).contains("_persistence_fetchGroup"), "weaver-injected fields are listed as skipped: " + fieldsSkipped(order));

        JsonNode response = spans.get(1).result.value;
        assertEquals("javax.ws.rs.core.Response", spans.get(1).result.declaredType);
        Set<String> kinds = new TreeSet<>();
        for (JsonNode handle : nodesWith(response, "@handle")) {
            kinds.add(handle.get("kind").asText());
        }
        assertEquals(new TreeSet<>(Arrays.asList("class reference", "stream")), kinds, "JAX-RS runtime handles inside the Response");
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
        assertEquals(7, sql.size());
        assertEquals("OrdersDS", sql.get(0).sql.dataSource);
        assertTrue(sql.get(0).sql.sql.startsWith("INSERT INTO ORDERS"), sql.get(0).sql.sql);
        assertEquals(Integer.valueOf(1), sql.get(0).sql.updateCount);
        assertEquals("EUR", sql.get(0).sql.parameters.get(1).asText());
        assertEquals(Long.valueOf(1), sql.get(1).sql.rows, "CALL IDENTITY() yields one row");

        assertMarkersKnownAndUntruncated(trace);
    }

    @Test
    void failedConfirmTraceCarriesTheExceptionWithJvmFieldsSkipped() throws Exception {
        Trace trace = load("captures/confirm-unknown-order-trace.json");
        assertEquals(Arrays.asList("confirmOrder"), methods(trace.spans.subList(2, 3)));
        Span confirm = trace.spans.get(2);
        assertEquals("com.acme.orders.api.OrderNotFoundException", confirm.exception.className);
        assertNull(confirm.result, "a call that threw has no result");
        JsonNode exception = confirm.exception.value;
        assertEquals("com.acme.orders.api.OrderNotFoundException", exception.get("@type").asText());
        assertEquals(999999, exception.get("orderId").asLong(), "the exception's own fields are data");
        assertTrue(exception.get("stackTrace").size() > 10, "the full stack");
        assertEquals(new TreeSet<>(Arrays.asList("backtrace", "depth")), new TreeSet<>(fieldsSkipped(exception)));
        assertEquals(Integer.valueOf(404), trace.spans.get(0).http.status);
        assertMarkersKnownAndUntruncated(trace);
    }

    @Test
    void messageDrivenTraceRecordsTheMessageAsItsApiView() throws Exception {
        Trace trace = load("captures/order-event-message-trace.json");
        assertNotNull(trace.trace.parentTraceId, "caused by the request that sent the message");
        Span onMessage = trace.spans.get(0);
        assertEquals(EjbInfo.INVOCATION_MESSAGE, onMessage.ejb.invocation);
        assertEquals("MESSAGE_DRIVEN", onMessage.ejb.beanType);
        JsonNode message = onMessage.args.get(0).value;
        assertEquals("TextMessage", message.get("@jms").asText());
        assertTrue(message.get("headers").has("JMSMessageID"));
        assertTrue(message.get("text").asText().contains("orderId"), message.get("text").asText());
        assertEquals("record", trace.spans.get(1).ejb.method);
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
            assertEquals(1, trace.formatVersion);
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
