# 세 PC에서 역할별 goal 시작하기

이 저장소는 각 담당자가 자기 PC에서 독립된 main clone과 개발 에이전트를 사용하는 방식이다.
이 문서를 준비하는 작업에서는 실제 역할별 goal을 시작하지 않는다.
이상효는 commerce 구현과 개발리더를 같은 goal에서 수행한다. 세 담당자의 DONE 후 전체 코드 검사·독립 검증·수정을 이어간다.

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
| 이상효 | [commerce·리더](commerce.md) | `/goal AGENTS.md와 docs/goals/commerce.md, docs/goals/lead.md에 따라 이상효의 커머스와 개발리더 역할을 수행해. 세 담당자의 DONE 이후에도 전체 코드·실제 동작을 직접 검사하고 모든 영역의 문제를 수정·재검증해. GitHub에 유효한 리더 APPROVED까지 공유하고 team-check가 성공할 때만 종료해.` |
| 김아름 | [voc](voc.md) | `/goal docs/goals/voc.md와 AGENTS.md를 읽고 김아름의 VOC·연동을 구현·검증하고 main에 계속 공유해. 자기 DONE 이후에도 리더의 검토·수정 요청에 대응해. GitHub에 세 유효한 DONE과 리더 APPROVED가 있고 team-check가 성공할 때까지 계속해.` |
| 한재홍 | [agent](agent.md) | `/goal docs/goals/agent.md, docs/prompts/implement-voc-investigation-agent.md와 AGENTS.md를 읽고 한재홍의 서비스 내부 AI로 VOC를 조사하고 결과를 반환하는 에이전트를 구현·검증하고 main에 계속 공유해. LLM API·서비스 시스템 프롬프트·실제 도구 호출·근거 저장·보고서 검증·VOC 결과 반환을 모두 포함해. 자기 DONE 이후에도 리더의 검토·수정 요청에 대응해. GitHub에 세 유효한 DONE과 리더 APPROVED가 있고 team-check가 성공할 때까지 계속해.` |

이미 한재홍의 구현 goal이 진행 중이면 새 goal을 만들지 않고 기존 세션에 다음 보완 지시를 전달한다.

```text
현재 agent goal을 유지하고 AGENTS.md, docs/goals/agent.md,
docs/prompts/implement-voc-investigation-agent.md, docs/status/agent.md를 다시 읽어.
서비스 내부 AI의 VOC 조사·결과 반환 구현을 필수 범위로 반영해.
접수 API 다음으로 영속 실행기 → 실제 LLM 도구 호출 → DB·로그·실행 소스·정책 조회 →
근거 저장 → 모델 후속 요청 → 보고서 검증·저장 → VOC의 결과·근거 조회를 연결해.
서비스 모델용 시스템 프롬프트와 LLM API 설정도 실제 실행 경로에 적용해.
다른 세션의 진행 중인 파일을 확인해 중복 구현을 피하고, 이 요청의 접수·산출물별 진행·실제 검증을
docs/status/agent.md에 기록해. 실제 모델 인증이나 상대 구현이 없으면 독립 구현을 계속하고 미검증을 명시해.
세 DONE 이후에도 리더의 독립 검토·수정 요청에 대응하고, 리더 APPROVED까지 확인하는 team-check가 성공해야 종료해.
```

위 지시는 저장소에 기록된 전달문이다. 다른 세션의 수신·반영은 해당 세션의 상태 기록이나 커밋으로 확인한다.

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
- `./scripts/dev status`로 최신 커밋과 세 상태 파일, 원격 완료 판정을 함께 읽는다.
- `./scripts/dev team-status`로 GitHub main의 담당별 DONE과 리더 APPROVED 유효성을 확인한다.
- `./scripts/dev watch`는 60초마다 fetch하고 상태를 출력한다. 작업 파일은 변경하지 않는다.
- 실제 반영은 깨끗한 커밋 경계에서 `./scripts/dev sync`, 공유는 `./scripts/dev publish`로 한다.
- 상세 반복·완료 기준은 [자동 개발 협업 규칙](../autonomous-development.md)을 따른다.

## 5. 세 담당자 완료 후 리더가 검증·승인할 때 종료

각자의 기능 완료 후 `./scripts/dev role-done <role>`로 실제 MVP를 검증하고 자기 JSON을 커밋·publish한다.
자기 DONE을 올린 뒤에도 goal은 유지한다. 다른 담당자의 요청과 main 변경을 확인하며 연동·검증·수정을 계속한다.
세 DONE이 모이면 이상효가 [리더 단계](lead.md)를 수행한다. 전체 코드와 실제 실행을 검사하고 발견한 문제를 수정·재검증한다.
공유된 검토·지적 해결 기록과 새 전체 검증을 바탕으로 lead-approve를 실행하고 리더 APPROVED도 GitHub에 공유한다.
최종적으로 깨끗한 최신 main에서 `./scripts/dev team-check`가 성공할 때만 goal을 완료한다.
세 DONE은 같은 저장소 내용을 검증해야 하며, 소스·계약·검증 기준 변경으로 오래된 완료는 무효가 된다.
진행·완료 상태 파일만 바뀌면 다른 담당자의 완료를 무효화하지 않는다.
담당자의 완료 JSON이나 리더의 검토 JSON이 바뀌면 기존 리더 승인은 무효가 된다.
기록 형식·재검증·철회 절차는 [세 담당자 완료 기준](../team-completion.md)에 있다.
