package com.acme.orders.api;

import javax.ejb.ApplicationException;

@ApplicationException(rollback = false)
public class OrderNotFoundException extends Exception {

    private static final long serialVersionUID = 1L;

    private final long orderId;

    public OrderNotFoundException(long orderId) {
        super("Order " + orderId + " not found");
        this.orderId = orderId;
    }

    public long getOrderId() {
        return orderId;
    }
}
