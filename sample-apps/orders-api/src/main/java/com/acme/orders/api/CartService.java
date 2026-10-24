package com.acme.orders.api;

import java.math.BigDecimal;
import java.util.Map;
import javax.ejb.Local;

/** A stateful conversation: one instance per shopper, held by the web tier across requests. */
@Local
public interface CartService {

    int addItem(String sku, int quantity);

    Map<String, Integer> items();

    BigDecimal total();

    /** Ends the conversation (@Remove on the bean). */
    void checkout();
}
