\set ON_ERROR_STOP on
-- Only this synthetic fixture's product and dependent orders are reset; other schemas are untouched.
BEGIN;
SELECT coalesce(array_agg(DISTINCT order_id), '{}'::varchar[]) AS fixture_order_ids
FROM commerce.order_items WHERE product_id = :'fixture_prefix' || '-product'
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
