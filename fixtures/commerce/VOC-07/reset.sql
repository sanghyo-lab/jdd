\set ON_ERROR_STOP on
-- Only this synthetic fixture's product and dependent orders are reset; other schemas are untouched.
BEGIN;
SELECT coalesce(array_agg(DISTINCT order_id), '{}'::varchar[]) AS fixture_order_ids
FROM commerce.order_items WHERE product_id = :'fixture_prefix' || '-product'
\gset
DELETE FROM commerce.order_operations WHERE order_id = ANY(:'fixture_order_ids'::varchar[]);
DELETE FROM commerce.refunds WHERE order_id = ANY(:'fixture_order_ids'::varchar[]);
DELETE FROM commerce.payments WHERE order_id = ANY(:'fixture_order_ids'::varchar[]);
DELETE FROM commerce.coupon_usages WHERE order_id = ANY(:'fixture_order_ids'::varchar[]);
DELETE FROM commerce.inventory_movements
WHERE product_id = :'fixture_prefix' || '-product' OR order_id = ANY(:'fixture_order_ids'::varchar[]);
DELETE FROM commerce.order_items WHERE order_id = ANY(:'fixture_order_ids'::varchar[]);
DELETE FROM commerce.orders WHERE id = ANY(:'fixture_order_ids'::varchar[]);
DELETE FROM commerce.product_stock WHERE product_id = :'fixture_prefix' || '-product';
DELETE FROM commerce.products WHERE id = :'fixture_prefix' || '-product';
COMMIT;
