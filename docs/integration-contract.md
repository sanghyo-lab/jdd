# VOC·Agent 연동 인터페이스 v1

**김아름**이 VOC·프론트, **한재홍**이 Agent를 구현할 때 사용하는 공통 계약이다. **이상효**가 제공할 API·DB·로그·소스는 [커머스 인터페이스](commerce-interface.md)에 정의한다. 실제 제공 범위와 남은 연동은 각 앱 README와 담당자 상태에서 확인하며, 진단 API는 [로컬 개발 안내](local-development.md)에 있다. 담당별 범위는 [구현 문서 모음](roles/README.md)에 있다.

## 0. 공통 표현

- VOC 기본 주소는 `http://localhost:8082`, Agent 기본 주소는 `http://localhost:8081`이다. 실제 접속 주소·인증 값은 환경 변수로 전달한다.
- 요청·응답은 JSON이다. ID는 의미를 해석하지 않는 문자열, 시간은 UTC ISO 8601, 버전은 양의 정수다. 계약·리포트의 `schemaVersion`은 문자열 `"1.0"`이다.
- 필수 필드는 아래 DTO에 따른다. 요청의 선택 필드는 생략 또는 null을 허용하며, 서버는 같은 의미로 정규화한다. 응답의 nullable 필드는 null, 빈 배열은 `[]`로 반환한다.
- 목록은 `{ "items": [...] }`, 오류는 `{ "code": "...", "message": "...", "retryable": false }` 형식이다. 문서의 예제 ID·시각·파일·관측값은 개발용 가상 데이터이며 실제 실행 결과가 아니다.

## 1. 책임과 식별자

| 데이터 | 원본 관리 | 규약 |
| --- | --- | --- |
| 티켓 | VOC / 김아름 | `ticketId`, `version`, 제목, 문의, 조사 대상, 담당자, 업무 상태 |
| 분석 요청 기록 | VOC / 김아름 | `analysisRequestId`, `ticketId`, `ticketVersion`, `requestKey`, 입력 스냅샷, 전달 상태, 연결된 `investigationId` |
| 조사 실행·리포트 | Agent / 한재홍 | `investigationId`, 티켓·요청 식별자, 입력 스냅샷, 조사 상태, 진행 내역, 근거·보고서 |
| 커머스 데이터·로그·실행 소스 | 커머스 / 이상효 | [커머스 인터페이스](commerce-interface.md)의 필드·경로·버전 |

티켓 하나에 여러 분석 요청을 연결할 수 있고 각 입력·결과를 보존한다. 티켓 버전은 생성 시 1이며 PATCH마다 1 증가한다. 담당자 ID는 앱 내부 값으로 `sanghyo` = 이상효, `areum` = 김아름, `jaehong` = 한재홍을 사용한다. 이 값은 GitHub 계정이 아니다.

## 2. 프론트 → VOC API

| 메서드·경로 | 요청·결과 |
| --- | --- |
| `GET /api/assignees` | `200`, `items: {id: string, displayName: string}[]` |
| `POST /api/tickets` | `CreateTicketRequest`. `201`과 `Ticket` 반환 |
| `GET /api/tickets` | 선택적 `status`, `assigneeId`, `limit` (기본 20, 1~100), `offset` (기본 0, 0 이상). `200`, `items: Ticket[]`, `createdAt`·`ticketId` 내림차순 |
| `GET /api/tickets/{ticketId}` | `200`, `{ticket: Ticket, analyses: AnalysisSummary[]}`. 분석은 생성 시각·ID 내림차순 |
| `PATCH /api/tickets/{ticketId}` | `expectedVersion`과 변경 필드. `200`, 수정된 `Ticket` |
| `POST /api/tickets/{ticketId}/analyses` | `requestKey`, `ticketVersion`, 선택적 `previousInvestigationId`. `202`와 `AnalysisView` 반환 |
| `GET /api/tickets/{ticketId}/analyses/{analysisRequestId}` | `200`, `AnalysisView` |
| `GET /api/tickets/{ticketId}/analyses/{analysisRequestId}/evidence/{evidenceId}` | `200`, 해당 티켓·분석의 `EvidenceDetail` |

### 티켓 DTO

| DTO | 필드 |
| --- | --- |
| `CreateTicketRequest` | 필수 `title: string`, `message: string`. 선택적 `context: InvestigationContext`, `assigneeId: string 또는 null` |
| `Ticket` | `ticketId: string`, `version: integer`, `title`, `message`, `context: InvestigationContext`, `assigneeId: string 또는 null`, `status: TicketStatus`, `createdAt`, `updatedAt` |
| PATCH 입력 | 필수 `expectedVersion: integer`. 선택적 `title`, `message`, `context`, `assigneeId`, `status`. 하나 이상의 변경 필드 필요 |

제목은 1~200자, 문의는 1~10,000자로 제한하고 공백만 있는 입력은 거절한다. `context` 생략 시 `{}`로 저장한다. PATCH에서 생략한 필드는 유지하고, `assigneeId: null`은 배정을 해제한다. context를 보내면 해당 객체 전체를 교체하고 `{}`는 비운다. title·message·status·context 자체의 null은 PATCH에서 거절한다.

`expectedVersion`이 현재 버전과 다르면 `409 TICKET_VERSION_CONFLICT`를 반환한다. 티켓 상태는 `OPEN`, `IN_PROGRESS`, `RESOLVED`이며 담당자가 변경한다. 재오픈을 위해 RESOLVED에서 OPEN 또는 IN_PROGRESS로 변경할 수 있다. Agent가 티켓 상태를 자동 변경하지 않는다.

### 분석 요청 DTO와 중복 처리

`requestKey`는 호출 측이 새 분석을 의도할 때 생성하고 통신 재시도에는 같은 값을 보낸다. 아래 요청의 `ticketVersion`은 화면에서 확인한 티켓 버전이다.

```json
{
  "requestKey": "analysis-key-demo-01",
  "ticketVersion": 1,
  "previousInvestigationId": null
}
```

VOC는 `(ticketId, requestKey)`의 기존 기록을 먼저 확인한다. 기존 기록의 ticketVersion·previousInvestigationId와 같으면 저장된 요청을 반환한다. 다르면 `409 REQUEST_KEY_CONFLICT`다. 기존 기록이 없을 때 ticketVersion이 현재 티켓 버전과 다르면 `409 TICKET_VERSION_CONFLICT`다. 일치하면 문의·context·버전·이전 조사 ID를 스냅샷으로 저장한다. 이후 티켓이 수정돼도 같은 요청의 재전송에는 기존 스냅샷을 사용한다.

VOC는 요청을 먼저 저장하고 서버 작업 실행기로 Agent에 전달한다. 전달 상태는 `PENDING`, `SUBMITTED`, `FAILED`이며 조사 상태와 분리한다. Agent 응답을 받기 전에는 `investigationId`와 조사 상태가 `null`이다. Agent에 접수됐는지 불확실한 통신 실패에는 같은 키로 재전송해 기존 조사와 연결한다. 재전송 횟수·간격·최대 대기 시간을 설정하고, 한도를 넘기면 오류와 수동 재시도 경로를 표시한다.

수동 전달 재시도도 같은 분석 POST와 같은 키·버전·이전 조사 ID를 사용한다. 기존 전달 상태가 FAILED이고 submissionError.retryable=true이면 동일한 분석 요청의 상태를 PENDING으로 바꾸고 오류를 비운 뒤 재전송한다. 그 외에는 저장된 요청을 반환한다. 이 처리는 원자적으로 수행해 중복 클릭으로 전달 작업이 여러 개 생성되지 않게 한다. SUBMITTED 이후 Agent 조사 자체의 FAILED를 재실행할 때에는 새 키로 별도 분석을 만든다.

| DTO | 필드 |
| --- | --- |
| `AnalysisSummary` | `analysisRequestId`, `ticketVersion: integer`, `submissionStatus`, `investigationId: string 또는 null`, `investigationStatus: InvestigationStatus 또는 null`, `createdAt`, `updatedAt` |
| `AnalysisView` | `analysisRequestId`, `ticketId`, `ticketVersion: integer`, `submissionStatus`, `investigationId: string 또는 null`, `input: InvestigationInput`, `investigation: Investigation 또는 null`, `submissionError: ApiError 또는 null`, `syncError: ApiError 또는 null`, `lastSyncedAt: timestamp 또는 null`, `createdAt`, `updatedAt` |

`input`은 저장된 분석 입력이다. 제출 직후에는 PENDING·조사 ID null·investigation null이고, 전달 실패는 `submissionError`, 결과 조회 실패는 `syncError`에 기록한다. 한 번 받은 결과는 마지막 확인 시각과 함께 보존한다. 화면은 현재 티켓 버전과 분석 당시 버전을 구분해 표시한다.

## 3. VOC → Agent API

| 메서드·경로 | 요청·결과 |
| --- | --- |
| `POST /api/investigations` | `InvestigationInput`을 저장하고 `202`, `{investigationId, ticketId, status}` 반환 |
| `GET /api/investigations/{investigationId}` | `200`, `Investigation` 반환 |
| `GET /api/investigations/{investigationId}/evidence/{evidenceId}` | `200`, `EvidenceDetail` 반환 |

### 조사 입력

`InvestigationInput`의 필수 필드는 `schemaVersion`, `ticketId`, `ticketVersion`, `requestKey`, `message`다. `context`는 생략 시 `{}`, `previousInvestigationId`는 생략 시 null이다. message 길이 제한은 티켓과 같다.

```json
{
  "schemaVersion": "1.0",
  "ticketId": "ticket-demo-01",
  "ticketVersion": 1,
  "requestKey": "analysis-key-demo-01",
  "message": "재고가 1개였는데 주문 2건이 성공했어요.",
  "context": {
    "productId": "product-demo-01",
    "occurredAt": "2026-09-21T01:00:00Z"
  },
  "previousInvestigationId": null
}
```

`InvestigationContext`는 `customerId`, `orderId`, `productId`, `requestId`, `checkoutKey`, `occurredAt`을 선택적으로 받는다. ID·키는 문자열, occurredAt은 timestamp다. context 내 생략/null 필드는 없는 조건으로 취급한다. VOC 생성/PATCH와 Agent 접수는 명시된 ID·키가 빈 문자열이거나 공백뿐이면 `400 INVALID_REQUEST`로 거절하며, 유효한 문자열의 문자를 임의로 trim하거나 바꾸지 않는다. 화면에서 입력하지 않은 선택 값은 생략/null로 보낸다. 기존에 저장한 공백 context를 전달하다 거절되면 재시도 불가능한 전달 오류로 표시하고 티켓 수정과 새 키의 분석을 안내한다. 기존 스냅샷이나 키 의미를 자동으로 바꾸지 않는다. 필요한 식별자나 시각이 부족하면 접수 후 `NEEDS_INPUT`과 필요한 항목을 반환한다. 호출 측은 시나리오 정답이나 결함 ID를 조사 입력에 넣지 않는다.

Agent는 `(ticketId, requestKey)`를 유일하게 저장한다. 같은 키·입력으로 재호출하면 기존 조사 ID와 현재 상태를 반환하고, 입력이 다르면 `409 REQUEST_KEY_CONFLICT`를 반환한다. 비교에는 정규화한 ticketVersion·message·context·previousInvestigationId·schemaVersion을 사용한다. JSON 키 순서는 비교에 영향을 주지 않는다. 중복 요청에서도 `202` 응답 형식은 동일하다.

QUEUED 수용량은 기본 20건(Agent 설정 1~100)이며 DB 잠금·개수 검사·삽입을 한 트랜잭션으로 처리한다. 가득 찬 경우 새 요청은 `429 INVESTIGATION_QUEUE_FULL`, `retryable=true`, `Retry-After: 5`이고 조사·모델 예약을 만들지 않는다. 이미 받은 키의 기존 결과/409가 수용량 거절보다 우선한다. RUNNING은 별도 실행 동시성으로 제한한다. 접수 시 대기 만료를 영속 저장하며 기본 10분(1초~1시간), 같은 키·재시작·설정 변경으로 연장하지 않는다. 만료된 QUEUED는 모델 실행 없이 FAILED/INVESTIGATION_TIMEOUT이 되고 기존 ID·입력·근거를 유지한다.

조사 상태는 `QUEUED`, `RUNNING`, `COMPLETED`, `NEEDS_INPUT`, `FAILED`다. 추가 정보로 재조사할 때에는 티켓 내용을 보완하고 새 버전·키와 같은 티켓의 previousInvestigationId로 새 조사를 만든다. 존재하지 않거나 다른 티켓의 이전 조사 ID는 `404 NOT_FOUND`로 처리한다. 기존 결과를 보존한다.

### 조사 조회 DTO

| `Investigation` 필드 | 타입·규칙 |
| --- | --- |
| `schemaVersion` | string, `"1.0"` |
| `investigationId`, `ticketId` | string |
| `ticketVersion` | integer, 분석 당시 버전 |
| `status` | `QUEUED`, `RUNNING`, `COMPLETED`, `NEEDS_INPUT`, `FAILED` |
| `createdAt`, `updatedAt` | timestamp |
| `progress` | `ToolExecution[]`, 실행 시작 시각·ID 오름차순 |
| `evidence` | `EvidenceSummary[]`, 해당 조사에서 저장한 근거 |
| `report` | `AnalysisReport` 또는 null |
| `error` | `ApiError` 또는 null |

접수 직후의 조회 예시:

```json
{
  "schemaVersion": "1.0",
  "investigationId": "investigation-demo-01",
  "ticketId": "ticket-demo-01",
  "ticketVersion": 1,
  "status": "QUEUED",
  "createdAt": "2026-09-21T01:01:00Z",
  "updatedAt": "2026-09-21T01:01:00Z",
  "progress": [],
  "evidence": [],
  "report": null,
  "error": null
}
```

`ToolExecution`은 `toolExecutionId`, `toolName`, `status` (`RUNNING`, `SUCCEEDED`, `FAILED`), `startedAt`, nullable `finishedAt`, `summary: string`, `evidenceIds: string[]`, nullable `error: ApiError`를 가진다. summary는 실제 도구 작업·반환 결과의 요약이다.

QUEUED·RUNNING은 report=null, error=null이다. COMPLETED는 유효한 report와 빈 missingInformation, NEEDS_INPUT은 report와 하나 이상의 missingInformation을 반환한다. FAILED는 report=null과 error를 반환하고 이미 모은 progress·evidence는 보존한다. 정상 동작을 확인한 조사도 COMPLETED가 될 수 있으며 이때 원인 후보 배열은 비어 있을 수 있다.

## 4. 리포트 계약

`AnalysisReport`의 아래 필드는 모두 필수다. 빈 결과는 빈 배열로 반환한다. 사실의 evidenceIds에는 하나 이상의 근거가 필요하다. 원인 후보에 근거가 없으면 UNVERIFIED로 표시하고 limitations에 미확인 내용을 적는다. SUPPORTED·PARTIAL 후보는 실제 근거를 참조한다.

| 필드 | 타입·내용 |
| --- | --- |
| `schemaVersion` | string, `"1.0"` |
| `summary` | string, 문의에 대한 조사 결론 요약 |
| `facts` | `{id, description, evidenceIds: string[]}[]` |
| `hypotheses` | `{id, description, supportLevel, evidenceIds: string[], limitations: string[]}[]` |
| `actions` | `{id, description, evidenceIds: string[], requiresHumanAction: true}[]`, 해당 건의 조치 |
| `prevention` | `{id, description, targetPaths: string[], evidenceIds: string[], validationSteps: string[]}[]`, 재발 방지 |
| `missingInformation` | `{field: string, reason: string}[]`, 필요한 추가 입력 |

각 항목의 id·description은 문자열이다. supportLevel은 `SUPPORTED`, `PARTIAL`, `UNVERIFIED`이며 증거의 충족 수준을 뜻한다. 확률이나 측정된 정확도가 아니다. missingInformation의 field는 `message` 또는 `context`의 허용 필드 경로(예: `context.orderId`)다.

아래 예제의 `evidence-demo-data`, `evidence-demo-log`, `evidence-demo-code`는 각각 DATA·LOG·CODE 예제 근거를 뜻한다. 이를 테스트 응답으로 사용할 때에는 같은 조사 evidence 배열과 근거 API에 해당 세 항목을 함께 준비한다. 파일 경로는 예시이며 실제 코드가 아니다.

```json
{
  "schemaVersion": "1.0",
  "summary": "두 요청이 재고 1개를 각각 확인한 뒤 차감해 초과 주문이 발생한 것으로 판단됩니다.",
  "facts": [{
    "id": "fact-1",
    "description": "초기 재고 1개에 대해 1개씩 예약한 두 건의 이력이 확인됩니다.",
    "evidenceIds": ["evidence-demo-data"]
  }],
  "hypotheses": [{
    "id": "cause-1",
    "description": "재고 확인과 차감이 분리돼 있으며 차감 시 남은 수량을 다시 검사하지 않습니다.",
    "supportLevel": "SUPPORTED",
    "evidenceIds": ["evidence-demo-data", "evidence-demo-log", "evidence-demo-code"],
    "limitations": []
  }],
  "actions": [{
    "id": "action-1",
    "description": "실제 가용 재고와 초과 주문을 대조하고 운영 정책에 따라 주문 조정 대상을 확인합니다.",
    "evidenceIds": ["evidence-demo-data"],
    "requiresHumanAction": true
  }],
  "prevention": [{
    "id": "change-1",
    "description": "재고 조건을 포함한 원자적 차감과 영향받은 행 수 확인을 적용합니다.",
    "targetPaths": ["commerce-infra/src/main/java/com/jdd/commerce/inventory/StockRepository.java"],
    "evidenceIds": ["evidence-demo-code"],
    "validationSteps": ["재고 1개에 요청 2건을 동시에 보내 성공 1건, 재고 부족 1건, 잔여 0개인지 확인합니다."]
  }],
  "missingInformation": []
}
```

한재홍은 보고서 저장 전 모든 근거 참조가 해당 조사에 존재하는지 검증한다. 김아름은 구조를 보존해 각 항목을 표시하고 evidenceIds로 근거 패널을 연결한다. 사람이 실제 수정·조치를 수행한 뒤 티켓을 해결 처리한다.

## 5. 근거 계약

`EvidenceSummary`는 `evidenceId: string`, `type`, `summary: string`, `observedAt: timestamp`, `source: object`를 가진다. `EvidenceDetail`은 여기에 `content`와 `truncated: boolean`을 추가한다. 근거 API는 저장된 관측 내용을 반환하며 나중에 달라진 DB·파일 내용으로 덮어쓰지 않는다.

| type | source 필드 | content 형식 |
| --- | --- | --- |
| `DATA` | `schema: string`, `table: string`, `recordIds: string[]`, `queryDescription: string` | `{columns: string[], rows: object[]}` |
| `LOG` | `buildId: string`, `path: string`, `startLine: integer`, `endLine: integer` | 해당 범위의 JSON 로그 객체 배열 |
| `CODE` | `buildId: string`, `path: string`, `startLine: integer`, `endLine: integer` | 소스 문자열 |
| `POLICY` | `version: string`, `path: string`, `section: string` | 정책 문자열 |

줄 번호는 1부터 시작하고 끝 줄을 포함한다. CODE 경로는 소스 스냅샷 아래의 저장소 상대 경로, LOG 경로는 해당 빌드 로그 폴더 아래의 상대 경로다. 연속되지 않은 로그 구간은 서로 다른 근거로 저장한다. 결과가 제한으로 잘렸으면 truncated=true로 표시하고, 그 범위를 넘어서는 결론에는 추가 조회가 필요하다.

```json
{
  "evidenceId": "evidence-demo-data",
  "type": "DATA",
  "summary": "초기 재고 1개와 두 건의 예약 이력",
  "observedAt": "2026-09-21T01:01:10Z",
  "source": {
    "schema": "commerce",
    "table": "inventory_movements",
    "recordIds": ["movement-demo-01", "movement-demo-02", "movement-demo-03"],
    "queryDescription": "product-demo-01의 초기 재고와 예약 이력"
  },
  "content": {
    "columns": ["id", "product_id", "movement_type", "quantity_delta", "quantity_after"],
    "rows": [
      {"id": "movement-demo-01", "product_id": "product-demo-01", "movement_type": "INITIAL", "quantity_delta": 1, "quantity_after": 1},
      {"id": "movement-demo-02", "product_id": "product-demo-01", "movement_type": "RESERVE", "quantity_delta": -1, "quantity_after": 0},
      {"id": "movement-demo-03", "product_id": "product-demo-01", "movement_type": "RESERVE", "quantity_delta": -1, "quantity_after": -1}
    ]
  },
  "truncated": false
}
```

Agent는 조사에 속한 근거인지, VOC는 티켓에 연결된 조사인지 확인한다. 해당 연결이 없으면 `404 NOT_FOUND`를 반환한다. 브라우저는 서버 파일을 직접 열지 않고 VOC의 근거 API를 사용한다.

## 6. 오류·재시도·복구

`ApiError`의 필드는 `code: string`, `message: string`, `retryable: boolean`이다. message에 인증 값과 내부 비밀 정보를 포함하지 않는다.

| 상황 | HTTP·상태 | code |
| --- | --- | --- |
| 입력 형식·지원하지 않는 schemaVersion | `400` | `INVALID_REQUEST` |
| 티켓·조사·근거가 없거나 해당 대상에 연결되지 않음 | `404` | `NOT_FOUND` |
| 같은 요청 키의 입력 충돌 | `409` | `REQUEST_KEY_CONFLICT` |
| 새 조사 접수 대기열 포화 | `429`, `Retry-After: 5` | `INVESTIGATION_QUEUE_FULL`, retryable=true |
| 티켓 버전 불일치 | `409` | `TICKET_VERSION_CONFLICT` |
| 인증 없음·잘못된 인증 / 접근 권한 없음 | `401` / `403` | `UNAUTHORIZED` / `FORBIDDEN` |
| Agent에 일시적으로 연결할 수 없음 | VOC 전달·조회 오류 | `AGENT_UNAVAILABLE`, retryable=true |
| 예상하지 못한 API 서버 오류 | `500` | `INTERNAL_ERROR` |
| 조사 시간 초과 | 조사 GET은 `200`, status=FAILED | `INVESTIGATION_TIMEOUT` |
| 필요한 도구 실행 실패 | 조사 GET은 `200`, status=FAILED | `TOOL_EXECUTION_FAILED` |
| 리포트 형식·근거 참조 검증 실패 | 조사 GET은 `200`, status=FAILED | `REPORT_VALIDATION_FAILED` |
| Agent 재시작으로 중단된 조사 | 조사 GET은 `200`, status=FAILED | `INTERRUPTED` |
| 모델 실행 비허용·필수 설정 누락·인증/모델 접근 거절 | 조사 GET은 `200`, status=FAILED | `LLM_CONFIGURATION_ERROR`, retryable=false |
| 한도 내 처리 후 모델 일시 장애·연결/개별 응답 시간 초과 | 조사 GET은 `200`, status=FAILED | `LLM_UNAVAILABLE`, retryable=true |
| 비용 예약 거절·조사 토큰/모델/도구 호출 한도 소진 | 조사 GET은 `200`, status=FAILED | `INVESTIGATION_BUDGET_EXCEEDED`, retryable=false |

모델 오류는 [DISC-20260921-agent-001 P1](discussions/DISC-20260921-agent-001-llm-errors.md)의 합의를 적용한다.
retryable=true는 자동 유료 재조사 허가가 아니다. 실패한 조사에 같은 키를 보내면 기존 실패를 반환하며,
허용 범위를 확인한 사용자의 새 키 재조사도 Agent의 기존 누적 예산 검사를 거친다.
설정·예산·서버 오류를 NEEDS_INPUT으로 바꾸지 않는다. VOC는 미지의 code도 일반 오류로 표시한다.

VOC가 Agent 상태를 조회하지 못하면 마지막 확인 상태·시각과 syncError를 표시한다. 통신 실패만으로 Agent 실행 상태를 FAILED로 바꾸지 않는다. 분석 실패를 새 실행으로 재시도할 때에는 새 요청 키를 사용한다. 접수 여부가 불확실한 전달 실패는 같은 키로 재전송해 기존 조사를 먼저 확인한다.

초기 연결 설정은 접속 제한 3초·요청 제한 10초다. 일반 연결 장애의 접수 전달은 최대 3회(재시도 간격 1초·2초)다. 대기열 429는 [DISC-agent-005 P1](discussions/DISC-20260921-agent-005-queue-limits.md)에 따라 submissionError로 구분하며, 최초 전달 외 최대 3회 자동 재전송한다. 같은 저장 입력·키와 영속 횟수를 유지하고 Retry-After 이상인 5/10/20초 + jitter를 적용한다. 소진 후에는 수동 동일 키 재전송을 안내하며 새 키를 자동 생성하지 않는다. 영구적인 다른 4xx는 자동 재전송하지 않는다.

조사 상태 polling은 기본 대기 10분+실행 3분을 고려한 관측 창 14분·최대 5초 간격으로 시작한다. 설정은 실제 대기/실행 한도에 맞춘다. 조회 오류나 관측 종료는 마지막 상태·시각과 조회 오류를 표시하며 조사를 FAILED로 바꾸거나 다시 실행하지 않는다. 관측 창이 끝나면 수동 새로고침으로 전환한다. 이 계약은 VOC 소비자의 실제 구현/화면 검증을 대신하지 않는다.

VOC 서버가 요청 전달과 상태 조회를 실행하므로 브라우저를 닫아도 계속 진행한다. 재시작 시 PENDING 전달과 미종료 조사 연결을 DB에서 복원한다. 초기 Agent는 단일 실행 인스턴스를 전제로 QUEUED 작업을 재개하고, 재시작 시 남은 RUNNING 작업은 FAILED·INTERRUPTED로 기록한다. 중단된 조사의 근거는 보존한다.

## 7. 첫 통합에서 확인할 사례

1. 티켓 생성 → 분석 요청 → Agent의 실제 조회 → 보고서·근거 표시
2. 접수 응답 유실 후 같은 키로 재시도해 같은 조사에 연결
3. Agent 조회가 일시 실패해도 실제 조사 상태를 보존
4. 추가 정보로 새 조사를 시작하고 이전 분석 이력을 유지
5. AI 조사 완료 후 티켓 상태를 유지하고, 담당자의 조치 확인으로 해결 처리
6. 다른 티켓의 조사·근거 식별자를 보내도 연결되지 않은 결과를 반환하지 않음
7. 티켓을 수정한 뒤 옛 버전으로 새 분석을 요청하면 버전 충돌을 반환하고, 기존 키 재전송은 기존 입력·결과에 연결
8. 리포트의 모든 evidenceIds가 같은 조사의 실제 근거로 열림
