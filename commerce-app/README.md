# Commerce 실행과 현재 제공 기능

상품·주문·쿠폰·재고, 모의 결제·전체 취소·모의 환불과 처리 이력을 실제 DB에 저장한다.
일곱 시연용 결함을 재현 대상으로 유지한다. 실제 PostgreSQL 업무 검증 후 `businessReady=true`이며 실제 AI 조사·팀 완료와는 별도다.

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

[VOC-02 경계값](../fixtures/commerce/VOC-02/README.md)과 [VOC-03 정률 계산](../fixtures/commerce/VOC-03/README.md)은
`reproduce_commerce.py --scenario VOC-02 --runs 3`처럼 선택할 수 있다. 정액 할인·상한·소유/기간/사용 상태도 함께 확인한다.
발급 쿠폰 행을 잠근 상태에서 검증·사용하며 주문·사용 기록·재고를 같은 트랜잭션으로 저장한다.
기간은 validFrom 이상·validUntil 미만이다. 실패한 주문은 쿠폰을 소비하지 않는다. 동시에 같은 발급 쿠폰을 재사용할 수 없다.

## 결제·취소와 일곱 재현

결제·취소는 주문 행 잠금과 `order_operations`의 처리 결과로 동시 요청·재전송을 직렬화한다.
같은 주문·동작·requestKey의 같은 입력에는 최초 응답을 반환한다. 입력 충돌은 409이며 이미 승인된 결제·재고 반환을 반복하지 않는다.
모의 제공자는 네트워크·실결제 없이 응답하며 동일 요청의 providerReference가 재시작 후에도 동일하다.
취소 reason은 비어 있지 않은 2,000자 이하 문자열이다. 실제 과금·실제 환불로 표시하지 않는다.

```bash
COMMERCE_REPRODUCTION_ENABLED=true ./scripts/dev up
python3 fixtures/commerce/reproduce_commerce.py --runs 3
python3 fixtures/commerce/reproduce_inventory.py --runs 20
```

첫 명령의 재현은 [VOC-01](../fixtures/commerce/VOC-01/README.md)부터 [VOC-06](../fixtures/commerce/VOC-06/README.md)까지 순차 실행한다.
[VOC-04](../fixtures/commerce/VOC-04/README.md)는 주문 생성의 중복이며 결제·취소 재전송과 구분한다.
[VOC-05](../fixtures/commerce/VOC-05/README.md)의 일시 환불 오류 제어는 opt-in 내부 API로 지정 주문·키에 한 번만 적용하고 실행 후 해제한다.
제어 구현·평가 데이터는 Agent 소스 스냅샷에 없다. 실제 DB·로그·소스 재현은 AI가 원인을 조사한 결과와 별도다.

각 reset.sql은 해당 합성 접두어만 초기화한다. 한 주문이 다른 접두어의 상품·쿠폰을 함께 참조하면
삭제 전에 전체 초기화를 거절한다. 이 경우 새로운 접두어로 재현하고 혼합 주문은 보존한다.
`python3 fixtures/commerce/check_fixture_isolation.py`로 일곱 초기화 스크립트의 혼합 주문 거절,
외부 쿠폰 의존 거절과 정상 초기화의 이웃 데이터 보존을 실제 HTTP·PostgreSQL에서 확인할 수 있다.

결제·취소 동시 요청과 같은 빌드의 재시작 보존은 아래 두 단계로 확인한다. `--report`는 이전 실행과 다른 새 경로를 지정한다.

```bash
python3 fixtures/commerce/check_recovery.py --phase prepare --report runtime/submission/recovery-first.json
docker compose --env-file .env restart commerce
python3 fixtures/commerce/check_recovery.py --phase verify --report runtime/submission/recovery-first.json
```

prepare는 합성 주문의 결제/취소에 각각 4개 동시 요청을 보내고, verify는 동일 키·새 키의 재전송과 DB 불변을 확인한다.
별도 Compose 프로젝트/파일을 사용했다면 재시작에도 같은 옵션을 전달한다. 새 바이너리를 빌드하는 up과 같은 프로세스의 restart를 구분한다.

## 로그와 조사 경계

- 호스트 `runtime/evidence/logs/commerce/<buildId>/business.jsonl`에 JSONL을 기록한다.
- INVENTORY_READ는 읽은 즉시 기록하고, 성공한 업무 이벤트는 같은 트랜잭션의 `commerce.event_outbox`에 저장한 뒤 커밋 후 내보낸다.
- 내보내기는 기본 100ms 주기, 한 번에 100건이다. 실패한 행은 DB에 남아 재시도하며 요청 성공을 허위 실패로 바꾸지 않는다.
- 프로세스가 파일 기록과 완료 표시 사이에 종료되면 동일 eventId가 다시 출력될 수 있다. 소비자는 이를 별도의 주문으로 계산하지 않는다.
- 과거 buildId의 미출력 이벤트도 원래 빌드의 로그 디렉터리로 출력한다. 로그 회전·삭제는 자동 수행하지 않는다.
- 소스는 `scripts/dev up`이 만든 불변 스냅샷과 manifest를 사용한다. 테스트·fixtures·평가·재현 제어 소스는 포함되지 않는다.
- 기본 `COMMERCE_REPRODUCTION_ENABLED=false`다. 재현 제어는 로컬 합성 검증에만 켜고 web/ngrok에 중계하지 않는다.

현재 상호 연동 확인은 [DISC-20260921-commerce-001](../docs/discussions/DISC-20260921-commerce-001-inventory-evidence.md)로 추적한다.
