package com.acme.tax.api;

import com.acme.common.dto.TaxRequest;
import com.acme.common.dto.TaxResult;

import javax.ejb.Remote;

/**
 * Owned by "the tax team" in a separate EAR. Callers look it up by JNDI:
 * java:global/tax-ear/tax-ejb/TaxService!com.acme.tax.api.TaxService or tax/TaxService.
 */
@Remote
public interface TaxService {

    String JNDI_GLOBAL = "java:global/tax-ear/tax-ejb/TaxService!com.acme.tax.api.TaxService";
    String JNDI_MAPPED = "tax/TaxService";

    TaxResult computeTax(TaxRequest request);
}
