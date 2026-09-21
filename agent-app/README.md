# Agent 조사 API와 영속 실행 상태

현재 구현 범위는 v1 조사 접수·입력 스냅샷 저장·조사/근거 조회와 실행 상태 저장 계층이다.
백그라운드 실행기·모델·조회 도구는 아직 연결하지 않았다.
접수한 요청은 `QUEUED`에 머무르며 `progress: []`, `evidence: []`, `report: null`, `error: null`을 반환한다.
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
- 근거는 조사 ID와 근거 ID를 함께 조회한다. 소속이 다르거나 없는 근거는 `404 NOT_FOUND`다. 아직 실제 근거를 생성하는 작업 실행기는 없다.
- v1에 없는 입력 필드, 잘못된 시각, 정수가 아닌 ticketVersion, 공백 문의, 10,000자를 넘는 문의는 `400 INVALID_REQUEST`다.
- 입력·조회 결과는 `agent.investigations`, 관측 원문은 `agent.investigation_evidence`에 저장한다. 요청 키의 DB 유일 제약으로 동시 접수도 한 조사만 생성한다.
- 단일 SQL 접수는 즉시 커밋된다. 이후 작업 실행기는 이 저장된 입력을 읽어 비동기로 실행하도록 연결할 예정이다.

필드 상세는 [v1 계약](../docs/integration-contract.md)을 따른다.

## 영속 실행 상태와 보고서 검사

`InvestigationExecutionRepository`는 QUEUED 작업을 원자적으로 선점하고 실행 토큰을 발급한다.
도구 시작 기록, 근거 원문·요약·도구 종료 기록, 보고서·최종 상태를 DB에 저장한다.
근거 배치 저장이 실패하면 같은 트랜잭션의 원문·요약을 모두 롤백한다. 커밋된 근거만 모델에 전달하도록 반환한다.
보고서는 같은 조사에 저장된 근거, 관측한 소스 경로, 필수 필드와 사람이 수행할 조치를 검증한다.
missingInformation에 따라 COMPLETED/NEEDS_INPUT을 서버에서 결정한다.

중단·시간 초과는 근거를 보존한 FAILED이며, 종료 후 이전 실행 토큰으로 들어온 응답은 반영하지 않는다.
`recoverInterrupted`는 단일 실행 소유자가 시작할 때만 호출해야 한다. 실행 소유권·스케줄러 연결은 다음 구현 범위다.
이 저장 계층의 합성 검증을 실제 VOC 조사·모델 품질 검증으로 간주하지 않는다.

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
`check_intake.py`는 실행 중인 Agent에 합성 요청을 보내고 `runtime/agent-intake.json`을 기록한다.
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
