# VOC-06 관측 기준

유효한 정액 쿠폰의 주문 CANCELLED·환불 COMPLETED 이후에도 발급 쿠폰 USED·사용 이력 ACTIVE·released_at=null이고 새 주문 422다. 정상 정책은 아직 유효한 쿠폰을 AVAILABLE로 복원하고 사용 이력을 RELEASED로 남기는 것이다. 사용 후 만료시킨 대조 쿠폰도 재사용 422이며 그 거절은 정상이다. 두 주문 모두 재고는 한 번씩 반환된다.
