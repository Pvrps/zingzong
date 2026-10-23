package com.acme.tax.ws;

import com.acme.common.dto.TaxCategory;
import com.acme.common.dto.TaxRequest;
import com.acme.common.dto.TaxResult;
import com.acme.tax.api.TaxService;

import javax.ejb.EJB;
import javax.jws.WebMethod;
import javax.jws.WebParam;
import javax.jws.WebResult;
import javax.jws.WebService;
import java.math.BigDecimal;
import java.util.logging.Logger;

/** SOAP front door to the tax team's EJB; document/literal wrapped, simple wire types. */
@WebService(name = "TaxPort", serviceName = "TaxWebService", portName = "TaxPortSoap", targetNamespace = "http://acme.com/tax")
public class TaxWebService {

    private static final Logger LOG = Logger.getLogger(TaxWebService.class.getName());

    @EJB
    private TaxService taxService;

    @WebMethod(operationName = "computeTax")
    @WebResult(name = "quote")
    public TaxQuote computeTax(@WebParam(name = "country") String country,
                               @WebParam(name = "region") String region,
                               @WebParam(name = "category") String category,
                               @WebParam(name = "amount") BigDecimal amount) {
        LOG.info("SOAP computeTax " + country + "/" + region + " " + category + " " + amount);
        TaxResult result = taxService.computeTax(new TaxRequest(country, region, TaxCategory.valueOf(category), amount));
        TaxQuote quote = new TaxQuote();
        quote.setJurisdiction(result.getJurisdiction());
        quote.setRate(result.getRate());
        quote.setTax(result.getTax());
        quote.setComputedAt(result.getComputedAt().toString());
        return quote;
    }
}
