package io.capture.agent.fixtures;

import java.io.Serializable;
import java.util.Date;

public class ConfirmRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    private long orderId;
    private String confirmedBy;
    private Date confirmedAt;
    private String notes;

    public ConfirmRequest() {
    }

    public ConfirmRequest(long orderId, String confirmedBy, Date confirmedAt, String notes) {
        this.orderId = orderId;
        this.confirmedBy = confirmedBy;
        this.confirmedAt = confirmedAt;
        this.notes = notes;
    }

    public long getOrderId() {
        return orderId;
    }

    public void setOrderId(long orderId) {
        this.orderId = orderId;
    }

    public String getConfirmedBy() {
        return confirmedBy;
    }

    public void setConfirmedBy(String confirmedBy) {
        this.confirmedBy = confirmedBy;
    }

    public Date getConfirmedAt() {
        return confirmedAt;
    }

    public void setConfirmedAt(Date confirmedAt) {
        this.confirmedAt = confirmedAt;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }
}
