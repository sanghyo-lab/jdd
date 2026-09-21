# Agent 조사 API와 영속 실행 상태

현재 구현 범위는 조사 API·영속 비동기 실행·도구 반복·근거 저장·보고서 검증·비용 제어다.
커머스 조회 도구를 연결했고 Spring AI OpenAI 전송 계층을 로컬 HTTP 모의 서버로 검증했다.
명시적인 데모 설정으로 OpenAI를 활성화할 수 있다. 실제 제공자 호출·모델 품질은 미검증이다.
기본 모델은 DISABLED다. 접수 후 실행기가 `FAILED / LLM_CONFIGURATION_ERROR`를 저장하며 유료 호출은 하지 않는다.
실행기를 명시적으로 끄면 요청은 QUEUED에 남는다. 모델·도구 반복은 합성 도구/모의 모델로 검증했다.
`businessReady=false`를 유지한다. 완료된 AI 분석처럼 표시하지 않는다.

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
- 단일 SQL 접수는 즉시 커밋된다. 백그라운드 실행기는 저장된 입력을 읽어 브라우저 연결과 독립적으로 실행한다.

필드 상세는 [v1 계약](../docs/integration-contract.md)을 따른다.

## 영속 실행 상태와 보고서 검사

`InvestigationExecutionRepository`는 QUEUED 작업을 원자적으로 선점하고 실행 토큰을 발급한다.
도구 시작 기록, 근거 원문·요약·도구 종료 기록, 보고서·최종 상태를 DB에 저장한다.
근거 배치 저장이 실패하면 같은 트랜잭션의 원문·요약을 모두 롤백한다. 커밋된 근거만 모델에 전달하도록 반환한다.
보고서는 같은 조사에 저장된 근거, 관측한 소스 경로, 필수 필드와 사람이 수행할 조치를 검증한다.
missingInformation에 따라 COMPLETED/NEEDS_INPUT을 서버에서 결정한다.

중단·시간 초과는 근거를 보존한 FAILED이며, 종료 후 이전 실행 토큰으로 들어온 응답은 반영하지 않는다.
PostgreSQL advisory lock을 가진 실행기 하나만 시작 복구·작업 접수를 수행한다.
재시작 시 QUEUED는 이어 처리하고 RUNNING은 INTERRUPTED로 정리한다. 미정산 모델 예약은 UNKNOWN으로 남긴다.
이 저장 계층의 합성 검증을 실제 VOC 조사·모델 품질 검증으로 간주하지 않는다.

`InvestigationRunner`가 도구 반복을 소유한다. 모델 요청→서버 인자 검증→실제 도구 호출→근거 커밋→후속 모델 요청→보고서 검사 순서다.
시스템 프롬프트는 `agent-infra/src/main/resources/prompts/investigation-system-v1.md`를 로딩하고 버전·SHA-256과 함께 모델 port에 전달한다.
기본 모델 호출은 비활성 구현이며 로컬 HTTP 모의 서버에서 실제 요청 프롬프트 전달을 확인했다.

| 실행 설정 | 기본값 | 용도 |
| --- | --- | --- |
| JDD_AGENT_WORKER_ENABLED | true | 접수 이후 비동기 실행. 비활성 모델은 설정 오류로 종료 |
| JDD_AGENT_WORKER_CONCURRENCY | 2 | 소형 로컬 DB 풀에서 실행 수 제한 |
| JDD_AGENT_WORKER_MAXIMUM_RUNTIME | PT3M | 조사 전체 시한과 늦은 결과 차단 |
| JDD_AGENT_LIMITS_MODEL_CALLS | 8 | 도구 후속 요청·보고서 수정 포함 |
| JDD_AGENT_LIMITS_TOOL_CALLS | 24 | 조회 반복 상한 |
| JDD_AGENT_LIMITS_REPORT_REPAIRS | 1 | 형식·근거 오류 수정 기회 |
| JDD_AGENT_LIMITS_ARGUMENT_REPAIRS | 1 | 잘못된 도구/인자 수정 기회 |

위 수치는 초기 실행 상한이며 실제 모델 지연·품질로 조정해야 한다. 처리량 측정 결과가 아니다.

## 모델 호출 허용과 비용 장부

`PaidModelGate`는 기본 금지이며 데모 모드·명시적 유료 허용·승인 범위·만료 시각·모델 허용 목록을 모두 검사한다.
OpenAI 클라이언트와 기본 금지 실행 설정을 구현했다. 이 계층이 있다는 이유로 유료 호출을 시작하지 않는다.
모의 검증은 메모리 함수와 IPv4 loopback HTTP 서버만 사용한다.

- `agent.demo_budget`의 단일 누적 예산은 로컬에 배정한 금액($30 이하)·범위·동시 호출·조사당 호출 수를 고정한다. 재시작·새 조사·다른 범위 이름으로 초기화하거나 확대하는 API는 없다.
- 데모 profile의 총 호출 한도도 같은 예산 행에 고정한다. 동시 예약을 포함해 원자적으로 검사하고 새 조사·재시작·설정 변경으로 초기화하지 않는다. V5 이전 장부는 null로 보존하며 실제 데모 활성화에는 명시한 총 한도가 필요하다.
- `agent.model_calls`는 실제 HTTP 시도마다 하나의 ID, 요청/실제 모델·가격 버전·prompt 지문·도구 스키마·시각·usage를 저장한다. 재시도·전환도 새 시도로 예약해야 하며 이 계층은 자동 재시도하지 않는다.
- DB 예산 행을 잠근 상태에서 확정+미확정+진행 중 예약+새 호출 최댓값을 검사한다. 모델 출력의 근거 ID와 비용 장부 ID는 별개다.
- 전송 전에 예약을 DISPATCHED로 한 번만 전환한다. 미전송 예약만 취소할 수 있다. 응답 유실·중단·필요 usage 누락·미등록 실제 모델은 UNKNOWN이며 새 유료 호출을 차단한다.
- 늦게 확보한 실제 사용량은 같은 시도의 UNKNOWN을 정산할 수 있다. 동일 정산 이벤트는 중복 합산하지 않고, 실제 비용이 예약을 넘으면 실제 값을 보존하고 추가 호출을 차단한다.
- input/output/cache read/cache write/reasoning은 nullable 수치다. 미관측을 0으로 채우지 않으며 reasoning을 output에 다시 더하지 않는다. 캐시 쓰기 적용 모델은 입력을 일반·읽기·쓰기 구간으로 나누어 계산한다.

가격은 하드코딩하지 않고 모델·컨텍스트 구간·service tier에 맞는 검증된 버전을 구성해야 한다.
현재 테스트 가격·모델명은 합성 값이다. [OpenAI 가격](https://developers.openai.com/api/docs/pricing)과
[캐시 비용 계산](https://developers.openai.com/api/docs/guides/prompt-caching)을 실제 데모 설정 시 재확인한다.
여러 PC의 예산 배분 또는 공유 장부·계정 전체 잔액 확인은 이 로컬 장부가 대신하지 않는다.

### OpenAI 전송 계층

`OpenAiInvestigationModel`은 Spring AI 2.0.1의 ChatModel을 매 반복 한 번 호출한다.
모델에는 버전 시스템 프롬프트, v1 입력, 엄격한 여덟 도구 정의, 보고서 JSON 스키마와
이전 도구 요청/서버 저장 근거만 전달한다. Spring AI가 자체 도구 실행이나 반복을 소유하지 않는다.
실제 endpoint는 `https://api.openai.com/v1/chat/completions`로 고정하며 ngrok 주소를 받지 않는다.
`localMock` 팩토리는 명시한 `http://127.0.0.1:<port>/v1`만 허용한다.

- HTTP 전송 경계에서 영속 예약 → DISPATCHED → 단일 전송 → native usage 정산을 수행한다.
  SDK 재시도와 실제 전송 클라이언트의 연결 재시도·리다이렉트를 모두 끈다.
  Spring AI client builder의 자동 연결 복구를 우회하도록 terminal interceptor에서 전송을 소유한다.
- 요청 본문 크기와 tokenizer 추정값을 제한한다. 추정값을 실제 입력 usage로 저장하지 않는다.
  최대 비용에는 별도로 구성한 공식 모델 입력 상한·출력 한도·장문 요금 전체를 예약한다.
- service tier는 `default`, store와 parallel tool calls는 false다. 실제 모델·tier·요금과 usage가
  확인되지 않으면 관측한 값은 보존하면서 UNKNOWN 예약을 유지하고 후속 유료 호출을 차단한다.
- 캐시 쓰기·reasoning을 포함한 native 응답 usage를 직접 읽는다. total이 있으면 input+output과
  대조하고 잘못된 수치·구간·합계는 확정하지 않는다. 누락을 0으로 채우지 않는다.
- 인증·429·시간 초과는 자동 재시도하지 않는다. 잘못된 보고서 응답도 관측된 비용은 보존한다.
  외부 오류 원문·키·모델 요청 본문을 오류 응답이나 앱 로그로 출력하지 않는다.

검증 명령은 `./gradlew :agent-app:test --tests com.jdd.agent.OpenAiTransportTest`다.
이 테스트는 H2 장부와 로컬 합성 HTTP만 사용하며 실제 OpenAI 접근·모델 품질을 검증하지 않는다.
[Spring AI ChatModel](https://docs.spring.io/spring-ai/reference/api/chat/openai-chat.html)과
[usage 처리](https://docs.spring.io/spring-ai/reference/api/usage-handling.html)를 적용했다.

### 승인된 데모의 명시적 활성화

실제 검증의 용도·모델·호출 수·최악 비용·기간과 팀 전체 예산 배분을 정한 뒤 사용한다.
예제 파일은 만료된 상태이며 실행 허가나 권장 예산을 뜻하지 않는다. 키·실제 profile은 Git에 넣지 않는다.

1. [profile 예제](config/demo-profile.example.json)를 무시되는 로컬 경로에 복사한다.
   scope·24시간 이내 validUntil·PC 배정 localBudgetUsd·maximumCalls를 승인된 값으로 설정한다.
   두 PC에 같은 전체 예산을 각각 배정하지 않는다. OpenAI 잔액·프로모션 연결·요금도 별도 확인한다.
2. 후보 모델과 catalogVersion을 선택한다. 가격 리소스는 2026-09-21 공식
   [Luna](https://developers.openai.com/api/docs/models/gpt-5.6-luna)·
   [Terra](https://developers.openai.com/api/docs/models/gpt-5.6-terra)의 기본 text 요금이다.
   확인 후 7일이 지난 catalog는 거절한다. tokenizer는 제한용 추정이며 제공자 정확 계수기가 아니다.
   모델 선택·품질 합격 결과를 뜻하지 않고 자동 전환도 없다.
3. 키는 별도 로컬 파일에 저장하고 Agent만 읽게 한다. profile에 키를 넣거나 ngrok authtoken을 사용하지 않는다.
   승인된 profile·키 파일의 절대 경로를 각각 JDD_DEMO_PROFILE_FILE·JDD_OPENAI_KEY_FILE 환경 변수로 지정한다.
4. 진행 중 조사가 없는지 확인한 후 저장소 루트에서 다음 **별도 명령**으로 Agent만 재생성한다.
   기존 스택과 같은 Compose 프로젝트·.env·build.env를 사용한다. 이 명령 자체는 모델을 호출하지 않지만
   활성화 후 접수/대기 조사는 승인 범위 안에서 모델을 호출할 수 있으므로 기존 QUEUED도 확인한다.

```bash
docker compose --env-file .env --env-file runtime/build.env \
  -f compose.yaml -f agent-app/compose.openai-demo.yaml \
  up --detach --no-build --no-deps --wait agent
```

override는 profile과 키 파일을 Agent에만 읽기 전용으로 마운트한다. 일반 up/check/publish는 이를 읽지 않는다.
종료 시 진행 중 조사가 끝난 뒤 같은 명령에서 두 번째 `-f` 옵션을 빼고 Agent를 재생성한다.
기본 DISABLED로 돌아가며 저장 예산·결과는 보존한다. 강제 중단은 미확정 예약을 해제하지 않는다.

호스트 JVM에서 별도 데모를 실행한다면 JDD_AGENT_MODEL_MODE=OPENAI, JDD_AGENT_DEMO_MODE=true,
JDD_AGENT_PAID_CALLS_ALLOWED=true, JDD_AGENT_DEMO_PROFILE과 JDD_AGENT_OPENAI_API_KEY_FILE을 명시한다.
키 파일 대신 OPENAI_API_KEY도 지원하지만 둘을 함께 지정하면 거절한다. 인자에 키를 넣지 않는다.
설정 누락·만료·미등록 모델·$30 초과·단일 호출 예약 불가능은 DISABLED이며 키를 먼저 읽지 않는다.
키만 존재해도 활성화되지 않는다. Gradle Agent 테스트는 상속된 유료 허용 값을 false로 고정한다.

입력 전체 상한 1,050,000과 출력 4,096으로 보수적으로 예약할 때 한 호출 최대치는
Luna $0.5323728, Terra $5.323728이다. 실제 짧은 조사 비용의 추정이나 측정값이 아니다.
성공 조사당 실제 총비용·지연·실패/재시도까지 비교한 뒤 모델을 확정해야 한다.
필요 usage가 누락되면 새 유료 호출을 차단하고 제공자 근거로 미확정 사용량을 조정할 때까지 유지한다.

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
`check_intake.py`는 `/internal/runtime`에서 모델 DISABLED를 확인한 경우에만 합성 요청을 보내고 `runtime/agent-intake.json`을 기록한다.
Agent를 재시작한 뒤 `python3 agent-app/scripts/check_intake.py --verify-existing`으로
동일 요청 ID·생성 시각의 보존을 확인한다. 포트를 바꿨으면 `--base-url`로 지정한다.

`InvestigationExecutionTest`는 실행 선점·원문 저장·롤백·중단 복구·늦은 결과·시간 제한·보고서 검사를 확인한다.
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
실제 프로세스 복구와 실행 소유권은 기존 Compose DB에 전용 `jdd_agent_worker_test`를 생성해 검증한다.
이 스크립트는 임시 Agent JVM을 시작/종료하고 합성 RUNNING 스냅샷을 주입한다. 서비스 DB는 초기화하지 않는다.

```bash
./gradlew :agent-app:bootJar
python3 agent-app/scripts/check_worker.py --compose-dir /path/to/verification-clone --project verification-project
```

DB 설정은 지정한 clone의 `.env`에서 읽는다. 임시 JVM에는 DB·Java·로컬 포트 설정만 전달하며 모델 키를 전달하지 않는다.
검증한 네트워크 호출은 로컬 HTTP/PostgreSQL이다. 실제 OpenAI·ngrok·VOC 업무 검증은 별도다.

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
입력 파일에서는 상관 ID와 buildId만 사용한다. 모의 모델 2회와 실제 OpenAI 검증을 구분한다.
`--rerun-tasks`로 이전 Gradle 결과를 재사용하지 않고 매번 현재 근거를 읽는다.
`command.json`/`command.log`에 성공·실패, `result.json`에 조사·저장 근거·모의 검증 한계를 남긴다.
