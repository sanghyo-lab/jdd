# VOC-07 — 독립 PostgreSQL 트랜잭션의 동시 주문

로컬 합성 데모 DB에서 실행한다. 모델 호출은 없으며 업무 재현과 실제 AI 조사 검증을 구분한다.
초기 상품 가격은 50,000원, 재고는 1개다. 쿠폰·결제 없이 서로 다른 고객·checkoutKey로 각각 1개를 주문한다.

```bash
COMMERCE_REPRODUCTION_ENABLED=true ./scripts/dev up
./scripts/dev smoke
python3 fixtures/commerce/reproduce_inventory.py --runs 20
```

실행마다 `jdd-v07-<실행 UUID>-<회차>` 접두어를 만들고 `reset.sql` → `seed.sql` → 실제 HTTP 요청 → DB·로그 대조를 수행한다.
첫 실행과 재실행은 서로 다른 상품·주문·이력을 보존한다. 초기화 SQL은 지정 접두어의 상품과 연결된 합성 주문에만 적용한다.
다른 시나리오·스키마·과거 로그를 지우지 않는다. 전체 시나리오는 순차 실행하고 이 시나리오의 두 HTTP 요청만 동시에 보낸다.

결과는 `runtime/submission/commerce-reproductions/<UTC 시각>-inventory.json`에 실행마다 별도로 남는다.
응답·DB 행·각 연결의 `pg_backend_pid()`·`txid_current()`·로그 줄·실행 소스 manifest를 보존한다.
실패 시 종료 코드가 0이 아니며 실패 결과도 저장한다. 이 파일을 live 모델 결과나 DONE 자료로 사용하지 않는다.

## 수동 실행과 runner 연동

SQL 실행은 `jdd_commerce` 준비 계정으로 `psql -v fixture_prefix=jdd-v07-manual -v stock_quantity=1`에
이 폴더의 `reset.sql`, `seed.sql`을 순서대로 전달한다. Agent의 `jdd_evidence`는 초기화에 사용하지 않는다.
Python 실행기의 `seed`, `http`, `observation`을 참고해 scenario-runner에서 동일 동작을 구현할 수 있다.

1. `POST /internal/reproduction/inventory-barrier`에 아래 본문을 보낸다. 기본 설정에서는 이 API가 없다.
2. 서로 다른 두 요청으로 `POST /api/orders`를 동시에 호출한다. customerId·checkoutKey·X-Request-Id를 각각 다르게 한다.
3. `GET /internal/reproduction/inventory-barrier/{id}`에서 두 도착의 DB 연결·트랜잭션을 확인한다.
4. 성공·실패와 관계없이 `DELETE /internal/reproduction/inventory-barrier/{id}`를 호출해 해제한다.

```json
{
  "productId": "jdd-v07-manual-product",
  "checkoutKeys": ["jdd-v07-manual-a", "jdd-v07-manual-b"],
  "timeoutMs": 8000
}
```

주문 요청 A다. B는 고객·키를 각각 `-b`로 바꾼다.

```json
{
  "customerId": "jdd-v07-manual-customer-a",
  "checkoutKey": "jdd-v07-manual-a",
  "items": [{"productId": "jdd-v07-manual-product", "quantity": 1}],
  "customerCouponId": null
}
```

동기화는 지정 상품·두 키에만 적용한다. 시간 제한은 100~10,000ms이고 한 번에 한 장벽을 준비한다.
두 요청이 재고를 읽은 후 함께 진행한다. 시간 초과·해제·인터럽트는 `503 DEPENDENCY_UNAVAILABLE`이며 주문·예약을 롤백한다.
재현 제어 소스는 `commerce-app/src/reproduction/java`에 있고 Agent 소스 스냅샷에 포함되지 않는다.
web/ngrok에는 `/internal/reproduction`을 중계하지 않는다. 일반 실행은 환경 변수를 생략해 재현 제어를 비활성화한다.

## 정상 대조와 복구

자동 실행은 재고 1개의 순차 요청(201·422, 최종 0), 재고 2개의 동시 요청(201·201, 최종 0),
장벽 시간 초과의 롤백 및 해제 후 재요청 성공을 함께 확인한다. [관측 기준](expected.md)을 따른다.
