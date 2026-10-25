package io.capture.agent.fixtures;

import java.io.Serializable;
import java.time.LocalDateTime;

public class Reservation implements Serializable {

    private static final long serialVersionUID = 1L;

    private String reservationId;
    private String sku;
    private int quantity;
    private String warehouse;
    private LocalDateTime reservedUntil;

    public Reservation() {
    }

    public Reservation(String reservationId, String sku, int quantity, String warehouse, LocalDateTime reservedUntil) {
        this.reservationId = reservationId;
        this.sku = sku;
        this.quantity = quantity;
        this.warehouse = warehouse;
        this.reservedUntil = reservedUntil;
    }

    public String getReservationId() {
        return reservationId;
    }

    public String getSku() {
        return sku;
    }

    public int getQuantity() {
        return quantity;
    }

    public String getWarehouse() {
        return warehouse;
    }

    public LocalDateTime getReservedUntil() {
        return reservedUntil;
    }
}
