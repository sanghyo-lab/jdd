# VOC-05 관측 기준

일시 오류 대상은 주문 CANCELLED·승인 결제 있음·refund=null·환불 DB 행 없음·REFUND_FAILED(retryable=true)다. 같은 취소 키와 새 키의 재호출에서도 추적/재처리가 누락된 상태가 남는다. 정상 대조는 환불 COMPLETED다. 두 취소의 재고 반환은 각각 한 번이며 최종 재고 10이다. 정상 정책은 실패 상태·사유·같은 환불 키 재처리 추적이다.
