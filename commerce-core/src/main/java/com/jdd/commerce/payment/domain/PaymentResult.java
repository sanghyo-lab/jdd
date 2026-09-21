package com.jdd.commerce.payment.domain;

import com.jdd.commerce.order.domain.Order;

public record PaymentResult(Order order, Payment payment) {}
