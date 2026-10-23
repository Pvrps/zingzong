package io.capture.agent.serialize;

import com.acme.common.dto.CardPayment;
import com.acme.common.dto.ConfirmRequest;
import com.acme.common.dto.ConfirmResult;
import com.acme.common.dto.CreateOrderRequest;
import com.acme.common.dto.LoyaltyTier;
import com.acme.common.dto.TaxCategory;
import com.acme.common.dto.TaxRequest;
import com.acme.common.dto.TaxResult;
import com.acme.common.model.AuditTrail;
import com.acme.common.model.Order;
import com.acme.common.model.OrderStatus;
import io.capture.trace.Span;
import io.capture.trace.Trace;
import io.capture.trace.TraceJson;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Traces captured on the real WebLogic server (recording rec-20261023-161102: one Create
 * order, one Confirm order), deserialized against common-dto. The acceptance check that a
 * captured trace round-trips.
 */
class CapturedSpanFixtureTest {

    private final JacksonGraphSerializer serializer = new JacksonGraphSerializer();
    private final ClassLoader loader = CapturedSpanFixtureTest.class.getClassLoader();

    @Test
    void createOrderTraceHasTheExpectedTree() throws Exception {
        Trace trace = load("captures/create-order-trace.json");
        assertEquals("AdminServer", trace.trace.serverName);
        assertNull(trace.trace.principal, "anonymous REST call");
        List<Span> spans = trace.spans;
        assertEquals(9, spans.size());
        assertEquals(Arrays.asList("createOrder", "reserve", "reserve", "price", "unitPrice", "unitPrice", "computeTax", "unitPrice", "unitPrice"), methods(spans));
        assertNull(spans.get(0).parentId);
        assertEquals("s1", spans.get(3).parentId);
        assertEquals("s4", spans.get(6).parentId, "computeTax is a child of price despite living in tax-ear");
        assertEquals(2, spans.get(6).depth);
        assertEquals(Boolean.TRUE, spans.get(4).selfInvocation);
        assertNull(spans.get(7).selfInvocation, "unitPrice called by OrderService is a real EJB call");
        assertEquals("ACTIVE", spans.get(0).ejb.transaction);
        for (Span span : spans) {
            assertNull(span.serializationWarnings, "no warnings expected in " + span.id);
        }
    }

    @Test
    void createOrderRootSpanDeserializesArgumentAndResult() throws Exception {
        Span span = load("captures/create-order-trace.json").spans.get(0);
        assertEquals("com.acme.orders.OrderServiceBean", span.ejb.beanClass);
        assertEquals("com.acme.common.model.Order createOrder(com.acme.common.dto.CreateOrderRequest)", span.ejb.signature);

        CreateOrderRequest request = (CreateOrderRequest) serializer.deserialize(
                span.args.get(0).value, loader.loadClass(span.args.get(0).declaredType), loader);
        assertEquals("Ada Lovelace", request.getCustomer().getName());
        assertEquals(LoyaltyTier.GOLD, request.getCustomer().getLoyaltyTier());
        assertEquals("NL", request.getCustomer().getAddress().getCountry());
        assertEquals(2, request.getLines().size());
        assertEquals("GADGET-2", request.getLines().get(1).getSku());
        assertEquals("autumn-2026", request.getAttributes().get("campaign"));
        assertEquals(LocalDate.of(2026, 10, 30), request.getRequestedDeliveryDate());
        assertEquals(CardPayment.class, request.getPaymentMethod().getClass());
        assertEquals(YearMonth.of(2028, 6), ((CardPayment) request.getPaymentMethod()).getExpiry());

        Order order = (Order) serializer.deserialize(span.result.value, loader.loadClass(span.result.declaredType), loader);
        assertEquals(OrderStatus.CREATED, order.getStatus());
        assertEquals(new BigDecimal("149.73"), order.getTotal());
        assertEquals(2, order.getTotal().scale());
        assertEquals("card ****4242 exp 2028-06", order.getPaymentSummary());
        assertTrue(order.getLines().isEmpty(), "lazy placeholder leaves the constructor's empty list");

        assertTrue(span.result.value.get("lines").get("@lazy").asBoolean());
        assertFalse(span.result.value.get("audit").get("@serializable").asBoolean());
        AuditTrail audit = order.getAudit();
        assertNotNull(audit);
        assertEquals(0, audit.getEntryCount());
        assertEquals(span.result.value.get("audit").get("openedAt").asText(), audit.getOpenedAt().toString());
    }

    @Test
    void computeTaxSpanDeserializesAcrossTheEarBoundary() throws Exception {
        Span span = load("captures/create-order-trace.json").spans.get(6);
        assertEquals("com.acme.tax.TaxServiceBean", span.ejb.beanClass);

        TaxRequest request = (TaxRequest) serializer.deserialize(span.args.get(0).value, TaxRequest.class, loader);
        assertEquals(TaxCategory.GOODS, request.getCategory());
        assertEquals(new BigDecimal("123.74"), request.getAmount());

        TaxResult result = (TaxResult) serializer.deserialize(span.result.value, TaxResult.class, loader);
        assertEquals("NL-NH", result.getJurisdiction());
        assertEquals(new BigDecimal("0.21"), result.getRate());
        assertEquals(new BigDecimal("25.99"), result.getTax());
    }

    @Test
    void confirmOrderTraceLinksToTheCreatedOrderId() throws Exception {
        Trace create = load("captures/create-order-trace.json");
        Trace confirm = load("captures/confirm-order-trace.json");
        assertEquals(Arrays.asList("confirmOrder", "findOrder"), methods(confirm.spans));
        assertEquals(Boolean.TRUE, confirm.spans.get(1).selfInvocation);

        long createdId = create.spans.get(0).result.value.get("id").asLong();
        ConfirmRequest request = (ConfirmRequest) serializer.deserialize(confirm.spans.get(0).args.get(0).value, ConfirmRequest.class, loader);
        assertEquals(createdId, request.getOrderId(), "the id typed into Confirm came from Create: Phase 4 lineage material");
        ConfirmResult result = (ConfirmResult) serializer.deserialize(confirm.spans.get(0).result.value, ConfirmResult.class, loader);
        assertEquals(OrderStatus.CONFIRMED, result.getStatus());
        assertEquals(2, result.getMessages().size());
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
        String[] names = new String[spans.size()];
        for (int i = 0; i < names.length; i++) {
            names[i] = spans.get(i).ejb.method;
        }
        return Arrays.asList(names);
    }
}
