package com.jdd.commerce.product.domain;

import java.time.Instant;

public record Product(String id, String name, long price, int stockQuantity, Instant updatedAt) {}
