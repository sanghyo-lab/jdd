# VOC·Agent·커머스 연동 계약 초안

이 문서는 [3인 협업](collaboration.md)의 초기 구현 기준안이다. 현재 실행 중인 API는 없다. A와 C가 요청·응답 예제를 구현 전에 맞추고, B는 커머스 DDL·초기 데이터와 로그 예제를 제공한다.

## 1. 책임과 식별자

| 데이터 | 원본 관리 | 규약 |
| --- | --- | --- |
| 티켓 | VOC / C | `ticketId`, 제목, 문의, 조사 대상 정보, 선택적 담당자, `OPEN`·`IN_PROGRESS`·`RESOLVED` |
| 분석 요청 기록 | VOC / C | `analysisRequestId`, `ticketId`, `requestKey`, 입력 스냅샷, 전달 상태, 연결된 `investigationId` |
| 조사 실행 | Agent / A | `investigationId`, `ticketId`, `requestKey`, 조사 상태, 진행 내역, 근거·보고서 |
| 커머스 데이터·로그 | 커머스 / B | 주문·결제·쿠폰·재고 상태, `requestId`, `checkoutKey`, 업무 식별자 |
| 실행 버전 | 커머스 / B | 로그의 `buildId`와 같은 버전의 소스·스키마 스냅샷 |

JSON 식별자는 문자열, 시간은 UTC ISO 8601 문자열로 교환한다. 티켓 하나에 여러 분석 요청을 연결할 수 있고, 각 요청의 입력 스냅샷과 결과를 보존한다. 담당자 지정은 해커톤에서 미리 정한 개발자 목록을 사용한다.

## 2. 프론트 → VOC API

| 메서드·경로 | 요청·결과 |
| --- | --- |
| `POST /api/tickets` | `title`, `message`, 선택적 `context`로 생성. `201`과 티켓 반환 |
| `GET /api/tickets` | 티켓 목록, 상태·담당자별 필터 |
| `GET /api/tickets/{ticketId}` | 티켓, 분석 요청 이력, 최신 결과의 연결 정보 |
| `PATCH /api/tickets/{ticketId}` | 제목·문의·대상 정보·담당자·티켓 상태 수정. 기존 분석의 입력 스냅샷은 보존 |
| `POST /api/tickets/{ticketId}/analyses` | `requestKey`, 선택적 `previousInvestigationId`로 분석 요청. `202`와 `analysisRequestId` 반환 |
| `GET /api/tickets/{ticketId}/analyses/{analysisRequestId}` | 전달 상태, 조사 ID·상태, 수행 작업, 근거 목록, 보고서, 추가 정보 요청 |
| `GET /api/tickets/{ticketId}/analyses/{analysisRequestId}/evidence/{evidenceId}` | 해당 티켓·분석에 연결된 근거 내용 |

`POST /analyses`는 티켓의 현재 문의·대상 정보를 요청 기록에 복사해 저장한다. `requestKey`는 호출 측이 새 분석을 의도할 때 생성하고 통신 재시도에는 같은 값을 보낸다. 같은 티켓·키·입력은 같은 요청을 반환하며, 같은 키로 다른 입력을 보내면 `409`로 처리한다.

VOC는 요청을 먼저 저장하고 서버 작업 실행기로 Agent에 전달한다. 전달 상태는 `PENDING`, `SUBMITTED`, `FAILED`이며 조사 상태와 분리한다. Agent 응답을 받기 전에는 `investigationId`와 조사 상태가 `null`이다. Agent에 접수됐는지 불확실한 통신 실패에는 같은 키로 재전송해 기존 조사와 연결한다. 재전송 횟수·간격·최대 대기 시간을 설정하고, 한도를 넘기면 오류와 수동 재시도 경로를 표시한다.

## 3. VOC → Agent API

| 메서드·경로 | 요청·결과 |
| --- | --- |
| `POST /api/investigations` | 아래 입력을 저장하고 `202`와 `investigationId`, 현재 `status` 반환 |
| `GET /api/investigations/{investigationId}` | `ticketId`, 상태, 도구 실행 요약, 근거 목록, 보고서, 추가 정보 요청, 오류 |
| `GET /api/investigations/{investigationId}/evidence/{evidenceId}` | 해당 조사에서 수집한 근거 원문과 출처 |

식별자는 형식을 설명하기 위한 가상 예시다.

```json
{
  "ticketId": "ticket-example",
  "requestKey": "request-example",
  "message": "재고가 1개였는데 주문 2건이 성공했어요.",
  "context": {
    "productId": "product-example",
    "occurredAt": "2026-09-21T01:00:00Z"
  },
  "previousInvestigationId": null
}
```

`context`는 선택적이며 `customerId`, `orderId`, `productId`, `requestId`, `checkoutKey`, `occurredAt`을 선택적으로 받는다. 필요한 식별자나 시각이 부족하면 `NEEDS_INPUT`과 필요한 항목을 반환한다.

Agent는 `(ticketId, requestKey)`를 유일하게 저장한다. 같은 키·입력으로 재호출하면 기존 조사 ID와 현재 상태를 반환하고, 입력이 다르면 `409`를 반환한다. 이 규칙으로 접수 응답이 유실돼도 분석이 중복 실행되지 않도록 한다.

조사 상태는 `QUEUED`, `RUNNING`, `COMPLETED`, `NEEDS_INPUT`, `FAILED`다. 추가 정보로 재조사할 때에는 티켓 내용을 보완하고 새 키와 같은 티켓의 `previousInvestigationId`로 새 조사를 만든다. 기존 결과를 보존한다.

## 4. 결과·근거와 오류

보고서에는 `summary`, `facts`, `hypotheses`, `actions`, `prevention`, `missingInformation`을 둔다. 사실·원인 후보는 `evidenceIds`로 수집된 근거를 참조한다. 배열 항목의 세부 필드와 예제는 A·C가 첫 계약 작업에서 고정한다.

근거에는 `evidenceId`, `type` (`DATA`, `LOG`, `CODE`, `POLICY`), `observedAt`, 출처와 관측 내용을 둔다. 출처에는 유형에 맞는 레코드 식별자, 로그 위치, 코드의 `buildId`·파일·줄 번호, 정책 버전을 넣는다. A의 근거 API는 자신의 조사에 속한 근거인지 확인하고, C는 티켓에 연결된 조사만 조회한다.

오류 응답의 공통 필드는 `code`, `message`, `retryable`이다. 입력 오류는 `400`, 존재하지 않는 대상은 `404`, 같은 요청 키의 입력 충돌은 `409`로 구분한다. 인증·접근 권한 오류와 일시적인 연결 실패도 별도로 처리한다. VOC가 Agent 상태를 조회하지 못하면 마지막 확인 시각과 조회 오류를 표시하고 재시도한다. 통신 실패만으로 Agent 실행 상태를 `FAILED`로 바꾸지 않는다.

VOC의 서버 작업이 Agent 상태를 주기적으로 조회하므로 브라우저를 닫아도 요청 전달과 상태 갱신을 이어간다. 서버 재시작 시 DB의 미완료 전달·조사 연결을 다시 확인하는 동작을 검증한다. Agent의 실행 중 작업 복구 방식도 구현 시 명시하고, 복구하지 못한 작업은 오류 상태로 남긴다.

## 5. B가 A에게 제공할 자료

- 커머스 DDL, 조회 대상 필드와 초기화·시드 실행 방법
- 주문·결제·쿠폰·재고·취소·환불의 상태 값과 [정상 업무 정책](business-policy.md)
- 로그 공통 필드: `timestamp`, `event`, `buildId`, `requestId`와 이벤트에 해당하는 `orderId`, `paymentId`, `productId`, `checkoutKey`
- `buildId`별 읽을 수 있는 커머스 Java·SQL·스키마 소스 위치
- 평가용으로 구분된 7개 시나리오의 입력과 재현 방법

A는 이 계약의 조회 범위를 사용한다. 시나리오 실행 코드·평가 정답·해커톤 보고서는 조사 도구의 검색 대상에 포함하지 않는다.

## 6. 첫 통합에서 확인할 사례

1. 티켓 생성 → 분석 요청 → Agent의 실제 조회 → 보고서·근거 표시
2. 접수 응답 유실 후 같은 키로 재시도해 같은 조사에 연결
3. Agent 조회가 일시 실패해도 실제 조사 상태를 보존
4. 추가 정보로 새 조사를 시작하고 이전 분석 이력을 유지
5. AI 조사 완료 후 티켓 상태를 유지하고, 담당자의 조치 확인으로 해결 처리
6. 다른 티켓의 조사·근거 식별자를 보내도 연결되지 않은 결과를 반환하지 않음
