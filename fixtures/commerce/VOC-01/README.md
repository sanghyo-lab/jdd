# VOC-01 — 결제 승인과 주문 상태

합성 데이터와 로컬 모의 결제 제공자를 사용한다. 외부 결제·LLM 호출은 없다.

```bash
COMMERCE_REPRODUCTION_ENABLED=true ./scripts/dev up
python3 fixtures/commerce/reproduce_commerce.py --scenario VOC-01 --runs 3
```

매회 `jdd-v01-<UUID>-<회차>`의 독립 데이터로 실행하고 DB·HTTP·JSONL·실행 소스 근거를
`runtime/submission/commerce-reproductions/<UTC>-business.json`에 남긴다. 실패 실행도 별도 보존한다.
초기 상품 재고는 10개이고 INITIAL 이력을 기록한다. 모든 주문을 순차로 실행해 재고 경쟁 결함과 분리한다.
수동 준비는 `jdd_commerce` 계정의 `psql -v fixture_prefix=jdd-v01-manual`로 reset.sql → seed.sql을 적용한다.
초기화는 이 접두어의 데이터만 대상으로 하며 다른 앱·데이터·로그를 삭제하지 않는다.

기본 주문 본문은 아래와 같다. `POST /api/orders`에 `X-Request-Id`를 지정하고 응답의 실제 주문 ID를 다음 요청에 사용한다.

```json
{"customerId":"jdd-v01-manual-customer","checkoutKey":"jdd-v01-manual-checkout","items":[{"productId":"jdd-v01-manual-product","quantity":1}],"customerCouponId":null}
```

[기대 관측과 대조](expected.md)는 평가 자료이며 Agent 입력/소스 검색에서 제외한다.

주문 A/B를 각각 생성하고 `POST /api/orders/{id}/payments`를 호출한다.

```json
{"requestKey":"jdd-v01-manual-payment-easy","method":"EASY_PAY"}
```

대조 주문은 다른 requestKey와 method=CARD로 결제한다. 같은 본문 재전송은 저장 결과를 반환한다.
