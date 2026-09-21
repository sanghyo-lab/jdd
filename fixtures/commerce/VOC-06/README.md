# VOC-06 — 취소 후 쿠폰 복원 누락

합성 데이터와 로컬 모의 결제 제공자를 사용한다. 외부 결제·LLM 호출은 없다.

```bash
COMMERCE_REPRODUCTION_ENABLED=true ./scripts/dev up
python3 fixtures/commerce/reproduce_commerce.py --scenario VOC-06 --runs 3
```

매회 `jdd-v06-<UUID>-<회차>`의 독립 데이터로 실행하고 DB·HTTP·JSONL·실행 소스 근거를
`runtime/submission/commerce-reproductions/<UTC>-business.json`에 남긴다. 실패 실행도 별도 보존한다.
초기 상품 재고는 10개이고 INITIAL 이력을 기록한다. 모든 주문을 순차로 실행해 재고 경쟁 결함과 분리한다.
수동 준비는 `jdd_commerce` 계정의 `psql -v fixture_prefix=jdd-v06-manual`로 reset.sql → seed.sql을 적용한다.
초기화는 이 접두어의 데이터만 대상으로 하며 다른 앱·데이터·로그를 삭제하지 않는다.

기본 주문 본문은 아래와 같다. `POST /api/orders`에 `X-Request-Id`를 지정하고 응답의 실제 주문 ID를 다음 요청에 사용한다.

```json
{"customerId":"jdd-v06-manual-customer","checkoutKey":"jdd-v06-manual-checkout","items":[{"productId":"jdd-v06-manual-product","quantity":1}],"customerCouponId":null}
```

[기대 관측과 대조](expected.md)는 평가 자료이며 Agent 입력/소스 검색에서 제외한다.

주문의 customerCouponId에 `jdd-v06-manual-cc-fixed`를 넣고 CARD 결제 후 전체 취소한다.
취소 요청은 requestKey와 reason 문자열이다. 이후 같은 쿠폰으로 다른 checkoutKey 주문을 시도한다.
대조 쿠폰은 `-cc-expiring`이다. 유효할 때 주문·결제한 뒤 이 합성 쿠폰의 valid_until만 CURRENT_TIMESTAMP로
설정해 사용 후 만료 경계를 만든다. 취소·환불 후에도 만료 쿠폰은 재사용할 수 없어야 한다.
실행기는 해당 접두어의 쿠폰 한 행만 시각을 바꾸고 글로벌 시계·다른 정책은 바꾸지 않는다.
