package com.acme.orders.api;

import javax.ejb.ApplicationException;

@ApplicationException(rollback = true)
public class InsufficientStockException extends Exception {

    private static final long serialVersionUID = 1L;

    private final String sku;
    private final int requested;
    private final int available;

    public InsufficientStockException(String sku, int requested, int available) {
        super("Insufficient stock for " + sku + ": requested " + requested + ", available " + available);
        this.sku = sku;
        this.requested = requested;
        this.available = available;
    }

    public String getSku() {
        return sku;
    }

    public int getRequested() {
        return requested;
    }

    public int getAvailable() {
        return available;
    }
}
