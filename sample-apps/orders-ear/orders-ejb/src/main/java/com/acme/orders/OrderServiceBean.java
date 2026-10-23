package com.acme.orders;

import com.acme.common.dto.ConfirmRequest;
import com.acme.common.dto.ConfirmResult;
import com.acme.common.dto.CreateOrderRequest;
import com.acme.common.dto.OrderLineRequest;
import com.acme.common.dto.PriceQuote;
import com.acme.common.model.Order;
import com.acme.common.model.OrderStatus;
import com.acme.orders.api.InsufficientStockException;
import com.acme.orders.api.OrderNotFoundException;
import com.acme.orders.api.OrderServiceLocal;
import com.acme.orders.api.OrderServiceRemote;

import javax.ejb.EJB;
import javax.ejb.Stateless;
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

    @Override
    public Order createOrder(CreateOrderRequest request) throws InsufficientStockException {
        LOG.info("OrderService.createOrder for " + request.getCustomer().getName());

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
        return new ConfirmResult(order.getId(), order.getStatus(), Instant.now(), messages);
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
