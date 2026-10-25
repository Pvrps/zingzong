package io.capture.agent.fixtures;

public class InvoicePayment implements PaymentMethod {

    private static final long serialVersionUID = 1L;

    private String purchaseOrderNumber;
    private int netDays;

    public InvoicePayment() {
    }

    public InvoicePayment(String purchaseOrderNumber, int netDays) {
        this.purchaseOrderNumber = purchaseOrderNumber;
        this.netDays = netDays;
    }

    public String getPurchaseOrderNumber() {
        return purchaseOrderNumber;
    }

    public void setPurchaseOrderNumber(String purchaseOrderNumber) {
        this.purchaseOrderNumber = purchaseOrderNumber;
    }

    public int getNetDays() {
        return netDays;
    }

    public void setNetDays(int netDays) {
        this.netDays = netDays;
    }

    @Override
    public String summary() {
        return "invoice PO " + purchaseOrderNumber + " net " + netDays;
    }
}
