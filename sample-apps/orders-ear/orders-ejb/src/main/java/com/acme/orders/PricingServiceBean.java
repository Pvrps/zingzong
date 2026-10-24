package com.acme.orders;

import com.acme.common.dto.Address;
import com.acme.common.dto.CreateOrderRequest;
import com.acme.common.dto.LoyaltyTier;
import com.acme.common.dto.OrderLineRequest;
import com.acme.common.dto.PriceQuote;
import com.acme.common.dto.TaxCategory;
import com.acme.common.dto.TaxRequest;
import com.acme.common.dto.TaxResult;
import com.acme.tax.api.RateLookup;
import com.acme.tax.api.RateLookupHome;
import com.acme.tax.api.TaxService;

import javax.ejb.EJBException;
import javax.ejb.Stateless;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

@Stateless(name = "PricingService")
public class PricingServiceBean implements PricingService {

    private static final Logger LOG = Logger.getLogger(PricingServiceBean.class.getName());
    private static final String CURRENCY = "EUR";

    private final Map<String, BigDecimal> catalog = new HashMap<>();

    public PricingServiceBean() {
        catalog.put("WIDGET-1", new BigDecimal("12.50"));
        catalog.put("GADGET-2", new BigDecimal("99.99"));
        catalog.put("GIZMO-3", new BigDecimal("1499.00"));
    }

    @Override
    public PriceQuote price(CreateOrderRequest request) {
        LOG.info("PricingService.price for " + request.getCustomer().getName()
                + " (" + request.getLines().size() + " lines)");

        Map<String, BigDecimal> lineTotals = new LinkedHashMap<>();
        BigDecimal subtotal = BigDecimal.ZERO;
        for (OrderLineRequest line : request.getLines()) {
            BigDecimal lineTotal = unitPrice(line.getSku()).multiply(BigDecimal.valueOf(line.getQuantity()));
            lineTotals.put(line.getSku(), lineTotal);
            subtotal = subtotal.add(lineTotal);
        }
        subtotal = applyLoyaltyDiscount(subtotal, request.getCustomer().getLoyaltyTier());

        Address address = request.getCustomer().getAddress();
        TaxResult tax = taxService().computeTax(
                new TaxRequest(address.getCountry(), address.getRegion(), TaxCategory.GOODS, subtotal));
        // Cross-check with the tax team's EJB 2.x bean, the old way: home lookup, create(), call.
        BigDecimal legacyRate = legacyRate(address.getCountry(), address.getRegion());
        if (legacyRate.compareTo(tax.getRate()) != 0) {
            LOG.warning("PricingService: legacy rate " + legacyRate + " differs from " + tax.getRate());
        }

        return new PriceQuote(CURRENCY, lineTotals, subtotal, tax, subtotal.add(tax.getTax()));
    }

    @Override
    public BigDecimal unitPrice(String sku) {
        BigDecimal price = catalog.get(sku);
        if (price == null) {
            throw new IllegalArgumentException("Unknown SKU: " + sku);
        }
        return price;
    }

    private static BigDecimal applyLoyaltyDiscount(BigDecimal amount, LoyaltyTier tier) {
        if (tier == null || tier == LoyaltyTier.NONE) {
            return amount;
        }
        BigDecimal factor = tier == LoyaltyTier.GOLD ? new BigDecimal("0.90") : new BigDecimal("0.95");
        return amount.multiply(factor).setScale(2, RoundingMode.HALF_UP);
    }

    /** The tax team's service lives in another EAR; resolve it through JNDI, not injection. */
    private static TaxService taxService() {
        try {
            return (TaxService) new InitialContext().lookup(TaxService.JNDI_GLOBAL);
        } catch (NamingException e) {
            throw new EJBException("TaxService not available at " + TaxService.JNDI_GLOBAL, e);
        }
    }
    /** EJB 2.x: RateLookupHome from JNDI, create() for the component, then the business call, then remove(). */
    private static BigDecimal legacyRate(String country, String region) {
        try {
            Object home = new InitialContext().lookup(RateLookupHome.JNDI_NAME);
            // No PortableRemoteObject.narrow: JDK 11+ has no javax.rmi, and a WebLogic T3 stub implements the home directly.
            RateLookupHome narrowed = (RateLookupHome) home;
            RateLookup lookup = narrowed.create();
            try {
                return lookup.rateFor(country, region);
            } finally {
                lookup.remove();
            }
        } catch (Exception e) {
            throw new EJBException("RateLookup not available at " + RateLookupHome.JNDI_NAME, e);
        }
    }
}
