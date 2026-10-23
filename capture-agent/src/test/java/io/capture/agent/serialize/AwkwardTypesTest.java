package io.capture.agent.serialize;

import com.acme.common.dto.Address;
import com.acme.common.dto.CardPayment;
import com.acme.common.dto.ConfirmRequest;
import com.acme.common.dto.ConfirmResult;
import com.acme.common.dto.CreateOrderRequest;
import com.acme.common.dto.Customer;
import com.acme.common.dto.InvoicePayment;
import com.acme.common.dto.LoyaltyTier;
import com.acme.common.dto.OrderLineRequest;
import com.acme.common.dto.PaymentMethod;
import com.acme.common.dto.PriceQuote;
import com.acme.common.dto.Reservation;
import com.acme.common.dto.TaxCategory;
import com.acme.common.dto.TaxRequest;
import com.acme.common.dto.TaxResult;
import com.acme.common.model.AuditTrail;
import com.acme.common.model.Order;
import com.acme.common.model.OrderLine;
import com.acme.common.model.OrderStatus;
import com.fasterxml.jackson.databind.JsonNode;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** One test per awkward type in common-dto: round-trips, or the expected placeholder plus warning. */
class AwkwardTypesTest {

    private final Graphs graphs = new Graphs();

    @Test
    void nestedObjectsWithTypeOnEveryObject() throws Exception {
        CreateOrderRequest request = sampleRequest(new CardPayment("4242", YearMonth.of(2028, 6)));

        Graphs.Result r = graphs.write(request);
        JsonNode n = r.node;
        assertEquals("com.acme.common.dto.CreateOrderRequest", n.get("@type").asText());
        assertEquals("com.acme.common.dto.Customer", n.get("customer").get("@type").asText());
        assertEquals("Amsterdam", n.get("customer").get("address").get("city").asText());
        assertTrue(r.warnings.isEmpty(), r.warnings());

        CreateOrderRequest back = graphs.read(n, CreateOrderRequest.class);
        assertEquals("Ada Lovelace", back.getCustomer().getName());
        assertEquals("NL", back.getCustomer().getAddress().getCountry());
    }

    @Test
    void listsAndStringKeyedMaps() throws Exception {
        CreateOrderRequest request = sampleRequest(null);
        JsonNode n = graphs.write(request).node;

        assertTrue(n.get("lines").isArray());
        assertEquals("WIDGET-1", n.get("lines").get(0).get("sku").asText());
        assertEquals(3, n.get("lines").get(0).get("quantity").asInt());
        assertEquals("web", n.get("attributes").get("channel").asText(), "string-keyed map is a plain JSON object");
        assertFalse(n.get("attributes").has("@type"));

        CreateOrderRequest back = graphs.roundTrip(request, CreateOrderRequest.class);
        assertEquals(2, back.getLines().size());
        assertEquals(OrderLineRequest.class, back.getLines().get(1).getClass());
        assertEquals("autumn", back.getAttributes().get("campaign"));
        assertEquals(LinkedHashMap.class, back.getAttributes().getClass());
    }

    @Test
    void enumsByName() throws Exception {
        TaxRequest request = new TaxRequest("NL", "NH", TaxCategory.SERVICES, new BigDecimal("10.00"));
        JsonNode n = graphs.write(request).node;
        assertEquals("SERVICES", n.get("category").asText());

        TaxRequest back = graphs.roundTrip(request, TaxRequest.class);
        assertSame(TaxCategory.SERVICES, back.getCategory());
        assertSame(LoyaltyTier.GOLD, graphs.roundTrip(sampleRequest(null), CreateOrderRequest.class).getCustomer().getLoyaltyTier());
    }

    @Test
    void javaTimeAndUtilDateAsIsoStrings() throws Exception {
        Instant instant = Instant.parse("2026-10-23T18:01:12.345Z");
        LocalDate date = LocalDate.of(2026, 10, 30);
        LocalDateTime dateTime = LocalDateTime.of(2026, 10, 23, 14, 30, 15);
        Date utilDate = new Date(1_800_000_000_000L);

        CreateOrderRequest request = sampleRequest(null);
        request.setRequestedAt(instant);
        request.setRequestedDeliveryDate(date);
        JsonNode n = graphs.write(request).node;
        assertEquals("2026-10-23T18:01:12.345Z", n.get("requestedAt").asText());
        assertEquals("2026-10-30", n.get("requestedDeliveryDate").asText());

        Reservation reservation = new Reservation("r1", "WIDGET-1", 2, "WH", dateTime);
        assertEquals("2026-10-23T14:30:15", graphs.write(reservation).node.get("reservedUntil").asText());
        assertEquals(dateTime, graphs.roundTrip(reservation, Reservation.class).getReservedUntil());

        ConfirmRequest confirm = new ConfirmRequest(42, "ada", utilDate, null);
        assertEquals("2027-01-15T08:00:00Z", graphs.write(confirm).node.get("confirmedAt").asText());
        ConfirmRequest back = graphs.roundTrip(confirm, ConfirmRequest.class);
        assertEquals(utilDate, back.getConfirmedAt());
        assertEquals(42L, back.getOrderId());
        assertNull(back.getNotes());

        CreateOrderRequest backRequest = graphs.roundTrip(request, CreateOrderRequest.class);
        assertEquals(instant, backRequest.getRequestedAt());
        assertEquals(date, backRequest.getRequestedDeliveryDate());
    }

    @Test
    void bigDecimalAsStringWithScalePreserved() throws Exception {
        Map<String, BigDecimal> lineTotals = new LinkedHashMap<>();
        lineTotals.put("WIDGET-1", new BigDecimal("37.50"));
        lineTotals.put("GADGET-2", new BigDecimal("99.99"));
        TaxResult tax = new TaxResult("NL-NH", new BigDecimal("0.21"), new BigDecimal("25.99"), Instant.parse("2026-10-23T18:00:00Z"));
        PriceQuote quote = new PriceQuote("EUR", lineTotals, new BigDecimal("123.74"), tax, new BigDecimal("149.73"));

        JsonNode n = graphs.write(quote).node;
        assertTrue(n.get("subtotal").isTextual());
        assertEquals("123.74", n.get("subtotal").asText());
        assertEquals("0.21", n.get("tax").get("rate").asText());
        assertEquals("37.50", n.get("lineTotals").get("WIDGET-1").asText());

        PriceQuote back = graphs.roundTrip(quote, PriceQuote.class);
        assertEquals(new BigDecimal("123.74"), back.getSubtotal());
        assertEquals(2, back.getSubtotal().scale());
        assertEquals(new BigDecimal("37.50"), back.getLineTotals().get("WIDGET-1"));
        assertEquals(new BigDecimal("25.99"), back.getTax().getTax());
    }

    @Test
    void polymorphicPaymentMethodsCarryTypeAndRebuildTheRightClass() throws Exception {
        CreateOrderRequest card = sampleRequest(new CardPayment("4242", YearMonth.of(2028, 6)));
        JsonNode cardNode = graphs.write(card).node;
        assertEquals("com.acme.common.dto.CardPayment", cardNode.get("paymentMethod").get("@type").asText());
        assertEquals("2028-06", cardNode.get("paymentMethod").get("expiry").asText());

        CreateOrderRequest cardBack = graphs.read(cardNode, CreateOrderRequest.class);
        assertEquals(CardPayment.class, cardBack.getPaymentMethod().getClass());
        assertEquals(YearMonth.of(2028, 6), ((CardPayment) cardBack.getPaymentMethod()).getExpiry());
        assertEquals("card ****4242 exp 2028-06", cardBack.getPaymentMethod().summary());

        CreateOrderRequest invoice = sampleRequest(new InvoicePayment("PO-7781", 45));
        JsonNode invoiceNode = graphs.write(invoice).node;
        assertEquals("com.acme.common.dto.InvoicePayment", invoiceNode.get("paymentMethod").get("@type").asText());

        PaymentMethod viaInterface = graphs.read(invoiceNode.get("paymentMethod"), PaymentMethod.class);
        assertEquals(InvoicePayment.class, viaInterface.getClass());
        assertEquals(45, ((InvoicePayment) viaInterface).getNetDays());
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

        Order back = graphs.read(r.node, Order.class);
        assertEquals("Ada Lovelace", back.getCustomerName());
        assertNotNull(back.getLines(), "a placeholder leaves the constructor default in place");
        assertTrue(back.getLines().isEmpty());
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
    void orderLineBackReferenceTerminatesAsRef() throws Exception {
        Order order = sampleOrder();
        order.addLine("WIDGET-1", 3, new BigDecimal("12.50"));
        order.addLine("GADGET-2", 1, new BigDecimal("99.99"));

        Graphs.Result r = graphs.write(order);
        JsonNode n = r.node;
        assertEquals(1, n.get("@id").asInt(), r.json());
        assertEquals(1, n.get("lines").get(0).get("order").get("@ref").asInt());
        assertEquals(1, n.get("lines").get(1).get("order").get("@ref").asInt());
        assertEquals("12.50", n.get("lines").get(0).get("unitPrice").asText());

        Order back = graphs.read(n, Order.class);
        assertEquals(2, back.getLines().size());
        assertSame(back, back.getLines().get(0).getOrder());
        assertSame(back, back.getLines().get(1).getOrder());
        assertEquals(new BigDecimal("99.99"), back.getLines().get(1).getUnitPrice());
    }

    @Test
    void auditTrailBecomesPlaceholderWithWarning() throws Exception {
        Order order = sampleOrder();
        order.setStatus(OrderStatus.CONFIRMED);

        Graphs.Result r = graphs.write(order);
        JsonNode audit = r.node.get("audit");
        assertEquals(AuditTrail.class.getName(), audit.get("@unserializable").asText(), r.json());
        assertTrue(audit.get("toString").asText().startsWith("AuditTrail[opened "), audit.toString());
        assertTrue(r.hasWarning("$.audit", "not java.io.Serializable"), r.warnings());
        assertEquals(1, r.warnings.size(), r.warnings());
        assertEquals("CONFIRMED", r.node.get("status").asText());

        Order back = graphs.read(r.node, Order.class);
        assertNotNull(back.getAudit(), "field initializer runs through the no-arg constructor");
        assertEquals(OrderStatus.CONFIRMED, back.getStatus());
    }

    @Test
    void confirmResultWithListOfStrings() throws Exception {
        ConfirmResult result = new ConfirmResult(11, OrderStatus.CONFIRMED, Instant.parse("2026-10-23T19:31:32.806Z"),
                Arrays.asList("Confirmed by ada", "Notes: gift-wrap"));
        ConfirmResult back = graphs.roundTrip(result, ConfirmResult.class);
        assertEquals(11, back.getOrderId());
        assertEquals(Arrays.asList("Confirmed by ada", "Notes: gift-wrap"), back.getMessages());
    }

    // ---- fixtures -----------------------------------------------------------------------

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
        // EclipseLink 2.7 (as bundled with WebLogic 14.1.2): ValueHolderInterface is not generic.
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
