package com.acme.tax;

import javax.ejb.SessionBean;
import javax.ejb.SessionContext;
import java.math.BigDecimal;
import java.util.logging.Logger;

/**
 * EJB 2.x stateless session bean (no annotations: ejb-jar.xml declares it, with
 * {@link com.acme.tax.api.RateLookupHome} / {@link com.acme.tax.api.RateLookup}). Same rate
 * table as TaxServiceBean, reached the old way: lookup the home, create(), call.
 */
public class RateLookupBean implements SessionBean {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = Logger.getLogger(RateLookupBean.class.getName());

    private SessionContext context;

    public void ejbCreate() {
        LOG.info("RateLookupBean.ejbCreate");
    }

    public BigDecimal rateFor(String country, String region) {
        if ("DUMP".equals(region)) {
            try {
                Thread.sleep(8000); // region DUMP: hold the 2.x call open for a thread dump
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        BigDecimal rate = "NL".equals(country) ? new BigDecimal("0.21") : "DE".equals(country) ? new BigDecimal("0.19") : new BigDecimal("0.00");
        LOG.info("RateLookupBean.rateFor " + country + "/" + region + " -> " + rate);
        return rate;
    }

    @Override
    public void setSessionContext(SessionContext sessionContext) {
        this.context = sessionContext;
    }

    @Override
    public void ejbRemove() {
    }

    @Override
    public void ejbActivate() {
    }

    @Override
    public void ejbPassivate() {
    }
}
