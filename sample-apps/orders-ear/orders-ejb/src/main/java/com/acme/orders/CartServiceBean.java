package com.acme.orders;

import com.acme.orders.api.CartService;

import javax.ejb.EJB;
import javax.ejb.Remove;
import javax.ejb.Stateful;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

@Stateful(name = "CartService")
public class CartServiceBean implements CartService {

    private static final Logger LOG = Logger.getLogger(CartServiceBean.class.getName());

    @EJB
    private PricingService pricing;

    private final Map<String, Integer> items = new LinkedHashMap<>();

    @Override
    public int addItem(String sku, int quantity) {
        items.merge(sku, quantity, Integer::sum);
        LOG.info("CartService.addItem " + sku + " x" + quantity + " -> " + items);
        return items.values().stream().mapToInt(Integer::intValue).sum();
    }

    @Override
    public Map<String, Integer> items() {
        return new LinkedHashMap<>(items);
    }

    @Override
    public BigDecimal total() {
        BigDecimal total = BigDecimal.ZERO;
        for (Map.Entry<String, Integer> e : items.entrySet()) {
            total = total.add(pricing.unitPrice(e.getKey()).multiply(BigDecimal.valueOf(e.getValue())));
        }
        return total;
    }

    @Remove
    @Override
    public void checkout() {
        LOG.info("CartService.checkout " + items);
    }
}
