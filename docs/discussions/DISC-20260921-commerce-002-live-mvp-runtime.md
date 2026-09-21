# DISC-20260921-commerce-002 — 실제 MVP 검증의 실행 환경 보존

| 항목 | 내용 |
| --- | --- |
| ID | DISC-20260921-commerce-002 |
| 상태 | OPEN |
| 제안 버전 | P1 |
| 작성자 / 역할 | 이상효 / commerce·lead |
| 정리 담당 | 이상효 |
| 영향받는 역할 | commerce·lead, agent, voc |
| 필수 합의자 | 이상효(리더), 한재홍(모델 실행), 김아름(공통 실행·runner) |
| 확인·답변 대기 | 한재홍, 김아름 |
| 생성 시각 / 최종 갱신 | 2026-09-21T21:17:00+09:00 / 2026-09-21T21:17:00+09:00 |
| 다음 행동 / 담당 | 리더 실행기 보완, 제공자·소비자 실행 순서 확인 |

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

- [ ] 세 작업자가 P1을 직접 확인·수락했다.
- [ ] mock/환경 누락/이전 빌드/불일치가 runner 전에 실패하고, 준비된 런타임을 교체하지 않음을 자동 검사했다.
- [ ] 일반 up/check/publish와 role/lead 완료 게이트 회귀가 통과했다.
- [ ] 제공자와 runner 소비자가 현재 모델·빌드 관측을 인수했다.
- [ ] 허용된 실제 모델로 현재 빌드 MVP를 실행하고 실제 결과·남은 제한을 기록했다.

## 답변 기록

### 이상효 — commerce / lead

2026-09-21T21:17:00+09:00 / 이상효 / commerce·lead / P1

- 의견: 수락. LEAD-018로 공통 완료 경로의 누락을 추적한다. 범위·근거·검증을 명시해 리더가 보완하며 필수 모델 검증과 완료 기준은 줄이지 않는다. 실제 앱 변경·OAuth/API 호출은 0회다.
- 한재홍은 provider/model 관측과 실행 순서, 김아름은 공통 스크립트·runner의 live 호출 조건을 확인해 직접 답변해 달라. 같은 경로를 이미 수정 중이면 담당 범위를 조정한다.

### 한재홍 — agent

아직 답변 없음.

### 김아름 — voc

아직 답변 없음.

## 결정·실행·검증

P1 제안 단계다. 실제 모델 미검증·runner 미구현 상태와 이번 흐름 검사를 분리한다. 제공자/소비자의 직접 답변과 구현 인수 전 OPEN을 유지한다.

## 해소 또는 재개 이력

2026-09-21T21:17:00+09:00 / 이상효: OPEN 등록. 모델 호출이나 타인의 수락·완료를 대신하지 않았다.
