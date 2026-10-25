package ca.gc.cra.xz02.devops.wltrace.model;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Proves that the trace model round-trips through the shared mapper with every field intact. */
class TraceJsonTest {

    @Test
    void roundTripsTheExampleFromThePlan() throws Exception {
        Trace trace = sampleTrace();

        String json = TraceJson.toJson(trace);
        Trace back = TraceJson.fromJson(json);

        assertEquals(1, back.formatVersion);
        assertEquals("rec-20261023-1401", back.recording.id);
        assertEquals(2, back.recording.seq);
        assertEquals(2, back.spans.size());
        Span ejb = back.spans.get(1);
        assertEquals("s1", ejb.parentId);
        assertEquals("com.acme.orders.api.OrderServiceRemote", ejb.ejb.interfaceName);
        assertEquals(42, ejb.args.get(0).value.get("orderId").asInt());
        assertNull(back.lineage);
        assertEquals(json, TraceJson.toJson(back));
    }

    @Test
    void usesThePlannedJsonKeysAndOmitsNulls() throws Exception {
        JsonNode node = TraceJson.mapper().readTree(TraceJson.toJson(sampleTrace()));

        assertTrue(node.get("spans").get(1).get("ejb").has("interface"));
        assertFalse(node.get("spans").get(1).get("ejb").has("interfaceName"));
        assertFalse(node.get("spans").get(0).has("ejb"));
        assertFalse(node.has("lineage"));
        assertFalse(node.get("spans").get(1).has("exception"), "null fields are omitted, not written as null");
    }

    @Test
    void readerToleratesUnknownFieldsFromNewerWriters() throws Exception {
        Trace back = TraceJson.fromJson("{\"formatVersion\":1,\"trace\":{\"id\":\"t\"},\"spans\":[],\"futureField\":true}");
        assertEquals("t", back.trace.id);
    }

    private static Trace sampleTrace() throws Exception {
        Trace trace = new Trace();
        trace.recording = new RecordingRef();
        trace.recording.id = "rec-20261023-1401";
        trace.recording.name = "create-and-confirm-order";
        trace.recording.seq = 2;

        trace.trace = new TraceInfo();
        trace.trace.id = "t-8c1f";
        trace.trace.startedAt = "2026-10-23T18:01:12.345Z";
        trace.trace.durationMs = 142;
        trace.trace.principal = "weblogic";
        trace.trace.serverName = "AdminServer";

        Span http = new Span();
        http.id = "s1";
        http.depth = 0;
        http.order = 0;
        http.kind = Span.KIND_HTTP;
        http.http = new HttpInfo();
        http.http.method = "POST";
        http.http.path = "/orders/api/orders/42/confirm";
        http.http.status = 200;
        http.app = "orders-ear";
        http.module = "orders-web";
        http.thread = "[ACTIVE] ExecuteThread: '3'";
        http.durationNs = 142000000L;

        Span ejb = new Span();
        ejb.id = "s2";
        ejb.parentId = "s1";
        ejb.depth = 1;
        ejb.order = 1;
        ejb.kind = Span.KIND_EJB;
        ejb.ejb = new EjbInfo();
        ejb.ejb.app = "orders-ear";
        ejb.ejb.module = "orders-ejb";
        ejb.ejb.beanName = "OrderService";
        ejb.ejb.beanClass = "com.acme.orders.OrderServiceBean";
        ejb.ejb.interfaceName = "com.acme.orders.api.OrderServiceRemote";
        ejb.ejb.invocation = EjbInfo.INVOCATION_REMOTE;
        ejb.ejb.jndiNames = Arrays.asList(
                "java:global/orders-ear/orders-ejb/OrderService!com.acme.orders.api.OrderServiceRemote",
                "orders/OrderService");
        ejb.ejb.method = "confirmOrder";
        ejb.ejb.signature = "com.acme.common.dto.ConfirmResult confirmOrder(com.acme.common.dto.ConfirmRequest)";
        ejb.ejb.transactionAttribute = "REQUIRED";
        ValueCapture arg = new ValueCapture();
        arg.declaredType = "com.acme.common.dto.ConfirmRequest";
        arg.value = TraceJson.mapper().readTree("{\"@type\":\"com.acme.common.dto.ConfirmRequest\",\"orderId\":42}");
        ejb.args = Collections.singletonList(arg);
        ejb.result = new ValueCapture();
        ejb.result.declaredType = "com.acme.common.dto.ConfirmResult";
        ejb.result.value = TraceJson.mapper().readTree("{\"status\":\"CONFIRMED\"}");
        ejb.serializationWarnings = Collections.singletonList(
                new SerializationWarning("$.args[0].lines[0].product.supplier", "depth limit", "com.acme.Supplier"));
        ejb.startNs = 3100000L;
        ejb.durationNs = 97000000L;

        trace.spans.add(http);
        trace.spans.add(ejb);
        return trace;
    }
}
