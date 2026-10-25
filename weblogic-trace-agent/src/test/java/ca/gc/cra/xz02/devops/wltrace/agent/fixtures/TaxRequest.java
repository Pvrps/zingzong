package ca.gc.cra.xz02.devops.wltrace.agent.fixtures;

import java.io.Serializable;
import java.math.BigDecimal;

public class TaxRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    private String country;
    private String region;
    private TaxCategory category;
    private BigDecimal amount;

    public TaxRequest() {
    }

    public TaxRequest(String country, String region, TaxCategory category, BigDecimal amount) {
        this.country = country;
        this.region = region;
        this.category = category;
        this.amount = amount;
    }

    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public TaxCategory getCategory() {
        return category;
    }

    public void setCategory(TaxCategory category) {
        this.category = category;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }
}
