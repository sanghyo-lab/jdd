# Agent 조사 API와 영속 실행 상태

현재 구현은 조사 API·영속 실행·도구 반복·근거 저장·보고서 검증·비용 제어를 포함한다.
AI 경로는 **local/Codex OAuth · deployed/OpenAI API key · test/mock**으로 명시적으로 분리한다.
[로그인·실행·데모·배포·재로그인·검증 설명](../docs/llm-runtime.md)을 먼저 읽는다.
기본 Compose는 네트워크 없는 실패 mock이며, 실제 조사 결과를 만들지 않는다. 설정 누락/오타는 시작 오류다.
`businessReady=false`를 유지하며 실제 모델 품질·VOC UI·공개 데모 검증은 별도다.

## 실행과 접수

저장소 루트에서 Java 21과 Docker를 준비하고 실행한다.

```bash
./scripts/dev up
./scripts/dev smoke
curl -i http://localhost:8081/api/investigations \
  -H 'Content-Type: application/json' \
  -d '{"schemaVersion":"1.0","ticketId":"ticket-local-01","ticketVersion":1,"requestKey":"request-local-01","message":"주문 상태를 확인해주세요.","context":{"orderId":"order-local-01"}}'
```

위 ID는 연동용 가상 입력이다. `202` 응답의 `investigationId`를 조회 URL에 넣는다.

```text
GET /api/investigations/{investigationId}
GET /api/investigations/{investigationId}/evidence/{evidenceId}
```

- 같은 티켓·요청 키와 동일한 입력은 기존 ID·현재 상태를 반환한다.
- 같은 키로 문의·버전·context·이전 조사 ID를 바꾸면 `409 REQUEST_KEY_CONFLICT`다.
- context 생략/null/빈 객체와 context 내 null은 동일하게 취급한다. JSON 키 순서와 같은 시각의 UTC offset 표현도 중복 비교에 영향을 주지 않는다.
- 추가 조사는 새 키와 같은 티켓의 `previousInvestigationId`로 접수한다. 없는 조사나 다른 티켓의 이전 조사는 `404 NOT_FOUND`다.
- 근거는 조사 ID와 근거 ID를 함께 조회한다. 소속이 다르거나 없는 근거는 `404 NOT_FOUND`다. 저장된 원문 조회는 모델을 호출하지 않는다.
- v1에 없는 입력 필드, 잘못된 시각, 정수가 아닌 ticketVersion, 공백 문의, 10,000자를 넘는 문의는 `400 INVALID_REQUEST`다.
- 입력·조회 결과는 `agent.investigations`, 관측 원문은 `agent.investigation_evidence`에 저장한다. 요청 키의 DB 유일 제약으로 동시 접수도 한 조사만 생성한다.
- 접수 트랜잭션은 공유 admission 행 잠금 안에서 기존 키·QUEUED 개수·삽입을 확인하고 커밋한다. 백그라운드 실행기는 저장된 입력을 읽어 브라우저 연결과 독립적으로 실행한다.
- 기본 QUEUED 수용량은 20건이며 Agent의 `JDD_AGENT_QUEUE_CAPACITY`(1~100)로 정한다. 기본 Compose도 같은 환경변수를 전달한다.
  가득 차면 새 입력은 `429 INVESTIGATION_QUEUE_FULL`, `retryable=true`, `Retry-After: 5`이며 조사/모델 예약을 만들지 않는다.
  이미 받은 같은 키는 기존 ID/상태를 반환하고 다른 입력은 409다. RUNNING은 worker 동시성으로 따로 제한한다.

필드 상세는 [v1 계약](../docs/integration-contract.md)을 따른다.

## 영속 실행 상태와 보고서 검사

`InvestigationExecutionRepository`는 QUEUED 작업을 원자적으로 선점하고 실행 토큰을 발급한다.
도구 시작 기록, 근거 원문·요약·도구 종료 기록, 보고서·최종 상태를 DB에 저장한다.
근거 배치 저장이 실패하면 같은 트랜잭션의 원문·요약을 모두 롤백한다. 커밋된 근거만 모델에 전달하도록 반환한다.
보고서는 같은 조사에 저장된 근거, 관측한 소스 경로, 필수 필드와 사람이 수행할 조치를 검증한다.
missingInformation에 따라 COMPLETED/NEEDS_INPUT을 서버에서 결정한다.
완료 보고서에는 저장한 근거를 인용한 사실이 최소 하나 필요하다. 정보 부족 보고서는 사실을 만들지 않고 필요한 입력을 요청할 수 있다.
이는 무근거 완료를 막는 구조 검사이며 자연어 주장의 정확성과 원인 품질 평가를 대신하지 않는다.

중단·시간 초과는 근거를 보존한 FAILED이며, 종료 후 이전 실행 토큰으로 들어온 응답은 반영하지 않는다.
PostgreSQL advisory lock을 가진 실행기 하나만 시작 복구·작업 접수를 수행한다.
재시작 시 기한이 남은 QUEUED만 이어 처리하고 RUNNING은 INTERRUPTED로 정리한다. 미정산 모델 예약은 UNKNOWN으로 남긴다.
QUEUED의 대기 만료는 접수 시 `queued_deadline_at`에 저장하며 같은 키 재전송·재시작·새 설정으로 연장하지 않는다.
V6 이전 기록은 원래 createdAt + 10분으로 이관한다. 실행 슬롯이 가득 차도 worker가 만료 작업을 최대 100건씩
FAILED/INVESTIGATION_TIMEOUT으로 정리하며, 선점 쿼리도 만료 작업을 배제한다. 같은 키는 종료된 기존 ID를 반환한다.
worker/DB가 중단된 동안에는 상태 갱신이 지연되지만 복구 후 만료 작업을 모델로 보내지 않는다.
[DISC-agent-005](../docs/discussions/DISC-20260921-agent-005-queue-limits.md)의 합의에 따라 V8이 접수 잠금 행을 추가한다.
기존 조사/근거는 변경하지 않는다. 기존 QUEUED가 상한보다 많으면 새 접수를 거절하고 선점/만료 정리로 빈자리가 생길 때까지 기다린다.
VOC는 미접수 429를 전달 오류로 표시하고 같은 저장 입력/키로 제한 재전송한다. 실제 VOC 화면/전달 인수는 별도다.
이 저장 계층의 합성 검증을 실제 VOC 조사·모델 품질 검증으로 간주하지 않는다.

`InvestigationRunner`가 도구 반복을 소유한다. 모델 요청→서버 인자 검증→실제 도구 호출→근거 커밋→후속 모델 요청→보고서 검사 순서다.
시스템 프롬프트는 `agent-infra/src/main/resources/prompts/investigation-system-v2.md`를 로딩하고 버전·SHA-256과 함께 모델 port에 전달한다. 기존 v1 리소스는 과거 실행 식별용으로 보존한다.
기본 Compose 모델은 네트워크 없는 실패 mock이며 로컬 HTTP 모의 서버에서 실제 요청 프롬프트 전달을 확인했다.

| 실행 설정 | 기본값 | 용도 |
| --- | --- | --- |
| JDD_AGENT_WORKER_ENABLED | true | 접수 이후 비동기 실행. 비활성 모델은 설정 오류로 종료 |
| JDD_AGENT_WORKER_CONCURRENCY | 2 | 소형 로컬 DB 풀에서 실행 수 제한 |
| JDD_AGENT_WORKER_MAXIMUM_RUNTIME | PT3M | RUNNING 선점 후 실행 시한과 늦은 결과 차단 |
| JDD_AGENT_QUEUE_MAXIMUM_WAIT | PT10M | 새 접수의 대기 한도, 1초~1시간. 기존 기한은 유지 |
| JDD_AGENT_LIMITS_MODEL_CALLS | 8 | 도구 후속 요청·보고서 수정 포함 |
| JDD_AGENT_LIMITS_TOOL_CALLS | 24 | 조회 반복 상한 |
| JDD_AGENT_LIMITS_REPORT_REPAIRS | 1 | 형식·근거 오류 수정 기회 |
| JDD_AGENT_LIMITS_ARGUMENT_REPAIRS | 1 | 잘못된 도구/인자 수정 기회 |

위 수치는 초기 실행 상한이며 실제 모델 지연·품질로 조정해야 한다. 처리량 측정 결과가 아니다.

## 모델 연결과 비용 장부

`InvestigationModel`에 `CodexOAuthInvestigationModel`과 `OpenAiInvestigationModel`을 연결한다.
두 어댑터는 직접 HTTP Responses/SSE를 사용한다. 공통 대화·도구·구조화 보고서 형식과 암호화 reasoning context를 보존한다.
SDK/CLI가 조사 도구를 실행하지 않으며 `InvestigationRunner`가 기존 허용된 읽기 전용 함수만 호출한다.
로컬 OAuth 실패·만료·429·시간 초과에 API key로 fallback하지 않는다. 배포는 OAuth 파일을 읽지 않는다.

- 로컬 로그인·run-local·demo·최소 실호출·진단: [실행 설명](../docs/llm-runtime.md).
- 배포 설정: `APP_RUNTIME=deployed`, `LLM_PROVIDER=openai_api`, `OPENAI_MODEL`, `OPENAI_API_KEY`, `JDD_AGENT_API_PROFILE`.
- [API profile 예제](config/api-profile.example.json)는 만료된 placeholder이며 운영 범위/예산을 채워 배포한다.
- `agent.demo_budget`/`agent.model_calls`의 기존 이름과 데이터를 유지한다. 이제 이 장부는 배포 API 유료 호출에만 사용한다.
  $30 이하 배정·총 호출·동시 호출·조사당 한도를 영속 고정하고 확정+미확정+예약+새 최대 비용을 원자적으로 검사한다.
  타임아웃·필수 usage 누락·가격 불명은 UNKNOWN이며 새 유료 호출을 차단한다. 재시작/새 조사로 예산을 초기화하지 않는다.
- API usage의 input/output/cache/reasoning은 nullable이며 미관측은 0으로 채우지 않는다. reasoning을 output에 중복 합산하지 않는다.
- OAuth 관측은 `agent.oauth_model_calls`에 모델·실제 nullable usage·결과·지연만 저장한다. API USD 비용으로 환산하지 않고
  API 장부/프로모션 크레딧을 사용하지 않는다. `DISPATCHED`인 채 종료된 기록은 사용량 미확정이며 성공으로 처리하지 않는다.
- 브라우저·로그·빌드에 key/token/auth.json을 넣지 않는다. 진단에는 runtime/provider/model/authConfigured만 표시한다.

### 저장한 사용량과 비용 내보내기

```bash
python3 agent-app/scripts/export_model_calls.py \
  --compose-dir /path/to/running-jdd-clone \
  --output runtime/submission/model-calls-unique.json
```

기존 PostgreSQL에 Agent 계정으로 연결해 하나의 REPEATABLE READ READ ONLY 트랜잭션으로 읽는다.
앱 기동·조사 접수·모델 호출·장부 변경은 수행하지 않는다. `--investigation-id UUID`로 개별 조사를 고를 수 있으며,
이때도 예산 합계는 설치 전체다. 없는 조사와 호출이 없는 조사를 구분한다. 최대 1,000건 초과는 잘린 성공 대신 실패한다.
기존 출력은 덮어쓰지 않는다. POSIX의 새 파일 권한은 0600이며 Windows에서는 대상 폴더의 ACL을 따른다. 산출물을 Git에 넣지 않는다.
실행 가능한 `docker compose`를 먼저 확인하고, 플러그인이 없으면 PATH의 `docker-compose`를 확인한다.
Windows의 시스템·사용자 프로필·프로그램 경로와 Docker 설정 경로를 보존하며 모델/앱 키는 자식 프로세스 환경으로 전달하지 않는다.
Compose 탐색 실패·DB 실행 실패·30초 조회 시간 초과는 결과 파일을 만들지 않고 실패하며 원본 자식 오류는 출력하지 않는다.

각 실제 시도의 요청/응답 모델·요금 버전·프롬프트 버전/해시·도구 버전·토큰 상한·usage·상태·시각과
확정 비용·미확정 책임액·진행 중 예약을 보존한다. USD 금액은 십진 문자열이다. 미관측 usage/비용은 null이다.
캐시/쓰기 입력과 reasoning은 각각 전체 입력/출력의 부분이므로 합쳐 총 토큰을 부풀리지 않는다.
UNKNOWN의 예약액은 실제 청구액이 아니고, 확정 비용도 기록한 요금으로 계산한 값이며 제공자 청구서가 아니다.
예약/정산 시각의 차이는 순수 모델 지연이 아니다. 다른 PC·계정 잔액을 이 합계로 추정하지 않는다.
키·프로필·문의 본문·보고서 원문·모델 요청 본문/옵션은 내보내지 않는다.

이 로컬 검수 파일은 runner의 선택 메타데이터 DTO나 새 필수 완료 계약이 아니다.
최종 보고서를 만든 실제 모델과 보고서 품질은 조사 원문을 함께 확인해 판정한다.

## 검증

```bash
./gradlew :agent-app:test
./scripts/dev check
python3 agent-app/scripts/check_intake.py
```

`InvestigationApiTest`는 실제 HTTP 서버와 H2의 PostgreSQL 호환 모드에서 마이그레이션,
입력 정규화·중복·충돌·동시 접수·이전 조사 연결·근거 소속·입력 오류를 확인한다.
근거 테스트의 원문은 테스트가 DB에 넣은 합성 데이터이며 모델 분석 결과가 아니다.
Docker 스택에서는 실제 PostgreSQL 접수·재조회·재시작 보존을 별도로 확인한다.

별도 PostgreSQL 테스트의 일곱 DB 모드는 Gradle 입력에 반영한다.
URL·비밀번호 자체는 작업 fingerprint에 넣지 않는다. 외부 DB 모드에서는 DB·근거 파일이
소스와 독립적으로 바뀔 수 있으므로 UP-TO-DATE/빌드 캐시로 검증을 대체하지 않고 매번 실행한다.
H2 모드로 돌아오면 PostgreSQL 결과를 재사용하지 않는다. 전용 DB·필수 환경변수·초기화 조건은
아래 각 검사의 안내를 따른다.

`check_intake.py`는 `/internal/runtime`에서 모델 MOCK를 확인한 경우에만 합성 요청을 보내고 `runtime/agent-intake.json`을 기록한다.
Agent를 재시작한 뒤 `python3 agent-app/scripts/check_intake.py --verify-existing`으로
동일 요청 ID·생성 시각의 보존을 확인한다. 포트를 바꿨으면 `--base-url`로 지정한다.

`InvestigationExecutionTest`는 실행 선점·원문 저장·롤백·중단 복구·늦은 결과·시간 제한·보고서 검사를 확인한다.
대기 기한의 경계·재전송/설정 변경 보존·선점 경쟁과 RUNNING 예산 분리도 검증한다.
기본은 별도 H2 DB다. 실제 PostgreSQL에서는 **전용 폐기 가능한 DB `jdd_agent_execution_test`**를 준비하고
`JDD_EXECUTION_TEST_DB_URL`, `JDD_EXECUTION_TEST_DB_USER`, `JDD_EXECUTION_TEST_DB_PASSWORD`를 테스트 프로세스에만 주입한 뒤 실행한다.

```bash
./gradlew :agent-app:test --tests com.jdd.agent.InvestigationExecutionTest --rerun-tasks
```

외부 URL은 위 DB 이름만 허용하며 각 테스트는 그 DB의 조사·근거를 초기화한다. 앱의 업무 DB를 지정하지 않는다.
테스트용 DB 자격 증명을 Git·명령 인수·로그에 남기지 않는다. 이 검증은 모델 API를 사용하지 않는다.

`ModelCallLedgerTest`는 기본적으로 별도 H2 DB에서 동시 예약·사용량 정산·중복 정산·예산/호출 한도·유료 차단을 검증한다.
실제 PostgreSQL 검증에는 전용 `jdd_agent_budget_test`와 `JDD_BUDGET_TEST_DB_URL`,
`JDD_BUDGET_TEST_DB_USER`, `JDD_BUDGET_TEST_DB_PASSWORD`를 사용한다. 해당 DB의 장부·합성 조사는 테스트마다 초기화한다.

```bash
./gradlew :agent-app:test --tests com.jdd.agent.ModelCallLedgerTest --rerun-tasks
```

`InvestigationRunnerTest`는 모의 모델의 도구 요청·저장 후 후속 요청·보고서/인자 수정 한도·실패·반복 HTTP 조회를 검증한다.

`QueueAdmissionTest`는 기본 H2와 전용 PostgreSQL `jdd_agent_admission_test`에서 실제 HTTP 동시 접수·상한·동일 키·409·429/Retry-After·선점/만료 후 빈자리 회복을 확인한다.
worker를 끄고 API/OAuth 호출/예산 행이 0임을 검사한다. 외부 모드는 `JDD_ADMISSION_TEST_DB_URL`, `JDD_ADMISSION_TEST_DB_USER`,
`JDD_ADMISSION_TEST_DB_PASSWORD`로 지정하며 매 검사 전에 해당 전용 DB의 합성 조사만 초기화한다.
`JDD_ADMISSION_TEST_REPORT_DIR`를 새 로컬 디렉터리로 지정하면 실제 HTTP 응답/DB 행 관측을 추가 보존한다.

```bash
./gradlew :agent-app:test --tests com.jdd.agent.QueueAdmissionTest
```

`QueueWaitingTest`는 실제 PostgreSQL/HTTP/worker의 유일한 실행 슬롯을 모의 모델로 점유한 동안
다른 요청이 대기 2초 후 모델 호출 없이 만료되는지 확인한다. 반복 GET/같은 키 POST는 기존 FAILED를
반환하고, 슬롯 해제 후 명시적인 새 키 조사만 실행된다. 비어 있는 전용 `jdd_agent_queue_test`와
`JDD_QUEUE_TEST_DB_URL`, `JDD_QUEUE_TEST_DB_PASSWORD`, 새 결과 경로 `JDD_QUEUE_TEST_REPORT`를 준비한다.

```bash
./gradlew :agent-app:test --tests com.jdd.agent.QueueWaitingTest
```

실제 프로세스 복구와 실행 소유권은 기존 Compose DB에 전용 `jdd_agent_worker_test`를 생성해 검증한다.
이 검사는 실제 1초 대기 기한으로 접수한 뒤 다른 설정의 JVM으로 재시작해 기한 보존·미실행을 확인한다.
이 스크립트는 임시 Agent JVM을 시작/종료하고 합성 RUNNING 스냅샷을 주입한다. 서비스 DB는 초기화하지 않는다.

```bash
./gradlew :agent-app:bootJar
python3 agent-app/scripts/check_worker.py --compose-dir /path/to/verification-clone --project verification-project
```

DB 설정은 지정한 clone의 `.env`에서 읽는다. 임시 JVM에는 DB·Java·로컬 포트 설정만 전달하며 모델 키를 전달하지 않는다.
검증한 네트워크 호출은 로컬 HTTP/PostgreSQL이다. 실제 OpenAI·ngrok·VOC 업무 검증은 별도다.

`ClientDisconnectionTest`는 실제 백그라운드 worker와 PostgreSQL에서 접수 응답을 읽지 않고 TCP 연결을 닫는다.
관측이 저장된 RUNNING 중 새 HTTP 클라이언트가 같은 키로 ID를 회복하고 근거를 조회한 뒤,
원래 조사의 완료·반복 조회·단일 조사/근거와 모의 모델 2회 유지 여부를 확인한다.
비어 있는 전용 `jdd_agent_disconnect_test` DB, `JDD_DISCONNECT_TEST_DB_URL`,
`JDD_DISCONNECT_TEST_DB_PASSWORD`, 결과 파일 `JDD_DISCONNECT_TEST_REPORT`를 준비해 명시적으로 실행한다.
서비스 DB를 지정하지 않는다. 모델과 관측 도구는 명시적 테스트 대역이며 ngrok/OAuth·web 화면 검증이 아니다.

```bash
./gradlew :agent-app:test --tests com.jdd.agent.ClientDisconnectionTest --rerun-tasks
```

## 읽기 전용 조사 도구

실행기는 `commerce-evidence-v1`의 여덟 도구를 제공한다: `findOrders`, `getOrderContext`,
`getCouponContext`, `getInventoryContext`, `searchLogs`, `searchCode`, `readCode`, `readBusinessPolicy`.
도구 인자는 엄격한 JSON 타입·알려진 필드·범위로 검증하며 모델이 SQL·명령·임의 파일을 실행하지 않는다.
환경은 기존 Compose의 `EVIDENCE_DB_URL/USERNAME/PASSWORD`, `SOURCE_ROOT`, `LOG_ROOT`, `POLICY_PATH`를 사용한다.
DB 계정은 Agent 저장 계정과 별개이며 계약 10개 테이블의 SELECT와 쓰기/DDL 권한 부재를 조회 때 확인한다.
한 DB 도구 안의 여러 SELECT는 REPEATABLE READ 스냅샷을 공유한다. 별도 도구끼리의 관측 시각은 다를 수 있다.

- 행 수는 기본 20 또는 50, 최대 100이다. 한 행을 추가로 읽어 잘림을 구분한다. 재고의 전체 종류별 이력·상태별 주문 수량 집계는 제한된 원문 행과 별도로 반환한다.
- 로그는 상관조건 AND·최대 하루 범위로 조회한다. 최대 32개 build/파일·4MiB·결과 100줄이며 미완성 마지막 줄·검색 한도를 부분 결과로 기록한다. 비어 있고 완전한 검색만 150ms 간격으로 최대 2회 재조회하며 모델을 재호출하지 않는다.
- 동일 eventId의 동일 원문은 줄 번호를 보존하고 중복임을 표시한다. 같은 eventId의 상충 내용, 손상된 완성 줄, buildId 불일치는 도구 실패다. 빈 결과는 지연된 로그나 장애의 부재를 입증하지 않는다.
- 소스는 지정 buildId의 manifest와 SHA-256이 일치하는 허용 Java·migration 파일만 읽는다. 검색은 최대 512파일·4MiB·30구간, 직접 읽기는 최대 300줄이다. 심볼릭 링크·경로 이탈·테스트·재현 제어·fixtures를 차단한다.
- 정책은 새 manifest의 `policy: {version, path, sha256}`와 해당 build 안의 고정 `policy/business-policy.md`를 대조한다. 사본이 선언되어 있으면 누락·변조·다른 버전·경로 이탈 때 현재 파일로 대체하지 않는다. 기존 policy 필드 없는 manifest만 현재 정책을 읽는 한계를 명시한다. 이미 저장한 근거 원문은 파일 변경과 무관하게 DB에서 조회한다.
- searchCode/readCode의 출처와 검색 요약에 manifest의 policyVersion을 제공한다. 모델은 이 값으로 readBusinessPolicy를 호출하며 정책 버전을 추측할 필요가 없다. 정책 사본을 코드 검색 허용 경로에 추가하지 않는다.
- 빈 검색의 범위와 한도는 도구 실행 요약에도 저장한다. 존재하지 않는 근거 ID를 만들어 빈 검색을 인용하지 않는다.

`EvidenceFileToolsTest`는 경로·해시·필드 타입·부분/중복 로그를 검사한다.
`CommerceEvidencePostgresTest`는 별도 `jdd_agent_tools_test` DB에서만 명시적으로 실행하며
`JDD_TOOLS_TEST_DB_URL`, `JDD_TOOLS_TEST_OWNER_PASSWORD`, `JDD_TOOLS_TEST_READER_PASSWORD`가 필요하다.
실제 commerce DDL과 역할 `jdd_commerce`/`jdd_evidence`를 준비하고 합성 데이터·SELECT·권한 거절·스냅샷을 검증한다.
이 검사는 실제 모델 품질이나 VOC 화면 검증을 대신하지 않는다.

## 실제 커머스 근거 인수 검증 (모의 모델)

커머스의 `reproduce_inventory.py --runs 20`과 정상 대조가 끝난 데이터·로그·소스를 유지한 상태에서 실행한다.
조사 종료 전에 해당 데이터를 초기화하거나 서비스를 재기동하지 않는다.

```bash
python3 agent-app/scripts/check_commerce_handoff.py \
  --compose-dir /path/to/running-jdd-clone \
  --inventory-artifact /path/to/retained-inventory.json \
  --report-dir runtime/submission/agent-handoff
```

이 명령은 기존 Compose DB에 전용 `jdd_agent_handoff_test` DB를 준비하고,
실제 근거 도구 → Agent PostgreSQL 저장 → 원문 HTTP 재조회·동일 키 재전송을 검사한다.
커머스에는 SELECT만 수행하며 재현 데이터 생성·초기화·앱 재시작은 수행하지 않는다.
Java 21과 실행 중인 제공자 스택의 `.env`가 필요하다. 비밀 값은 출력·보고서에 포함하지 않는다.

VOC-01~06도 검사하려면 같은 buildId의 `reproduce_commerce.py --runs 3` 결과를
`--business-artifact /path/to/retained-business.json`으로 추가한다. 출력 디렉터리는 새 경로여야 한다.
각 시나리오의 첫 재현에 공통 모의 모델 절차를 적용하며 후보 주문·쿠폰·재고·로그를 읽은 뒤,
관측한 주문 ID·코드 경로·policyVersion으로 상세 근거를 읽어 저장한다. 원문 HTTP 재조회·동일 키·새로고침도 확인한다.
모델에는 합성 식별자만 전달하고 공급자의 기대 DB/로그 비교는 모델 프로세스 밖에서 수행한다.
`business-comparison.json`은 모든 주문·결제·환불·쿠폰 상태와 로그 원문의 일치를 기록한다.
첫 실패를 포함한 명령 로그와 각 조사 원문을 보존한다. 이 검사는 일곱 실제 AI 원인/조치 품질이나 VOC 화면 검증을 대신하지 않는다.
입력 파일에서는 상관 ID와 buildId만 사용한다. 모의 모델 2회와 실제 OpenAI 검증을 구분한다.
`--rerun-tasks`로 이전 Gradle 결과를 재사용하지 않고 매번 현재 근거를 읽는다.
`command.json`/`command.log`에 성공·실패, `result.json`에 조사·저장 근거·모의 검증 한계를 남긴다.
