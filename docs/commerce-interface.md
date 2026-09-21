# 커머스 API·DB·로그·소스 인터페이스 v1

제공자는 **이상효**, 사용자는 커머스 API·재현 실행의 **김아름**과 근거 조회의 **한재홍**이다. 상품·주문·재고 API와 아래 DDL, JSONL 근거를 구현했다. 결제·쿠폰 적용·취소·환불은 다음 구현 단위이며 전체 업무 완료를 뜻하지 않는다. 실제 검증 상태는 [commerce 상태](status/commerce.md), 실행은 [Commerce 안내](../commerce-app/README.md)를 따른다. 결함의 기대 관측값은 [VOC 시나리오](voc-scenarios.md), 정상 동작은 [업무 정책](business-policy.md)을 따른다.

## 1. 공통 규칙

- 기본 주소: 로컬 `http://localhost:8080`. 배포 주소는 환경 변수로 전달한다.
- 요청·응답: JSON. ID는 의미를 해석하지 않는 문자열, 금액은 원 단위 정수, 수량은 정수, 시간은 UTC ISO 8601 문자열이다.
- 요청 헤더 `X-Request-Id`가 있으면 사용하고, 없으면 서버에서 발급한다. 응답 헤더에 같은 값을 반환하고 로그에 기록한다.
- `checkoutKey`는 하나의 주문 의도를 식별한다. 같은 의도의 재시도는 같은 키, 다른 주문은 다른 키를 사용한다.
- 목록 응답은 `{ "items": [...] }`이다. `limit`은 1~100이며 기본값은 20, `offset`은 0 이상이며 기본값은 0이다. 다음 페이지는 offset을 늘려 조회한다.
- 오류 본문은 `{ "code": "...", "message": "...", "retryable": false }`이다. 잘못된 입력은 `400`, 없는 대상은 `404`, 정상적인 재고·쿠폰 조건 거절은 `422`, 예기치 않은 서버 오류는 `500`으로 구분한다.
- 오류 코드 시작값은 `INVALID_INPUT`, `NOT_FOUND`, `INSUFFICIENT_STOCK`, `COUPON_NOT_ELIGIBLE`, `INTERNAL_ERROR`다. 일시적인 의존 서비스 오류는 `503`·`DEPENDENCY_UNAVAILABLE`·`retryable: true`로 반환한다.

아래 요청·응답 형식은 결함이 있어도 유지한다. 상태·금액·주문 수량처럼 의도한 결함으로 잘못되는 값은 시나리오의 관측 대상이다.

## 2. 김아름이 호출할 HTTP API

| 메서드·경로 | 입력 | 정상 응답 |
| --- | --- | --- |
| `GET /api/products` | `limit`, `offset` | `200`, `items: Product[]`, `id` 오름차순 |
| `GET /api/products/{productId}` | 경로의 상품 ID | `200`, `Product` |
| `POST /api/orders` | `CreateOrderRequest` | `201`, `Order` |
| `GET /api/orders` | 선택적 `customerId`, `checkoutKey`, `limit`, `offset` | `200`, `items: Order[]`, `createdAt`·`id` 내림차순 |
| `GET /api/orders/{orderId}` | 경로의 주문 ID | `200`, `Order` |
| `POST /api/orders/{orderId}/payments` | `requestKey`, `method` (`CARD` 또는 `EASY_PAY`) | `200`, `{ "order": Order, "payment": Payment }` |
| `POST /api/orders/{orderId}/cancel` | `requestKey`, `reason` | `200`, `{ "order": Order, "refund": Refund 또는 null }` |
| `GET /api/customers/{customerId}/coupons` | 고객 ID, `limit`, `offset` | `200`, `items: CustomerCoupon[]`, 발급 쿠폰 `id` 오름차순 |

취소 API의 성공은 주문 취소 처리 결과다. 환불 완료 여부는 `refund.status`로 별도 확인한다. 결제 전 취소 또는 환불 기록을 남기지 못한 결함 사례에서는 `refund`가 `null`일 수 있다. 티켓 조사에서 이를 결제·로그와 대조한다.

결제·취소 요청의 requestKey·method·reason은 문자열이다. 같은 주문·동작·requestKey로 같은 입력을 재전송하면 기존 처리 결과를 반환하고, 입력이 다르면 `409 REQUEST_KEY_CONFLICT`를 반환한다. 이미 승인된 결제를 중복 승인하거나 취소된 주문의 재고를 다시 반환하지 않는다. VOC-04의 의도적인 중복 처리는 주문 생성 경로에만 둔다.

### 요청·응답 DTO

별도 표시가 없으면 필수 필드이며, nullable 필드는 응답에 `null`로 포함한다. POST 요청의 선택적 필드는 생략 또는 `null`을 허용한다.

| DTO | 필드 |
| --- | --- |
| `Product` | `id: string`, `name: string`, `price: integer`, `stockQuantity: integer`, `updatedAt: timestamp` |
| `CreateOrderRequest` | `customerId: string`, `checkoutKey: string`, `items: {productId: string, quantity: integer}[]`, 선택적 `customerCouponId: string 또는 null` |
| `Order` | `id`, `customerId`, `checkoutKey`, `status`, `items: OrderItem[]`, `subtotal: integer`, `discountAmount: integer`, `totalAmount: integer`, `customerCouponId: string 또는 null`, `createdAt`, `updatedAt` |
| `OrderItem` | `productId: string`, `quantity: integer`, `unitPrice: integer`, `lineAmount: integer` |
| `Payment` | `id`, `orderId`, `requestKey`, `method`, `status`, `amount: integer`, `providerReference: string 또는 null`, `createdAt`, `updatedAt` |
| `Refund` | `id`, `orderId`, `paymentId`, `requestKey`, `status`, `amount: integer`, `failureCode: string 또는 null`, `createdAt`, `updatedAt` |
| `CustomerCoupon` | `id`, `customerId`, `couponId`, `status`, `discountType`, `minOrderAmount: integer`, `fixedDiscountAmount: integer 또는 null`, `discountRate: number 또는 null`, `maxDiscountAmount: integer 또는 null`, `validFrom`, `validUntil` |

`id`·외래 ID·키·enum은 문자열이고, `createdAt`, `updatedAt`, `validFrom`, `validUntil`은 timestamp다. `discountRate`는 퍼센트 값으로 10%를 `10`으로 표현한다. `subtotal`은 할인 전 상품 합계, `totalAmount`는 할인 후 금액이다. 주문 상품 배열은 비어 있지 않아야 하고 수량은 양수다. 같은 상품을 요청 배열에 중복으로 넣으면 `400`으로 거절한다.

| 대상 | 상태·종류 값 |
| --- | --- |
| 주문 `status` | `PAYMENT_PENDING`, `PAID`, `CANCELLED` |
| 결제 `status` | `APPROVED`, `FAILED` |
| 환불 `status` | `PENDING`, `COMPLETED`, `FAILED` |
| 발급 쿠폰 `status` | `AVAILABLE`, `USED` — 유효기간은 별도 필드로 판단 |
| 할인 `discountType` | `FIXED`, `PERCENT` |
| 결제 `method` | `CARD`, `EASY_PAY` |

주문 생성 예시다. 아래 ID·시각·값은 개발용 가상 데이터다.

```json
{
  "customerId": "customer-demo-01",
  "checkoutKey": "checkout-demo-01",
  "items": [{ "productId": "product-demo-01", "quantity": 1 }],
  "customerCouponId": null
}
```

```json
{
  "id": "order-demo-01",
  "customerId": "customer-demo-01",
  "checkoutKey": "checkout-demo-01",
  "status": "PAYMENT_PENDING",
  "items": [{ "productId": "product-demo-01", "quantity": 1, "unitPrice": 50000, "lineAmount": 50000 }],
  "subtotal": 50000,
  "discountAmount": 0,
  "totalAmount": 50000,
  "customerCouponId": null,
  "createdAt": "2026-09-21T01:00:00Z",
  "updatedAt": "2026-09-21T01:00:00Z"
}
```

## 3. 한재홍이 SELECT할 DB 계약

스키마는 `commerce`다. 아래 컬럼은 조사용 최소 계약으로 마이그레이션에 포함한다. ID·키·상태는 문자열 컬럼, 금액은 `BIGINT`, 수량은 `INTEGER`, 할인율은 소수 타입, 시각은 `TIMESTAMPTZ`로 저장한다. `?`는 nullable이다. 내부 전용 컬럼은 추가할 수 있지만 아래 이름과 의미를 유지한다.

| 테이블 | 최소 컬럼 |
| --- | --- |
| `products` | `id`, `name`, `price`, `created_at`, `updated_at` |
| `product_stock` | `product_id`, `quantity`, `updated_at` |
| `orders` | `id`, `customer_id`, `checkout_key`, `request_id`, `status`, `subtotal`, `discount_amount`, `total_amount`, `customer_coupon_id?`, `created_at`, `updated_at` |
| `order_items` | `id`, `order_id`, `product_id`, `quantity`, `unit_price`, `line_amount` |
| `payments` | `id`, `order_id`, `request_key`, `method`, `status`, `amount`, `provider_reference?`, `created_at`, `updated_at` |
| `refunds` | `id`, `order_id`, `payment_id`, `request_key`, `status`, `amount`, `failure_code?`, `created_at`, `updated_at` |
| `coupons` | `id`, `discount_type`, `min_order_amount`, `fixed_discount_amount?`, `discount_rate?`, `max_discount_amount?`, `valid_from`, `valid_until` |
| `customer_coupons` | `id`, `customer_id`, `coupon_id`, `status`, `issued_at`, `updated_at` |
| `coupon_usages` | `id`, `customer_coupon_id`, `order_id`, `status`, `discount_amount`, `used_at`, `released_at?` |
| `inventory_movements` | `id`, `product_id`, `order_id?`, `request_id?`, `checkout_key?`, `movement_type`, `quantity_delta`, `quantity_after`, `occurred_at` |

추가 타입은 `request_id: string`, `quantity_delta`·`quantity_after: integer`, `name: string`이다. 테이블의 `id`는 기본키이며 `product_stock`은 `product_id`가 기본키다. `_at`, `valid_from`, `valid_until`은 시각 컬럼이다.

### 관계와 의미

- `order_items.order_id`, `payments.order_id`, `refunds.order_id`, `coupon_usages.order_id`는 `orders.id`에 연결한다. `inventory_movements.order_id`도 값이 있으면 같은 기본키를 참조한다.
- 재고와 주문 상품의 `product_id`는 `products.id`에 연결한다. `refunds.payment_id`는 `payments.id`에 연결한다.
- `customer_coupons.coupon_id`는 `coupons.id`, 주문·사용 기록의 `customer_coupon_id`는 `customer_coupons.id`에 연결한다.
- `coupon_usages.status`는 `ACTIVE`, `RELEASED`다. 취소 복원 시 사용 이력을 삭제하지 않고 `RELEASED`와 `released_at`을 기록한다.
- `inventory_movements.movement_type`은 `INITIAL`, `RECEIPT`, `RESERVE`, `RELEASE`, `ADJUST`다. `quantity_delta`는 예약 시 음수, 입고·반환 시 양수이고 `quantity_after`는 변경 직후의 실제 수량이다.
- 초기 재고도 `INITIAL` 이력으로 남긴다. 주문·예약·예약 이력은 같은 트랜잭션에서 저장하고, 해당 주문을 만들지 못하면 예약도 롤백한다.
- 재고 초과 판매는 현재 재고뿐 아니라 초기 수량·변경 이력·성공 주문 수량을 함께 조회해 판단한다.

VOC-04의 취약 경로에서는 체크아웃 키의 중복을 허용하고, VOC-07에서는 음수 재고를 막는 제약을 적용하지 않아 설계한 결함을 재현한다. 이 두 조건은 시연용 결함이며 [정상 정책](business-policy.md)의 기준과 구분한다. 다른 외래키와 업무 데이터의 연결은 유지한다.

조사 계정에는 이 표의 테이블에 필요한 SELECT 권한을 제공한다. 마이그레이션·시드 실행 계정과 분리한다. Agent는 정해진 조회 메서드에서 조건을 바인딩하고 결과 건수를 제한한다.

## 4. JSON Lines 로그 계약

한 줄에 JSON 객체 하나를 쓴다. 에이전트에서 읽는 위치는 `/evidence/logs/commerce/{buildId}/`이며 실제 호스트 경로는 볼륨 설정으로 연결한다.

| 필드 | 타입·필수 여부 | 의미 |
| --- | --- | --- |
| `schemaVersion` | string, 필수 | `"1.0"` |
| `timestamp` | timestamp, 필수 | UTC 발생 시각 |
| `level` | string, 필수 | `INFO`, `WARN`, `ERROR` |
| `service` | string, 필수 | `commerce-app` |
| `buildId` | string, 필수 | 실행 소스 스냅샷 식별자 |
| `event` | string, 필수 | 아래 이벤트명 |
| `requestId` | string, 필수 | 해당 업무 요청의 추적 ID |
| `checkoutKey`, `orderId`, `paymentId`, `productId`, `customerCouponId` | string 또는 null | 이벤트에 해당하는 식별자. 관련 없는 필드는 null |
| `details` | object, 필수 | 이벤트별 관측값. 비밀번호·토큰·결제 수단의 실제 비밀 정보는 기록하지 않음 |

| 이벤트 | `details`의 최소 내용 |
| --- | --- |
| `INVENTORY_READ` | `requestedQuantity`, `observedQuantity` |
| `INVENTORY_RESERVED` | `quantityDelta`, `quantityAfter` |
| `ORDER_CREATED` | `status`, `totalAmount` |
| `PAYMENT_APPROVED` | `method`, `amount`, `providerReference` |
| `COUPON_REJECTED` | `reasonCode`, `subtotal`, `minOrderAmount` |
| `DISCOUNT_CALCULATED` | `discountType`, `subtotal`, `discountRate` (정액이면 null), `discountAmount` |
| `ORDER_CANCELLED` | `previousStatus`, `status` |
| `REFUND_FAILED` | `refundRequestKey`, `failureCode`, `retryable` |
| `REFUND_COMPLETED` | `refundRequestKey`, `amount`, `providerReference` |
| `COUPON_RELEASED` | `customerCouponId`, `orderId` |

DB 쓰기의 완료를 나타내는 이벤트는 커밋 후 기록한다. `INVENTORY_READ`는 읽은 시점의 값이며 주문 성공을 뜻하지 않는다. 모의 결제·환불 결과 이벤트는 제공자의 실제 응답을 기록한다. 한재홍은 성공 주문·예약 여부를 DB와 대조한다.

현재 구현은 커밋된 `commerce.event_outbox`를 기본 100ms마다 최대 100건 JSONL로 내보낸다. 조회자는 짧은 출력 지연을 고려한다.
추가 필드 `eventId: string`은 기록 식별자다. 재시작 직전 출력된 행이 다시 출력될 수 있으므로 같은 eventId를 별도의 업무 처리로 세지 않는다.
INVENTORY_READ는 즉시 기록하며 트랜잭션 성공 이벤트와 구분한다. 회전·자동 삭제는 아직 수행하지 않는다.

```json
{
  "schemaVersion": "1.0",
  "timestamp": "2026-09-21T01:00:00Z",
  "level": "INFO",
  "service": "commerce-app",
  "buildId": "build-demo-01",
  "event": "INVENTORY_READ",
  "requestId": "request-demo-01",
  "checkoutKey": "checkout-demo-01",
  "orderId": null,
  "paymentId": null,
  "productId": "product-demo-01",
  "customerCouponId": null,
  "details": { "requestedQuantity": 1, "observedQuantity": 1 }
}
```

위 로그는 형식 예제이며 실제 장애 실행 결과가 아니다.

## 5. 실행 소스 계약

각 빌드는 고유한 `buildId`를 사용하고 로그와 같은 값을 기록한다. Agent가 읽는 `/evidence/source/{buildId}/` 아래에 저장소 상대 경로를 유지한 소스와 `manifest.json`을 둔다. 시연 빌드는 커밋된 소스로 만들고 manifest에 `buildId`, 실제 `commitSha`, `createdAt`, `policyVersion: "demo-v1"`을 기록한다. 같은 `buildId`의 내용을 다른 코드로 교체하지 않는다.

허용할 소스 범위:

- `commerce-app/src/main/java/`
- `commerce-core/src/main/java/`
- `commerce-infra/src/main/java/`
- `commerce-infra/src/main/resources/db/migration/`

정상 정책은 별도 `business-policy.md`로 제공한다. Agent 소스 조회는 해당 스냅샷의 경로·1부터 시작하는 줄 번호·내용을 반환한다. `fixtures/`, `scenario-runner/`, 테스트 소스, 평가 정답과 기타 문서는 검색 대상에 포함하지 않는다. 테스트 전용 동기화 구현도 테스트 영역에 둔다.

## 6. 최초 전달 자료와 호환성 검증

이상효는 DDL, 초기 상품·고객 식별자, 예제 HTTP 요청·응답, 예제 로그 한 파일, 소스 스냅샷·manifest 생성 방법, 7개 재현 자료를 커밋으로 전달한다. 비밀 접속 정보와 실행 로그·소스 스냅샷 자체는 로컬 환경·런타임 볼륨에서 제공한다.

김아름은 DTO와 HTTP 상태가 계약에 맞는지 확인하고, 한재홍은 최소 컬럼을 조회하고 로그와 코드를 읽는 통합 검증을 작성한다. 필드나 상태를 바꿀 때에는 이 문서와 사용하는 두 구현을 함께 맞춘다.
