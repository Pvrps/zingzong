package io.capture.agent.fixtures;

import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class ConfirmResult implements Serializable {

    private static final long serialVersionUID = 1L;

    private long orderId;
    private OrderStatus status;
    private Instant confirmedAt;
    private List<String> messages = new ArrayList<>();

    public ConfirmResult() {
    }

    public ConfirmResult(long orderId, OrderStatus status, Instant confirmedAt, List<String> messages) {
        this.orderId = orderId;
        this.status = status;
        this.confirmedAt = confirmedAt;
        this.messages = messages;
    }

    public long getOrderId() {
        return orderId;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }

    public List<String> getMessages() {
        return messages;
    }
}
