# DISC-20260921-agent-003 — 선택 식별자의 공백 입력 경계

| 항목 | 내용 |
| --- | --- |
| ID | DISC-20260921-agent-003 / AGENT-VOC-003 |
| 상태 | AGREED |
| 제안 버전 | P1 |
| 작성자 / 정리 담당 | 한재홍 / agent |
| 영향받는 역할 | voc·agent, commerce/lead 확인 |
| 필수 합의자 | 김아름(티켓·화면·전달), 한재홍(접수) |
| 확인·답변 대기 | 김아름(기존 잘못된 티켓의 분석 전달 오류 안내) |
| 생성 시각 / 최종 갱신 | 2026-09-21T19:00:00+09:00 / 2026-09-21T23:07:13+09:00 |
| 다음 행동 / 담당 | Agent HTTP/영속 전달·새 티켓 화면 직렬화 인수 완료. 기존 입력 오류 안내 후 재검증 |

## 결정할 질문

선택 식별자는 생략/null이면 정보 없음으로 두고, API에 문자열을 명시하면 공백만인 값은 거절하도록 VOC와 Agent를 맞추는가?

## 배경과 확인 근거

- 기준: [v1 계약](../integration-contract.md), `voc-core` TicketService와 `agent-core` InvestigationInput.Context. Agent는 기존부터 null을 허용하고 빈 문자열·공백을 거절한다. VOC는 명시된 공백 문자열을 저장한다.
- 실제 `07aeadd` 스택에서 POST /api/tickets에 title/message와 `context: {"orderId":" "}`를 보냈다. VOC는 201로 저장했고 해당 티켓의 message/context/version을 그대로 POST /api/investigations에 전달하면 400 INVALID_REQUEST, retryable=false였다.
- 합성 티켓 ID `e71e55a3-6b26-456b-bb91-eddc86ef5a3f`. 한재홍 PC의 `runtime/submission/agent-20260921/voc-blank-context.json`에 전달 입력·실제 응답을 보존했다. 로컬 HTTP 검증이며 모델 호출은 없다.
- 예상 영향: 화면의 빈 선택 입력을 그대로 보내면 티켓 저장 후 조사 전달이 실패할 수 있다. 현재 VOC 분석 전달·화면은 구현 중이며 실제 공개 화면에서 발생했다고 주장하지 않는다.

## P1 제안과 대안

- VOC 생성/PATCH의 선택 ID(customerId/orderId/productId/requestId/checkoutKey)에 명시한 빈 문자열·공백은 400 INVALID_REQUEST로 거절한다. 생략/null은 현재의 정보 없음 표현을 유지한다. 유효한 ID의 문자를 임의로 trim하거나 바꾸지 않는다.
- 화면에서 입력하지 않은 선택 항목은 생략/null로 직렬화한다. 이미 저장된 공백 context 티켓은 사용자가 수정하도록 명확히 알리고 전달 오류를 조사 실패와 구분한다. 자동 반복 전송이나 임의 데이터 변경으로 숨기지 않는다.
- Agent의 기존 입력 정규화·같은 키 비교는 유지한다. 기존 잘못된 입력을 바꾼 뒤 재요청하는 경우 스냅샷/키 충돌 규칙도 유지한다.
- 대안은 모든 경계에서 공백을 null로 정규화하는 것이다. 입력 허용 범위와 멱등 fingerprint 의미가 달라지므로 양쪽 계약/기존 스냅샷을 함께 검토해야 한다. 현행 Agent와 일치시키는 P1을 우선 제안한다.
- 김아름은 진행 중인 VOC·화면 경로에서 처리하고 한재홍은 실제 VOC → Agent 인수 검증을 맡는다. commerce API·정책 변경은 제안하지 않는다. 합의 대기 중 정책 snapshot 소비와 모의 OpenAI 검증을 계속한다.

## 해소 기준

- [x] 세 작업자가 영향 확인을 남기고 필수 합의자가 최신 제안을 수락했다.
- [ ] 생성/PATCH·화면 직렬화·기존 잘못된 티켓의 오류 처리 기준을 계약과 구현에 반영했다.
- [x] 공백/빈 문자열/생략/null/정상 식별자를 실제 VOC → Agent에서 검증하고 같은 키 재전송을 확인했다.
- [ ] 공유 커밋·직접 소비 결과와 README 상태를 맞췄다.

## 답변 기록

### 이상효 — commerce / lead

2026-09-21T19:08:10+09:00 / 이상효 / commerce·lead / P1

의견: 영향 확인·수락. commerce는 공백 식별자를 이미 거절하므로 API·업무 정책 변경은 필요 없다. 선택 입력의 생략/null은 정보 부족으로 전달하고 명시된 공백을 거절하는 P1에 동의한다. 김아름의 생성/PATCH·화면·기존 티켓 오류 처리와 한재홍의 실제 전달 검증을 기다리며 중복 구현하지 않는다. 최종 통합에서 같은 키 재전송과 수정 후 새 버전/키 흐름도 직접 확인한다. 이 답변은 소비자 검증 완료가 아니다.

### 이상효 — commerce / lead 추가 검증

2026-09-21T20:52:00+09:00 / 이상효 / commerce·lead / P1 — 독립 입력 경계 확인

- `8d80786`의 생성/PATCH 소스·계약·검사를 읽고 내 PC의 전용 PostgreSQL에서 HTTP 계약 7개를 실행해 모두 통과·실패/건너뜀 0을 확인했다. 명시 공백 다섯 ID·빈 값·유니코드 공백 거절 시 데이터/버전 불변과 생략/null/정상 문자 보존을 확인했다.
- `VOC_TEST_DB_URL` 검사 재사용 오류는 LEAD-014/`6b9ca6f`로 보완했다. 실패/수정 원문과 7개 실제 PostgreSQL XML은 [commerce 상태](../status/commerce.md)의 20:43:25 기록에 연결했다. 이는 VOC 경계의 직접 검사이며 영속 전달/화면·Agent 담당자 인수를 대신하지 않는다. 남은 대기자와 AGREED를 유지한다.

### 한재홍 — agent

2026-09-21T19:00:00+09:00 / 한재홍 / agent / P1

의견: 제안·수락. 실제 201 → 400 불일치를 확인했다. 기존 Agent의 null/생략 정규화와 공백 거절을 유지하며 VOC 수정 후 동일한 실제 HTTP 입력을 다시 대조한다. 타 담당자 코드를 중복 수정하지 않는다.

### 김아름 — voc

2026-09-21T19:18:47+09:00 / 김아름 / voc / P1

- 의견: 수락. 생략/null은 유지하고 명시적인 빈 문자열·공백만인 ID는 거절한다. 정상 문자를 trim하지 않으며 화면 미입력은 생략/null로 보낸다. 기존 잘못된 티켓은 전달 실패와 수정·새 키 안내로 처리하고 기존 스냅샷을 자동 변경하지 않는다.
- 현재 구현: VOC 생성/PATCH의 공백 거절과 계약을 반영했다. 실제 HTTP 검사에서 빈 customerId가 201로 저장되는 실패를 재현한 뒤 전체 VOC 테스트 7개를 통과했다. 다섯 ID의 빈 값·공백·탭/개행·유니코드 공백, 거절 시 데이터/버전 보존과 유효한 문자 보존을 확인했다.
- 통합: 리더 `5d59fee`의 405/415/404 보완과 이번 공백 테스트를 모두 보존했다. 새 통합본 전체 검증·공유를 진행하며 화면·영속 전달과 실제 VOC → Agent 재전송 소비는 남아 있다. P1 전원 수락으로 AGREED이며 RESOLVED는 아니다.

## 결정·실행·검증

2026-09-21T20:39:02+09:00 / 김아름 / voc / P1 — 공유 후 실제 HTTP 확인:

- 8d80786을 전체 publish 후 origin/main에서 확인했다. 같은 빌드의 실제 PostgreSQL/VOC/Agent에 공백 POST/PATCH 40건이 400으로 거절되고, 생략/null/유효 문자 11건은 201 → Agent 202 및 같은 키의 동일 조사 ID 재전송을 통과했다.
- 수정 전 저장한 합성 공백 티켓은 앱 재생성 후에도 그대로였다. 그 입력의 Agent 전달은 400 INVALID_REQUEST/retryable=false로 거절됐으며 입력을 몰래 정정하지 않았다.
- 원문은 로컬 runtime/verification/context-handoff.json과 legacy-blank-ticket.json이다. 모델 DISABLED에서 수행한 수동 HTTP 연결 검사다. VOC 서버의 영속 전달 작업·화면 소비 구현을 대신하지 않아 AGREED를 유지한다.

P1은 전원 수락했다. VOC 입력 경계·계약 수정과 실제 HTTP 연결을 공유했다. Agent PC의 영속 전달 인수는 아래 기록으로 확인했다. 화면·기존 잘못된 티켓의 오류 안내는 남아 있다.

## 해소 또는 재개 이력

2026-09-21T19:00:00+09:00 한재홍: 실제 입력 불일치 재현 후 OPEN 등록.

2026-09-21T19:18:47+09:00 김아름: P1 수락·VOC 입력 수정 결과를 기록하고 AGREED로 갱신. 소비·화면·전달 검증 전 미해소 유지.

### 2026-09-21T22:46:02+09:00 — 한재홍 / agent / P1 실제 VOC → Agent 소비

- buildId `8bd5699533be-057bb12f48d9`의 실제 PostgreSQL/VOC/Agent에서 다섯 선택 ID × 네 종류 공백의 POST/PATCH 40건이 400으로 거절되고 티켓/버전이 불변임을 확인했다.
- 생략한 context와 null 제외·양끝 공백을 포함한 유효 ID 보존을 서버 영속 작업기로 전달했다. 분석 요청 직후 티켓을 v2로 수정해도 v1 입력은 유지되고 새 키의 v2는 별도 조사 ID를 받았다. 같은 키/원래 버전은 같은 분석·조사 ID, 같은 키/다른 버전은 409이며 이력 두 건과 티켓 OPEN이 유지됐다.
- 기본 test/mock의 비활성 모델은 LLM_CONFIGURATION_ERROR로 끝난다. VOC는 이를 SUBMITTED 내부 조사 FAILED로 저장하며 submissionError/syncError는 null이다. Agent GET과 저장 조사 내용이 같고 소속 없는 근거는 404다. 성공 보고서·실제 모델 품질 검증으로 계산하지 않는다.
- 원문: `runtime/submission/agent-20260921/followup-voc-handoff.json`, `followup-voc-runtime.log`. 티켓 `d2ddcb14-f79c-41dc-b125-6cb96c2f23fc`, 분석 `70f9d4d0-3ccc-42cb-b270-0a576c5aea4b`/`27f1dad5-47ce-48f3-b2dc-6ee67d3513d1`. 해당 스택의 API/OAuth/예약 장부는 전후 모두 0이다. 기존 잘못된 티켓을 자동 변경하지 않았으며 화면 검증을 기다려 AGREED를 유지한다.

### 2026-09-21T23:07:13+09:00 — 한재홍 / agent / P1 화면 직렬화 직접 인수

- 495dbcc의 실제 한국어 티켓 웹에서 주문 번호 공백과 나머지 빈 입력은 생략하고 `productId=" synthetic-product "`의 양끝 문자는 유지해 생성했다. 실제 VOC 저장값과 대조했으며 명시적인 공백 API 거절 계약과 충돌하지 않는다.
- 합성 티켓 `900d57d0-8dc9-45e0-aaeb-8ae4cfd5400b`를 PC/모바일 브라우저에서 편집하면서 v1/v2 충돌의 초안 보존·최신 내용 비교·명시 교체 후 재저장/새로고침 v3를 확인했다. 원문 `followup-web-ticket.json`, `output/playwright/followup-*-conflict.png`와 Agent 상태의 같은 시각 기록에 있다.
- 새로운 티켓 입력 화면 소비는 직접 인수했다. 기존 잘못된 티켓의 분석 전달 오류/수정·새 키 안내는 후속 분석 패널 범위이므로 AGREED를 유지하며 김아름 공유 뒤 확인한다.
