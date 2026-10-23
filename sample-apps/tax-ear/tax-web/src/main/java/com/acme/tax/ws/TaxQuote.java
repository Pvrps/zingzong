package com.acme.tax.ws;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlType;
import java.math.BigDecimal;

/** JAXB view of a TaxResult for the SOAP response. */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "TaxQuote", namespace = "http://acme.com/tax", propOrder = {"jurisdiction", "rate", "tax", "computedAt"})
public class TaxQuote {

    private String jurisdiction;
    private BigDecimal rate;
    private BigDecimal tax;
    private String computedAt;

    public String getJurisdiction() {
        return jurisdiction;
    }

    public void setJurisdiction(String jurisdiction) {
        this.jurisdiction = jurisdiction;
    }

    public BigDecimal getRate() {
        return rate;
    }

    public void setRate(BigDecimal rate) {
        this.rate = rate;
    }

    public BigDecimal getTax() {
        return tax;
    }

    public void setTax(BigDecimal tax) {
        this.tax = tax;
    }

    public String getComputedAt() {
        return computedAt;
    }

    public void setComputedAt(String computedAt) {
        this.computedAt = computedAt;
    }
}
