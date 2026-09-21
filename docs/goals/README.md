# 세 PC에서 역할별 goal 시작하기

이 저장소는 각 담당자가 자기 PC에서 독립된 main clone과 개발 에이전트를 사용하는 방식이다.
이 문서를 준비하는 작업에서는 실제 역할별 goal을 시작하지 않는다.

## 1. 최초 준비

1. 각자 GitHub 쓰기 권한과 자기 Git 작성자 정보를 설정한다.
2. 저장소를 clone하거나 깨끗한 main에서 최신 변경을 받는다.
3. [로컬 실행 안내](../local-development.md)에 따라 Git·Java 21·Python 3.9 이상·Docker와 Compose를 준비한다.
4. 각 PC의 Codex에서 저장소 폴더를 열고 아래 자기 역할의 명령을 한 번 입력한다.

이미 clone한 경우:

```bash
git switch main
git pull --ff-only origin main
```

처음 받는 경우:

```bash
git clone https://github.com/sanghyo-lab/jdd.git
cd jdd
```

## 2. 각자 입력할 시작 명령

| 담당자 | goal 문서 | Codex 입력 |
| --- | --- | --- |
| 이상효 | [commerce](commerce.md) | `/goal docs/goals/commerce.md를 읽고 이상효의 이커머스 담당 목표를 완료 조건까지 수행해. AGENTS.md에 따라 main을 동기화하고 검증한 변경을 계속 push해.` |
| 김아름 | [voc](voc.md) | `/goal docs/goals/voc.md를 읽고 김아름의 VOC·연동 담당 목표를 완료 조건까지 수행해. AGENTS.md에 따라 main을 동기화하고 검증한 변경을 계속 push해.` |
| 한재홍 | [agent](agent.md) | `/goal docs/goals/agent.md를 읽고 한재홍의 AI Agent 담당 목표를 완료 조건까지 수행해. AGENTS.md에 따라 main을 동기화하고 검증한 변경을 계속 push해.` |

해당 환경에 slash 명령이 없지만 goal 도구가 있다면 같은 문구를 “이 목표를 goal로 생성하고 수행해”로 전달한다.
Codex의 goal 기능은 검증 가능한 목표를 여러 턴에 걸쳐 수행하는 용도다. goal 기능이 보이지 않는 CLI에서는 `codex features enable goals`로 활성화할 수 있다. [OpenAI 공식 goal 안내](https://learn.chatgpt.com/use-cases/follow-goals)

## 3. 입력 없이 진행할 실행 권한

사용자는 이 프로젝트의 구현·검증·커밋·일반 push를 사전 승인했다. 이 범위에서 매번 재승인을 묻지 않는 규칙은 [AGENTS.md](../../AGENTS.md)에 기록했다.
각 PC의 실제 권한 설정도 맞아야 한다. 저장소 문서가 앱의 sandbox나 조직 정책을 변경하지는 않는다.

Codex CLI에서 사용자가 요청한 전체 접근·승인 질문 없음으로 새 세션을 시작하는 예:

```bash
codex --enable goals --sandbox danger-full-access --ask-for-approval never -C .
```

이 설정은 해당 CLI 실행에 파일·네트워크 전체 접근을 부여한다. 시작한 세션에서 위 자기 역할의 /goal을 입력한다.
데스크톱 앱을 사용하면 해당 앱이 제공하는 권한 설정에서 동일한 실행 권한을 정한다.
관리자 정책, GitHub 최초 로그인, 모델 인증·사용량 한도는 별도로 적용된다. [OpenAI 공식 권한 안내](https://learn.chatgpt.com/docs/agent-approvals-security)

GitHub 계정·모델 API 키는 각 PC에서 준비하고 커밋하지 않는다. 현재 실행 골격은 모델 키 없이 기동되지만 실제 AI 분석 완료에는 선택한 제공자의 유효한 인증이 필요하다.
PC와 실행 세션이 계속 동작하고 외부 의존성이 준비돼 있어야 goal이 계속 진행할 수 있다.

## 4. 진행 확인

- 각 에이전트는 다른 두 담당자의 소스·계약·[상태 문서](../status/commerce.md)를 원격 main에서 확인한다.
- `./scripts/dev status`로 최신 커밋과 세 상태 파일을 함께 읽는다.
- `./scripts/dev watch`는 60초마다 fetch하고 상태를 출력한다. 작업 파일은 변경하지 않는다.
- 실제 반영은 깨끗한 커밋 경계에서 `./scripts/dev sync`, 공유는 `./scripts/dev publish`로 한다.
- 상세 반복·완료 기준은 [자동 개발 협업 규칙](../autonomous-development.md)을 따른다.
