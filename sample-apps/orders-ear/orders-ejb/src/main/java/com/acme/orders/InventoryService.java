package com.acme.orders;

import com.acme.common.dto.Reservation;
import com.acme.orders.api.InsufficientStockException;

import javax.ejb.Local;

@Local
public interface InventoryService {

    Reservation reserve(String sku, int quantity) throws InsufficientStockException;

    int available(String sku);
}
