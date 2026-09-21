package com.jdd.commerce.order.port;

import com.jdd.commerce.order.domain.Order;
import com.jdd.commerce.product.domain.Product;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface CommerceRepository {
    Optional<Product> product(String id);
    List<Product> products(int limit, int offset);
    Optional<Order> order(String id);
    Optional<Order> lockOrder(String id);
    void updateStatus(String id, String status, Instant at);
    List<Order> orders(String customerId, String checkoutKey, int limit, int offset);
    void insertOrder(Order order, String requestId);
    int subtractStock(String productId, int quantity, Instant at);
    int addStock(String productId, int quantity, Instant at);
    void movement(String productId, String orderId, String requestId, String checkoutKey,
                  String type, int delta, int after, Instant at);
}
