package com.acme.common.dto;

import java.io.Serializable;
import java.math.BigDecimal;

/** One row of the sales summary: how many orders in a status, and what they add up to. */
public class StatusSummary implements Serializable {

    private static final long serialVersionUID = 1L;

    private String status;
    private long orders;
    private BigDecimal total;

    public StatusSummary() {
    }

    public StatusSummary(String status, long orders, BigDecimal total) {
        this.status = status;
        this.orders = orders;
        this.total = total;
    }

    public String getStatus() {
        return status;
    }

    public long getOrders() {
        return orders;
    }

    public BigDecimal getTotal() {
        return total;
    }
}
