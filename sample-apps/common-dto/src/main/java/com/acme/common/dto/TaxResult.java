package com.acme.common.dto;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;

public class TaxResult implements Serializable {

    private static final long serialVersionUID = 1L;

    private String jurisdiction;
    private BigDecimal rate;
    private BigDecimal tax;
    private Instant computedAt;

    public TaxResult() {
    }

    public TaxResult(String jurisdiction, BigDecimal rate, BigDecimal tax, Instant computedAt) {
        this.jurisdiction = jurisdiction;
        this.rate = rate;
        this.tax = tax;
        this.computedAt = computedAt;
    }

    public String getJurisdiction() {
        return jurisdiction;
    }

    public BigDecimal getRate() {
        return rate;
    }

    public BigDecimal getTax() {
        return tax;
    }

    public Instant getComputedAt() {
        return computedAt;
    }
}
