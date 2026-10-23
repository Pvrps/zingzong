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
}
