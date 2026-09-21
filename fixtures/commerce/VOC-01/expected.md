# VOC-01 관측 기준

간편결제 APPROVED·주문 PAYMENT_PENDING과 카드 APPROVED·주문 PAID를 각각 확인한다. 두 승인 로그의 orderId/paymentId/providerReference를 DB와 대조한다. 같은 키 재전송은 같은 결제·저장 응답이며 다른 method로 같은 키를 쓰면 409다. 정상 정책은 두 수단 모두 주문 PAID 전이다.
