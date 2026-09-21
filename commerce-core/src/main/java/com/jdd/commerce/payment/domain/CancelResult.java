package com.jdd.commerce.payment.domain;

import com.jdd.commerce.order.domain.Order;

public record CancelResult(Order order, Refund refund) {}
