# VOC-03 — 정률 할인 계산

실제 로컬 PostgreSQL과 HTTP에서 합성 데이터로 재현한다. 모델을 호출하지 않는다.

```bash
./scripts/dev up
python3 fixtures/commerce/reproduce_commerce.py --scenario VOC-03 --runs 3
```

매회 독립 `jdd-v03-<UUID>-<회차>` 접두어의 데이터만 초기화한다. 해당 폴더의 reset.sql → seed.sql을
`jdd_commerce` 계정으로 `psql -v fixture_prefix=jdd-v03-manual`에 전달해 수동 준비할 수도 있다.
상품 초기 수량은 각 10개이며 INITIAL 이력을 함께 기록한다. 과거 실행의 로그·다른 접두어·다른 스키마는 보존한다.

`POST /api/orders`에 `X-Request-Id: jdd-v03-manual-request`와 아래 JSON을 보낸다.

```json
{"customerId":"jdd-v03-manual-customer","checkoutKey":"jdd-v03-manual-checkout","items":[{"productId":"jdd-v03-manual-product","quantity":1}],"customerCouponId":"jdd-v03-manual-cc-percent"}
```

`GET /api/customers/jdd-v03-manual-customer/coupons`로 소유·기간·할인 정책을 조회한다.
재현 입력·응답·DB 행·JSONL 줄 번호·실행 소스 manifest는 실행별
`runtime/submission/commerce-reproductions/<UTC>-business.json`에 저장한다. 실패 결과도 보존하고 종료 코드는 0이 아니다.
[관측 기준](expected.md)은 평가 자료이며 Agent 입력/검색 범위에서 제외한다.
