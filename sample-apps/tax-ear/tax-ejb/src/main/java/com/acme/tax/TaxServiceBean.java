package com.acme.tax;

import com.acme.common.dto.TaxCategory;
import com.acme.common.dto.TaxRequest;
import com.acme.common.dto.TaxResult;
import com.acme.tax.api.TaxService;

import javax.ejb.Stateless;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.logging.Logger;

@Stateless(name = "TaxService")
public class TaxServiceBean implements TaxService {

    private static final Logger LOG = Logger.getLogger(TaxServiceBean.class.getName());

    @Override
    public TaxResult computeTax(TaxRequest request) {
        LOG.info("TaxService.computeTax " + request.getCountry() + "/" + request.getRegion()
                + " " + request.getCategory() + " " + request.getAmount());
        BigDecimal rate = rateFor(request);
        BigDecimal tax = request.getAmount().multiply(rate).setScale(2, RoundingMode.HALF_UP);
        return new TaxResult(request.getCountry() + "-" + request.getRegion(), rate, tax, Instant.now());
    }

    private static BigDecimal rateFor(TaxRequest request) {
        if (request.getCategory() == TaxCategory.EXEMPT) {
            return BigDecimal.ZERO;
        }
        if ("NL".equals(request.getCountry())) {
            return request.getCategory() == TaxCategory.SERVICES ? new BigDecimal("0.09") : new BigDecimal("0.21");
        }
        if ("US".equals(request.getCountry()) && "CA".equals(request.getRegion())) {
            return new BigDecimal("0.0725");
        }
        return new BigDecimal("0.20");
    }
}
