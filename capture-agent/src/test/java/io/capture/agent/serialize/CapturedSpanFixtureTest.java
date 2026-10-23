package io.capture.agent.serialize;

import com.acme.common.dto.CardPayment;
import com.acme.common.dto.CreateOrderRequest;
import com.acme.common.dto.LoyaltyTier;
import com.acme.common.dto.TaxCategory;
import com.acme.common.dto.TaxRequest;
import com.acme.common.dto.TaxResult;
import com.acme.common.model.Order;
import com.acme.common.model.OrderStatus;
import io.capture.trace.Span;
import io.capture.trace.Trace;
import io.capture.trace.TraceJson;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Files captured on the real WebLogic server (recording rec-20261023-155008), deserialized
 * against common-dto: the acceptance check that a captured span round-trips.
 */
class CapturedSpanFixtureTest {

    private final JacksonGraphSerializer serializer = new JacksonGraphSerializer();
    private final ClassLoader loader = CapturedSpanFixtureTest.class.getClassLoader();

    @Test
    void createOrderSpanDeserializesArgumentAndResult() throws Exception {
        Span span = load("captures/create-order-span.json");
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
        assertEquals(Instant.parse("2026-10-23T19:50:08.506498989Z"), request.getRequestedAt());
        assertEquals(CardPayment.class, request.getPaymentMethod().getClass());
        assertEquals(YearMonth.of(2028, 6), ((CardPayment) request.getPaymentMethod()).getExpiry());

        Order order = (Order) serializer.deserialize(span.result.value, loader.loadClass(span.result.declaredType), loader);
        assertEquals(17L, order.getId());
        assertEquals(OrderStatus.CREATED, order.getStatus());
        assertEquals(new BigDecimal("149.73"), order.getTotal());
        assertEquals(2, order.getTotal().scale());
        assertEquals(LocalDateTime.parse("2026-10-23T15:50:08.557116"), order.getCreatedAt());
        assertEquals("card ****4242 exp 2028-06", order.getPaymentSummary());
        assertTrue(order.getLines().isEmpty(), "lazy placeholder leaves the constructor's empty list");
        assertNotNull(order.getAudit(), "placeholder leaves the constructor's AuditTrail");

        assertEquals(1, span.serializationWarnings.size());
        assertEquals("$.result.audit", span.serializationWarnings.get(0).path);
        assertEquals("not java.io.Serializable", span.serializationWarnings.get(0).reason);
    }

    @Test
    void computeTaxSpanDeserializesAcrossTheEarBoundary() throws Exception {
        Span span = load("captures/compute-tax-span.json");
        assertEquals("com.acme.tax.TaxServiceBean", span.ejb.beanClass);

        TaxRequest request = (TaxRequest) serializer.deserialize(span.args.get(0).value, TaxRequest.class, loader);
        assertEquals(TaxCategory.GOODS, request.getCategory());
        assertEquals(new BigDecimal("123.74"), request.getAmount());

        TaxResult result = (TaxResult) serializer.deserialize(span.result.value, TaxResult.class, loader);
        assertEquals("NL-NH", result.getJurisdiction());
        assertEquals(new BigDecimal("0.21"), result.getRate());
        assertEquals(new BigDecimal("25.99"), result.getTax());
    }

    private static Span load(String resource) throws Exception {
        try (InputStream in = CapturedSpanFixtureTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, resource);
            Trace trace = TraceJson.read(in);
            assertEquals(1, trace.formatVersion);
            return trace.spans.get(0);
        }
    }
}
