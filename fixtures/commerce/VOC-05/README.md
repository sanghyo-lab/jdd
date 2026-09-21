# VOC-05 — 환불 실패 후 추적 누락

합성 데이터와 로컬 모의 결제 제공자를 사용한다. 외부 결제·LLM 호출은 없다.

```bash
COMMERCE_REPRODUCTION_ENABLED=true ./scripts/dev up
python3 fixtures/commerce/reproduce_commerce.py --scenario VOC-05 --runs 3
```

매회 `jdd-v05-<UUID>-<회차>`의 독립 데이터로 실행하고 DB·HTTP·JSONL·실행 소스 근거를
`runtime/submission/commerce-reproductions/<UTC>-business.json`에 남긴다. 실패 실행도 별도 보존한다.
초기 상품 재고는 10개이고 INITIAL 이력을 기록한다. 모든 주문을 순차로 실행해 재고 경쟁 결함과 분리한다.
수동 준비는 `jdd_commerce` 계정의 `psql -v fixture_prefix=jdd-v05-manual`로 reset.sql → seed.sql을 적용한다.
초기화는 이 접두어의 데이터만 대상으로 하며 다른 앱·데이터·로그를 삭제하지 않는다.

기본 주문 본문은 아래와 같다. `POST /api/orders`에 `X-Request-Id`를 지정하고 응답의 실제 주문 ID를 다음 요청에 사용한다.

```json
{"customerId":"jdd-v05-manual-customer","checkoutKey":"jdd-v05-manual-checkout","items":[{"productId":"jdd-v05-manual-product","quantity":1}],"customerCouponId":null}
```

[기대 관측과 대조](expected.md)는 평가 자료이며 Agent 입력/소스 검색에서 제외한다.

각 주문을 method=CARD로 결제한다. 실패 대상 주문은 취소 전에 아래 제어 요청을
`POST /internal/reproduction/refund-failure`로 보낸다. 정상 대조 주문은 제어를 걸지 않는다.

```json
{"orderId":"실제 주문 ID","requestKey":"jdd-v05-manual-cancel"}
```

이후 `POST /api/orders/{id}/cancel`에 같은 requestKey와 reason=합성 전체 취소를 보낸다.
제어는 해당 주문·키의 모의 환불 응답 1회에만 일시 오류를 반환한다. 성공/실패 뒤 같은 제어 본문으로
DELETE를 호출해 남은 제어를 해제한다. 기본 설정에서 제어 API는 없고 재시작 시 제어는 지워진다.
web/ngrok·Agent 도구에는 노출하지 않는다. 정상 응답의 providerReference는 합성 식별자다.
