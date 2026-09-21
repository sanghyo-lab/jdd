\set ON_ERROR_STOP on
-- Synthetic products/coupons and their dependent orders only. Requires no TEMP or DDL privilege.
BEGIN;
SELECT coalesce(array_agg(DISTINCT order_id), '{}'::varchar[]) AS fixture_order_ids
FROM commerce.order_items WHERE left(product_id,length(:'fixture_prefix')+1)=:'fixture_prefix'||'-'
\gset
SELECT NOT EXISTS (
 SELECT 1 FROM commerce.order_items WHERE order_id=ANY(:'fixture_order_ids'::varchar[])
 AND left(product_id,length(:'fixture_prefix')+1)<>:'fixture_prefix'||'-'
) AND NOT EXISTS (
 SELECT 1 FROM commerce.orders WHERE id=ANY(:'fixture_order_ids'::varchar[]) AND customer_coupon_id IS NOT NULL
 AND left(customer_coupon_id,length(:'fixture_prefix')+1)<>:'fixture_prefix'||'-'
) AND NOT EXISTS (
 SELECT 1 FROM commerce.inventory_movements WHERE order_id=ANY(:'fixture_order_ids'::varchar[])
 AND left(product_id,length(:'fixture_prefix')+1)<>:'fixture_prefix'||'-'
) AS fixture_scope_safe
\gset
\if :fixture_scope_safe
\else
\warn 'Refusing reset: an order references data outside this synthetic fixture prefix.'
ROLLBACK;
DO $$ BEGIN RAISE EXCEPTION 'Refusing reset: cross-prefix dependency.'; END $$;
\endif
DELETE FROM commerce.order_operations WHERE order_id=ANY(:'fixture_order_ids'::varchar[]);
DELETE FROM commerce.refunds WHERE order_id=ANY(:'fixture_order_ids'::varchar[]);
DELETE FROM commerce.payments WHERE order_id=ANY(:'fixture_order_ids'::varchar[]);
DELETE FROM commerce.coupon_usages WHERE order_id=ANY(:'fixture_order_ids'::varchar[]);
DELETE FROM commerce.inventory_movements WHERE left(product_id,length(:'fixture_prefix')+1)=:'fixture_prefix'||'-'
 OR order_id=ANY(:'fixture_order_ids'::varchar[]);
DELETE FROM commerce.order_items WHERE order_id=ANY(:'fixture_order_ids'::varchar[]);
DELETE FROM commerce.orders WHERE id=ANY(:'fixture_order_ids'::varchar[]);
DELETE FROM commerce.customer_coupons WHERE left(id,length(:'fixture_prefix')+1)=:'fixture_prefix'||'-';
DELETE FROM commerce.coupons WHERE left(id,length(:'fixture_prefix')+1)=:'fixture_prefix'||'-';
DELETE FROM commerce.product_stock WHERE left(product_id,length(:'fixture_prefix')+1)=:'fixture_prefix'||'-';
DELETE FROM commerce.products WHERE left(id,length(:'fixture_prefix')+1)=:'fixture_prefix'||'-';
COMMIT;
