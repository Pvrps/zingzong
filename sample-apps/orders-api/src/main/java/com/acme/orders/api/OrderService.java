package com.acme.orders.api;

import com.acme.common.dto.ConfirmRequest;
import com.acme.common.dto.ConfirmResult;
import com.acme.common.dto.CreateOrderRequest;
import com.acme.common.model.Order;

/** Business methods shared by the local and remote views of OrderService. */
public interface OrderService {

    Order createOrder(CreateOrderRequest request) throws InsufficientStockException;

    ConfirmResult confirmOrder(ConfirmRequest request) throws OrderNotFoundException;

    Order findOrder(long orderId) throws OrderNotFoundException;

    /** Fire-and-forget follow-up work for an order event; runs on another thread (@Asynchronous). */
    void notifyAsync(long orderId, String event);

    /** Orders per status and what they add up to, straight from the data source (plain JDBC, no JPA). */
    java.util.List<com.acme.common.dto.StatusSummary> salesSummary(String customerName);
}
