\set ON_ERROR_STOP on
BEGIN;
INSERT INTO commerce.products (id,name,price,created_at,updated_at) VALUES
(:'fixture_prefix'||'-product','합성 기준 상품',50000,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
(:'fixture_prefix'||'-lower','합성 경계 아래',49999,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
(:'fixture_prefix'||'-upper','합성 경계 위',50001,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
INSERT INTO commerce.product_stock SELECT id,10,CURRENT_TIMESTAMP FROM commerce.products
 WHERE left(id,length(:'fixture_prefix')+1)=:'fixture_prefix'||'-';
INSERT INTO commerce.inventory_movements (id,product_id,movement_type,quantity_delta,quantity_after,occurred_at)
 SELECT id||'-initial',id,'INITIAL',10,10,CURRENT_TIMESTAMP FROM commerce.products
 WHERE left(id,length(:'fixture_prefix')+1)=:'fixture_prefix'||'-';
INSERT INTO commerce.coupons (id,discount_type,min_order_amount,fixed_discount_amount,valid_from,valid_until) VALUES
(:'fixture_prefix'||'-fixed','FIXED',50000,5000,CURRENT_TIMESTAMP-interval '1 day',CURRENT_TIMESTAMP+interval '1 day'),
(:'fixture_prefix'||'-expired','FIXED',0,5000,CURRENT_TIMESTAMP-interval '2 days',CURRENT_TIMESTAMP-interval '1 day'),
(:'fixture_prefix'||'-future','FIXED',0,5000,CURRENT_TIMESTAMP+interval '1 day',CURRENT_TIMESTAMP+interval '2 days'),
(:'fixture_prefix'||'-other','FIXED',0,5000,CURRENT_TIMESTAMP-interval '1 day',CURRENT_TIMESTAMP+interval '1 day');
INSERT INTO commerce.customer_coupons (id,customer_id,coupon_id,status,issued_at,updated_at)
 SELECT :'fixture_prefix'||'-cc-'||suffix,:'fixture_prefix'||'-customer',:'fixture_prefix'||'-'||suffix,
 'AVAILABLE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP FROM (VALUES ('fixed'),('expired'),('future'),('other')) v(suffix);
COMMIT;
