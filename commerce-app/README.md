# Commerce 실행과 현재 제공 기능

상품 목록·상세, 주문 생성·목록·상세, 재고 예약과 이력을 실제 DB에 저장한다.
결제·쿠폰 적용·취소·환불은 다음 구현 단위이며 `businessReady=false`를 유지한다.
의도한 VOC-04의 주문 생성 재전송 중복과 VOC-07의 재고 경쟁 조건은 재현 대상으로 남아 있다.

```bash
./scripts/dev up
./scripts/dev smoke
./gradlew --no-daemon :commerce-app:test
```

상품은 자동으로 임의 시드하지 않는다. [VOC-07 준비·독립 재현](../fixtures/commerce/VOC-07/README.md)의 실행기가
지정된 합성 상품과 초기 재고 이력을 만든다. 실행 결과 파일에 상품·주문·고객·요청·checkoutKey가 기록된다.
그 식별자를 [v1 커머스 API](../docs/commerce-interface.md)에 사용한다.

검증은 HTTP 입력·저장·목록/상세, 순차 재고 거절, 금액 범위, DB 저장 실패의 전체 롤백,
기본 설정의 재현 API 비노출을 포함한다. H2 검사는 실제 PostgreSQL 동시성 검증을 대체하지 않는다.

## 로그와 조사 경계

- 호스트 `runtime/evidence/logs/commerce/<buildId>/business.jsonl`에 JSONL을 기록한다.
- INVENTORY_READ는 읽은 즉시 기록하고, 성공한 업무 이벤트는 같은 트랜잭션의 `commerce.event_outbox`에 저장한 뒤 커밋 후 내보낸다.
- 내보내기는 기본 100ms 주기, 한 번에 100건이다. 실패한 행은 DB에 남아 재시도하며 요청 성공을 허위 실패로 바꾸지 않는다.
- 프로세스가 파일 기록과 완료 표시 사이에 종료되면 동일 eventId가 다시 출력될 수 있다. 소비자는 이를 별도의 주문으로 계산하지 않는다.
- 과거 buildId의 미출력 이벤트도 원래 빌드의 로그 디렉터리로 출력한다. 로그 회전·삭제는 자동 수행하지 않는다.
- 소스는 `scripts/dev up`이 만든 불변 스냅샷과 manifest를 사용한다. 테스트·fixtures·평가·재현 제어 소스는 포함되지 않는다.
- 기본 `COMMERCE_REPRODUCTION_ENABLED=false`다. 재현 제어는 로컬 합성 검증에만 켜고 web/ngrok에 중계하지 않는다.

현재 상호 연동 확인은 [DISC-20260921-commerce-001](../docs/discussions/DISC-20260921-commerce-001-inventory-evidence.md)로 추적한다.
