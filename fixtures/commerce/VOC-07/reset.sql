\set ON_ERROR_STOP on
-- Only this synthetic fixture's product and dependent orders are reset; other schemas are untouched.
BEGIN;
CREATE TEMP TABLE fixture_order_ids ON COMMIT DROP AS
SELECT DISTINCT order_id AS id FROM commerce.order_items WHERE product_id = :'fixture_prefix' || '-product';
DELETE FROM commerce.order_operations WHERE order_id IN (SELECT id FROM fixture_order_ids);
DELETE FROM commerce.refunds WHERE order_id IN (SELECT id FROM fixture_order_ids);
DELETE FROM commerce.payments WHERE order_id IN (SELECT id FROM fixture_order_ids);
DELETE FROM commerce.coupon_usages WHERE order_id IN (SELECT id FROM fixture_order_ids);
DELETE FROM commerce.inventory_movements
WHERE product_id = :'fixture_prefix' || '-product' OR order_id IN (SELECT id FROM fixture_order_ids);
DELETE FROM commerce.order_items WHERE order_id IN (SELECT id FROM fixture_order_ids);
DELETE FROM commerce.orders WHERE id IN (SELECT id FROM fixture_order_ids);
DELETE FROM commerce.product_stock WHERE product_id = :'fixture_prefix' || '-product';
DELETE FROM commerce.products WHERE id = :'fixture_prefix' || '-product';
COMMIT;
