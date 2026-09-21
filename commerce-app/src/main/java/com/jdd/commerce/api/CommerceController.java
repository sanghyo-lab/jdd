package com.jdd.commerce.api;

import com.jdd.commerce.common.CommerceException;
import com.jdd.commerce.coupon.application.CouponService;
import com.jdd.commerce.coupon.domain.CustomerCoupon;
import com.jdd.commerce.order.application.OrderService;
import com.jdd.commerce.order.domain.CreateOrder;
import com.jdd.commerce.order.domain.Order;
import com.jdd.commerce.payment.application.PaymentService;
import com.jdd.commerce.payment.domain.PaymentResult;
import com.jdd.commerce.payment.domain.CancelResult;
import com.jdd.commerce.product.domain.Product;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api")
public class CommerceController {
    private final OrderService orders;
    private final CouponService coupons;
    private final PaymentService payments;
    public CommerceController(OrderService orders, CouponService coupons, PaymentService payments) {
        this.orders = orders; this.coupons = coupons; this.payments = payments;
    }
    @GetMapping("/customers/{customerId}/coupons") public Map<String, List<CustomerCoupon>> coupons(
            @PathVariable String customerId, @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return Map.of("items", coupons.coupons(customerId, limit, offset));
    }
    @GetMapping("/products") public Map<String, List<Product>> products(
            @RequestParam(defaultValue = "20") int limit, @RequestParam(defaultValue = "0") int offset) {
        return Map.of("items", orders.products(limit, offset));
    }
    @GetMapping("/products/{id}") public Product product(@PathVariable String id) { return orders.product(id); }
    @GetMapping("/orders") public Map<String, List<Order>> orders(
            @RequestParam(required = false) String customerId, @RequestParam(required = false) String checkoutKey,
            @RequestParam(defaultValue = "20") int limit, @RequestParam(defaultValue = "0") int offset) {
        return Map.of("items", orders.orders(customerId, checkoutKey, limit, offset));
    }
    @GetMapping("/orders/{id}") public Order order(@PathVariable String id) { return orders.order(id); }
    @PostMapping("/orders/{id}/payments") public PaymentResult pay(@PathVariable String id,
            @RequestBody JsonNode body, HttpServletRequest request) {
        return payments.pay(id, string(body, "requestKey", false), string(body, "method", false),
                (String) request.getAttribute(RequestTraceFilter.ATTRIBUTE));
    }
    @PostMapping("/orders/{id}/cancel") public CancelResult cancel(@PathVariable String id,
            @RequestBody JsonNode body, HttpServletRequest request) {
        return payments.cancel(id, string(body, "requestKey", false), string(body, "reason", false),
                (String) request.getAttribute(RequestTraceFilter.ATTRIBUTE));
    }
    @PostMapping("/orders") @ResponseStatus(HttpStatus.CREATED)
    public Order create(@RequestBody JsonNode body, HttpServletRequest request) {
        if (!body.isObject() || !body.path("items").isArray()) throw CommerceException.invalid("Order items must be an array");
        var items = new ArrayList<CreateOrder.Item>();
        for (JsonNode item : body.path("items")) {
            JsonNode quantity = item.path("quantity");
            if (!quantity.isIntegralNumber() || !quantity.canConvertToInt()) {
                throw CommerceException.invalid("quantity must be an integer in range");
            }
            items.add(new CreateOrder.Item(string(item, "productId", false), quantity.intValue()));
        }
        return orders.create(new CreateOrder(string(body, "customerId", false), string(body, "checkoutKey", false),
                items, string(body, "customerCouponId", true)), (String) request.getAttribute(RequestTraceFilter.ATTRIBUTE));
    }
    static String string(JsonNode body, String key, boolean optional) {
        JsonNode value = body.path(key);
        if (optional && (value.isMissingNode() || value.isNull())) return null;
        if (!value.isString()) throw CommerceException.invalid(key + " must be a string");
        return value.stringValue();
    }
}
