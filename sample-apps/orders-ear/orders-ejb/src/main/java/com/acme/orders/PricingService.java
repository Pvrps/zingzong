package com.acme.orders;

import com.acme.common.dto.CreateOrderRequest;
import com.acme.common.dto.PriceQuote;

import javax.ejb.Local;
import java.math.BigDecimal;

@Local
public interface PricingService {

    PriceQuote price(CreateOrderRequest request);

    BigDecimal unitPrice(String sku);
}
