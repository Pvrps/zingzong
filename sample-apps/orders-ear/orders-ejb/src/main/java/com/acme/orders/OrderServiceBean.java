package com.acme.orders;

import com.acme.common.dto.ConfirmRequest;
import com.acme.common.dto.ConfirmResult;
import com.acme.common.dto.CreateOrderRequest;
import com.acme.common.dto.OrderLineRequest;
import com.acme.common.dto.PriceQuote;
import com.acme.common.dto.StatusSummary;
import com.acme.common.model.Order;
import com.acme.common.model.OrderStatus;
import com.acme.orders.api.InsufficientStockException;
import com.acme.orders.api.OrderNotFoundException;
import com.acme.orders.api.OrderServiceLocal;
import com.acme.orders.api.OrderServiceRemote;

import javax.annotation.Resource;
import javax.ejb.Asynchronous;
import javax.ejb.EJBException;
import javax.ejb.EJB;
import javax.ejb.Stateless;
import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.MessageProducer;
import javax.jms.Queue;
import javax.jms.Session;
import javax.jms.TextMessage;
import javax.naming.InitialContext;
import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

@Stateless(name = "OrderService")
public class OrderServiceBean implements OrderServiceLocal, OrderServiceRemote {

    private static final Logger LOG = Logger.getLogger(OrderServiceBean.class.getName());

    @PersistenceContext(unitName = "orders")
    private EntityManager em;

    @EJB
    private InventoryService inventory;

    @EJB
    private PricingService pricing;

    /** The bean's own local view: a call through it goes through the container (and, for notifyAsync, to another thread). */
    @EJB
    private OrderServiceLocal self;

    @Override
    public Order createOrder(CreateOrderRequest request) throws InsufficientStockException {
        LOG.info("OrderService.createOrder for " + request.getCustomer().getName());
        pauseIfRequested(request);

        for (OrderLineRequest line : request.getLines()) {
            inventory.reserve(line.getSku(), line.getQuantity());
        }
        PriceQuote quote = pricing.price(request);

        Order order = new Order();
        order.setCustomerName(request.getCustomer().getName());
        order.setCustomerEmail(request.getCustomer().getEmail());
        order.setCreatedAt(LocalDateTime.now());
        order.setDeliveryDate(request.getRequestedDeliveryDate());
        order.setCurrency(quote.getCurrency());
        order.setSubtotal(quote.getSubtotal());
        order.setTax(quote.getTax().getTax());
        order.setTotal(quote.getTotal());
        if (request.getPaymentMethod() != null) {
            order.setPaymentSummary(request.getPaymentMethod().summary());
        }
        for (OrderLineRequest line : request.getLines()) {
            order.addLine(line.getSku(), line.getQuantity(), pricing.unitPrice(line.getSku()));
        }

        em.persist(order);
        em.flush();
        // Return a fresh instance so the lines collection is a lazy, unloaded proxy.
        em.clear();
        return em.find(Order.class, order.getId());
    }

    @Override
    public ConfirmResult confirmOrder(ConfirmRequest request) throws OrderNotFoundException {
        LOG.info("OrderService.confirmOrder " + request.getOrderId() + " by " + request.getConfirmedBy());

        Order order = findOrder(request.getOrderId());
        List<String> messages = new ArrayList<>();
        if (order.getStatus() == OrderStatus.CONFIRMED) {
            messages.add("Order was already confirmed");
        } else {
            order.setStatus(OrderStatus.CONFIRMED);
            messages.add("Confirmed by " + request.getConfirmedBy());
        }
        if (request.getNotes() != null && !request.getNotes().isEmpty()) {
            messages.add("Notes: " + request.getNotes());
        }
        ConfirmResult result = new ConfirmResult(order.getId(), order.getStatus(), Instant.now(), messages);
        self.notifyAsync(order.getId(), "CONFIRMED");
        publishEvent(order.getId(), "CONFIRMED", request.getConfirmedBy());
        return result;
    }


    @Resource(lookup = "jdbc/OrdersDS")
    private DataSource dataSource;

    @Override
    public List<StatusSummary> salesSummary(String customerName) {
        String sql = "SELECT STATUS, COUNT(*) AS ORDERS, SUM(TOTAL) AS TOTAL FROM ORDERS"
                + (customerName == null ? "" : " WHERE CUSTOMER_NAME = ?") + " GROUP BY STATUS ORDER BY STATUS";
        List<StatusSummary> summary = new ArrayList<>();
        try (java.sql.Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            if (customerName != null) {
                statement.setString(1, customerName);
            }
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    summary.add(new StatusSummary(rows.getString("STATUS"), rows.getLong("ORDERS"), rows.getBigDecimal("TOTAL")));
                }
            }
        } catch (SQLException e) {
            throw new EJBException("sales summary failed", e);
        }
        LOG.info("OrderService.salesSummary " + (customerName == null ? "all" : customerName) + ": " + summary.size() + " statuses");
        return summary;
    }
    @Asynchronous
    @Override
    public void notifyAsync(long orderId, String event) {
        Order order = em.find(Order.class, orderId);
        if (order != null && "Dump".equals(order.getCustomerName())) {
            try {
                Thread.sleep(8000); // customer "Dump": hold the async call open for a thread dump
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        LOG.info("OrderService.notifyAsync " + event + " for " + orderId + " on " + Thread.currentThread().getName()
                + (order == null ? " (no such order)" : " total=" + order.getTotal()));
    }

    /**
     * Publishes the event to jms/OrderEvents (consumed by notify-ear's MDB). Best effort: a domain
     * without the JMS resources (see scripts/jms-resources.py) just logs and goes on.
     */
    private static void publishEvent(long orderId, String event, String by) {
        Connection connection = null;
        try {
            InitialContext ctx = new InitialContext();
            ConnectionFactory factory = (ConnectionFactory) ctx.lookup("jms/OrdersCF");
            Queue queue = (Queue) ctx.lookup("jms/OrderEvents");
            connection = factory.createConnection();
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            MessageProducer producer = session.createProducer(queue);
            TextMessage message = session.createTextMessage("{\"orderId\":" + orderId + ",\"event\":\"" + event + "\",\"by\":\"" + by + "\"}");
            message.setLongProperty("orderId", orderId);
            message.setStringProperty("event", event);
            message.setStringProperty("by", by);
            producer.send(message);
        } catch (Exception e) {
            LOG.warning("OrderService: event not published (JMS not configured?): " + e);
        } finally {
            if (connection != null) {
                try {
                    connection.close();
                } catch (Exception ignored) {
                    // closing
                }
            }
        }
    }

    /** attributes.delayMillis holds the call open inside the bean, for thread dumps of the EJB container. */
    private static void pauseIfRequested(CreateOrderRequest request) {
        String delay = request.getAttributes() == null ? null : request.getAttributes().get("delayMillis");
        if (delay != null) {
            try {
                Thread.sleep(Math.min(Long.parseLong(delay), 30_000L));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (NumberFormatException ignored) {
                // not a number: no pause
            }
        }
    }

    @Override
    public Order findOrder(long orderId) throws OrderNotFoundException {
        Order order = em.find(Order.class, orderId);
        if (order == null) {
            throw new OrderNotFoundException(orderId);
        }
        return order;
    }
}
