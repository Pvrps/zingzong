package io.capture.agent.fixtures;

import java.io.Serializable;

public class OrderLineRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    private String sku;
    private int quantity;

    public OrderLineRequest() {
    }

    public OrderLineRequest(String sku, int quantity) {
        this.sku = sku;
        this.quantity = quantity;
    }

    public String getSku() {
        return sku;
    }

    public void setSku(String sku) {
        this.sku = sku;
    }

    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }
}
