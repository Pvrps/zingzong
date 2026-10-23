package com.acme.common.dto;

import java.io.Serializable;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class CreateOrderRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    private Customer customer;
    private List<OrderLineRequest> lines = new ArrayList<>();
    private Map<String, String> attributes = new LinkedHashMap<>();
    private LocalDate requestedDeliveryDate;
    private Instant requestedAt;

    public Customer getCustomer() {
        return customer;
    }

    public void setCustomer(Customer customer) {
        this.customer = customer;
    }

    public List<OrderLineRequest> getLines() {
        return lines;
    }

    public void setLines(List<OrderLineRequest> lines) {
        this.lines = lines;
    }

    public Map<String, String> getAttributes() {
        return attributes;
    }

    public void setAttributes(Map<String, String> attributes) {
        this.attributes = attributes;
    }

    public LocalDate getRequestedDeliveryDate() {
        return requestedDeliveryDate;
    }

    public void setRequestedDeliveryDate(LocalDate requestedDeliveryDate) {
        this.requestedDeliveryDate = requestedDeliveryDate;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public void setRequestedAt(Instant requestedAt) {
        this.requestedAt = requestedAt;
    }
}
