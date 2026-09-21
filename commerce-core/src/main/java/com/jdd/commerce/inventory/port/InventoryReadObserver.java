package com.jdd.commerce.inventory.port;

@FunctionalInterface
public interface InventoryReadObserver {
    void observed(String productId, String checkoutKey, int quantity);
}
