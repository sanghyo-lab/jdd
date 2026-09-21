# Agent 조사 API와 영속 실행 상태

현재 구현 범위는 조사 API·영속 비동기 실행·도구 반복·근거 저장·보고서 검증·비용 제어다.
실제 OpenAI 어댑터와 커머스 조회 도구는 아직 연결하지 않았다.
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
- 근거는 조사 ID와 근거 ID를 함께 조회한다. 소속이 다르거나 없는 근거는 `404 NOT_FOUND`다. 실제 커머스 근거 조회 도구는 연결 중이다.
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
현재 모델 호출은 비활성 구현이며 테스트가 주입한 모의 모델에서 실제 프롬프트 전달을 확인했다.

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
현재 실제 OpenAI 클라이언트·환경 설정 연결은 구현 중이다. 이 계층이 있다는 이유로 유료 호출을 시작하지 않는다.
모의 검증에서는 실제 네트워크가 없는 함수를 호출해 허용/차단과 실패 처리를 확인한다.

- `agent.demo_budget`의 단일 누적 예산은 로컬에 배정한 금액($30 이하)·범위·동시 호출·조사당 호출 수를 고정한다. 재시작·새 조사·다른 범위 이름으로 초기화하거나 확대하는 API는 없다.
- `agent.model_calls`는 실제 HTTP 시도마다 하나의 ID, 요청/실제 모델·가격 버전·prompt 지문·도구 스키마·시각·usage를 저장한다. 재시도·전환도 새 시도로 예약해야 하며 이 계층은 자동 재시도하지 않는다.
- DB 예산 행을 잠근 상태에서 확정+미확정+진행 중 예약+새 호출 최댓값을 검사한다. 모델 출력의 근거 ID와 비용 장부 ID는 별개다.
- 전송 전에 예약을 DISPATCHED로 한 번만 전환한다. 미전송 예약만 취소할 수 있다. 응답 유실·중단·필요 usage 누락·미등록 실제 모델은 UNKNOWN이며 새 유료 호출을 차단한다.
- 늦게 확보한 실제 사용량은 같은 시도의 UNKNOWN을 정산할 수 있다. 동일 정산 이벤트는 중복 합산하지 않고, 실제 비용이 예약을 넘으면 실제 값을 보존하고 추가 호출을 차단한다.
- input/output/cache read/cache write/reasoning은 nullable 수치다. 미관측을 0으로 채우지 않으며 reasoning을 output에 다시 더하지 않는다. 캐시 쓰기 적용 모델은 입력을 일반·읽기·쓰기 구간으로 나누어 계산한다.

가격은 하드코딩하지 않고 모델·컨텍스트 구간·service tier에 맞는 검증된 버전을 구성해야 한다.
현재 테스트 가격·모델명은 합성 값이다. [OpenAI 가격](https://developers.openai.com/api/docs/pricing)과
[캐시 비용 계산](https://developers.openai.com/api/docs/guides/prompt-caching)을 실제 데모 설정 시 재확인한다.
여러 PC의 예산 배분 또는 공유 장부·계정 전체 잔액 확인은 이 로컬 장부가 대신하지 않는다.

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
