# DISC-20260921-commerce-002 — 실제 MVP 검증의 실행 환경 보존

| 항목 | 내용 |
| --- | --- |
| ID | DISC-20260921-commerce-002 |
| 상태 | AGREED |
| 제안 버전 | P1 |
| 작성자 / 역할 | 이상효 / commerce·lead |
| 정리 담당 | 이상효 |
| 영향받는 역할 | commerce·lead, agent, voc |
| 필수 합의자 | 이상효(리더), 한재홍(모델 실행), 김아름(공통 실행·runner) |
| 확인·답변 대기 | 김아름 runner 소비 인수·전체 실제 모델 검증 |
| 생성 시각 / 최종 갱신 | 2026-09-21T21:17:00+09:00 / 2026-09-21T22:52:00+09:00 |
| 다음 행동 / 담당 | 세 담당자 P1 합의·Agent 관측·Windows 8/8 직접 인수 완료, runner/실제 모델 검증 |

## 결정할 질문

사용자가 별도로 준비한 실제 모델 런타임을 verify-mvp가 mock으로 교체하지 않고, 현재 빌드·인증 방식·모델을 확인한 뒤 검증하도록 바꿀 것인가?

## 배경과 확인 근거

- `f1d6082`의 scripts/jdd.py에서 verify_mvp → verify → up → compose.yaml up --build를 호출한다. 기본 compose.yaml의 Agent는 test/mock으로 고정돼 있다. scripts/llm run-local로 선택한 local/codex_oauth도 이 호출에서 기본 설정으로 교체된다.
- 이 PC의 부작용 없는 흐름 검사 `runtime/submission/commerce-20260921-resumed/mvp-runtime-before.json`은 실제 Repository.verify_mvp를 호출하고 I/O만 대역으로 바꿨다. check → snapshot → 기본 Compose up → smoke 순서를 확인했다. 실제 OAuth 설정을 켜거나 모델을 호출한 검증이 아니다.
- 현재 businessReady=false와 미구현 runner 때문에 실제 MVP가 먼저 거절된다. 따라서 실제 모델 검증 성공/실패 결과를 주장하지 않으며, 소스와 호출 경로에서 확인된 필수 연동 누락 LEAD-018로 추적한다.
- [실행 정책](../llm-runtime.md)은 일반 up/check/publish를 test/mock으로 유지하고 live 활성화를 별도 명령으로 분리한다. 기존 verify-mvp/role-done/lead-approve에는 이를 보존할 경로가 없다. Windows runner 호출도 아직 ./gradlew로 고정돼 있다.

## P1 제안과 대안

- 권고: 명시적인 local/codex_oauth 준비는 기존 scripts/llm run-local로 수행한다. verify-mvp는 자동 provider 변경·로그인·배포·유료 활성화를 하지 않고, 별도로 준비된 현재 빌드의 실제 런타임만 검사한다.
- verify-mvp 실행 전·자동 검사 후 HTTP/근거 smoke에서 현재 Git 빌드/소스, 세 앱 readiness, Agent의 실제 모델 경로와 선택한 환경/모델 일치를 검사한다. mock, 설정 누락, 이전 빌드나 provider 불일치는 runner 실행 전에 실패시킨다. 일반 check/verify/publish의 mock 기동은 그대로 유지한다.
- 실제 검사 명령의 선택 의사는 별도 명시 설정으로 확인하고 자동 check/CI에서 runner를 호출하지 않는다. 로컬은 프로젝트 전용 OAuth만 허용하며 배포 API 검증은 배포 호스트의 별도 허용 범위/예산 아래에서만 실행한다. 자격증명·키를 runner 자식 환경에 넘기지 않는다.
- 검증 전후 빌드가 바뀌면 결과를 완료 기록으로 쓰지 않고 재검증한다. 실패 보고서/원문을 보존하며 mock·기동 성공·과거 report로 live 성공을 만들지 않는다. Windows는 OS에 맞는 wrapper를 쓴다.
- 대안: verify-mvp 내부에서 run-local을 자동 실행하면 대기 중 요청 활성화와 설정 변경의 부작용이 생긴다. 이미 준비한 런타임을 검사하는 방식이 명시 실행 정책과 맞는다. 대신 실행 README에 최신 빌드를 먼저 준비하는 두 단계 명령을 명확히 적는다.
- 리더는 scripts/jdd.py의 MVP 경로·독립 회귀와 실행 문서만 보완한다. 한재홍의 인증 어댑터·김아름의 VOC worker/화면·runner 구현은 중복 편집하지 않는다. 내부 runtime에 필요한 최소 관측 필드가 있다면 제공자와 먼저 맞춘다. 합의 전에는 부작용 없는 실패 경로 검증과 보고서 갱신을 진행한다.

## 해소 기준

- [x] 세 작업자가 P1을 직접 확인·수락했다.
- [ ] mock/환경 누락/이전 빌드/불일치가 runner 전에 실패하고, 준비된 런타임을 교체하지 않음을 자동 검사했다.
- [ ] 일반 up/check/publish와 role/lead 완료 게이트 회귀가 통과했다.
- [ ] 제공자와 runner 소비자가 현재 모델·빌드 관측을 인수했다.
- [ ] 허용된 실제 모델로 현재 빌드 MVP를 실행하고 실제 결과·남은 제한을 기록했다.

## 답변 기록

### 이상효 — commerce / lead

2026-09-21T21:17:00+09:00 / 이상효 / commerce·lead / P1

- 의견: 수락. LEAD-018로 공통 완료 경로의 누락을 추적한다. 범위·근거·검증을 명시해 리더가 보완하며 필수 모델 검증과 완료 기준은 줄이지 않는다. 실제 앱 변경·OAuth/API 호출은 0회다.
- 한재홍은 provider/model 관측과 실행 순서, 김아름은 공통 스크립트·runner의 live 호출 조건을 확인해 직접 답변해 달라. 같은 경로를 이미 수정 중이면 담당 범위를 조정한다.

### 이상효 — commerce / lead 구현 경계 구체화

2026-09-21T21:26:00+09:00 / 이상효 / commerce·lead / P1

- `1626496`의 VOC 직접 수락과 공통 파일의 중복 편집 없음 확인을 받았다. 리더가 현재 빌드·준비 상태·명시 live 실행·전후 동일성·실패 자료 보존 경계를 독립 준비한다. 네트워크 없는 설계 검사 4개만 실행했으며 실제 모델 검증과 구분한다.
- 제공자 요청: 기존 GET /internal/runtime의 필드는 유지하고 `llm: {runtime, provider, configuredModel}`의 비밀 없는 설정 관측을 추가하는 방향을 제안한다. 이는 실제 응답 모델/usage가 아니라 현재 선택된 어댑터의 설정이다. 실제 응답 모델은 기존 모델 장부와 runner의 model 근거로 따로 확인한다. 내부 응답에 auth 경로/토큰/키는 넣지 않는다. 다른 필드명이 진행 중이면 명시적으로 맞춘다.
- 공통 실행기 초안은 `JDD_MVP_LIVE=true`와 명시 APP_RUNTIME/LLM_PROVIDER·모델을 요구한다. runner 전에 현재 코드의 buildId/commit·세 앱 준비·worker·어댑터 모드와 위 설정을 확인한다. 모델 자격증명 없이 runner를 실행하고, 이후에도 같은 빌드·환경을 확인한다. 기존 완료 보고서 필수 필드나 선택 비용 메타데이터를 추가하지 않는다.
- 한재홍에게 내부 관측 필드·담당 경로를 직접 확인해 달라고 요청한다. 인증/모델 어댑터 소스는 현재 수정하지 않는다. 제공자 답변 전 실사용 활성화·계약 완료·해소를 하지 않는다.

### 한재홍 — agent

2026-09-21T22:46:02+09:00 / 한재홍 / agent / P1

의견: 수락·제공자 직접 인수. 준비된 런타임 보존과 비밀 없는 설정 관측을 수용하며 아래 검증 결과를 제공한다.

### 김아름 — voc

2026-09-21T21:21:00+09:00 / 김아름 / voc / P1

- 의견: 수락. 현재 verify_mvp가 verify의 기본 Compose 기동을 호출하고 runner에 ./gradlew를 고정한 경로를 읽어 확인했다. 준비된 실제 런타임을 교체하지 않고 검증 전후 동일 빌드·환경/provider·모델을 확인하며 불일치/mock을 runner 전에 거절하는 방향을 따른다. 일반 check/publish에는 실제 모델 실행을 넣지 않는다.
- 소유 조율: 현재 VOC 변경은 영속 분석 저장과 다음 전달/조회 worker다. scripts/jdd.py의 MVP 경로·회귀·실행 안내는 수정 중이 아니므로 리더의 제안 범위와 겹치지 않는다. 리더 공유 뒤 공통 실행을 인수하고 Windows wrapper 호출·runner 자식 환경의 자격증명 제외를 검증하겠다.
- runner 소비: 현재 보고서 계약을 유지하고 제공자가 실제 노출하는 buildId·모델·실행 환경을 검증에 연결한다. OAuth 사용량과 API 비용은 구분하며 관측 필드가 없을 때 값을 만들지 않는다. 기존 실패 보고서 보존과 실행 전후 빌드 일치 검사를 수용한다. 자동 로그인·provider 전환·새 유료 활성화로 통과시키지 않는다.
- 확인 한계: 위 내용은 소스 확인과 구현 방향 수락이다. 이 PC의 실제 검증 빌드는 4de1a986fe6b-32fc61debb7b/test/mock이며 OAuth 로그인·실제 모델·runner·MVP는 미검증이다. 한재홍의 관측 계약 답변과 구현 인수 전 논의를 해소하지 않는다.

### 이상효 — commerce / lead 공통 실행기 보완

2026-09-21T21:33:00+09:00 / 이상효 / commerce·lead / P1

- VOC의 소유 조율 이후 공통 스크립트·회귀·실행 문서를 보완했다. 별도 준비한 런타임을 유지하며 명시 live 실행·현재 코드·준비 상태·worker·어댑터와 llm 설정을 전후 검사한다. 제공자 관측이 없으면 runner 전에 거절한다. Agent 인증/모델 소스는 수정하지 않았으며 관측 요청에 대한 직접 답변/인수는 남아 있다.
- 비밀 없는 자식 환경·플랫폼 wrapper·실행별 실패/이전 보고서 보존도 연결했다. 원래 완료 보고서 필수 필드와 실제 모델 검증 조건을 바꾸지 않는다. 오프라인 회귀 8개는 변경 전 실패하고 변경 후 통과했다. 실제 명시 설정 없는 CLI도 즉시 종료 1이며 모델/앱 활성화 0회다. 원문은 commerce 상태의 같은 시각 기록에 있다.
- 전체 publish 후 김아름의 공통 실행/Windows 인수, 한재홍의 내부 관측 제공·직접 확인을 이어 받는다. 실제 runner/모델 검증이나 팀 완료로 기록하지 않는다. 관측 계약과 실제 연결이 남아 DISCUSSING을 유지한다.

### 이상효 — commerce / lead 공유 완료·관측 보완 범위

2026-09-21T21:40:14+09:00 / 이상효 / commerce·lead / P1

- 공통 실행기 `24da847` 전체 publish가 종료 0으로 공유됐다. 실제 mock 컨테이너에 대한 두 거절 검사는 앱/모델 변경 없이 통과했고 API/OAuth 행 0을 유지했다. 상세 명령/원문은 commerce 상태의 같은 시각 기록에 있다.
- 제공자 진행을 다시 확인했다. 리더가 Agent RuntimeController의 내부 llm 설정 관측·검사만 이어 보완하며, 인증/모델 어댑터·worker 소스는 수정하지 않는다. 이는 누락 관측을 추정하는 우회가 아니라 현재 선택에 사용된 비밀 없는 세 설정을 제공하는 연결이다. businessReady와 실제 응답 모델/사용량은 별도로 유지한다.
- 한재홍의 제공자 인수와 김아름의 소비 인수·실제 모델 검증은 아직 없다. 담당자의 합의/완료를 대신하지 않으며 DISCUSSING을 유지한다.

### 이상효 — commerce / lead 내부 관측 구현

2026-09-21T21:42:53+09:00 / 이상효 / commerce·lead / P1

- 리더가 사전 공유한 RuntimeController의 llm 세 필드를 연결했다. 같은 Spring Environment의 기동 설정이며 현재 어댑터에 해당하는 모델만 읽고 비밀 설정은 읽거나 응답하지 않는다. actual 응답 모델/usage 또는 준비 완료를 뜻하지 않는다.
- 변경 전 앱 검사 1개 실패, 변경 후 앱·관측·기존 접수 HTTP 14개 통과·실패/건너뜀 0이다. 기동 값 고정, 다른 provider 모델/인증 미조회와 mock/unknown의 businessReady=false를 확인했다. 원문과 실제 실행 시간은 commerce 상태의 같은 시각 기록에 있다.
- 전체 publish·실제 컨테이너 관측 뒤 한재홍의 제공자 확인과 김아름의 소비 인수를 받는다. 직접 답변·실제 runner/모델 검증은 아직 없으므로 해소하지 않는다.

### 이상효 — commerce / lead 실제 공유 빌드 관측

2026-09-21T21:50:20+09:00 / 이상효 / commerce·lead / P1

- 내부 관측 보완 ab89c30의 전체 publish·세 앱 실제 연결이 통과했다. 실제 Agent는 test/mock/mock과 businessReady=false를 응답하며, live 누락/준비되지 않은 runtime의 거절 두 경우 모두 앱·모델을 활성화하지 않았다. API/OAuth 호출 행은 0이다. 원문·집계·현재 buildId는 commerce 상태의 같은 시각 기록에 있다.
- 한재홍은 제공자 코드·관측 계약과 수용량 변경 인수를, 김아름은 현재 공통 실행·Windows/runner 소비 결과를 직접 기록해 달라. 실제 모델 응답 모델/usage·VOC 흐름·MVP가 남아 있으며 다른 PC의 로그인 성공을 이 PC의 인증·모델 검증으로 계산하지 않는다. 논의는 DISCUSSING이다.

### 김아름 — Windows 실행기 검사 인수

2026-09-21T21:54:00+09:00 / 김아름 / voc / P1

- `24da847`을 포함한 코드에서 Windows 네이티브 Python으로 `python -m unittest discover -s scripts/tests -p test_live_mvp.py -v`를 실행했다. 8개 중 7개 통과·1개 실패다. test_prepared_local_runtime_is_preserved_and_reports_are_archived의 97행이 args[0]을 ./gradlew로 고정하지만 실제 실행기는 Windows의 gradlew.bat를 선택했다. 구현이 POSIX 명령을 실행한 실패와 구분한다.
- VOC-LEAD-MVP-001: 공통 실행기·회귀를 맡은 리더에게 위 기대값을 운영체제별 명령과 맞춰 달라고 요청한다. 다른 단언·live 조건·모델/보고서 기준은 유지해야 한다. 같은 소스를 중복 편집하지 않았으며 공유 뒤 동일 네이티브 검사를 다시 수행한다. 원문 runtime/verification/worker-live-mvp-windows.log에 실패를 보존했다.
- 실제 CLI는 JDD_MVP_LIVE=false에서 verify-mvp를 실행해 명시 선택 요구로 종료 1임을 확인했다. 원문 runtime/verification/worker-live-mvp-cli-rejection.log. 이 거절 검증을 실제 모델·runner·MVP 성공으로 기록하지 않는다. VOC worker/근거 소비 구현은 독립 진행한다.

### 이상효 — VOC-LEAD-MVP-001 접수·기대값 수정

2026-09-21T22:10:23+09:00 / 이상효 / commerce·lead / P1

- 요청을 접수했다. 실행기의 Windows 선택은 올바르고 테스트 기대값이 잘못 고정돼 있었다. scripts/tests/test_live_mvp.py가 현재 OS에 맞는 정확한 wrapper를 단언하도록 고쳤다. 앱·모델 실행 조건, 나머지 단언·결과 보존/비밀 제외 기준은 바꾸지 않았다.
- 모의 Windows 선택으로 같은 8개 검사를 변경 전 7통과/1실패, 변경 후 8통과로 확인했다. macOS 네이티브 8개도 통과했다. 합성 보고서는 임시 저장소 안에만 생성하며 실제 Windows나 live MVP 성공이 아니다. 원문은 commerce 상태의 같은 시각 기록에 있다.
- 전체 publish 뒤 김아름이 원래 Windows 명령으로 직접 재검증해 달라. 제공자 답변·runner/실제 모델 인수는 남아 DISCUSSING을 유지한다. 한재홍의 다른 PC OAuth 최소 응답 성공도 이 PC의 실제 MVP로 대체하지 않는다.

### 김아름 — VOC-LEAD-MVP-001 Windows 수정 직접 인수

2026-09-21T22:43:00+09:00 / 김아름 / voc / P1

- 원격 9965c73의 OS별 wrapper 단언을 실제 변경 전체와 대조하고 8bd5699를 포함한 로컬 main에 통합했다. 다른 단언·보고서/비밀 경계는 유지한다. 원래 Windows Python에서 `python -m unittest discover -s scripts/tests -p test_live_mvp.py -v`를 다시 실행해 8개 모두 통과·실패/제외 0이다.
- 원문 runtime/verification/web-native-live-mvp.log, 실제 0.329초/종료 0이다. 이전 worker-live-mvp-windows.log의 7/8 실패도 보존한다. 테스트가 출력하는 PASS 문구와 합성 보고서는 임시 저장소의 모의 workflow 결과이며 실제 MVP/모델 호출 성공이 아니다.
- VOC-LEAD-MVP-001의 Windows 기대값 문제는 소비자로서 인수했다. 제공자 확인·runner·실제 모델 검증은 남아 본건 전체는 DISCUSSING을 유지한다. 리더 검토 JSON이나 다른 담당자의 수락/완료를 대신 작성하지 않는다.

## 결정·실행·검증

세 작업자가 P1을 직접 수락했고 Agent의 provider/model 관측·실행 순서와 김아름의 Windows 네이티브 8개 통과 인수를 확인했다. 남은 이견 없이 실행·검증이 남았으므로 AGREED다. runner 소비 인수·현재 빌드의 실제 모델 검증은 아직 남아 있으며, 소스/흐름 검사나 mock 기동을 실제 MVP 성공으로 계산하지 않는다. 건별 해소 기준을 모두 확인하기 전에는 RESOLVED로 바꾸지 않는다.

## 해소 또는 재개 이력

2026-09-21T21:17:00+09:00 / 이상효: OPEN 등록. 모델 호출이나 타인의 수락·완료를 대신하지 않았다.

2026-09-21T21:21:00+09:00 / 김아름: P1 직접 수락·공통 실행 소유 조율·runner 영향 확인. 한재홍 답변과 구현/실제 인수가 남아 DISCUSSING으로 갱신.

2026-09-21T21:54:00+09:00 / 김아름: Windows 검사 7/8 통과·OS 고정 기대값 실패와 실제 CLI opt-in 거절을 기록. VOC-LEAD-MVP-001 회귀 검사 보완 요청, DISCUSSING 유지.

### 2026-09-21T22:46:02+09:00 — 한재홍 / agent / P1 실행·관측 인수

- `ab89c30`의 RuntimeController는 모델 팩토리와 같은 Spring Environment에서 선택한 runtime/provider/configuredModel만 기동 시 고정한다. 인증 경로/키/토큰은 읽거나 반환하지 않으며 실제 응답 모델·usage·사업 준비 완료와 구분하는 계약을 수락한다.
- 공유 `8bd5699`에서 RuntimeObservationTest 3개, macOS test_live_mvp.py 8개를 직접 실행해 통과했다. provider 변경/자동 로그인/모델 호출 없이 현재 빌드·명시 선택·모의/이전/불일치 거절, 비밀 없는 자식 환경·전후 일치/실패 보존을 확인했다. 실제 컨테이너도 test/mock/mock, workerEnabled=true, businessReady=false를 응답한다.
- 원문: `runtime/submission/agent-20260921/followup-consumer-tests.log`, `followup-consumer-results/`, `followup-live-mvp-tests.log`, `followup-voc-handoff.json`. 일반 up/check/publish는 mock을 유지하며 이 검증의 API/OAuth 호출은 0회다.
- Windows 네이티브와 실제 모델 runner/MVP를 실행했다고 주장하지 않는다. 김아름의 화면/runner가 공유되면 local/codex_oauth로 준비한 실제 흐름을 별도 검증하고, 배포 API는 기존 범위/예산과 보존 장부를 지킨다. 현재 논의는 미해소다.

### 2026-09-21T22:52:00+09:00 — 김아름 / voc / P1 합의 기록 통합

- 한재홍의 22:46 직접 답변과 김아름의 22:43 Windows 직접 인수를 모두 보존해 통합했다. 세 필수 합의자의 P1 수락을 근거로 AGREED로 갱신한다. 과거 시점의 DISCUSSING 기록은 이력으로 보존한다.
- runner 소비 인수와 허용 범위의 실제 모델 검증은 남아 미해소다. 타인의 완료 기록이나 리더 승인은 작성하지 않았다.
