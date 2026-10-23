package com.acme.common.dto;

import java.time.YearMonth;

public class CardPayment implements PaymentMethod {

    private static final long serialVersionUID = 1L;

    private String lastFourDigits;
    private YearMonth expiry;

    public CardPayment() {
    }

    public CardPayment(String lastFourDigits, YearMonth expiry) {
        this.lastFourDigits = lastFourDigits;
        this.expiry = expiry;
    }

    public String getLastFourDigits() {
        return lastFourDigits;
    }

    public void setLastFourDigits(String lastFourDigits) {
        this.lastFourDigits = lastFourDigits;
    }

    public YearMonth getExpiry() {
        return expiry;
    }

    public void setExpiry(YearMonth expiry) {
        this.expiry = expiry;
    }

    @Override
    public String summary() {
        return "card ****" + lastFourDigits + " exp " + expiry;
    }
}
