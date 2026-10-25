package io.capture.agent.fixtures;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

public class PriceQuote implements Serializable {

    private static final long serialVersionUID = 1L;

    private String currency;
    private Map<String, BigDecimal> lineTotals = new LinkedHashMap<>();
    private BigDecimal subtotal;
    private TaxResult tax;
    private BigDecimal total;

    public PriceQuote() {
    }

    public PriceQuote(String currency, Map<String, BigDecimal> lineTotals, BigDecimal subtotal, TaxResult tax, BigDecimal total) {
        this.currency = currency;
        this.lineTotals = lineTotals;
        this.subtotal = subtotal;
        this.tax = tax;
        this.total = total;
    }

    public String getCurrency() {
        return currency;
    }

    public Map<String, BigDecimal> getLineTotals() {
        return lineTotals;
    }

    public BigDecimal getSubtotal() {
        return subtotal;
    }

    public TaxResult getTax() {
        return tax;
    }

    public BigDecimal getTotal() {
        return total;
    }
}
