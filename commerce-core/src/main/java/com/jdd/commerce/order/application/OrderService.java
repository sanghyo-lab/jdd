package com.jdd.commerce.order.application;

import com.jdd.commerce.common.BusinessEvents;
import com.jdd.commerce.common.BusinessEvents.Trace;
import com.jdd.commerce.common.CommerceException;
import com.jdd.commerce.common.Inputs;
import com.jdd.commerce.coupon.application.CouponService;
import com.jdd.commerce.inventory.port.InventoryReadObserver;
import com.jdd.commerce.order.domain.CreateOrder;
import com.jdd.commerce.order.domain.Order;
import com.jdd.commerce.order.port.CommerceRepository;
import com.jdd.commerce.product.domain.Product;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {
    private final CommerceRepository repository;
    private final BusinessEvents events;
    private final InventoryReadObserver observer;
    private final Clock clock;
    private final CouponService coupons;

    public OrderService(CommerceRepository repository, BusinessEvents events,
            InventoryReadObserver observer, Clock clock, CouponService coupons) {
        this.repository = repository;
        this.events = events;
        this.observer = observer;
        this.clock = clock;
        this.coupons = coupons;
    }

    @Transactional(readOnly = true)
    public List<Product> products(int limit, int offset) {
        Inputs.page(limit, offset);
        return repository.products(limit, offset);
    }

    @Transactional(readOnly = true)
    public Product product(String id) {
        return repository.product(Inputs.identifier(id, "productId"))
                .orElseThrow(() -> CommerceException.notFound("Product"));
    }

    @Transactional(readOnly = true)
    public Order order(String id) {
        return repository.order(Inputs.identifier(id, "orderId"))
                .orElseThrow(() -> CommerceException.notFound("Order"));
    }

    @Transactional(readOnly = true)
    public List<Order> orders(String customerId, String checkoutKey, int limit, int offset) {
        Inputs.page(limit, offset);
        if (customerId != null) Inputs.identifier(customerId, "customerId");
        if (checkoutKey != null) Inputs.identifier(checkoutKey, "checkoutKey");
        return repository.orders(customerId, checkoutKey, limit, offset);
    }

    @Transactional
    public Order create(CreateOrder request, String requestId) {
        validate(request);
        Inputs.identifier(requestId, "requestId");
        List<Order.Item> items = new ArrayList<>();
        long subtotal = 0;
        for (CreateOrder.Item item : request.items().stream()
                .sorted(Comparator.comparing(CreateOrder.Item::productId)).toList()) {
            Product product = product(item.productId());
            Trace trace = new Trace(requestId, request.checkoutKey(), null, null, product.id(), request.customerCouponId());
            events.observed("INVENTORY_READ", trace, Map.of(
                    "requestedQuantity", item.quantity(), "observedQuantity", product.stockQuantity()));
            if (product.stockQuantity() < item.quantity()) {
                throw new CommerceException(422, "INSUFFICIENT_STOCK", "Requested quantity exceeds available stock", false);
            }
            observer.observed(product.id(), request.checkoutKey(), product.stockQuantity());
            try {
                long amount = Math.multiplyExact(product.price(), item.quantity().longValue());
                subtotal = Math.addExact(subtotal, amount);
                items.add(new Order.Item(product.id(), item.quantity(), product.price(), amount));
            } catch (ArithmeticException overflow) {
                throw CommerceException.invalid("Order amount exceeds supported integer range");
            }
        }
        Instant at = clock.instant();
        CouponService.Quote quote = request.customerCouponId() == null ? null : coupons.quote(
                request.customerId(), request.customerCouponId(), subtotal,
                new Trace(requestId, request.checkoutKey(), null, null, null, request.customerCouponId()));
        long discount = quote == null ? 0 : quote.discountAmount();
        Order order = new Order(UUID.randomUUID().toString(), request.customerId(), request.checkoutKey(),
                "PAYMENT_PENDING", items, subtotal, discount, subtotal - discount, request.customerCouponId(), at, at);
        repository.insertOrder(order, requestId);
        if (quote != null) coupons.use(quote, order.id(), subtotal,
                new Trace(requestId, order.checkoutKey(), order.id(), null, null, order.customerCouponId()), at);
        for (Order.Item item : items) {
            int after = repository.subtractStock(item.productId(), item.quantity(), at);
            repository.movement(item.productId(), order.id(), requestId, order.checkoutKey(),
                    "RESERVE", -item.quantity(), after, at);
            events.afterCommit("INVENTORY_RESERVED", new Trace(requestId, order.checkoutKey(), order.id(), null,
                    item.productId(), order.customerCouponId()), Map.of("quantityDelta", -item.quantity(), "quantityAfter", after));
        }
        events.afterCommit("ORDER_CREATED", new Trace(requestId, order.checkoutKey(), order.id(), null, null, order.customerCouponId()),
                Map.of("status", order.status(), "totalAmount", order.totalAmount()));
        return order;
    }

    private void validate(CreateOrder request) {
        if (request == null) throw CommerceException.invalid("Order body is required");
        Inputs.identifier(request.customerId(), "customerId");
        Inputs.identifier(request.checkoutKey(), "checkoutKey");
        if (request.items() == null || request.items().isEmpty()) {
            throw CommerceException.invalid("At least one order item is required");
        }
        var products = new HashSet<String>();
        for (CreateOrder.Item item : request.items()) {
            if (item == null) throw CommerceException.invalid("Order items must not be null");
            Inputs.identifier(item.productId(), "productId");
            if (item.quantity() == null || item.quantity() <= 0) {
                throw CommerceException.invalid("quantity must be a positive integer");
            }
            if (!products.add(item.productId())) throw CommerceException.invalid("Duplicate productId in order items");
        }
        if (request.customerCouponId() != null) Inputs.identifier(request.customerCouponId(), "customerCouponId");
    }
}
