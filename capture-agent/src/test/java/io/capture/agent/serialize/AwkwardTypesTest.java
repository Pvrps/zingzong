package io.capture.agent.serialize;

import com.fasterxml.jackson.databind.JsonNode;
import io.capture.agent.fixtures.Address;
import io.capture.agent.fixtures.AuditTrail;
import io.capture.agent.fixtures.CardPayment;
import io.capture.agent.fixtures.ConfirmRequest;
import io.capture.agent.fixtures.ConfirmResult;
import io.capture.agent.fixtures.CreateOrderRequest;
import io.capture.agent.fixtures.Customer;
import io.capture.agent.fixtures.InvoicePayment;
import io.capture.agent.fixtures.LoyaltyTier;
import io.capture.agent.fixtures.Order;
import io.capture.agent.fixtures.OrderLine;
import io.capture.agent.fixtures.OrderLineRequest;
import io.capture.agent.fixtures.OrderStatus;
import io.capture.agent.fixtures.PaymentMethod;
import io.capture.agent.fixtures.PriceQuote;
import io.capture.agent.fixtures.Reservation;
import io.capture.agent.fixtures.TaxCategory;
import io.capture.agent.fixtures.TaxRequest;
import io.capture.agent.fixtures.TaxResult;
import org.eclipse.persistence.indirection.IndirectList;
import org.eclipse.persistence.indirection.ValueHolder;
import org.eclipse.persistence.indirection.ValueHolderInterface;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Proves the JSON form of every awkward type in the fixtures package: nested objects, polymorphism, lazy JPA state, cycles, non-serializable data. */
class AwkwardTypesTest {

    private final Graphs graphs = new Graphs();

    @Test
    void nestedObjectsWithTypeOnEveryObject() {
        Graphs.Result r = graphs.write(sampleRequest(new CardPayment("4242", YearMonth.of(2028, 6))));
        JsonNode n = r.node;
        assertEquals("io.capture.agent.fixtures.CreateOrderRequest", n.get("@type").asText());
        assertEquals("io.capture.agent.fixtures.Customer", n.get("customer").get("@type").asText());
        assertEquals("Amsterdam", n.get("customer").get("address").get("city").asText());
        assertEquals("NL", n.get("customer").get("address").get("country").asText());
        assertTrue(r.warnings.isEmpty(), r.warnings());
    }

    @Test
    void listsAndStringKeyedMaps() {
        JsonNode n = graphs.write(sampleRequest(null)).node;
        assertTrue(n.get("lines").isArray());
        assertEquals(2, n.get("lines").size());
        assertEquals("WIDGET-1", n.get("lines").get(0).get("sku").asText());
        assertEquals(3, n.get("lines").get(0).get("quantity").asInt());
        assertEquals("io.capture.agent.fixtures.OrderLineRequest", n.get("lines").get(1).get("@type").asText());
        assertEquals("web", n.get("attributes").get("channel").asText(), "string-keyed map is a plain JSON object");
        assertFalse(n.get("attributes").has("@type"));
        assertTrue(n.get("paymentMethod").isNull());
    }

    @Test
    void enumsByName() {
        JsonNode n = graphs.write(new TaxRequest("NL", "NH", TaxCategory.SERVICES, new BigDecimal("10.00"))).node;
        assertEquals("SERVICES", n.get("category").asText());
        assertEquals("GOLD", graphs.write(sampleRequest(null)).node.get("customer").get("loyaltyTier").asText());
    }

    @Test
    void javaTimeAndUtilDateAsIsoStrings() {
        CreateOrderRequest request = sampleRequest(null);
        request.setRequestedAt(Instant.parse("2026-10-23T18:01:12.345Z"));
        request.setRequestedDeliveryDate(LocalDate.of(2026, 10, 30));
        JsonNode n = graphs.write(request).node;
        assertEquals("2026-10-23T18:01:12.345Z", n.get("requestedAt").asText());
        assertEquals("2026-10-30", n.get("requestedDeliveryDate").asText());

        Reservation reservation = new Reservation("r1", "WIDGET-1", 2, "WH", LocalDateTime.of(2026, 10, 23, 14, 30, 15));
        assertEquals("2026-10-23T14:30:15", graphs.write(reservation).node.get("reservedUntil").asText());

        ConfirmRequest confirm = new ConfirmRequest(42, "ada", new Date(1_800_000_000_000L), null);
        JsonNode c = graphs.write(confirm).node;
        assertEquals("2027-01-15T08:00:00Z", c.get("confirmedAt").asText());
        assertEquals(42, c.get("orderId").asLong());
        assertTrue(c.get("notes").isNull());
    }

    @Test
    void bigDecimalAsStringWithScalePreserved() {
        Map<String, BigDecimal> lineTotals = new LinkedHashMap<>();
        lineTotals.put("WIDGET-1", new BigDecimal("37.50"));
        lineTotals.put("GADGET-2", new BigDecimal("99.99"));
        TaxResult tax = new TaxResult("NL-NH", new BigDecimal("0.21"), new BigDecimal("25.99"), Instant.parse("2026-10-23T18:00:00Z"));
        PriceQuote quote = new PriceQuote("EUR", lineTotals, new BigDecimal("123.74"), tax, new BigDecimal("149.73"));

        JsonNode n = graphs.write(quote).node;
        assertTrue(n.get("subtotal").isTextual());
        assertEquals("123.74", n.get("subtotal").asText());
        assertEquals("0.21", n.get("tax").get("rate").asText());
        assertEquals("37.50", n.get("lineTotals").get("WIDGET-1").asText(), "the scale survives as text");
    }

    @Test
    void polymorphicPaymentMethodsCarryTheirRuntimeType() {
        JsonNode card = graphs.write(sampleRequest(new CardPayment("4242", YearMonth.of(2028, 6)))).node;
        assertEquals("io.capture.agent.fixtures.CardPayment", card.get("paymentMethod").get("@type").asText());
        assertEquals("2028-06", card.get("paymentMethod").get("expiry").asText());

        JsonNode invoice = graphs.write(sampleRequest(new InvoicePayment("PO-7781", 45))).node;
        assertEquals("io.capture.agent.fixtures.InvoicePayment", invoice.get("paymentMethod").get("@type").asText());
        assertEquals(45, invoice.get("paymentMethod").get("netDays").asInt());
    }

    @Test
    void unloadedIndirectListIsMarkedLazyAndNeverInstantiated() throws Exception {
        Order order = sampleOrder();
        AtomicInteger loads = new AtomicInteger();
        setLines(order, unloadedIndirectList(loads));

        Graphs.Result r = graphs.write(order);
        JsonNode lines = r.node.get("lines");
        assertTrue(lines.get("@lazy").asBoolean(), r.json());
        assertFalse(lines.get("loaded").asBoolean());
        assertEquals(0, loads.get(), "serializer must not instantiate the lazy collection");
        assertFalse(r.hasWarning("lines", ""), "an unloaded lazy collection is expected, not a warning: " + r.warnings());
    }

    @Test
    void loadedIndirectListSerializesItsContents() throws Exception {
        Order order = sampleOrder();
        IndirectList<OrderLine> loaded = new IndirectList<>();
        loaded.setValueHolder(new ValueHolder(new java.util.Vector<>(Arrays.asList(new OrderLine(order, "GIZMO-3", 1, new BigDecimal("1499.00"))))));
        setLines(order, loaded);

        Graphs.Result r = graphs.write(order);
        assertTrue(r.node.get("lines").isArray(), r.json());
        assertEquals("GIZMO-3", r.node.get("lines").get(0).get("sku").asText());
    }

    @Test
    void orderLineBackReferenceTerminatesAsRef() {
        Order order = sampleOrder();
        order.addLine("WIDGET-1", 3, new BigDecimal("12.50"));
        order.addLine("GADGET-2", 1, new BigDecimal("99.99"));

        Graphs.Result r = graphs.write(order);
        JsonNode n = r.node;
        assertEquals(1, n.get("@id").asInt(), r.json());
        assertEquals(1, n.get("lines").get(0).get("order").get("@ref").asInt());
        assertEquals(1, n.get("lines").get(1).get("order").get("@ref").asInt());
        assertEquals("12.50", n.get("lines").get(0).get("unitPrice").asText());
        assertTrue(r.warnings.isEmpty(), r.warnings());
    }

    @Test
    void nonSerializableAuditTrailIsCapturedAndFlaggedWithoutWarning() {
        Order order = sampleOrder();
        order.setStatus(OrderStatus.CONFIRMED);

        Graphs.Result r = graphs.write(order);
        JsonNode audit = r.node.get("audit");
        assertEquals(AuditTrail.class.getName(), audit.get("@type").asText(), r.json());
        assertFalse(audit.get("@serializable").asBoolean(), r.json());
        assertEquals(1, audit.get("entryCount").asInt());
        assertEquals(order.getAudit().getOpenedAt().toString(), audit.get("openedAt").asText());
        assertTrue(audit.get("entries").asText().endsWith("status -> CONFIRMED\n"));
        assertEquals("java.lang.Object", audit.get("lock").get("@type").asText(), "a bare monitor object is captured as an empty object");
        assertTrue(r.warnings.isEmpty(), r.warnings());
        assertFalse(r.node.has("@serializable"), "Order itself is Serializable");
    }

    @Test
    void confirmResultWithListOfStrings() {
        ConfirmResult result = new ConfirmResult(11, OrderStatus.CONFIRMED, Instant.parse("2026-10-23T19:31:32.806Z"),
                Arrays.asList("Confirmed by ada", "Notes: gift-wrap"));
        JsonNode n = graphs.write(result).node;
        assertEquals(11, n.get("orderId").asLong());
        assertEquals("CONFIRMED", n.get("status").asText());
        assertEquals("Notes: gift-wrap", n.get("messages").get(1).asText());
    }

    static CreateOrderRequest sampleRequest(PaymentMethod payment) {
        CreateOrderRequest request = new CreateOrderRequest();
        request.setCustomer(new Customer("Ada Lovelace", "ada@example.com", LoyaltyTier.GOLD,
                new Address("Keizersgracht 1", "Amsterdam", "NH", "1015 CC", "NL")));
        request.getLines().add(new OrderLineRequest("WIDGET-1", 3));
        request.getLines().add(new OrderLineRequest("GADGET-2", 1));
        request.getAttributes().put("channel", "web");
        request.getAttributes().put("campaign", "autumn");
        request.setRequestedDeliveryDate(LocalDate.of(2026, 10, 30));
        request.setRequestedAt(Instant.parse("2026-10-23T18:00:00Z"));
        request.setPaymentMethod(payment);
        return request;
    }

    static Order sampleOrder() {
        Order order = new Order();
        order.setCustomerName("Ada Lovelace");
        order.setCustomerEmail("ada@example.com");
        order.setCreatedAt(LocalDateTime.of(2026, 10, 23, 15, 8, 18));
        order.setDeliveryDate(LocalDate.of(2026, 10, 30));
        order.setCurrency("EUR");
        order.setSubtotal(new BigDecimal("123.74"));
        order.setTax(new BigDecimal("25.99"));
        order.setTotal(new BigDecimal("149.73"));
        order.setPaymentSummary("card ****4242 exp 2028-06");
        return order;
    }

    static void setLines(Order order, List<OrderLine> lines) throws Exception {
        Field field = Order.class.getDeclaredField("lines");
        field.setAccessible(true);
        field.set(order, lines);
    }

    /** A real EclipseLink IndirectList whose value holder counts, and fails, any attempt to load. */
    static IndirectList<OrderLine> unloadedIndirectList(final AtomicInteger loads) {
        IndirectList<OrderLine> list = new IndirectList<>();
        list.setValueHolder(new ValueHolderInterface() {
            @Override
            public Object getValue() {
                loads.incrementAndGet();
                throw new IllegalStateException("lazy collection was instantiated by the serializer");
            }

            @Override
            public void setValue(Object value) {
            }

            @Override
            public boolean isInstantiated() {
                return false;
            }

            @Override
            public Object clone() {
                return this;
            }
        });
        return list;
    }
}
