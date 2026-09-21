\set ON_ERROR_STOP on
BEGIN;
INSERT INTO commerce.products (id,name,price,created_at,updated_at) VALUES
(:'fixture_prefix'||'-product','합성 할인 상품',60000,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
INSERT INTO commerce.product_stock VALUES (:'fixture_prefix'||'-product',10,CURRENT_TIMESTAMP);
INSERT INTO commerce.inventory_movements (id,product_id,movement_type,quantity_delta,quantity_after,occurred_at)
 VALUES (:'fixture_prefix'||'-initial',:'fixture_prefix'||'-product','INITIAL',10,10,CURRENT_TIMESTAMP);
INSERT INTO commerce.coupons (id,discount_type,min_order_amount,fixed_discount_amount,discount_rate,max_discount_amount,valid_from,valid_until) VALUES
(:'fixture_prefix'||'-percent','PERCENT',50000,NULL,10,10000,CURRENT_TIMESTAMP-interval '1 day',CURRENT_TIMESTAMP+interval '1 day'),
(:'fixture_prefix'||'-fixed','FIXED',50000,5000,NULL,NULL,CURRENT_TIMESTAMP-interval '1 day',CURRENT_TIMESTAMP+interval '1 day'),
(:'fixture_prefix'||'-capped','FIXED',0,80000,NULL,10000,CURRENT_TIMESTAMP-interval '1 day',CURRENT_TIMESTAMP+interval '1 day'),
(:'fixture_prefix'||'-subtotal','FIXED',0,80000,NULL,NULL,CURRENT_TIMESTAMP-interval '1 day',CURRENT_TIMESTAMP+interval '1 day');
INSERT INTO commerce.customer_coupons (id,customer_id,coupon_id,status,issued_at,updated_at)
 SELECT :'fixture_prefix'||'-cc-'||suffix,:'fixture_prefix'||'-customer',:'fixture_prefix'||'-'||suffix,
 'AVAILABLE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP FROM (VALUES ('percent'),('fixed'),('capped'),('subtotal')) v(suffix);
COMMIT;
