package ca.gc.cra.xz02.devops.wltrace.agent.serialize;

import com.fasterxml.jackson.databind.JsonNode;
import ca.gc.cra.xz02.devops.wltrace.agent.fixtures.Address;
import ca.gc.cra.xz02.devops.wltrace.agent.fixtures.AuditTrail;
import ca.gc.cra.xz02.devops.wltrace.agent.fixtures.CardPayment;
import ca.gc.cra.xz02.devops.wltrace.agent.fixtures.ConfirmRequest;
import ca.gc.cra.xz02.devops.wltrace.agent.fixtures.ConfirmResult;
import ca.gc.cra.xz02.devops.wltrace.agent.fixtures.CreateOrderRequest;
import ca.gc.cra.xz02.devops.wltrace.agent.fixtures.Customer;
import ca.gc.cra.xz02.devops.wltrace.agent.fixtures.InvoicePayment;
import ca.gc.cra.xz02.devops.wltrace.agent.fixtures.LoyaltyTier;
import ca.gc.cra.xz02.devops.wltrace.agent.fixtures.Order;
import ca.gc.cra.xz02.devops.wltrace.agent.fixtures.OrderLine;
import ca.gc.cra.xz02.devops.wltrace.agent.fixtures.OrderLineRequest;
import ca.gc.cra.xz02.devops.wltrace.agent.fixtures.OrderStatus;
import ca.gc.cra.xz02.devops.wltrace.agent.fixtures.PaymentMethod;
import ca.gc.cra.xz02.devops.wltrace.agent.fixtures.PriceQuote;
import ca.gc.cra.xz02.devops.wltrace.agent.fixtures.Reservation;
import ca.gc.cra.xz02.devops.wltrace.agent.fixtures.TaxCategory;
import ca.gc.cra.xz02.devops.wltrace.agent.fixtures.TaxRequest;
import ca.gc.cra.xz02.devops.wltrace.agent.fixtures.TaxResult;
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
        Graphs.Result result = graphs.write(sampleRequest(new CardPayment("4242", YearMonth.of(2028, 6))));
        JsonNode node = result.node;
        assertEquals("ca.gc.cra.xz02.devops.wltrace.agent.fixtures.CreateOrderRequest", node.get("@type").asText());
        assertEquals("ca.gc.cra.xz02.devops.wltrace.agent.fixtures.Customer", node.get("customer").get("@type").asText());
        assertEquals("Amsterdam", node.get("customer").get("address").get("city").asText());
        assertEquals("NL", node.get("customer").get("address").get("country").asText());
        assertTrue(result.warnings.isEmpty(), result.warnings());
    }

    @Test
    void listsAndStringKeyedMaps() {
        JsonNode node = graphs.write(sampleRequest(null)).node;
        assertTrue(node.get("lines").isArray());
        assertEquals(2, node.get("lines").size());
        assertEquals("WIDGET-1", node.get("lines").get(0).get("sku").asText());
        assertEquals(3, node.get("lines").get(0).get("quantity").asInt());
        assertEquals("ca.gc.cra.xz02.devops.wltrace.agent.fixtures.OrderLineRequest", node.get("lines").get(1).get("@type").asText());
        assertEquals("web", node.get("attributes").get("channel").asText(), "string-keyed map is a plain JSON object");
        assertFalse(node.get("attributes").has("@type"));
        assertTrue(node.get("paymentMethod").isNull());
    }

    @Test
    void enumsByName() {
        JsonNode node = graphs.write(new TaxRequest("NL", "NH", TaxCategory.SERVICES, new BigDecimal("10.00"))).node;
        assertEquals("SERVICES", node.get("category").asText());
        assertEquals("GOLD", graphs.write(sampleRequest(null)).node.get("customer").get("loyaltyTier").asText());
    }

    @Test
    void javaTimeAndUtilDateAsIsoStrings() {
        CreateOrderRequest request = sampleRequest(null);
        request.setRequestedAt(Instant.parse("2026-10-23T18:01:12.345Z"));
        request.setRequestedDeliveryDate(LocalDate.of(2026, 10, 30));
        JsonNode node = graphs.write(request).node;
        assertEquals("2026-10-23T18:01:12.345Z", node.get("requestedAt").asText());
        assertEquals("2026-10-30", node.get("requestedDeliveryDate").asText());

        Reservation reservation = new Reservation("r1", "WIDGET-1", 2, "WH", LocalDateTime.of(2026, 10, 23, 14, 30, 15));
        assertEquals("2026-10-23T14:30:15", graphs.write(reservation).node.get("reservedUntil").asText());

        ConfirmRequest confirm = new ConfirmRequest(42, "ada", new Date(1_800_000_000_000L), null);
        JsonNode confirmNode = graphs.write(confirm).node;
        assertEquals("2027-01-15T08:00:00Z", confirmNode.get("confirmedAt").asText());
        assertEquals(42, confirmNode.get("orderId").asLong());
        assertTrue(confirmNode.get("notes").isNull());
    }

    @Test
    void bigDecimalAsStringWithScalePreserved() {
        Map<String, BigDecimal> lineTotals = new LinkedHashMap<>();
        lineTotals.put("WIDGET-1", new BigDecimal("37.50"));
        lineTotals.put("GADGET-2", new BigDecimal("99.99"));
        TaxResult tax = new TaxResult("NL-NH", new BigDecimal("0.21"), new BigDecimal("25.99"), Instant.parse("2026-10-23T18:00:00Z"));
        PriceQuote quote = new PriceQuote("EUR", lineTotals, new BigDecimal("123.74"), tax, new BigDecimal("149.73"));

        JsonNode node = graphs.write(quote).node;
        assertTrue(node.get("subtotal").isTextual());
        assertEquals("123.74", node.get("subtotal").asText());
        assertEquals("0.21", node.get("tax").get("rate").asText());
        assertEquals("37.50", node.get("lineTotals").get("WIDGET-1").asText(), "the scale survives as text");
    }

    @Test
    void polymorphicPaymentMethodsCarryTheirRuntimeType() {
        JsonNode card = graphs.write(sampleRequest(new CardPayment("4242", YearMonth.of(2028, 6)))).node;
        assertEquals("ca.gc.cra.xz02.devops.wltrace.agent.fixtures.CardPayment", card.get("paymentMethod").get("@type").asText());
        assertEquals("2028-06", card.get("paymentMethod").get("expiry").asText());

        JsonNode invoice = graphs.write(sampleRequest(new InvoicePayment("PO-7781", 45))).node;
        assertEquals("ca.gc.cra.xz02.devops.wltrace.agent.fixtures.InvoicePayment", invoice.get("paymentMethod").get("@type").asText());
        assertEquals(45, invoice.get("paymentMethod").get("netDays").asInt());
    }

    @Test
    void unloadedIndirectListIsMarkedLazyAndNeverInstantiated() throws Exception {
        Order order = sampleOrder();
        AtomicInteger loads = new AtomicInteger();
        setLines(order, unloadedIndirectList(loads));

        Graphs.Result result = graphs.write(order);
        JsonNode lines = result.node.get("lines");
        assertTrue(lines.get("@lazy").asBoolean(), result.json());
        assertFalse(lines.get("loaded").asBoolean());
        assertEquals(0, loads.get(), "serializer must not instantiate the lazy collection");
        assertFalse(result.hasWarning("lines", ""), "an unloaded lazy collection is expected, not a warning: " + result.warnings());
    }

    @Test
    void loadedIndirectListSerializesItsContents() throws Exception {
        Order order = sampleOrder();
        IndirectList<OrderLine> loaded = new IndirectList<>();
        loaded.setValueHolder(new ValueHolder(new java.util.Vector<>(Arrays.asList(new OrderLine(order, "GIZMO-3", 1, new BigDecimal("1499.00"))))));
        setLines(order, loaded);

        Graphs.Result result = graphs.write(order);
        assertTrue(result.node.get("lines").isArray(), result.json());
        assertEquals("GIZMO-3", result.node.get("lines").get(0).get("sku").asText());
    }

    @Test
    void orderLineBackReferenceTerminatesAsRef() {
        Order order = sampleOrder();
        order.addLine("WIDGET-1", 3, new BigDecimal("12.50"));
        order.addLine("GADGET-2", 1, new BigDecimal("99.99"));

        Graphs.Result result = graphs.write(order);
        JsonNode node = result.node;
        assertEquals(1, node.get("@id").asInt(), result.json());
        assertEquals(1, node.get("lines").get(0).get("order").get("@ref").asInt());
        assertEquals(1, node.get("lines").get(1).get("order").get("@ref").asInt());
        assertEquals("12.50", node.get("lines").get(0).get("unitPrice").asText());
        assertTrue(result.warnings.isEmpty(), result.warnings());
    }

    @Test
    void nonSerializableAuditTrailIsCapturedAndFlaggedWithoutWarning() {
        Order order = sampleOrder();
        order.setStatus(OrderStatus.CONFIRMED);

        Graphs.Result result = graphs.write(order);
        JsonNode audit = result.node.get("audit");
        assertEquals(AuditTrail.class.getName(), audit.get("@type").asText(), result.json());
        assertFalse(audit.get("@serializable").asBoolean(), result.json());
        assertEquals(1, audit.get("entryCount").asInt());
        assertEquals(order.getAudit().getOpenedAt().toString(), audit.get("openedAt").asText());
        assertTrue(audit.get("entries").asText().endsWith("status -> CONFIRMED\n"));
        assertEquals("java.lang.Object", audit.get("lock").get("@type").asText(), "a bare monitor object is captured as an empty object");
        assertTrue(result.warnings.isEmpty(), result.warnings());
        assertFalse(result.node.has("@serializable"), "Order itself is Serializable");
    }

    @Test
    void confirmResultWithListOfStrings() {
        ConfirmResult result = new ConfirmResult(11, OrderStatus.CONFIRMED, Instant.parse("2026-10-23T19:31:32.806Z"),
                Arrays.asList("Confirmed by ada", "Notes: gift-wrap"));
        JsonNode node = graphs.write(result).node;
        assertEquals(11, node.get("orderId").asLong());
        assertEquals("CONFIRMED", node.get("status").asText());
        assertEquals("Notes: gift-wrap", node.get("messages").get(1).asText());
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
