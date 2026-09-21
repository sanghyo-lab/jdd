# JSONL 형식 예제

[business.jsonl](business.jsonl)은 정상 주문의 읽기·예약·생성 필드를 보여 주기 위해 작성한 합성 예제다.
모든 식별자와 시각은 예시이며 실제 실행 로그·장애 재현·검증 성공의 근거가 아니다.
실제 이벤트와 필드 의미는 [로그 계약](../../../docs/commerce-interface.md)을 따른다.
Agent의 로그 볼륨이나 소스 스냅샷으로 복사하지 않는다. fixtures는 조사 검색 범위 밖이다.

실제 로그는 재현 실행 후 `runtime/evidence/logs/commerce/<buildId>/business.jsonl`에서 확인한다.
읽기 이벤트는 커밋을 보장하지 않으며 성공 이벤트는 업무 트랜잭션 outbox에서 내보낸다.
