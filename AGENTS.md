# JDD 개발 에이전트 작업 규칙

사용자는 3명·2일 해커톤을 각자 PC의 독립 clone에서 진행하며, 모든 개발과 공유를 main에서 수행한다.
요청 범위의 구현·수정·테스트·의존성 준비·커밋·일반 push는 매 단계마다 사용자에게 다시 묻지 않고 진행한다.
실행 환경이 강제하는 권한 승인·인증·관리자 정책은 그대로 따른다. 비밀 값이나 없는 결과를 만들어내지 않는다.

## 먼저 읽을 것

1. docs/autonomous-development.md, docs/team-completion.md와 docs/local-development.md
2. docs/roles/README.md, 현재 역할의 구현 문서와 docs/goals 문서
3. docs/integration-contract.md, docs/commerce-interface.md, docs/business-policy.md
4. docs/status의 세 담당자 파일

현재 역할은 goal의 지정 또는 로컬 .jdd-role로 확인한다.
commerce = 이상효, agent = 한재홍, voc = 김아름이다.
공통 실행 기반을 만드는 명시적 작업은 세 영역의 골격·빌드·인프라를 함께 수정할 수 있다.

## 소유 범위

- commerce: commerce-app/core/infra, fixtures/commerce, 업무 정책, docs/status/commerce.md와 commerce.json
- agent: agent-app/core/infra, docs/status/agent.md와 agent.json
- voc: voc-app/core/infra, web, scenario-runner, docs/status/voc.md와 voc.json
- 공통 Gradle·Compose·scripts·CI·인터페이스: 기본 유지 담당은 voc. 변경 시 소비자 구현·문서·검증을 함께 맞춘다.
- 모든 역할은 모든 소스·테스트·계약을 읽고 전체 앱을 실행한다.
  다른 담당 경로의 변경이 필요하면 자신의 상태 파일에 대상·필드·실패 명령·필요한 변경을 기록한다.
  이미 진행 중인 변경과 중복 구현하지 않는다. 깨진 공통 빌드의 작은 수정은 직접 반영하고 영향을 기록한다.

## 반복 절차

1. 작업 시작과 작은 작업 완료 시 scripts/dev status로 원격 변경과 세 역할 상태를 확인한다.
   긴 작업 중에도 약 5분마다 fetch로 변경을 확인한다. 편집 중인 파일에는 pull/rebase를 하지 않는다.
2. 작업 트리가 깨끗하면 scripts/dev sync로 main을 갱신한다.
   변경이 있으면 자신이 수정한 파일만 명시적으로 add하고 먼저 로컬 커밋한다.
3. 기능 하나를 구현하고 관련 검증을 실행한다. 다른 앱의 진행을 기다리는 동안 계약 기반의 독립 작업을 수행한다.
4. docs/status/<role>.md에 완료·진행·필요한 연동·실제 실행한 검증과 실패를 기록한다.
   미구현·예제 응답·모의 LLM 결과를 실제 완료로 표시하지 않는다.
5. 작은 검증 가능한 단위로 main에 커밋하고 scripts/dev publish를 실행한다.
   이 명령은 동기화 → 전체 검증 → 3개 앱 재기동·연동 검사 → 일반 push를 수행한다.
   동시 push로 원격이 바뀌면 통합하고 재검증한다. 보통 15~30분마다 공유 가능한 단위를 만든다.
6. 충돌 시 양쪽 변경과 계약을 읽어 해결하고 rebase를 완료한 뒤 publish를 다시 실행한다.
   강제 push, 공유 이력 재작성, reset --hard, 사용자 변경 삭제로 해결하지 않는다.
7. 역할의 다음 완료 조건으로 계속 진행한다. 자기 기능이 끝나면 role-done <role>로 실제 MVP 검증 후
   자기 완료 기록만 커밋·publish한다. 세 담당자의 완료가 모두 모일 때까지 goal을 유지한다.
   먼저 끝난 역할은 약 60초마다 원격 변경·요청을 확인하고 필요한 연동·재검증을 계속한다.

## 실행과 완료 판정

- scripts/dev up: 이 PC의 PostgreSQL과 Spring Boot 앱 3개를 빌드·실행한다.
- scripts/dev check: 자동화 도구 테스트·문서 검증·전체 Gradle check·존재하는 web 빌드.
- scripts/dev verify: check + 3개 앱 기동 + 실제 DB·HTTP·근거 볼륨 연결 검증.
- scripts/dev verify-mvp: 위 검증과 실제 모델을 사용하는 7개 VOC 및 정상·정보 부족·재전송·복구 검증.
- scripts/dev role-done <role>: 최신 공유 코드의 실제 MVP 검증 후 자기 docs/status/<role>.json에 DONE 작성.
- scripts/dev team-status: 원격 main의 세 완료 기록과 현재 코드에 대한 유효성 확인.
- scripts/dev team-check: 깨끗한 최신 main에서 세 담당자의 유효한 DONE이 모두 있을 때만 성공.
- 기동 골격의 businessReady=false는 정상이다. 도메인 구현이 완료되기 전 true로 바꾸지 않는다.
- 완료는 경과 시간이나 작업량으로 판단하지 않는다. 예상 16~24시간과 해커톤 일정은 계획용 추정이다.
  정한 필수 기능·데이터·정상/예외 흐름의 실제 검증이 모두 통과하고, 의도한 VOC-01~07 외의
  알려진 미해결 오류·불안정한 재현·미처리 필수 연동 요청이 없어야 DONE을 기록한다.
  docs/team-completion.md의 품질 기준을 적용하며, 시간을 맞추려고 범위·검증을 줄이거나 실패를 숨기지 않는다.
- 모든 역할의 goal 종료 조건은 GitHub main에 이상효·한재홍·김아름의 유효한 DONE이 모두 존재하는 것이다.
  자기 역할 완료만으로 goal을 complete로 처리하지 않는다. docs/team-completion.md에 따라
  마지막 scripts/dev team-check가 종료 코드 0일 때만 goal을 완료한다.
  역할 완료 기록에는 검증한 커밋·내용 해시·모델·시나리오 결과가 필요하며, 각 담당자의 위임받은 에이전트가 자기 기록만 작성한다.
  docs/status/ 밖의 추적 내용이 바뀌면 기존 DONE은 STALE로 제외되므로 다시 검증한다.
  코드가 그대로여도 실패·미해결 요청이 발견되면 role-reopen <role>로 자기 완료를 철회하고 이유와 함께 공유한다.
  docs/status/에는 상태 기록만 두며 코드·검증 기준을 넣어 완료 판정을 우회하지 않는다.
- 모델 키·GitHub 인증·외부 서버 권한이 없으면 독립 구현을 계속한다.
  남은 작업이 그 입력에만 막힌 경우 실제 사유를 남기고 goal 실행기의 blocked 규칙을 따른다.
  다른 PC를 이 세션이 실행·설정했다고 가정하지 않는다.

## 구현 기준

Java 21, Gradle Wrapper, Spring Boot 버전을 공유한다. 각 앱은 자기 core/infra만 의존한다.
정상 정책과 시연용 결함을 분리하며 평가 정답·시드·테스트 소스는 Agent의 조사 입력에 넣지 않는다.
Agent의 커머스 접근은 SELECT 전용이고 제안한 수정은 자동 적용하지 않는다.
티켓 해결과 AI 조사 완료는 별도 상태다. 접수 재전송은 같은 키, 새 조사는 새 키를 사용한다.
계약 필드 제거·이름 변경은 양쪽 구현이 연결되는 순서로 반영한다.
Git에는 소스·설정 예제·합성 재현 입력을 저장한다. .env·API 키·실행 로그·개인정보는 저장하지 않는다.
