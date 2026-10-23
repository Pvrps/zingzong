package com.acme.orders;

import com.acme.common.dto.Reservation;
import com.acme.orders.api.InsufficientStockException;

import javax.ejb.ConcurrencyManagement;
import javax.ejb.ConcurrencyManagementType;
import javax.ejb.Lock;
import javax.ejb.LockType;
import javax.ejb.Singleton;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/** In-memory stock, seeded at startup. A singleton so reservations survive across calls. */
@Singleton(name = "InventoryService")
@ConcurrencyManagement(ConcurrencyManagementType.CONTAINER)
public class InventoryServiceBean implements InventoryService {

    private static final Logger LOG = Logger.getLogger(InventoryServiceBean.class.getName());

    private final Map<String, Integer> stock = new ConcurrentHashMap<>();

    public InventoryServiceBean() {
        // Plenty for load tests; GIZMO-3 stays scarce so InsufficientStockException is reachable.
        stock.put("WIDGET-1", 1_000_000);
        stock.put("GADGET-2", 1_000_000);
        stock.put("GIZMO-3", 5);
    }

    @Override
    @Lock(LockType.WRITE)
    public Reservation reserve(String sku, int quantity) throws InsufficientStockException {
        int available = stock.getOrDefault(sku, 0);
        LOG.info("InventoryService.reserve " + sku + " x" + quantity + " (available " + available + ")");
        if (quantity > available) {
            throw new InsufficientStockException(sku, quantity, available);
        }
        stock.put(sku, available - quantity);
        return new Reservation(UUID.randomUUID().toString(), sku, quantity, "WH-AMS", LocalDateTime.now().plusHours(2));
    }

    @Override
    @Lock(LockType.READ)
    public int available(String sku) {
        return stock.getOrDefault(sku, 0);
    }
}
