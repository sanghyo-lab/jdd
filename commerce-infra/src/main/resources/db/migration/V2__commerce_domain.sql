CREATE TABLE products (
    id VARCHAR(128) PRIMARY KEY,
    name VARCHAR(256) NOT NULL,
    price BIGINT NOT NULL CHECK (price >= 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE product_stock (
    product_id VARCHAR(128) PRIMARY KEY REFERENCES products(id),
    quantity INTEGER NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE coupons (
    id VARCHAR(128) PRIMARY KEY,
    discount_type VARCHAR(16) NOT NULL CHECK (discount_type IN ('FIXED', 'PERCENT')),
    min_order_amount BIGINT NOT NULL CHECK (min_order_amount >= 0),
    fixed_discount_amount BIGINT CHECK (fixed_discount_amount >= 0),
    discount_rate NUMERIC(7, 4) CHECK (discount_rate >= 0 AND discount_rate <= 100),
    max_discount_amount BIGINT CHECK (max_discount_amount >= 0),
    valid_from TIMESTAMP WITH TIME ZONE NOT NULL,
    valid_until TIMESTAMP WITH TIME ZONE NOT NULL,
    CHECK (valid_until > valid_from),
    CHECK ((discount_type = 'FIXED' AND fixed_discount_amount IS NOT NULL AND discount_rate IS NULL)
        OR (discount_type = 'PERCENT' AND discount_rate IS NOT NULL AND fixed_discount_amount IS NULL))
);

CREATE TABLE customer_coupons (
    id VARCHAR(128) PRIMARY KEY,
    customer_id VARCHAR(128) NOT NULL,
    coupon_id VARCHAR(128) NOT NULL REFERENCES coupons(id),
    status VARCHAR(16) NOT NULL CHECK (status IN ('AVAILABLE', 'USED')),
    issued_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX customer_coupons_customer_idx ON customer_coupons(customer_id, id);

CREATE TABLE orders (
    id VARCHAR(128) PRIMARY KEY,
    customer_id VARCHAR(128) NOT NULL,
    checkout_key VARCHAR(128) NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    status VARCHAR(24) NOT NULL CHECK (status IN ('PAYMENT_PENDING', 'PAID', 'CANCELLED')),
    subtotal BIGINT NOT NULL CHECK (subtotal >= 0),
    discount_amount BIGINT NOT NULL CHECK (discount_amount >= 0),
    total_amount BIGINT NOT NULL CHECK (total_amount >= 0),
    customer_coupon_id VARCHAR(128) REFERENCES customer_coupons(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CHECK (total_amount = subtotal - discount_amount)
);
CREATE INDEX orders_customer_time_idx ON orders(customer_id, created_at, id);
CREATE INDEX orders_checkout_idx ON orders(checkout_key, created_at, id);
CREATE INDEX orders_request_idx ON orders(request_id);

CREATE TABLE order_items (
    id VARCHAR(128) PRIMARY KEY,
    order_id VARCHAR(128) NOT NULL REFERENCES orders(id),
    product_id VARCHAR(128) NOT NULL REFERENCES products(id),
    position INTEGER NOT NULL CHECK (position >= 0),
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    unit_price BIGINT NOT NULL CHECK (unit_price >= 0),
    line_amount BIGINT NOT NULL CHECK (line_amount >= 0),
    UNIQUE (order_id, product_id),
    UNIQUE (order_id, position)
);
CREATE INDEX order_items_product_idx ON order_items(product_id, order_id);

CREATE TABLE payments (
    id VARCHAR(128) PRIMARY KEY,
    order_id VARCHAR(128) NOT NULL REFERENCES orders(id),
    request_key VARCHAR(128) NOT NULL,
    method VARCHAR(16) NOT NULL CHECK (method IN ('CARD', 'EASY_PAY')),
    status VARCHAR(16) NOT NULL CHECK (status IN ('APPROVED', 'FAILED')),
    amount BIGINT NOT NULL CHECK (amount >= 0),
    provider_reference VARCHAR(128),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (order_id, request_key)
);

CREATE TABLE refunds (
    id VARCHAR(128) PRIMARY KEY,
    order_id VARCHAR(128) NOT NULL REFERENCES orders(id),
    payment_id VARCHAR(128) NOT NULL REFERENCES payments(id),
    request_key VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED')),
    amount BIGINT NOT NULL CHECK (amount >= 0),
    failure_code VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (order_id, request_key)
);

CREATE TABLE coupon_usages (
    id VARCHAR(128) PRIMARY KEY,
    customer_coupon_id VARCHAR(128) NOT NULL REFERENCES customer_coupons(id),
    order_id VARCHAR(128) NOT NULL REFERENCES orders(id),
    status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE', 'RELEASED')),
    discount_amount BIGINT NOT NULL CHECK (discount_amount >= 0),
    used_at TIMESTAMP WITH TIME ZONE NOT NULL,
    released_at TIMESTAMP WITH TIME ZONE,
    UNIQUE (order_id, customer_coupon_id)
);
CREATE INDEX coupon_usages_coupon_idx ON coupon_usages(customer_coupon_id, used_at);

CREATE TABLE inventory_movements (
    id VARCHAR(128) PRIMARY KEY,
    product_id VARCHAR(128) NOT NULL REFERENCES products(id),
    order_id VARCHAR(128) REFERENCES orders(id),
    request_id VARCHAR(128),
    checkout_key VARCHAR(128),
    movement_type VARCHAR(16) NOT NULL CHECK (movement_type IN ('INITIAL', 'RECEIPT', 'RESERVE', 'RELEASE', 'ADJUST')),
    quantity_delta INTEGER NOT NULL,
    quantity_after INTEGER NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX inventory_product_time_idx ON inventory_movements(product_id, occurred_at, id);
CREATE INDEX inventory_order_idx ON inventory_movements(order_id);

CREATE TABLE order_operations (
    order_id VARCHAR(128) NOT NULL REFERENCES orders(id),
    operation VARCHAR(16) NOT NULL CHECK (operation IN ('PAYMENT', 'CANCEL')),
    request_key VARCHAR(128) NOT NULL,
    input_fingerprint VARCHAR(64) NOT NULL,
    response_json TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (order_id, operation, request_key)
);
