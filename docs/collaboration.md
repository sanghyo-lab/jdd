# 3인 랄프톤 GitHub 협업 가이드

이 문서는 AI Agent, 이커머스 애플리케이션, VOC 티켓 관리·AI 연동을 세 명이 나누어 구현하는 작업 기준이다. 저장소는 하나를 사용하고, 각자 자신의 PC에 clone해서 담당 기능의 브랜치에서 개발한다. 같은 PC에서 여러 개발 에이전트를 실행할 때에는 서로 다른 Git worktree를 사용한다.

현재는 설계와 협업 템플릿을 준비한 단계다. GitHub의 팀원 초대, Issue·PR 생성, 브랜치 보호, CI와 자동 실행은 아직 설정하지 않았다.

## 1. 담당 범위

| 담당 | 역할 | 주로 수정할 경로 | 완성할 결과 |
| --- | --- | --- | --- |
| A | AI Agent | `agent-app/`, `agent-core/`, `agent-infra/`, `docs/status/agent.md` | 조사 접수·진행·근거·보고서 API, DB·로그·소스·정책 조사 |
| B | 이커머스 애플리케이션 | `commerce-app/`, `commerce-core/`, `commerce-infra/`, `docs/business-policy.md`, `docs/status/commerce.md` | 주문·결제·쿠폰·재고·취소, 7개 장애 조건과 초기 데이터, 추적 로그 |
| C | VOC 티켓 관리·AI 연동 | `voc-app/`, `voc-core/`, `voc-infra/`, `web/`, `scenario-runner/`, `docs/status/voc.md` | 티켓 CRUD, 분석 요청·결과 연결, 문의·리포트 화면, 전체 흐름 검증 |

A·B·C는 역할 표기이며 실제 GitHub 계정은 배정 후 기록한다. API 문서와 루트 Gradle 설정, Compose, CI는 공통 파일이다. 초기 틀은 C가 취합하고 A·B가 각 앱의 실행 조건을 제공한다. 이후 공통 파일을 바꾸는 작업에는 영향을 받는 담당자를 명시한다.

쿠폰을 포함한 커머스 구현은 B가 맡는다. A는 커머스 코드를 읽고 조사 도구를 구현하며, 커머스 수정이 필요하면 근거와 필요한 변경을 B의 작업에 연결한다. C는 티켓을 관리하고 A의 HTTP API를 호출한다.

## 2. 실행 단위와 연결

Spring Boot 앱 세 개를 제안한다. 각 앱에 `app`, `core`, `infra` 모듈을 두고 `scenario-runner`를 더해 백엔드 Gradle 모듈은 10개다. `web`은 별도 Next.js 프로젝트다.

```mermaid
flowchart LR
    U[개발팀] --> W[web / C]
    W --> V[VOC 티켓 / C]
    V -->|HTTP 분석 요청·조회| A[AI Agent / A]
    A -->|읽기| D[커머스 DB·로그·소스]
    B[이커머스 / B] -->|데이터·로그 생성| D
    W -->|주문 재현| B
```

- B의 서버: 업무 데이터와 로그를 생성한다. 실행 버전의 소스 스냅샷도 제공한다.
- A의 서버: 조사 작업과 근거·보고서의 원본을 보관한다. `investigationId`를 발급한다.
- C의 서버: 티켓, 담당자, 업무 처리 상태, 분석 요청과 `investigationId` 연결을 보관한다. `ticketId`를 발급한다.
- 티켓 상태는 `OPEN`, `IN_PROGRESS`, `RESOLVED`를 사용한다. AI 조사 상태는 `QUEUED`, `RUNNING`, `COMPLETED`, `NEEDS_INPUT`, `FAILED`를 사용한다.
- 조사 완료 후 티켓 화면에 결과를 표시한다. 티켓의 `RESOLVED` 전이는 담당자가 실제 조치를 확인했을 때 수행한다.

앱 세 개의 실행·연동 비용을 줄이기 위해 PostgreSQL 한 인스턴스와 Compose 실행 환경을 공유한다. `commerce`, `agent`, `voc` 스키마와 마이그레이션은 각 담당 앱에서 관리한다. 첫 통합은 첫날에 끝내는 것을 목표로 한다.

## 3. 첫 한 시간에 맞출 규약

| 규약 | 작성·검토 | 포함할 내용 |
| --- | --- | --- |
| VOC → Agent HTTP 계약 | A 작성, C 검토 | 요청·응답, 상태, 오류, 재시도, 근거·보고서 JSON |
| 커머스 조회 스키마 | B 작성, A 검토 | 주문·결제·쿠폰·재고 테이블, 조회 필드, 데이터 초기화 방법 |
| 로그·실행 소스 규약 | B 작성, A 검토 | UTC 시각, 이벤트명, `buildId`, 요청·주문·상품 식별자, 로그·소스 위치 |
| VOC 화면 계약 | C 작성, A 검토 | 티켓과 조사 상태 표시, 추가 정보 입력, 근거 조회 |
| 실행·검증 규약 | C 취합, 전원 확인 | JDK·Gradle·Node 버전, 앱별 포트·환경 변수, 빌드·테스트 명령 |

HTTP 연결의 시작안은 [연동 계약 초안](integration-contract.md)에 정리했다. 구체적인 테이블 DDL과 OpenAPI·JSON 예제는 초기 계약 작업에서 확정한다. 확정한 규약과 동일한 예제 응답을 사용하면 C는 A의 구현 완료 전에 화면과 연동 코드를 만들 수 있다.

규약을 변경할 때에는 제공자와 사용자가 함께 변경을 확인한다. 필드 추가처럼 기존 호출이 계속 동작하는 변경을 우선한다. 필드 제거·이름 변경은 양쪽 구현을 포함한 같은 PR이나 순서가 명확한 연관 PR로 처리한다.

## 4. GitHub에서 소통하는 방식

| 수단 | 기록할 내용 |
| --- | --- |
| Issue | 목표, 담당, 완료 조건, 선행 작업, 막힌 이유 |
| 작업 브랜치·커밋 | 실제 코드 변경과 변경 이유 |
| PR | 변경 동작, 검증 결과, 연동 영향, Issue 연결 |
| 역할별 상태 문서 | 현재 작업, 제공 가능한 기능, 필요한 변경, 다음 작업 |
| `main` | 통합 검증을 거친 공통 기준 |

GitHub Issues는 작업과 논의를 추적하고, 브랜치·PR는 변경과 검토를 공유하는 데 사용한다. [GitHub Issues](https://docs.github.com/en/issues/tracking-your-work-with-issues/about-issues), [GitHub flow](https://docs.github.com/en/get-started/using-github/github-flow)

작업은 다음 순서로 진행한다.

1. 역할별 Issue에서 작은 작업 하나를 고른다. 예: “분석 접수 API와 중복 요청 처리”.
2. `main`의 최신 통합 상태를 확인하고 작업 브랜치를 만든다.
3. 담당 범위에서 구현하고, 실제 실행한 검증과 결과를 기록한다.
4. 의미 있는 단위로 커밋한 뒤 자신의 원격 브랜치에 push한다. 진행 중인 작업은 Draft PR로 공유할 수 있다.
5. PR에 연동 영향과 검증 결과를 적고 통합한다. 첫날에는 1~2시간마다 작은 통합 기회를 잡는다.
6. 다음 작업 전 `main`의 새 변경과 계약·상태 문서를 확인한다.

커밋은 로컬 기록이고 push 이후 원격에서 공유된다. 원격에 올라온 코드를 실행하려면 다른 작업 공간에서도 fetch 후 해당 변경을 통합해야 한다. 이미 작업 중인 브랜치에서는 변경을 먼저 커밋하고 `origin/main`을 merge하는 방식을 기본으로 한다. 충돌이 나면 충돌 파일의 담당자와 계약을 확인하고 해결한다.

브랜치는 작업 단위로 짧게 유지한다.

```text
feat/agent-investigation-api
feat/commerce-order-inventory
feat/voc-ticket-analysis
fix/agent-evidence-reference
```

시작 예시이며, 아래 명령은 새 작업을 시작하는 깨끗한 작업 트리에서 실행한다.

```bash
git switch main
git pull --ff-only origin main
git switch -c feat/agent-investigation-api
```

개발 중인 다른 사람의 브랜치를 임시로 사용해야 한다면 필요한 PR·커밋을 Issue에 기록한다. 공유 완료의 기준은 `main` 통합으로 맞춘다.

## 5. 개발 에이전트의 반복 작업

GitHub를 공유 기록으로 사용하려면 각 개발 에이전트가 원격 변경과 문서를 확인하는 절차를 실행해야 한다. 팀원 간 대화나 에이전트의 내부 기억은 각 실행 환경에 따로 남으므로, 다음 작업에 필요한 내용을 저장소에 기록한다.

각 담당자는 자신의 개발 에이전트에 다음 기준을 전달한다.

```text
이 저장소에서 맡은 역할과 이번 작업의 완료 조건을 확인한다.
docs/collaboration.md, docs/integration-contract.md, docs/architecture.md와
docs/status의 세 역할 상태 문서를 읽는다.

반복 단위:
1. 원격 main의 변경, 담당 Issue와 연관 PR을 확인한다.
2. 현재 작업을 보존하고 필요한 통합 변경을 반영한다.
3. 담당 경로에서 작은 작업 하나를 구현한다.
4. 해당 변경에 필요한 검증을 실행하고 실제 결과를 기록한다.
5. 담당 상태 문서에 완료 사항, 연동 영향, 다음 작업을 갱신한다.
6. 팀이 정한 GitHub 작업 권한 범위에서 커밋·push·PR을 갱신한다.

다른 담당자의 구현이 필요하면 계약과 필요한 변경을 구체적으로 기록하고,
그동안 진행할 수 있는 독립 작업을 수행한다.
작업 수, 실행 시간, 모델 사용량의 한도와 종료 조건을 지킨다.
```

이 문서 자체는 스케줄러나 자동 실행 프로그램이 아니다. 실제 사용하는 에이전트 실행기에 위 반복과 종료 조건을 설정한다. GitHub 조회·push 권한도 각자의 계정으로 확인한다. 3명이 동시에 한 checkout의 파일과 브랜치를 바꾸지 않도록 작업 공간을 분리한다.

상태 기록은 역할별 파일로 나눠 충돌을 줄인다. 작업 브랜치의 최신 진행 상황은 PR과 해당 브랜치의 상태 파일에서 확인하고, `main`의 상태 파일은 통합된 결과로 읽는다.

- [A — AI Agent 상태](status/agent.md)
- [B — 이커머스 상태](status/commerce.md)
- [C — VOC·연동 상태](status/voc.md)

## 6. 통합과 검증

C를 초기 통합 담당으로 제안한다. A·B는 자신의 앱을 빌드·실행할 수 있는 상태로 전달하고, C는 VOC → Agent → 커머스 근거 조회 → 리포트 표시 흐름을 연결한다. 모듈별 테스트는 각 담당자가 유지한다.

| 시점 | 함께 확인할 결과 |
| --- | --- |
| 첫 한 시간 | 소유 경로, 연동 계약 초안, 최소 테이블·로그 필드, 공통 실행 틀 |
| 첫날 오전 | 세 앱과 PostgreSQL 실행, 각 API의 예제 요청·응답 |
| 첫날 오후 | 티켓 1건에서 실제 조사·근거·보고서 표시까지 연결 |
| 둘째 날 오전 | 7개 VOC, 정상·정보 부족 사례, 중복 분석 요청·통신 실패 검증 |
| 둘째 날 오후 | Vercel 시연, 실제 결과표·리포트 완성 |

초기 CI에는 백엔드 빌드·관련 테스트와 프론트 빌드를 연결한다. 실제 실행 가능한 스크립트가 생긴 뒤 해당 검사를 필수 체크로 설정한다. 계약의 예제와 실제 API가 맞는지도 검증한다. CI 없이 자동 merge만 켜는 설정은 이 가이드에 포함하지 않는다.

GitHub 설정에서는 팀원에게 저장소 쓰기 권한을 부여하고 `main`에 PR·상태 검사 규칙을 적용하는 안을 사용한다. CODEOWNERS는 실제 계정과 접근 권한을 확인한 뒤 경로별로 등록한다. 코드 소유자 지정은 리뷰 요청을 연결하며 파일 수정을 잠그는 기능은 아니다. 필수 리뷰와 브랜치 보호는 별도 설정이고 저장소 공개 범위·요금제의 지원 조건을 확인한다. [CODEOWNERS 공식 문서](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/about-code-owners)

이 저장소에 준비한 [작업 Issue 템플릿](../.github/ISSUE_TEMPLATE/task.md)과 [PR 템플릿](../.github/PULL_REQUEST_TEMPLATE.md)을 이용해 완료 조건·연동 영향·검증 결과를 같은 형식으로 공유한다. 실제 자동 merge나 다른 사람의 권한 변경은 아직 적용하지 않았다.
