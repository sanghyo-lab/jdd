\set ON_ERROR_STOP on
-- Synthetic products/coupons and their dependent orders only. Requires no TEMP or DDL privilege.
BEGIN;
SELECT coalesce(array_agg(DISTINCT order_id), '{}'::varchar[]) AS fixture_order_ids
FROM commerce.order_items WHERE left(product_id,length(:'fixture_prefix')+1)=:'fixture_prefix'||'-'
\gset
DELETE FROM commerce.order_operations WHERE order_id=ANY(:'fixture_order_ids'::varchar[]);
DELETE FROM commerce.refunds WHERE order_id=ANY(:'fixture_order_ids'::varchar[]);
DELETE FROM commerce.payments WHERE order_id=ANY(:'fixture_order_ids'::varchar[]);
DELETE FROM commerce.coupon_usages WHERE order_id=ANY(:'fixture_order_ids'::varchar[]);
DELETE FROM commerce.inventory_movements WHERE left(product_id,length(:'fixture_prefix')+1)=:'fixture_prefix'||'-'
 OR order_id=ANY(:'fixture_order_ids'::varchar[]);
DELETE FROM commerce.order_items WHERE order_id=ANY(:'fixture_order_ids'::varchar[]);
DELETE FROM commerce.orders WHERE id=ANY(:'fixture_order_ids'::varchar[]);
DELETE FROM commerce.customer_coupons WHERE customer_id=:'fixture_prefix'||'-customer';
DELETE FROM commerce.coupons WHERE left(id,length(:'fixture_prefix')+1)=:'fixture_prefix'||'-';
DELETE FROM commerce.product_stock WHERE left(product_id,length(:'fixture_prefix')+1)=:'fixture_prefix'||'-';
DELETE FROM commerce.products WHERE left(id,length(:'fixture_prefix')+1)=:'fixture_prefix'||'-';
COMMIT;
