# 한재홍 — AI Agent 작업 상태

- 상태: 조사 API·영속 실행·8개 실제 근거 도구·OpenAI 전송·명시 데모 활성화·비용 장부를 구현했다. 로컬 모의 전송과 실제 PostgreSQL/커머스 인수를 검증했고 실제 모델 품질·VOC/화면·ngrok 연동은 진행 중이다.
- 담당자: 한재홍 (역할 A)
- GitHub 계정: 공유받은 뒤 기입
- 작업 브랜치: `main`
- 논의 소통 경로: [논의 목록·작성 규칙](../discussions/README.md). 새 논의의 답변은 해당 건의 Markdown에 직접 남기고 README의 상태·해소 근거를 함께 갱신한다. 기존 진행·검증 기록은 이 문서에서 유지한다.
- 완료 선언: [agent.json](agent.json)의 IN_PROGRESS. 실제 검증 후 자기 DONE을 공유하고 [세 담당자 완료 기준](../team-completion.md)이 충족될 때까지 goal을 유지한다.
- 시작 지침: [agent goal](../goals/agent.md), [복사할 goal 시작문](../prompts/goal-han-jaehong-agent.md), [필수 구현 프롬프트](../prompts/implement-voc-investigation-agent.md), [공통 실행](../local-development.md)
- 작업 Issue·공유 커밋: 접수 `b2b46ef`, 실행 상태/보고서 `e60fa86`, 비용 장부 `943e961`(각 전체 publish 통과). 모델 오류 논의는 세 역할 P1 수락 후 AGREED다.
- 담당 경로: `agent-app/`, `agent-core/`, `agent-infra/`
- 준비된 자료: [구현 범위](../roles/han-jaehong-agent.md), [VOC·Agent 계약](../integration-contract.md), [커머스 조회 계약](../commerce-interface.md)
- 다음 작업: 새 정책 snapshot과 VOC 분석 전달·web·runner를 인수하고 승인된 실제 모델·ngrok 검증을 연결한다. 일곱 업무의 실제 근거 조회·저장·원문 재조회 검사는 통과했다. 일반 실행의 유료 차단을 유지한다.
- 필요한 입력: 김아름의 정책 생성기·분석/화면 전달과 데모 모델·기간·호출 수·팀/PC 예산 배분·사용 범위 확정. 제공된 데모 키는 저장·설정·호출하지 않았다.
- 검증 결과: 전체 Gradle check·세 앱 Docker·PostgreSQL/HTTP smoke, 실제 DB 동시성·권한·복구·비용 예약과 일곱 커머스 조사 300근거·333필드/로그 대조를 확인했다. 모의 모델과 실제 모델을 구분하며 실제 모델 품질·화면은 미검증.
- 연동 요청: 정책 archive 소비자 구현 제공, DISC-20260921-agent-003의 선택 ID 공백 입력 경계 확인. 기본 모델은 DISABLED이며 키 존재만으로 활성화하지 않는다.

작업 단위가 끝날 때 제공 가능한 기능, 변경한 계약, 실제 검증 명령·결과, 다음 작업을 갱신한다. 실패와 막힌 이유도 함께 기록한다.



## 2026-09-21T20:59:43+09:00 — OAuth/API 분리 전체 공유와 시작 오류 검증

- `56cadd5`를 scripts/dev publish로 main에 공유했다. Python 51개·JUnit 146개(실패/오류 0, 조건부 건너뜀 9), 문서·Gradle check, 세 앱/실제 PostgreSQL/읽기 전용 근거 연결 통과. buildId `56cadd53eb47-5e968d60e7e8`, Agent MOCK. 원문 `runtime/submission/agent-20260921/oauth-api-publish.log`·json·publication-junit.json이다. 사용 인증 없는 환경 허용 목록으로 실행했고 실제 OAuth/API 호출은 0회다.
- 최종 검토 보완: runtime/provider·필수 모델/인증 설정의 기본 검사를 Spring 초기화 전에 넣어 잘못된 조합이 DB 연결보다 먼저 종료되게 했다. 실제 jar를 잘못된 조합·조합 누락·배포 key 누락으로 기동한 세 경우 모두 종료 1/명확한 설정 오류·DB TCP 접속 0회였다. `runtime/llm-early-startup-results.json`과 각 startup 로그. 최초 검사기의 Python 3.9 socket.timeout 처리 누락으로 감시 스레드가 종료된 관측은 `llm-early-startup-invalid-harness-results.json`으로 제외했고, 수정된 살아 있는 socket 감시기로 세 경우를 다시 통과했다.
- 같은 보완의 환경 격리 7개·bootJar, 스크립트 6개 통과. 로그인 자식 프로세스의 Windows 사용자 환경과 Gradle wrapper 분기는 준비했으나 Windows 실제 OAuth 검증은 미수행이다. 배포 jar에 auth.json/.codex/.env가 없음을 확인했다.
- DISC-agent-004에 최신 사용자 지시를 P2로 공유했다. 상대의 기존 로컬 API 예산 수락을 새 배포 범위 수락으로 재사용하지 않는다. CODEX_MODEL/프로젝트 최초 로그인은 아직 미준비라 실제 OAuth 호출·품질은 미검증이다. role DONE은 보류한다.

## 2026-09-21 — 사용자 지시: local OAuth / deployed API / test mock 구현

- 최신 사용자 지시를 적용해 기존 로컬 API 데모 설정을 교체했다. APP_RUNTIME/LLM_PROVIDER를 명시하고 local/codex_oauth, deployed/openai_api, test/mock 외에는 시작 오류다. OAuth 실패·만료·429·권한·timeout에 API fallback하지 않으며 배포는 OAuth 설정/파일을 읽지 않는다.
- 기존 InvestigationModel·비동기 실행·8개 읽기 전용 도구·근거/보고서 계약을 유지한다. 두 직접 HTTP Responses 어댑터, strict schema/function call/history/encrypted reasoning 보존, SSE UTF-8/chunk/완료·실패·EOF·취소 처리를 구현했다. Spring AI ChatCompletions와 기존 demo 활성화는 제거했다.
- 로컬 auth는 저장소 밖 전용 디렉터리와 공식 CLI 최초 로그인만 사용한다. 매 요청 새 파일을 읽으며 앱이 refresh하지 않는다. ./scripts/llm login/diagnose/run-local/demo/smoke-local/smoke-deployed, 두 Compose override, 배포 profile·환경 예제와 docs/llm-runtime.md를 제공한다. 일반 up/check/publish는 explicit test/mock이다.
- $50 API 크레딧은 배포에만 사용한다. 기존 $30 한도·가격 확인·영속 API 예약/정산은 유지하고 OAuth nullable usage는 V7 별도 관측 표에 저장한다. OAuth를 API USD로 환산하거나 API 장부를 초기화하지 않는다.
- 근거: 공식 Codex 공개 소스 ebc05da3bdb76f25861e7cb418bd06d28cadc609와 CLI 0.155.1 login help를 확인했다. 헤더·auth.json·Responses body/SSE 근거와 실제 호환성 한계는 실행 설명에 연결했다. 모델 지원·워크스페이스 무제한 혜택은 주장하지 않는다.
- 실행 검증: Agent app/core 전체 회귀·bootJar 성공(runtime/llm-agent-regression.log). Spring AI 제거 직후 slf4j 직접 의존 누락으로 컴파일 실패했으며 명시 의존 추가 후 재검증 성공(runtime/llm-agent-final-check-after-dependency.log). 최종 추가 36개 검사(전송 23·환경 격리 7·배포 설정 5·OAuth 장부 1) 통과(runtime/llm-final-coverage.log), SSE 경계 4개 별도 통과. Python 44개 성공(runtime/llm-python-check.log). Compose 세 경로 자격증명 분리 통과(runtime/llm-compose-isolation.json). 모두 합성/loopback이며 실제 모델 호출 0회다.
- 실제 프로젝트 전용 auth.json과 CODEX_MODEL은 미준비다. 개발자 로그인/모델명 요청을 보냈으며 토큰을 요구하지 않았다. 실제 OAuth·배포 API·VOC 품질·ngrok/화면은 미검증이다. 코드 연결 완료를 실제 AI 품질 또는 role DONE으로 기록하지 않는다.
- 원격 6b9ca6f까지 전체 변경을 확인했다. 김아름의 정책 snapshot/공백 입력/PG 재검증·직접 합의는 보존해 통합한다. 새 사용자 인증 정책에 따라 DISC-agent-004의 로컬 API 예산 계획은 재정리가 필요하다. VOC는 기존 오류 DTO/재조사 흐름을 유지하면 되며 일반 제품 화면에 provider 설정을 추가하지 않는다. 수용량/429·Windows 장부 exporter 요청은 이 인증 분리와 별개로 추적한다.
- 이 단위는 최신 main 통합 후 전체 publish/세 앱 연결을 실행해 결과를 후속 기록한다. 세 역할/리더 완료를 대신 작성하지 않는다.

## 2026-09-21T20:06:04+09:00 — 외부 입력과 소비자 구현 대기 재확인

- 원격 `0ad4bfd`와 깨끗한 로컬 main이 일치하며 새 변경/논의 답변은 없었다. `team-check` 종료 1, commerce·agent·voc·lead는 모두 IN_PROGRESS다. 실제 실행 원문은 `runtime/submission/agent-20260921/blocking-audit-team-check.*`다.
- 실제 다음 단계의 차단 조건을 재확인했다. `web/`이 없고 VOC TicketController는 analyses 빈 목록을 반환하며 ScenarioRunner는 미구현 안내 후 종료 2를 내는 골격이다. 소비자의 정책 snapshot·분석/화면/runner 인수와 DISC-agent-003/004/005 답변·구현이 아직 공유되지 않았다. 상태 문서만으로 다른 PC의 실행 프로세스가 살아 있다고 주장하지 않는다.
- 이 PC의 세 앱은 `104761f49aec-4f5375dfb8b5`로 실행 중이며 Agent 모델은 DISABLED다. `ngrok config check`는 기본 설정 파일 없음으로 종료 1이다. Agent용 실제 키의 로컬 파일 경로·ngrok 설정 경로·공개 접속 허용 계정을 요청했으며 비밀 값 자체는 요청하지 않았다. 실제 모델 데모 범위/팀 배분 확인도 남아 있다. 관측은 `blocking-audit-runtime.json`, `blocking-audit-ngrok.*`에 보존했다.
- 이전 두 goal 실행에서도 이 실제 모델 설정·소비자 연동 조건이 남아 있었고, 그동안 외부 DB 검증 재사용 방지와 영속 대기 만료를 독립적으로 완료해 공유했다. 이번 재확인에서는 새 제공 구현이나 답변이 없으며 다음 필수 연결은 외부 입력/공유 변경이 필요하다. 같은 성공 검사를 반복하거나 타인의 답변·DONE을 작성해 진행으로 대신하지 않는다.
- 다음 재개 조건: 실제 모델 설정/허용 범위와 팀 배분 확인, 또는 소비자의 새 구현·합의/리더 수정 요청이다. 재개 시 전체 원격 변경을 읽고 필요한 검증부터 이어간다. 서비스/역할의 완료 상태는 IN_PROGRESS이고 실제 모델·공개 URL·팀 완료를 성공으로 표시하지 않는다.

## 2026-09-21T20:00:00+09:00 — 영속 QUEUED 기한과 포화/재시작 검증

- 공유 결과(20:03): `104761f`의 전체 publish 종료 0/100.469초. Python 38개·문서·전체 Gradle check·3앱 재빌드/기동·DB/HTTP/근거 smoke를 통과했다. 기본 JUnit 132개 중 통과 123·실패 0·조건부 건너뜀 9개다. 별도 PostgreSQL/실제 worker 검사는 위 자료와 구분한다. 세 앱 buildId `104761f49aec-4f5375dfb8b5` 일치, 모델 DISABLED. 자료 `queue-deadline-publish.*`, `queue-published-runtime.json`.
- 깨끗한 최신 main의 `team-check` 종료 1을 `queue-unit-team-check.*`에 보존했다. 세 담당자와 리더가 모두 IN_PROGRESS다. 원격 `145f404`의 커머스 외부 DB 재검사 보완 전체를 검토·통합했고 새 소비자 답변은 아직 없다. 실제 모델 범위·비밀 설정과 VOC/web/runner·정책 사본·대기열 수용량 합의/구현은 미완료다.
- 실제 가시 세션 이벤트 1,197건의 중간 사본은 `session/20260921T110155Z/`다. 이메일 32곳을 가렸고 SHA-256 `4c699ba00153f3cbcc7b978df0d22aaddb843db662f9a6a8d36ce2d0f8c6180a`를 manifest에 보존했다. 실제 세션 기록과 요약·내부 추론을 구분한다.
- V6에 queued_deadline_at을 저장하고 새 접수 기본 10분(설정 1초~1시간)의 대기 기한을 적용했다. 이전 기록은 원래 createdAt + 10분으로 이관한다. 같은 키·재시작·설정 변경으로 기한을 갱신하지 않으며 RUNNING 3분과 별도로 계산한다.
- worker가 모든 슬롯 사용 중에도 한 번에 최대 100개 만료 QUEUED를 FAILED/INVESTIGATION_TIMEOUT으로 정리한다. 선점 SELECT/UPDATE도 만료 행을 배제한다. 상태/필수 DTO 필드는 유지했고 접수 수용량/429는 [DISC-agent-005](../discussions/DISC-20260921-agent-005-queue-limits.md)의 직접 합의 후 연결할 미완료 작업이다.
- H2 접수/실행 검사를 통과한 뒤 실제 PostgreSQL 실행 저장소 11개(실패 0·건너뜀 0)를 확인했다. QueueWaitingTest도 별도 PostgreSQL/HTTP/worker에서 통과했다. 실행 슬롯 포화 중 만료 ID `7e7fa782-7786-4730-8649-114b97ae388b`는 모의 모델을 호출하지 않았고 같은 키/GET 재조회로 재실행되지 않았다. 슬롯 해제 뒤 명시적 새 키만 실행돼 총 모의 2회·유료 0회다.
- 네 개의 임시 JVM을 사용한 기존 소유권·복구 검사를 확장해 실제 1초 기한 접수 → 종료 → 새 10분 설정 시작을 확인했다. 만료 ID `20f49f7d-ecec-4e1c-9c0d-78484622d077`의 기한·입력이 보존되고 모델 실행 없이 종료됐다. V4의 기존 종료 기록 8개도 V6 이관 시 입력/응답 digest·상태 보존과 원래 생성 시각 기준 기한을 확인했다. 검사가 시작한 임시 JVM은 종료했다.
- 자료: `runtime/submission/agent-20260921/queue-deadline-h2.*`, `queue-deadline-postgres.*`, `queue-waiting/`, `queue-waiting-postgres.*`, `queue-worker-restart/`, `queue-worker-legacy-{before,after}.json`. 실제 모델·VOC 화면·공개 ngrok 성공으로 해석하지 않는다. 전체 publish 검증 후 공유한다.

## 2026-09-21T19:54:00+09:00 — 요구사항 재검토: 대기열 한도 누락

- `6e240ab`에서 구현 프롬프트·비용 계획·현재 코드를 다시 대조해 QUEUED의 최대 대기와 수용량이 없음을 확인했다. RUNNING 3분·동시성·유료 예산 제한만으로는 이 요구가 충족되지 않는다. 기존 검증 성공을 대기열 완료 근거로 사용하지 않는다.
- [DISC-agent-005](../discussions/DISC-20260921-agent-005-queue-limits.md) P1을 등록했다. 기존 시간 초과 오류로 영속 대기 만료를 독립 구현하고, 새 접수 429/수용량과 VOC 동일 키 재전송·polling은 소비자의 직접 답변 후 연결한다. 김아름의 진행 중인 화면/전달 영역은 중복 편집하지 않는다.

## 2026-09-21T19:50:00+09:00 — 소비자 인계와 실제 검증의 남은 조건

- 외부 DB 검증 재사용 수정은 `620654f`로 전체 publish 종료 0/98.720초 후 공유했다. Python 38개·문서·전체 Gradle check·3앱 재기동·DB/HTTP/SELECT/근거 smoke를 통과했다. 세 앱의 실제 buildId `620654f403b1-4d37d5d26309`가 일치하며 Agent 모델은 DISABLED다. 원문은 `gradle-db-mode-publish.*`, `runtime-after-db-mode-publish.json`이다.
- 깨끗하고 동기화된 `620654f`에서 `./scripts/dev team-check`는 종료 1을 반환했다. commerce·agent·voc·lead 모두 IN_PROGRESS이며 원문은 `team-check-after-agent-source.*`다. 리더가 공유한 `6a01656`의 같은 응답 유실 테스트 독립 성공도 읽었으나 최종 APPROVED로 해석하지 않는다.
- [DISC-agent-001](../discussions/DISC-20260921-agent-001-llm-errors.md)에 `4c20c9a`의 제공자 비용 한도 오류 구분과 `fbb43be`의 클라이언트 응답 유실 복구를 인계했다. 소비자 화면·재조사 동작은 김아름이 검증해야 하므로 AGREED를 유지한다. 원격 `9b1933d`의 commerce/lead 상태와 전체 변경도 읽었다.
- [DISC-agent-004](../discussions/DISC-20260921-agent-004-demo-allocation.md)의 이상효 수락을 확인했고 내 PC 기본 DB의 예산 0행·호출 0행·QUEUED/RUNNING 0행을 읽기 전용으로 기록했다. 이 PC $5·88회·11종·최대 24시간 범위는 사용자 답변 대기이며 김아름의 배분 답변도 필요하다. 실제 키·활성 프로필·예산을 등록하지 않았다.
- ngrok 3.39.11 설치 완료, `ngrok config check`는 설정 파일 없음으로 종료 1이다. 설치/확인 원문은 `ngrok-install.log`, `ngrok-version.log`, `ngrok-config-check.log`다. 공개 web 구현·접근 허용 범위·authtoken 설정이 없어 실제 공개 주소·PC/모바일 흐름 검증은 수행하지 않았다. 과거 다른 담당자의 로그인 이메일을 한재홍의 공개 접속 계정으로 가정하지 않는다.
- 실제 가시 세션 이벤트 1,124건을 `session/20260921T105009Z/visible-events.redacted.jsonl`과 manifest로 중간 내보냈다. 이메일 30곳을 가렸고 provider key/URL 비밀번호 패턴은 0곳이다. 제출 사본 SHA-256은 `2562aa76fd7e1649c0b8e986a42d4cf37b2e1493f958a13f3ef2b9674e5c7c7d`다. 실제 기록·요약·이전 중간 캡처를 구분하며 내부 추론/비공개 지시를 포함하지 않는다.
- 남은 필수 인수는 김아름의 정책 사본 생성기·분석 전달/화면·runner 및 DISC-agent-003의 공백 입력 처리다. 진행 중인 소유 범위를 중복 구현하거나 타인의 답변·DONE을 대신 작성하지 않는다. 실제 모델 품질·공개 연동·세 DONE·리더 APPROVED를 확보하기 전 goal을 완료하지 않는다.

## 2026-09-21T19:49:00+09:00 — 외부 DB 검증 결과 재사용 방지

- 원격 `67ec034`의 commerce 테스트 모드 구분을 읽고 Agent의 같은 경계를 확인했다. 수정 전 같은 ModelCallLedgerTest를 H2 실행 뒤 PostgreSQL 환경변수로 재실행하면 `:agent-app:test UP-TO-DATE`이고 결과 XML도 바뀌지 않았다. 원문은 `runtime/submission/agent-20260921/gradle-db-mode-before/`다. 이전에 공유한 실제 PostgreSQL 결과는 `--rerun-tasks` 실행이므로 이 사례와 구분한다.
- Agent Gradle에 장부·실행 저장·조회 도구·커머스 인수·응답 유실의 다섯 외부 DB 모드 플래그를 입력으로 등록했다. URL·비밀번호는 fingerprint에서 제외한다. 명시적 외부 DB 검사에서는 같은 모드 반복에도 UP-TO-DATE와 빌드 캐시 재사용을 차단한다. DB·근거 내용은 소스와 별도로 바뀔 수 있기 때문이다.
- 수정 후 강제 실행 옵션 없이 H2 → 실제 PostgreSQL → PostgreSQL 반복 → H2 복귀를 수행했다. 네 실행 각각 장부 12개·실패 0·건너뜀 0이며 모든 XML이 새로 생성됐다. 원문·명령·시각·XML은 `gradle-db-mode-after/`에 있다. 유료 모델 호출은 0회다.
- 응답 유실 단위 `fbb43be`의 전체 publish 성공을 확인하고 `9b1933d`까지 양쪽 변경을 보존해 동기화했다. 이번 수정도 전체 publish 후 공유한다. 실제 모델·VOC/web·ngrok·팀 DONE은 아직 미검증이다.

## 2026-09-21 — 비동기 실행·서비스 프롬프트·모의 도구 반복

- 구현: PostgreSQL 세션 잠금으로 단일 작업 소유권을 확보한 뒤 미정산 비용→중단된 RUNNING 복구→QUEUED 접수를 수행한다. 500ms 주기, 기본 동시 조사 2개·전체 3분·모델 8회·도구 24회·인자/보고서 수정 각 1회다. 실측 최적값이 아닌 초기 상한이다.
- 모델 흐름: 버전 리소스 `investigation-system-v1.md`와 SHA-256을 모델 port에 전달한다. 모델 도구 요청은 허용 이름/인자 검증 후 서버 도구로 실행하고, 원문·근거 ID 커밋 후에만 후속 모델에 전달한다. 최종 보고서를 검증·저장하며 이미 종료된 조사에는 늦은 결과를 반영하지 않는다.
- 기본 실행: 모델은 DISABLED이고 접수 뒤 LLM_CONFIGURATION_ERROR로 실패를 저장한다. API 키 존재만으로 모델을 생성하지 않는다. 실제 OpenAI 어댑터·업무 도구는 미연결이고 businessReady=false다.
- 모의 검증: InvestigationRunnerTest 8개 통과. 실제 HTTP의 GET/같은 키 재전송·근거 조회가 모의 모델 호출을 늘리지 않았고, 가짜 근거·알 수 없는 도구·반복 한도·시간 초과·필수 도구 실패·세 모델 오류 매핑을 확인했다. 모의 자료는 실제 VOC 근거/모델 품질이 아니다.
- 실제 프로세스: `check_worker.py`가 PostgreSQL 17.6의 전용 `jdd_agent_worker_test`와 임시 JVM 세 개를 사용했다. 합성 RUNNING 기록의 INTERRUPTED/근거 보존, QUEUED 재개, 같은 키 ID, 두 JVM의 배타적 소유권과 소유자 종료 후 인계를 검증했다. 조사 ID는 `worker-postgres/report.json`, 원문은 `worker-postgres/*.log`, 실행 명령은 `worker-postgres-check.*`에 있다. 테스트가 시작한 JVM은 종료했다.
- 소비자 인계: [DISC-20260921-agent-001](../discussions/DISC-20260921-agent-001-llm-errors.md)의 실제 세 역할 합의를 읽고 계약 표와 오류 매핑을 반영했다. Agent 측 합성 HTTP 검증만 완료했고 VOC 화면·실제 모델·ngrok는 미검증이다. AGREED를 RESOLVED로 바꾸지 않았다.
- 공유 전 검증 자료: `runtime/submission/agent-20260921/runner-tests.*`, `worker-postgres-check.*`. 모든 개발 검증에서 실제 모델 호출 0회, DONE 미작성이다.

## 2026-09-21 — 실제 DB 접수 공유와 영속 실행 상태

- 접수 공유: `b2b46ef`를 분리한 main 검증 clone에서 `scripts/dev publish`로 공유했다. 협업 자동 검사·문서·전체 Gradle check·세 앱 재빌드와 DB/HTTP/SELECT 권한/근거 볼륨 smoke를 통과했다. businessReady=false와 실제 모델 미검증을 유지한다.
- 실제 PostgreSQL 접수: 합성 HTTP 동시 요청 8개가 같은 조사 `1c83804b-dc25-480f-b8ce-a8191e793e2a`를 반환했다. 입력 충돌·타입 오류·없는 근거를 확인했고 Agent만 재시작한 뒤 같은 ID·createdAt을 재조회했다. 원문 자료는 무시 경로 `runtime/submission/agent-20260921/intake-postgres.json`, `intake-postgres-http.log`, `intake-postgres-recovery.log`, `intake-publish.log`에 있다.
- 실행 상태 구현: 신규 V3 마이그레이션으로 작업 상태·선점 토큰·종료 시한을 저장한다. 중복 선점 차단, 도구 시작·근거 원문/요약·종료의 원자적 저장, 최종 상태·검증 보고서 저장, 중단/시간 초과 시 근거 보존, 종료 후 늦은 응답 차단을 제공한다. 백그라운드 스케줄러와 모델은 아직 연결하지 않았다.
- 보고서 검사: 같은 조사에 저장된 근거만 참조하고 사실 근거·후보 한계·사람의 조치·확인한 소스 경로·허용된 추가 입력 필드를 확인한다. 형식 검증은 원인 정확성 평가를 대신하지 않는다.
- 검증: core 보고서 테스트 6개, 기존 app 9개와 실행 상태 7개를 H2에서 통과했다. 실행 상태 7개는 실제 PostgreSQL 17.6의 전용 `jdd_agent_execution_test`에서도 통과했고 3개 Flyway 마이그레이션 적용을 확인했다. 합성 관측을 사용했으며 실제 커머스 데이터·모델 결과가 아니다. 명령·종료 코드·원문은 `execution-tests.*`, `execution-postgres-tests.*`에 보존한다.
- 협업: 김아름의 JDD-VOC-003과 기존 오류 매핑 요청을 구체화해 [DISC-20260921-agent-001](../discussions/DISC-20260921-agent-001-llm-errors.md) P1을 공유했다. 제공자 본인 제안 외 타인의 합의·구현·검증은 미확인이다. 실제 모델·ngrok 검증, 완료 선언은 수행하지 않았다.

## 2026-09-21 — 유료 호출 차단과 영속 비용 장부

- 독립 구현: 기본 금지인 PaidModelGate에 데모 모드·유료 허용·승인 범위·유효기간·허용 모델 검사를 추가했다. 실제 OpenAI 클라이언트와 실행기 환경 연결은 다음 범위다. 실제 모델 호출은 0회다.
- 비용: 단일 누적 예산·조사당 호출 수·동시 예약을 DB 행 잠금으로 검사한다. 예약→전송→확정/미확정/미전송 취소를 구분하고 재시작·새 조사로 기존 예산을 초기화하지 않는다. 미확정 usage가 있으면 새 호출을 차단한다.
- 계측: 호출별 input/output/cache read/cache write/reasoning의 null을 보존한다. 일반·캐시 읽기·캐시 쓰기 입력을 분리하고 output에 포함된 reasoning을 이중 합산하지 않는다. 최신 공식 캐시/가격 문서를 확인했으며 코드에 실제 모델 단가를 고정하지 않았다.
- 검증: 합성 가격 계산 5개, H2 비용 장부 초기 9개를 통과했다. 승인 만료 시 미전송 취소 사례를 보강해 실제 PostgreSQL 17.6 전용 DB에서 비용 장부·호출 게이트 10개를 통과했다. $0.01 예산에 $0.0049 최대 비용을 8개 동시 예약해 2개만 허용했고, 응답 유실/재시작·중복 정산·늦은 사용량·실제 비용 초과·새 조사·설정 변경·호출 한도·유료 금지의 동작을 검증했다.
- 기록: `runtime/submission/agent-20260921/budget-tests.*`, `budget-postgres-tests.*`, `budget-postgres-result.xml`은 실제 명령·출력·테스트 결과다. 외부 API·계정 잔액이나 실제 조사 품질을 검증한 자료가 아니다.
- 협업: 원격 `b3f90ab`의 commerce/lead P1 수락을 읽었다. 김아름의 오류 매핑 답변은 아직 없으며 계약에 외부 오류 코드를 먼저 추가하지 않았다. commerce의 새 업무 구현은 해당 상태 기록상 PostgreSQL 검증 중이고 아직 공유 전이다.

## 최신 사용자 제한 — OpenAI 데모 전용 키와 $30 기준

최신 지시: 사용자는 데모 비용에 GS 해커톤 OpenAI API 프로모션 크레딧을 사용하기로 변경했다. 프로모션 코드는 API 인증 키를 대체하지 않는다.
사용자가 전달한 Applied promotions / Amount / Date applied 표기에 따라 $50.00 크레딧이 2026년 9월 21일에 적용된 것으로 기록한다. 해당 날짜는 적용일이며 만료일이 아니다.
실제 잔액·만료일·적용 조직/프로젝트와 기존 키의 연계는 미확인이다. 이 세션은 계정 조회·코드 등록·결제 설정 변경을 하지 않았다.
정책과 구현 프롬프트에 사용자 제공 적용 금액·적용일을 반영했으며, $50 크레딧을 현재 잔액이나 예산 확대·개발 사용 허가로 해석하지 않는다.
개발·CI·자동 반복 평가 사용 금지와 $30 초과 예상 시 별도 처리 조건을 유지한다. 이번 확인 작업에서도 키·코드 원문을 파일에 저장하거나 모델 API 호출에 사용하지 않았다.
프로모션 인계 변경은 `python3 scripts/check_docs.py`(로컬 문서 34개·링크 258개·JSON 예제 8개)와 `git diff --check`를 통과했다. 공유 검증에는 키·프로모션 원문이 없는 별도 main clone을 사용한다.
[데모 키·비용·로컬 실행 규칙](../planning/demo-llm-policy.md)을 AGENTS와 구현 프롬프트에 연결했다. 유료 개발 호출과 $30 초과 예상 사용은 별도 처리한다.
데모 방식은 로컬 web·세 앱·DB + ngrok로 반영했다. ngrok는 web 외부 접속에만 사용하고 로컬 Agent는 OpenAI API를 직접 호출한다.
모델명·계정의 실제 접근·예산 기간/공유 범위·ngrok 계정/공개 URL은 미확정/미검증이다. 계정 전체 사용액·한도는 조회하지 않았다.
유료 호출 게이트·영속 비용 예약은 위 단위에서 합성 검증했으며 런타임 실행기·OpenAI 연결은 아직 미완료다. 실제 모델 검증은 미완료로 유지한다.
이번 변경은 문서·인계 기준이며 키를 사용한 모델 API 호출은 0회다. 이전 기록의 키 제공 예정·제공자 미정 상태는 이 내용으로 갱신한다.
문서 검증은 `python3 scripts/check_docs.py`(로컬 30개 문서·193개 링크·8개 JSON 예제)와 `git diff --cached --check`를 통과했다. 비밀 값이 staged 내용에 포함되지 않았는지 패턴 검사도 수행했다. 공유 검증은 키가 없는 분리한 main clone에서 publish로 수행한다.

## 2026-09-21 — ngrok 로컬 데모 구성 반영

- 사용자 요청: 로컬에서 처리하고 ngrok로 연결하는 방향으로 관련 문서를 수정한다. OpenAI 프로모션 API를 함께 사용할 수 있도록 로컬 Agent의 직접 호출을 유지한다.
- 반영 범위: [실행 절차](../ngrok-local-demo.md), 데모 정책·Agent 프롬프트·비용/검토/연동 문서, 프론트·아키텍처·로컬 실행·VOC goal·시연 문서. Vercel 기본안을 로컬 web의 단일 ngrok 진입점으로 바꿨다. 기존 구현 초안·v1 DTO·Compose 서비스 주소는 변경하지 않았다.
- AGENT-NGROK-001 / voc 요청: web의 build/start·127.0.0.1:3000 수신, 고정 API 중계, 화면/API 접근 제어, ngrok 인증/정책과 공개 URL 검증. web 서버의 VOC_API_BASE_URL·COMMERCE_API_BASE_URL은 호스트 포트, VOC의 AGENT_BASE_URL·COMMERCE_BASE_URL은 기존 Compose 주소를 사용한다.
- AGENT-NGROK-002 / agent 요청: OpenAI 키를 실제 Agent 실행 환경에만 주입하고 ngrok URL을 모델 주소로 사용하지 않는다. 터널 중단·재연결 뒤에도 조사 지속·같은 ID 조회·근거 반환·조회 시 모델 재호출 없음·비용 예약을 검증한다.
- commerce / lead 인계: 로컬 근거 볼륨·SELECT 전용 권한은 유지한다. 공개 URL에서 실제 티켓→조사→근거를 별도로 확인하고 기존 MVP·팀 완료 기준을 유지한다.
- 관측·제약: `web/`이 없고 `command -v ngrok`에서 CLI를 찾지 못했다. 공개 터널을 열거나 ngrok 계정/토큰을 설정하지 않았으며 OpenAI 호출도 0회다. 문서의 명령은 web·접근 제어·ngrok 준비 후 실행하는 절차다.
- 공유·검증: `python3 scripts/check_docs.py` 통과(로컬 문서 36개·링크 279개·JSON 예제 8개), `git diff --check` 통과. 전체 publish 검증은 API 키 없는 별도 main clone에서 수행한다. 외부 URL·실제 모델 검증 또는 상대 세션의 수신·반영 완료를 뜻하지 않는다.

## 2026-09-21 — 서비스 내부 AI 구현 범위 보완 및 세션 인계

- 요청 대상: 현재 한재홍의 Agent 코드를 구현하는 세션. 기존 goal을 유지하고 [보완 지시](../goals/README.md)를 적용한다.
- 전달 사항: 접수 API 이후 실제 서비스 모델이 도구를 선택·호출하고, 커머스 관측을 근거로 저장한 뒤 검증된 보고서를 VOC로 반환하는 전체 흐름이 필수다. 서비스 시스템 프롬프트는 버전 리소스로 작성해 실제 LLM 요청에 연결한다.
- 분담: 이 보완 세션은 AGENTS·goal·역할·프롬프트·검토 기록·자기 상태 문서만 수정한다. 진행 중인 `agent-app/`, `agent-core/`, `agent-infra/` 구현은 해당 세션이 이어간다.
- 관측 근거: 로컬 `agent-app/README.md`에는 현재 접수 결과가 QUEUED이며 실행기·모델·도구가 아직 연결되지 않았다고 명시되어 있었다. 초안·합성 근거 테스트를 실제 AI 조사 완료로 취급하지 않는다.
- 접수 API 다음 산출물: 영속 작업 선점·복구 → LLM API·서비스 프롬프트 적용 → 허용된 8개 도구 → 근거 저장·후속 모델 호출 → 보고서 검증·상태 확정 → 기존 GET 결과·근거 반환.
- 필요한 연동: commerce 담당자는 계약의 SELECT 테이블·로그·buildId 소스·정상 정책을 제공하고, VOC 담당자는 실제 조사 상태·보고서·근거 조회를 연결한다. 기존 v1 필드·상태는 변경하지 않았다.
- 별도 확인할 계약: 모델 미구성·인증·호출 제한 등 내부 분류를 외부 ApiError·retryable에 어떻게 매핑할지 정해 VOC 소비자와 함께 검증한다.
- 의존성 없이 진행할 작업: 실행기·설정 검증·조회 도구·저장·보고서 검증·실패 복구. 모델 인증과 실제 상대 구현을 사용한 평가는 준비되기 전까지 미검증으로 유지한다.
- 검토 규모: 총괄 1개와 읽기 전용 전문 검토 에이전트 3개가 실행·계약·협업 경로를 검토했다. 1,000개 이상 사용 요청은 충족하지 못했으며 서비스 런타임의 에이전트 수 요구로 전환하지 않았다.
- 검증·공유: `python3 scripts/check_docs.py`와 `git diff --check` 통과. 분리한 main clone·Compose 프로젝트·포트에서 Java 21을 지정한 `scripts/dev publish` 통과: 협업 테스트 21개, 공유 커밋 기준 문서 27개·링크 171개·JSON 예제 8개, 전체 Gradle check, PostgreSQL·세 앱의 DB/HTTP/SELECT 전용 권한·근거 볼륨 smoke 확인. 원격 품질 기준 `34cdca3`를 통합·재검증해 `3a8068e`로 공유했다. buildId는 `3a8068ef77c7-29e903424201`이다.
- 환경 실패·조치: 최초 publish는 기본 Java 26 환경에서 Java 21 toolchain을 찾지 못해 실패했다. 기존 `/Users/jaehonghan/.local/share/jdd/jdk-21/Contents/Home`을 JAVA_HOME으로 지정한 검증 프로세스에서 해결했다. 서비스 AI 구현·실제 모델 평가는 이 보완 세션에서 수행하지 않았다.
- 다른 세션의 접수: 미확인. 현재 도구에 기존 독립 세션으로 직접 메시지를 전달하는 경로가 없어 공유 파일에 남겼다. 구현 세션은 읽은 뒤 수신·반영 범위·다음 산출물을 이 파일에 기록한다.

## 2026-09-21 — commerce goal 연동·LLM 비용 설계 검토

- 이번 작업 범위: 전달받은 이상효 goal을 한재홍의 Agent 책임·김아름의 VOC 구현과 대조해 문서·구현 프롬프트만 보완했다. 서비스 구현·기동·실제 LLM 호출·role-done은 실행하지 않았다. 기존 미커밋 구현 초안과 다른 세션의 문서 보완을 보존했다.
- 결과물: [통합 영향 분석](../planning/agent-integration-risk-review.md), [LLM 효율·비용 계획](../planning/llm-efficiency-plan.md), [구현 프롬프트](../prompts/implement-voc-investigation-agent.md)의 모델 비용·연동 부작용·DONE 절차 보완.
- 이상효에게 필요한 사항: 초기화 범위·실행별 식별자, 실제 DDL 소유자/SELECT 권한, 커밋 후 로그 지연과 rotation, buildId·정책 버전, 주문 생성 전 실패의 조회 식별자. 현재 발생한 장애가 아닌 예방 설계 항목이다.
- 김아름에게 필요한 사항: 같은 키 재전송과 새 조사 구분, GET/polling의 LLM 재호출 금지, 시나리오별 조사 종료·근거 저장 후 초기화, 예산·인증 오류 기본 표시, 대기열/polling 정책, 실제 행동을 검증하는 runner, 배포 URL부터 근거까지 연결 검증.
- 추가 계약 후보: LLM 설정/일시 장애/예산 오류 코드와 retryable, runner의 선택적 modelsUsed·usageSummary·verificationProfileId. 기존 v1 계약을 이번 문서 작업으로 변경하지 않았다.
- 계측 주의: 입력·캐시 읽기/쓰기·출력·reasoning을 실제 호출별로 집계하고 Spring AI 누적 usage를 중복 합산하지 않는다. 응답 유실의 비용은 0이 아닌 미확정으로 기록한다.
- 완료 절차 영향: docs/status 밖의 문서도 완료 fingerprint에 포함된다. 최종 검증 전 변경을 모아 STALE와 유료 재검증 반복을 줄이며, 세 담당자 각자의 live 검증을 생략하지 않는다.
- 남은 입력·검증(후속 정책 반영): OpenAI 데모 전용 키 선택과 $30 초과 예상 시 별도 처리 기준은 확정됐다. 모델명·실제 접근·예산 기간/공유 범위·API 한도·배포 주소는 미정이다. 가격·토큰 계산은 공식 자료의 예시이며 성능·비용 실측은 미수행이다.
- 원격 확인: scripts/dev status에서 origin/main `34cdca3`, 세 역할 IN_PROGRESS를 확인했다. 추가 품질 기준의 반복 재현과 실제 모델 평가를 구분해 분석에 반영했다. 편집 중인 작업 트리에 pull하지 않았다.
- 검증: `python3 scripts/check_docs.py` 통과(29개 Markdown·189개 로컬 링크·8개 JSON 예제), `git diff --check` 통과. 서비스 AI의 검증 결과로 간주하지 않는다.

## 2026-09-21 — 문서 정합성 확정과 중단 지점 인계

- 요청 범위: ① 최신 데모 정책과 비용 계획 일치 ② 최종 구현 프롬프트·인계 기록 확정 ③ 문서만 검증·커밋·공유. 이번 세션에서 서비스 기능 구현·실제 모델 호출·DONE/리더 승인 작성은 수행하지 않는다.
- 1단계 반영: 비용 계획의 제공자 미정·개발 중 live 평가 문구를 OpenAI 데모 전용 정책에 맞췄다. $30 기준, 영속·원자적 비용 예약, 미확정 비용 유지, 팀 공유 예산과 재조사 우회 방지를 명시했다.
- 2단계 반영: 구현 프롬프트 §13에 재개 순서와 이상효/김아름/개발리더 인계를 추가했다. 개발·CI는 유료 호출 금지, 실제 모델 평가는 승인된 데모 범위에서만 수행하고 미검증을 완료로 바꾸지 않는다.
- 원격 기준: `c9ecf7a`까지 확인했다. `e6280e5`의 세 DONE 이후 이상효의 독립 코드 검토·새 verify-mvp·APPROVED를 반영했다. 세 역할 33개 case에 리더 11개를 더한 최소 44개 case 실행을 비용 계획에 포함했으며 모델 호출 수와 구분한다.
- 구현 재개 첫 작업: 실제 코드와 접수 초안의 계약·저장·동시 요청·근거 소속을 검증한다. 이어 영속 선점·재시작 복구·유료 호출 차단·비용 예약을 모의 모델로 연결한다. 접수 후 QUEUED 상태를 실제 AI 조사 성공으로 취급하지 않는다.
- 상대 의존성: 이상효에게 DDL·SELECT·재현/초기화·로그·소스 버전을, 김아름에게 멱등 요청·오류/예산 표시·순차 runner·근거 화면을 요청하는 인계 항목을 남겼다. 외부 메시지는 보내지 않았고 수신·구현 완료는 미확인이다.
- 공유 대상: 구현 프롬프트, 통합 영향 분석, LLM 비용 계획, 프롬프트 검토 기록, 이 Agent 상태 문서의 5개 파일. 기존 미커밋 구현 파일은 공유 대상에서 제외하고 내용 해시로 보존 여부를 확인한다.
- 공유 검증 방식: API 키 없는 별도 main clone·고유 Compose 프로젝트·포트에서 최신 원격과 문서를 통합해 `scripts/dev publish`를 실행한다. 원래 작업 공간의 실행 환경과 구현 초안은 유지한다.
- 검증·공유 결과: 원격 `0cc5824`의 commerce/리더 프롬프트도 보존해 통합하고 `50815c7`로 publish했다. 협업 테스트 31개·문서 34개/링크 247개/JSON 8개·전체 Gradle check·PostgreSQL/3개 앱의 DB·HTTP·SELECT 전용 권한·근거 볼륨 smoke 통과. buildId는 `50815c73ffe6-e1212422fafe`다. 첫 publish는 검증 후 원격 변경으로 보류됐고 충돌 해결·재검증 후 공유했다. 실제 서비스 AI·시나리오 검증은 미수행이다.

## 2026-09-21 — 최종 검수와 사용자 확정 대기

- 사용자 후속 지시: 1~3단계를 마친 뒤 최종 검수하고 구현에 문제가 없는지 확인한 후 사용자가 프롬프트를 확정한다. 이번 세션에서 서비스 구현을 시작하지 않는다.
- 검수 결과: [검토 기록 §7](../planning/han-jaehong-prompt-review.md)에 역할·v1 계약·commerce 근거·VOC 연동·데모 키/비용·리더 완료 조건을 대조한 결과를 남겼다. 검토 범위에서 구현을 막는 문서 충돌은 발견하지 않았다. 실제 서비스 구현·모델 호환성의 통과 판정은 아니다.
- 남은 실행 조건: OpenAI 모델명/주소/실제 접근, $30 기준의 기간·공유 범위와 호출 계획, 로컬 연결 방식·배포 주소, 두 솔루션의 실제 전달물. 모의 모델을 이용한 독립 개발과 실제 데모 선행 조건을 구분했다.
- 프롬프트 상태: 최종 검수용 후보. 사용자 확정 대기임을 문서 첫머리에 표시했다. 별도로 승인된 다른 구현 세션의 권한을 새로 부여하거나 철회하지 않는다.
- 기존 작업 보호: 구현 초안 15개 파일의 내용 해시를 보존했고 이번 공유에는 포함하지 않았다. 역할 DONE·리더 승인 기록도 작성하지 않았다.

## 2026-09-21 — 한재홍 goal 시작문 작성

- 사용자 요청: 이상효의 commerce·개발리더 goal 예시를 바탕으로 한재홍의 구현 goal 시작문을 만든다. 이번 작업은 시작문 작성이며 구현 goal을 실제로 생성·실행하지 않는다.
- 결과물: [goal 시작 프롬프트](../prompts/goal-han-jaehong-agent.md). Agent 책임·8개 도구·영속 실행·근거·보고서, 이상효/김아름 연동, 유료 호출 제한·모델별 비용, 실제 검증·리더 승인까지의 종료 조건을 포함했다.
- 정책 정합성: 사용자 제공 $50.00 프로모션 적용 금액과 2026-09-21 적용일을 비용 계획·검수 기록에도 반영했다. 잔액·만료일·키 연결은 미확인이며 데모 전용·$30 기준을 유지한다.
- 문서 검토: 역할·상세 구현 프롬프트·협업 규칙·팀 완료 기준과 대조했다. 커머스 반복 재현과 실제 모델 평가를 구분하고 기존 goal이 있으면 중복 생성하지 않도록 인계했다.
- 정적 검증: `python3 scripts/check_docs.py` 통과(35개 Markdown·262개 로컬 링크·8개 JSON 예제), `git diff --check` 통과. 서비스 업무·모델 검증 결과로 간주하지 않는다.
- 구현 상태: 기존 초안은 보존한다. 이번 문서 작성에서 서비스 LLM 호출·역할 DONE·리더 APPROVED를 수행하지 않는다.

## 2026-09-21 — ngrok 구성을 반영한 goal 시작문 갱신

- 사용자 요청: ngrok 공개 주소 → 로컬 web → 로컬 VOC·Agent·commerce 구조와 로컬 Agent의 OpenAI 직접 호출을 goal에 반영한다.
- 기준: 원격 `d69e0d4`의 로컬 데모 문서 변경 전체를 확인했다. [상세 구현 프롬프트](../prompts/implement-voc-investigation-agent.md)·[데모 절차](../ngrok-local-demo.md)·비용 정책의 기존 변경을 보존하고 [goal 시작문](../prompts/goal-han-jaehong-agent.md)과 [Agent goal](../goals/agent.md)을 맞췄다.
- 인계: AGENT-NGROK-001의 web 중계·화면/API 접근 제어·주소 구분은 김아름과 연동하고, AGENT-NGROK-002의 모델 설정·터널 중단 중 조사 지속·재연결 후 기존 ID 조회·모델 재호출 방지는 한재홍의 검증에 포함했다. 상대의 접수·구현·실제 외부 검증은 아직 확인하지 않았다.
- 정적 검증: `python3 scripts/check_docs.py` 통과(36개 Markdown·285개 로컬 링크·8개 JSON 예제), `git diff --check` 통과. 공유 검증은 키가 없는 별도 main clone에서 수행하며 공개 ngrok 경로·실제 모델 검증으로 간주하지 않는다.
- 공유 검증 결과: `e138b4e`에서 `scripts/dev publish` 통과. 협업 테스트 31개·문서 38개/링크 296개/JSON 예제 8개·전체 Gradle check·PostgreSQL과 세 앱의 DB/HTTP/SELECT 전용 권한·근거 볼륨 smoke를 확인했다. 공유 중 추가된 `0196fc4`·`5b11710`의 논의 문서와 협업 문서 즉시 공유 규칙도 통합했다. 현재 논의 목록은 등록 0건이며 기존 연동 요청의 해소를 뜻하지 않는다. 이번 결과 기록만의 후속 공유는 최신 협업 문서 공유 절차를 적용한다.
- 범위: 문서·goal 시작문만 수정한다. 기존 구현 초안을 보존하고 공개 터널·실제 OpenAI 호출·구현 goal은 이번 작성 세션에서 실행하지 않는다. 프로모션 적용 금액·데모 전용·$30 기준을 유지한다.

## 2026-09-21 — Agent 구현 goal 착수와 조사 접수

- 사용자 지시: 한재홍의 실제 구현 goal을 접수했다. ngrok → 로컬 web → VOC → Agent, 로컬 Agent의 OpenAI 직접 호출, commerce 읽기 전용 근거, 실제 팀 검증·리더 승인까지 진행한다. 이 기록은 역할 DONE이 아니다.
- 시작 확인: `0da095c`의 코드·기존 미커밋 초안을 읽었고 원격 `134bb39`의 commerce/lead 시작, `f466d37`의 VOC 시작·AGENT-NGROK-001 수용을 확인했다. 논의 목록은 0건이며 모델 오류 매핑은 김아름과 협의할 항목이다.
- 첫 구현 단위: 기존 조사 접수·스냅샷·동시 중복 방지·충돌·이전 조사 연결·저장 근거 조회 초안을 채택한다. 실제 실행기는 아직 없으며 접수는 QUEUED에 머문다. businessReady=false를 유지한다.
- 실패와 수정: 최초 HTTP/H2 검증에서 JSON 숫자 orderId가 문자열로 바뀌어 202를 반환했다. 별도 문자열 coercion 제한으로 수정했다. 추가 경계 검증에서 숫자 occurredAt도 접수되어 ISO 문자열 전용 시각 역직렬화로 수정했다. 두 실패를 확인한 뒤 회귀 테스트를 통과했다.
- 현재 검증: Java 21에서 `./gradlew :agent-app:test --no-daemon` 통과(앱 기본 1개·조사 API 8개). 실 HTTP·H2에서 동시 8건·입력 정규화·충돌·근거 소속·잘못된 타입을 확인했다. PostgreSQL·재시작은 다음 실행 결과로 구분해 기록한다.
- 실제 산출물: 로컬 `runtime/submission/agent-20260921/`에 실행 명령·원시 터미널 결과와 시각/종료코드를 보존한다. 최초 실패는 `/tmp/jdd-agent-intake-initial-test.log`·입력 타입 진단 로그에 남겼다. 원본 개발 대화 로그 내보내기는 아직 확보하지 않았고 요약을 원본으로 표시하지 않는다.
- 연동 제공: 김아름은 v1 POST/GET/근거 조회의 접수·오류·재전송 경로를 연결할 수 있다. 실제 조사·보고서는 준비 중이다. 이상효의 DDL·로그·소스 전달이 준비되기 전에는 계약 기반 독립 도구 구현을 계속한다.
- 모델 사용: 모든 개발 검증에서 모델 호출 0회. OpenAI 공식 도구 호출 흐름과 Spring Boot/Jackson 입력 설정을 확인했으며 실제 API 키를 사용하지 않았다.

## 2026-09-21 — 실제 읽기 전용 근거 도구

- 비동기 실행기 공유 `71d74aa`: 전체 check·3앱 재빌드/재기동·실제 PostgreSQL/HTTP smoke 후 일반 push 종료 0. 원문 `runtime/submission/agent-20260921/runner-publish-merged.log`.
- 여덟 조회 도구와 엄격한 인자 스키마를 실행기에 연결했다. 별도 SELECT 계정·테이블 권한 확인·동일 스냅샷·바인딩 SQL·원문 제한과 전체 재고 집계, buildId/manifest/파일 해시, 허용 소스, JSONL 원문/줄 번호/중복·부분 결과를 구현했다.
- 파일/인자 9개 및 모의 실행기 8개, 실제 PostgreSQL 17.6 전용 DB의 조회/권한/스냅샷 5개가 통과했다. 실제 UPDATE/DDL 거절과 잘못 지정한 쓰기 계정의 차단을 확인했다. 원문 `evidence-tools-postgres.log`; 앞선 테스트 코드 컴파일 오류는 `evidence-file-tests.log`에 보존하고 타입 단언을 수정했다.
- 제공자 `878f602`, `920f51f`, `9a4b688`의 초기화 권한 수정·쿠폰 구현·HTTP 오류 구분과 검증 기록 전체를 읽었다. 이 PC에서 커머스 HTTP로 재현한 데이터에 도구를 연결하는 소비자 검증은 다음 단위다. 제공자 PC의 반복 결과를 이 PC의 실행으로 쓰지 않는다.
- 정책의 현재 계약은 manifest에 버전만 연결한다. Agent는 정책 조회 시 실제 원문·해시와 그 한계를 저장한다. 과거 정책 사본의 보관을 주장하지 않는다.
- 모델 호출은 0회다. 실제 OpenAI 어댑터·허용된 실제 모델 검증·VOC/웹/외부 ngrok 통합은 계속 진행하며 DONE을 보류한다.

## 2026-09-21 — 이 PC의 실제 커머스 인수 검증

- 조회 도구 `f715885` 전체 publish 종료 0: 전체 check·3앱 재빌드/재기동·PostgreSQL/HTTP smoke 후 main에 공유했다. 원문 `runtime/submission/agent-20260921/evidence-tools-publish.log`.
- buildId `f715885049d7-a99bb5a5ec05`의 실제 Compose에서 VOC-07 20/20회·독립 트랜잭션/연결·재고 -1·성공 주문 2건 및 정상·충분 재고·시간 초과 롤백·복구를 확인했다. VOC-02·03 각 3/3회와 정상 대조도 실제 HTTP/DB/로그/소스에서 통과했다. 로그 `commerce-handoff-reproductions.log`.
- 같은 데이터의 8개 도구 결과 25건을 전용 Agent PostgreSQL에 저장하고, 모든 근거 원문 GET 200과 같은 키 재전송의 동일 ID를 확인했다. 조사 `6b2ab6d9-c22d-4302-8ea3-d1c1af9f1a84`, 결과 `commerce-handoff-result.json`은 모의 모델 2회·OpenAI 호출 0회임을 명시한다. 최종 원인 분석 품질이나 VOC UI 완료를 뜻하지 않는다.
- 재현 가능한 `CommerceHandoffTest`·`agent-app/scripts/check_commerce_handoff.py`를 제공한다. 평가 정답·fixtures 내용은 모델 입력에 포함하지 않는다. 저장·재조회 완료까지 재현 데이터를 보존했다.
- [DISC-20260921-commerce-001](../discussions/DISC-20260921-commerce-001-inventory-evidence.md)에 직접 검증 결과를 답변했다. COMMERCE-002 조회 연결을 확인했으며 VOC 직접 답변·runner 확인이 남아 논의는 미해소다.

## 2026-09-21 — 장문 요금과 최악 비용 예약

- `595034a`의 실제 커머스 근거 인수 단위도 전체 publish·세 앱 재기동/smoke 후 공유됐다. `check_commerce_handoff.py` 명령을 동일 보존 데이터에 다시 실행한 결과도 종료 0이며 원문은 `commerce-handoff-rechecked/`에 있다.
- [DISC-20260921-agent-002](../discussions/DISC-20260921-agent-002-policy-snapshot.md)에 build별 정책 사본·해시 보관을 제안했다(`0166c7c`, 목록 형식 수정 `92dc81a`). 다른 담당자의 답변·공통 생성기 담당 확정을 기다리는 동안 모델 연동을 진행한다.
- 모델 가격에 입력 길이 경계와 입력/출력 배수를 추가했다. 경계를 넘으면 일반 입력·캐시 읽기·쓰기와 출력 전체에 해당 배수를 적용한다. 예약은 최대 입력 분류와 출력의 최악 비용을 사용하고 실제 정산은 관측 usage 길이로 요금을 선택한다. 기존 단일 구간 가격과 저장 JSON은 호환한다.
- `:agent-core:test`의 가격 7개 및 기존 core 검증, `ModelCallLedgerTest`의 모의 장부 10개가 통과했다. 합성 가격으로 경계 바로 아래/같음/초과와 캐시·reasoning 중복 제외를 확인했으며 실제 모델 비용 측정은 아니다. 원문 `runtime/submission/agent-20260921/pricing-context-tests.log`.
- OpenAI 연동에서는 추정 tokenizer 값으로 최대 비용을 보장했다고 주장하지 않고, 공식 모델 context 한도와 제한한 출력 상한을 예약하는 방법을 적용한다. 이는 초기 보수적 예약 설계이며 실제 후보 품질·비용/지연 비교는 아직 미수행이다.

## 2026-09-21 — OpenAI 전송과 실제 응답 단위 계측

- 장문 예약 단위는 `0d65d77`로 전체 check·3앱 재기동·PostgreSQL/HTTP smoke 후 공유했다. 원문 `pricing-context-publish.log`. 원격 `c2bdb4e`까지 VOC 티켓·정밀도 수정·논의 답변 전체를 검토했다.
- Spring AI 2.0.1 ChatModel에 시스템 프롬프트·엄격한 도구·보고서 스키마·저장 근거 이력을 연결했다. HTTP 전송 직전 영속 예약/전송 표시, 실제 응답의 모델·tier·입력/출력/캐시/쓰기/reasoning과 비용 정산을 구현했다. 요청 추정 토큰과 실제 usage를 구분한다.
- 숨은 재시도를 막기 위해 SDK retries=0과 별도 OkHttp 단일 전송을 적용했다. Spring AI builder가 직접 OkHttp builder라는 최초 가정은 컴파일에서 실패했고 실제 2.0.1 소스를 읽어 terminal interceptor 방식으로 수정했다. 최초 실패 `openai-transport-compile.log`/`openai-transport-tests.log`도 보존한다.
- 로컬 HTTP 모의 전송 14개와 H2 장부 10개 통과(`openai-transport-final.log`). 인증·429·시간 초과·리다이렉트·본문 상한·불일치 usage·미등록 모델·변경 tier·형식 실패를 확인했다. 비용 장부 10개를 실제 별도 PostgreSQL에서도 다시 통과했다(`openai-ledger-postgres.log`). 모의 가격·응답 검증이며 실제 OpenAI 호출은 0회다.
- [DISC-20260921-voc-001](../discussions/DISC-20260921-voc-001-runner-metadata.md)의 현재 필수 완료 메타데이터 유지에 수락 답변을 `1b5adc8`로 공유했다. [정책 사본 제안](../discussions/DISC-20260921-agent-002-policy-snapshot.md)은 김아름이 공통 생성기 구현을 맡았고 Agent 소비자를 다음 단위에서 연결한다.
- 실행 환경 활성화는 다음 단위다. 기본 DISABLED·businessReady=false와 DONE 보류를 유지한다. 실제 모델·웹·ngrok·전체 VOC 품질은 미검증이다.

## 2026-09-21 — 명시적 데모 설정과 누적 호출 한도

- OpenAI 전송 단위 `07aeadd`를 전체 check·3앱 재빌드/재기동·PostgreSQL/HTTP smoke 후 공유했다. 최초 publish는 Docker Hub DNS 조회 실패로 중단됐고 원문 `openai-transport-publish.log`를 남겼다. DNS 복구 뒤 최신 commerce 변경을 통합해 `openai-transport-publish-retry.log`의 종료 0으로 공유했다.
- 데모 전용 Compose override·별도 profile·키 파일을 연결했다. OPENAI 모드/데모/유료 허용의 명시 설정과 만료·요금 버전·PC 배정 예산·총 호출 수가 필요하다. 기본 실행은 DISABLED이며 profile 예제는 이미 만료된 상태다. 키는 Agent에만 마운트하고 설정 오류에 원문을 출력하지 않는다.
- 공식 2026-09-21 Luna/Terra 기본 text 요금·장문 배수·모델 입력 상한을 버전 리소스로 보관했다. 후보 등록이며 실제 접근·품질·선택을 검증한 것은 아니다. 보수적 전체 입력 예약과 실제 응답 정산을 구분한다.
- V5는 설치 전체 총 호출 한도를 영속 예산에 추가한다. 동시 접수·새 조사·재시작·설정 변경으로 한도를 초기화하지 않는다. 이미 저장된 다른 배정은 CONFIGURATION 오류로 거절한다.
- 검증: 설정 6개·전송 14개·장부 12개를 유료 허용 환경이 상속된 조건에서도 통과했다(`openai-demo-inherited.log`). 장부 12개는 실제 별도 PostgreSQL에서 재통과했다(`openai-demo-postgres-final.log`). Compose config의 기본 비활성·Agent 전용 마운트와 실제 컨테이너 UID의 합성 키 파일 읽기도 확인했다(`demo-configuration/compose-check.json`). 해당 컨테이너 검사는 shell만 실행했고 Java·모델은 호출하지 않았다.
- 소비자 확인: 실제 VOC가 context.orderId=" "를 201로 저장하지만 같은 context의 Agent 접수는 400 INVALID_REQUEST였다. 티켓 `e71e55a3-6b26-456b-bb91-eddc86ef5a3f`, 원문 `voc-blank-context.json`. 김아름의 전달 구현 전에 입력 경계를 맞추도록 AGENT-VOC-003을 논의에 등록한다. 공백 식별자를 실제 조사 ID로 허용하지 않는다.
- 실제 OpenAI 호출은 0회이며 DONE을 보류한다. 다음은 정책 archive 소비·일곱 실제 재현 근거·VOC/웹 통합과 승인된 실제 모델 검증 준비다.

## 2026-09-21 — build 정책 사본 소비 구현

- 데모 활성화/호출 상한 단위 `1e179f3` 전체 publish 종료 0: check·3앱 재빌드/재기동·실제 PostgreSQL/HTTP smoke, 원문 `openai-demo-publish.log`. 일반 실행의 모델 비활성을 유지했다. 공백 context 제안은 `2596164` / [DISC-20260921-agent-003](../discussions/DISC-20260921-agent-003-empty-context.md)로 별도 공유했다.
- [DISC-20260921-agent-002](../discussions/DISC-20260921-agent-002-policy-snapshot.md)의 전원 수락 P1에 따라 Agent 소비자를 구현했다. 새 manifest의 고정 정책 사본·버전·해시를 검사하며 선언된 사본이 없거나 변조되면 현재 파일로 대체하지 않는다. policy 필드가 없는 기존 manifest만 현재 정책임을 명시하는 호환 모드다.
- searchCode/readCode가 실제 manifest policyVersion을 근거 출처와 검색 요약에 반환하도록 보완했다. 모델은 조회 가능한 메타데이터로 readBusinessPolicy의 버전을 지정할 수 있다. 정책 파일을 코드 검색·수정 대상 경로에 포함하지 않는다.
- 파일/도구 검증 14개와 기존 모의 실행기 8개 통과. 정책 A/build A와 B/build B, 현재 파일 삭제 후 과거 원문 유지, 잘못된 경로·버전·해시·null·사본 누락·symlink 거절, 저장 후 원문 변경과 다른 조사 소속의 조회 거절을 확인했다. 원문 `policy-archive-final.log`, `policy-archive-stored.log`.
- 공통 생성기는 김아름의 진행 범위이며 중복 수정하지 않았다. 새 실제 생성기 snapshot의 직접 소비와 제공자 확인은 아직 남아 있어 논의를 해소하지 않는다. 이 단위에서도 모델 호출 0회다.

## 2026-09-21 — 근거 없는 완료 차단과 시스템 프롬프트 v2

- 정책 소비 단위는 최신 리더 보완을 통합한 `a545332`로 전체 publish 후 공유했다(`policy-archive-publish.log`). `5d59fee`·`ac4525e`의 HTTP 오류 구분/무저장 회귀·복구 준비 대기·P1 답변을 읽었다. 리더가 진행 중인 Agent 방식/경로 오류 DTO 보완은 중복 편집하지 않는다.
- 추가 검수에서 사실·근거·부족 입력이 모두 빈 모델 응답도 COMPLETED가 되는 것을 실제 실행기 테스트로 재현했다. 수정 전 실패 원문 `empty-report-before-fix.log`/`.xml`을 보존한다.
- 완료 보고서는 저장한 근거를 인용한 사실을 최소 하나 요구하도록 보완했다. 정상 동작 판단도 관측이 필요하다. 정보 부족은 허용된 missingInformation을 반환하는 NEEDS_INPUT으로 구분한다. 가짜 사실을 추가해 구조 검사를 통과시키도록 요구하지 않는다.
- 시스템 프롬프트는 새 `investigation-system-v2.md`로 적용하고 v1 원문을 보존했다. 실제 모델 요청의 버전/SHA와 비용 장부에 연결된다. 자연어 인과관계/품질까지 자동 검증했다고 주장하지 않는다.
- 검증: core 13개, 모의 실행기 9개·H2 실행 저장 7개·로컬 HTTP 전송 14개 통과(`grounded-completion-tests.log`, `grounded-report-core-final.log`). 실행 저장 7개는 실제 별도 PostgreSQL에서도 통과했다(`grounded-completion-postgres.log`). 실제 모델 호출 0회다.

## 2026-09-21T19:22:00+09:00 — 이 PC의 일곱 커머스 재현과 근거 재조회

- 무근거 완료 차단·프롬프트 v2는 `cfd36d1`로 전체 publish 종료 0 후 공유했다. 원문 `runtime/submission/agent-20260921/grounded-completion-publish.log`. 원격 문서 갱신을 통합한 뒤 전체 검사·세 앱 재빌드·실제 PostgreSQL/HTTP smoke를 다시 통과했다.
- 새 합성 접두어와 buildId `cfd36d1c9044-8fd07a02b0f4`에서 제공자 스크립트를 직접 실행했다. `reproduce_commerce.py --runs 3`: VOC-01~06 각각 3/3, `reproduce_inventory.py --runs 20`: VOC-07 20/20과 정상·순차 거절·장벽 복구 대조 통과. `check_fixture_isolation.py` 9건과 `check_recovery.py` prepare → 실제 commerce 재시작 → verify도 종료 0이다.
- 시작 전에 기본 Agent DB의 QUEUED/RUNNING 0건을 확인했다. 재현부터 근거 저장 종료까지 publish·재초기화를 겹치지 않았다. 복구 검사의 동일 build 재시작은 Agent 인수 전에 수행했다.
- 유지한 VOC-07 데이터에 실제 8개 도구를 실행해 근거 25건을 별도 Agent PostgreSQL에 저장하고 모든 원문을 HTTP로 재조회했다. 같은 키의 동일 조사 ID·모의 모델 호출 2회 유지 확인. 조사 ID `bb0f38d6-adb3-4ba8-980b-86c0f1880754`. 실제 모델 품질 검증은 아니다.
- 전체 명령·종료 코드·제공자 원문 경로·조사/근거 원문: `runtime/submission/agent-20260921/seven-commerce-20260921T101653Z/report.json`, `agent-handoff/result.json`. 요약과 실제 응답을 구분한다. 커머스 데이터는 조회 종료 뒤에도 보존했다.
- 일곱 공급자 재현 성공을 일곱 실제 AI 조사 성공으로 계산하지 않는다. VOC-01~06의 Agent 소비 확장, 새 생성기의 정책 사본, VOC/web/ngrok와 승인된 실제 모델 검증이 남아 있다. 유료 모델 호출 0회, DONE·리더 승인은 보류다.

## 2026-09-21 — 일곱 재현의 공통 Agent 근거 소비 검사

- `check_commerce_handoff.py --business-artifact`를 추가했다. VOC-01~06의 보존한 첫 재현마다 같은 모의 모델 절차로 8종 도구를 실행하고 실제 DB·로그·실행 소스·정책을 Agent PostgreSQL에 저장한다. 다음 도구 입력의 주문 ID·코드 경로·policyVersion은 앞선 조회에서 얻는다. 시나리오별 원인/조치 정답을 반환하는 고정 모델은 만들지 않았다.
- 식별자만 모의 모델 프로세스로 전달하고 제공자 기대 응답·DB/로그 원문 대조는 별도 Python 검사에서 수행한다. 여섯 건의 주문·결제·환불·쿠폰 상태 333개 필드와 제공자의 업무 로그 원문이 일치했다. 정상 대조의 행도 모두 포함한다. 근거 원문 GET·조회 3회·같은 키 재전송 뒤 모의 모델 호출 수는 건당 3회로 유지됐다.
- 실제 빌드 `cfd36d1c9044-8fd07a02b0f4`의 보존 데이터로 전체 명령 종료 0. 원문 `runtime/submission/agent-20260921/seven-agent-handoff-02/`의 result.json(VOC-07), business/VOC-01~06.json, business-comparison.json과 `seven-handoff-final.log`. 유료 모델 호출 0회, 일곱 건 모의 모델 총 20회다.
- 최초 Java 소비 검사는 통과했으나 Python 후처리가 LOG의 content.entry 경로를 잘못 읽어 KeyError로 실패했다. `seven-agent-handoff-01/`·`seven-handoff-run.log`를 보존하고 실제 DTO 경로에 맞게 수정했다. 결제 상태 변조·로그 누락을 복사한 메모리 산출물에 주입했을 때 비교기가 모두 거절함도 확인했다. 원본 데이터는 변경하지 않았다.
- `d1f92dc`의 리더 Agent 405/404 오류 DTO·회귀·상태/검토와 실행 안내 변경 전체를 읽고 통합했다. 새 정책 생성기의 실제 인수, VOC/web/ngrok, 실제 모델 품질·토큰/비용은 여전히 별도 미검증이다. 역할 완료를 선언하지 않는다.

## 2026-09-21 — 실제 데모 비용 범위 제안

- 일곱 근거 소비 검사 단위를 `0ba2862`로 전체 publish 종료 0 후 공유했다. 원문 `runtime/submission/agent-20260921/seven-handoff-publish.log`. 총 근거 300건은 모의 모델과 실제 PostgreSQL/파일을 사용한 결과이며 실제 AI 품질 결과가 아니다.
- [DISC-20260921-agent-004](../discussions/DISC-20260921-agent-004-demo-allocation.md)에 Agent $5 / VOC $10 / commerce·lead 합산 $15의 누적 배분과 후보 Luna·호출/시간 제한·수동 첫 검증을 제안했다. 사용자 범위·팀 배분·실제 장부 확인 전 활성화하지 않는다. 제안만으로 다른 PC에 배분을 적용했다고 기록하지 않는다.
- 미확정 관측·가격/모델 불일치·예산 소진 때 다음 호출을 멈추며 같은 키/조회에는 추가 호출을 하지 않는 구현을 사용한다. 원격 확인과 일반 publish의 유료 호출은 계속 0회다.

## 2026-09-21 — 영속 모델 장부의 읽기 전용 내보내기

- `agent-app/scripts/export_model_calls.py`를 추가했다. 기존 Compose DB에 Agent 계정으로 연결해 REPEATABLE READ READ ONLY 한 스냅샷을 읽는다. 개별 HTTP 시도의 실제 모델/usage·요금·프롬프트/도구 버전·상태·시각과 설치 전체의 확정/미확정/예약 금액을 로컬 파일로 내보낸다. 모델·앱 기동·접수·장부 변경은 하지 않는다.
- 조사 필터를 적용해도 예산 합계는 설치 전체다. 없는 조사와 호출 0건, null 미관측과 관측한 0, 예약 책임액과 실제 요금 계산을 구분한다. 1,000건 초과는 명시적으로 거절하며 문의/프롬프트 원문·요청 옵션·프로필·키를 내보내지 않는다. 기존 파일 덮어쓰기를 거절하고 새 파일은 0600으로 작성한다.
- 별도 실제 PostgreSQL `jdd_agent_export_test`에만 합성 상태 5종을 준비해 필터/총합/미확정/누락 ID/민감 본문 제외/덮어쓰기/잘못된 ID/행 불변/파일 모드/1,000건 경계를 통과했다. 원문 `runtime/submission/agent-20260921/ledger-export/20260921T103000Z/verification.json`, `ledger-export-postgres-final.log`.
- 기본 앱 DB의 장부 미설정·호출 0건도 실제 내보냈고, 기존 도메인 테스트가 작성한 `jdd_agent_budget_test`를 추가로 읽었다. `ledger-export/default-no-calls.json`, `ledger-export/domain-test-ledger.json`. 합성 장부 검증이며 제공자 청구·실제 모델 사용 기록이 아니다.
- 이 내보내기는 로컬 검수용이다. runner의 선택 관측 DTO/조회 경로 또는 최종 모델 판정을 임의로 확정하지 않았다. 실제 데모 승인·팀 예산 합의·모델 품질 검증은 아직 남아 있다.

## 2026-09-21 — 제공자 과금 오류 구분과 토크나이저 사전 재사용

- 내보내기는 `85e3f72`로 전체 publish 종료 0 후 공유했다(`ledger-export-publish.log`). `5edef99`의 제공자 근거 검사 강화 전체를 읽고 기존 재현 원문을 새 검사기로 읽기 전용 검토했다. 보존 빌드 `cfd36d1c9044-8fd07a02b0f4`의 실행 소스 35개 해시·완료 로그 436줄이 정상이며 새 재현으로 계산하지 않는다. 원문 `retained-evidence-integrity.json`.
- 추가 검수에서 OpenAI의 과금 관련 429를 모두 일시 장애로 반환하는 누락을 확인했다. 공식 오류 코드의 크레딧/조직·프로젝트 지출/조직 사용 한도와 insufficient_quota를 로컬 모의 HTTP로 먼저 재현했다. 6개 실패 원문 `billing-errors-before-fix.log`/`.xml`을 보존했다.
- 비용 장부 정산 뒤 알려진 과금 오류를 기존 INVESTIGATION_BUDGET_EXCEEDED·retryable=false로 반환한다. 일반 rate limit은 LLM_UNAVAILABLE다. 알려진 분류값만 장부 outcome에 남기고 제공자 자유 형식 오류를 SDK에 넘기지 않는다. usage 없음은 0원이 아닌 UNKNOWN·전체 예약액이며 다음 유료 시도를 차단한다. 새 API 필드는 없다.
- 확대 검증 중 토크나이저 사전을 모델 인스턴스마다 적재해 기본 테스트 JVM에서 Java heap space가 발생했다. `billing-errors-final.log`, `billing-errors-executor-failure.xml`, 원인 stacktrace `billing-errors-diagnose.log`를 보존했다. 힙 한도나 검사 기준을 낮추지 않고 thread-safe lazy registry를 재사용하고 테스트 클라이언트를 닫도록 수정했다. 특수 토큰 형태의 문자열도 삭제 없이 일반 텍스트로 추정한다.
- 수정 후 OpenAiTransportTest 21개·InvestigationRunnerTest 9개, 실패/건너뜀 0(`billing-errors-memory-fixed.log`). 가격/상한 예약은 그대로이며 실제 OpenAI 호출은 0회다.
- `4a2aeed`의 리더 일곱 근거 독립 인수·예산 P1 직접 수락을 읽었다. 같은 300근거·333필드 검사가 공급자 PC에서도 통과했지만 VOC/web·실제 모델 검증을 대신하지 않는다. 김아름의 답변·생성기/연동과 사용자 데모 범위 확인을 기다리며 독립 작업을 계속한다.

## 2026-09-21 — 접수 응답 유실과 백그라운드 조사 지속

- 과금 오류/사전 재사용을 `4c20c9a`로 전체 publish 종료 0 후 공유했다(`billing-errors-publish.log`). 전체 협업·문서·Gradle·세 앱 PostgreSQL/HTTP 검증이 통과했으며 일반 publish의 유료 호출은 0회다.
- `ClientDisconnectionTest`로 실제 TCP 접수 본문을 보낸 뒤 202 응답을 읽지 않고 연결을 닫았다. 별도 PostgreSQL `jdd_agent_disconnect_test`의 실제 배타 worker가 도구 근거를 저장하고 RUNNING인 동안 새 HTTP 클라이언트가 같은 키로 원래 ID를 회복했다. 저장 근거 조회 뒤 원래 실행을 완료하고 반복 조회해도 조사 1건·근거 1건·모의 모델 2회·유료 호출 0회가 유지됐다.
- 실제 검사 1개 통과·실패/건너뜀 0. 조사 ID `53ab819e-cdef-4ea5-932c-126b93e2867c`. 모의 모델·합성 도구와 실제 PostgreSQL/HTTP/worker를 구분한다. 원문 `runtime/submission/agent-20260921/client-disconnection/result.json`, `client-disconnection-postgres.log`와 JUnit XML. 공개 URL 변경/OAuth/web/ngrok 외부 검증으로 계산하지 않는다.
- 이 PC에 ngrok 3.39.11을 설치하고 버전을 확인했다. `ngrok config check`는 로컬 기본 설정 파일 없음으로 종료 1이다. `ngrok-install.log`, `ngrok-version.log`, `ngrok-config-check.log`를 보존했으며 터널·공개 URL을 생성하지 않았다. 인증·허용 대상·web 공유와 실제 외부 검증은 준비 후 이어 수행한다.
- 실제 개발 세션의 사용자/응답/도구 이벤트 852건을 `runtime/submission/agent-20260921/session/20260921T100624Z/`에 중간 캡처했다. 이메일·비밀 값 검사/가림과 원문 prefix/산출물 SHA·제외 유형을 manifest에 남겼다. 요약이나 미가림 원본과 구분하고 진행 종료 시 갱신한다. Git에는 원시 세션/개인정보를 공유하지 않는다.
