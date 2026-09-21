\set ON_ERROR_STOP on
-- Invoke with a unique synthetic fixture_prefix . No shared/global reset.
BEGIN;
INSERT INTO commerce.products (id, name, price, created_at, updated_at)
VALUES (:'fixture_prefix' || '-product', '합성 한정 상품', 50000, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO commerce.product_stock (product_id, quantity, updated_at)
VALUES (:'fixture_prefix' || '-product', 10, CURRENT_TIMESTAMP);
INSERT INTO commerce.inventory_movements
    (id, product_id, order_id, request_id, checkout_key, movement_type, quantity_delta, quantity_after, occurred_at)
VALUES (:'fixture_prefix' || '-initial', :'fixture_prefix' || '-product', NULL, NULL, NULL,
        'INITIAL', 10, 10, CURRENT_TIMESTAMP);
COMMIT;
