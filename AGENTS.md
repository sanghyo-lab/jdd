# JDD 개발 에이전트 작업 규칙

사용자는 3명·2일 해커톤을 각자 PC의 독립 clone에서 진행하며, 모든 개발과 공유를 main에서 수행한다.
요청 범위의 구현·수정·테스트·의존성 준비·커밋·일반 push는 매 단계마다 사용자에게 다시 묻지 않고 진행한다.
실행 환경이 강제하는 권한 승인·인증·관리자 정책은 그대로 따른다. 비밀 값이나 없는 결과를 만들어내지 않는다.

## 먼저 읽을 것

1. docs/autonomous-development.md, docs/team-completion.md와 docs/local-development.md
2. docs/roles/README.md, 현재 역할의 구현 문서와 docs/goals 문서
3. docs/integration-contract.md, docs/commerce-interface.md, docs/business-policy.md
4. docs/status의 세 담당자 파일과 lead.md, lead-review.json, lead.json
5. docs/discussions/README.md와 미해소·새 답변이 있는 논의 문서

현재 역할은 goal의 지정 또는 로컬 .jdd-role로 확인한다.
commerce = 이상효, agent = 한재홍, voc = 김아름이다.
이상효는 commerce와 개발리더(lead)를 겸한다. docs/roles/lee-sanghyo-lead.md와 docs/goals/lead.md도 적용한다.
리더 단계는 commerce goal에서 이어 수행하며, 별도 인원·세션·goal을 시작하지 않는다.
agent 역할은 docs/prompts/implement-voc-investigation-agent.md도 필수 구현 기준으로 읽는다.
한재홍의 범위에는 서비스 내부 LLM의 실제 VOC 조사·도구 실행·근거 저장·검증된 결과 반환이 포함된다.
개발을 분담하는 에이전트와 서비스에서 VOC를 조사하는 AI를 구분하고, 접수 API만으로 완료하지 않는다.
공통 실행 기반을 만드는 명시적 작업은 세 영역의 골격·빌드·인프라를 함께 수정할 수 있다.

## 데모용 LLM 키 사용 제한

제공된 OpenAI 키는 데모 전용이다. 개발 에이전트·개발 테스트·CI·자동 반복 평가에 사용하지 않는다.
개발 중 유료 호출이 필요하거나 데모 누적 비용이 $30을 넘길 것으로 예상되면 먼저 별도 사용 범위·예산을 정한다.
키가 있다는 이유로 verify-mvp·role-done 등에서 유료 호출을 자동 실행하지 않는다.
독립 구현과 모의 검증은 계속하며 실제 모델 검증이 없으면 DONE을 기록하지 않는다.
구체적인 구현·비용·로컬 실행 기준은 docs/planning/demo-llm-policy.md를 따른다.

## 소유 범위

- commerce: commerce-app/core/infra, fixtures/commerce, 업무 정책, docs/status/commerce.md와 commerce.json
- agent: agent-app/core/infra, docs/status/agent.md와 agent.json
- voc: voc-app/core/infra, web, scenario-runner, docs/status/voc.md와 voc.json
- 공통 Gradle·Compose·scripts·CI·인터페이스: 기본 유지 담당은 voc. 변경 시 소비자 구현·문서·검증을 함께 맞춘다.
- 개발리더 이상효는 전체 코드·테스트·인프라·계약을 검사하고 모든 담당 영역을 직접 수정·보완할 권한이 있다.
  다른 담당자의 진행 상태를 확인해 중복 편집을 피하고, 변경 대상·이유·검증·계약 영향을 docs/status/lead.md에 공유한다.
  리더의 지적·검토·승인 기록은 lead.md, lead-review.json, lead.json에 작성한다. 타인의 DONE은 대신 작성하지 않는다.
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
5. 검증 가능한 작업 단위가 끝날 때마다 상태를 기록하고 즉시 main에 커밋·scripts/dev publish를 실행한다.
   이 명령은 동기화 → 전체 검증 → 3개 앱 재기동·연동 검사 → 일반 push를 수행한다.
   동시 push로 원격이 바뀌면 통합하고 재검증한다. 시간 간격이나 여러 기능이 모일 때까지 기다리지 않는다.
6. 충돌 시 양쪽 변경과 계약을 읽어 해결하고 rebase를 완료한 뒤 publish를 다시 실행한다.
   강제 push, 공유 이력 재작성, reset --hard, 사용자 변경 삭제로 해결하지 않는다.
7. 역할의 다음 완료 조건으로 계속 진행한다. 자기 기능이 끝나면 role-done <role>로 실제 MVP 검증 후
   자기 완료 기록만 커밋·publish한다. 세 담당자의 완료와 리더의 독립 검토·최종 승인까지 goal을 유지한다.
   먼저 끝난 역할은 약 60초마다 원격 변경·요청을 확인하고 필요한 연동·재검증을 계속한다.
8. 세 담당자의 DONE이 모이면 이상효는 개발리더로 전체 코드 검사와 실제 통합 검증을 새로 수행한다.
   지적 사항을 직접 수정하거나 담당자에게 요청하고, 수정·담당자 완료 갱신·리더 재검증 후 최종 승인한다.

### 협업 문서만 변경한 단위의 즉시 공유

협업 지침·논의·역할 상태 설명만 변경한 단위는 [문서 공유 절차](docs/autonomous-development.md#협업-문서만-공유할-때)를 적용한다.
최신 main 통합 → 변경 범위 확인 → 문서·차이 검증 → 즉시 일반 push 순서로 공유하며 앱 준비·전체 빌드를 기다리지 않는다.
소스·실행 설정·API/업무 계약·테스트·완료/승인 JSON이 섞이면 기존 scripts/dev publish와 필요한 검증을 적용한다.
문서 공유 성공은 앱 실행·MVP 성공이나 DONE을 뜻하지 않는다. 팀 완료 기준과 내용 해시 판정은 그대로 유지한다.

## 논의와 답변

- 논의 목록과 작성 규칙은 [docs/discussions/README.md](docs/discussions/README.md)를 따른다.
  계약·정책·책임·연동에서 합의가 필요한 건은 템플릿으로 건별 Markdown을 만들고 목록에 등록한다.
- 전원은 시작·작은 단위 완료·긴 작업 중 약 5분마다 목록과 새 답변을 확인한다.
  각 작업자는 해당 건의 Markdown 안에 자기 이름·역할·시각·제안 버전을 밝히고 직접 답변한다.
  관련이 없으면 영향 없음을 남긴다. 다른 사람의 답변·수락을 대신 작성하지 않는다.
- 제안·답변·검증을 기록한 작업자가 같은 커밋에서 건별 상태와 README의 해당 행·집계·다음 행동을 갱신한다.
  침묵이나 단순 확인을 합의로 간주하지 않는다. 답변의 조건·반대·미확인 사항이 남으면 미해소로 유지한다.
- 최신 제안에 필요한 담당자의 명시적 합의와 건별 해소 기준·실제 검증 근거를 갖춰야 RESOLVED로 표시한다.
  합의했어도 구현·검증이 남으면 AGREED다. 새 반례·요구 변경이면 다시 열고 근거와 답변 대기자를 갱신한다.
- 논의 파일과 README는 공동 편집 경로다. 자기 답변을 추가하고 기존 답변을 보존하며 동시 push 충돌은 양쪽 기록을 합친다.
  역할별 상태에는 논의 ID·링크·자기 실행 결과를 요약하고, 답변 원문은 건별 문서에 모은다.
  논의·답변·목록 갱신도 검증 가능한 단위마다 즉시 main에 커밋·publish한다.
- 이 규칙은 타인의 역할 상태·DONE·리더 승인을 대필할 권한을 주지 않는다. 논의 해소는 팀 완료와 별개다.

## 실행과 완료 판정

- scripts/dev up: 이 PC의 PostgreSQL과 Spring Boot 앱 3개를 빌드·실행한다.
- scripts/dev check: 자동화 도구 테스트·문서 검증·전체 Gradle check·존재하는 web 빌드.
- scripts/dev verify: check + 3개 앱 기동 + 실제 DB·HTTP·근거 볼륨 연결 검증.
- scripts/dev verify-mvp: 위 검증과 실제 모델을 사용하는 7개 VOC 및 정상·정보 부족·재전송·복구 검증.
- scripts/dev role-done <role>: 최신 공유 코드의 실제 MVP 검증 후 자기 docs/status/<role>.json에 DONE 작성.
- scripts/dev roles-check: 깨끗한 최신 main에서 세 담당자의 유효한 DONE 확인. 리더 검토의 시작 조건이다.
- scripts/dev lead-approve: 공유된 코드 검토 기록과 지적 해결·반복 재현을 확인하고 전체 verify-mvp를 새로 실행한 뒤 리더 승인 작성.
- scripts/dev lead-reopen: 리더 최종 승인을 철회한다. 새 문제를 기록·공유하고 수정·재검증한다.
- scripts/dev team-status: 원격 main의 세 완료 기록과 리더 승인 유효성 확인.
- scripts/dev team-check: 깨끗한 최신 main에서 세 유효한 DONE과 독립 검토에 근거한 리더 APPROVED가 모두 있을 때만 성공.
- 기동 골격의 businessReady=false는 정상이다. 도메인 구현이 완료되기 전 true로 바꾸지 않는다.
- 완료는 경과 시간이나 작업량으로 판단하지 않는다. 예상 16~24시간과 해커톤 일정은 계획용 추정이다.
  정한 필수 기능·데이터·정상/예외 흐름의 실제 검증이 모두 통과하고, 의도한 VOC-01~07 외의
  알려진 미해결 오류·불안정한 재현·미처리 필수 연동 요청이 없어야 DONE을 기록한다.
  docs/team-completion.md의 품질 기준을 적용하며, 시간을 맞추려고 범위·검증을 줄이거나 실패를 숨기지 않는다.
- 모든 역할의 goal 종료 조건은 GitHub main에 세 담당자의 유효한 DONE과 개발리더 이상효의 유효한 APPROVED가 모두 존재하는 것이다.
  자기 역할 완료만으로 goal을 complete로 처리하지 않는다. docs/team-completion.md에 따라
  마지막 scripts/dev team-check가 종료 코드 0일 때만 goal을 완료한다.
  역할 완료 기록에는 검증한 커밋·내용 해시·모델·시나리오 결과가 필요하며, 각 담당자의 위임받은 에이전트가 자기 기록만 작성한다.
  docs/status/ 밖의 추적 내용이 바뀌면 기존 DONE은 STALE로 제외되므로 다시 검증한다.
  코드가 그대로여도 실패·미해결 요청이 발견되면 role-reopen <role>로 자기 완료를 철회하고 이유와 함께 공유한다.
  docs/status/에는 상태 기록만 두며 코드·검증 기준을 넣어 완료 판정을 우회하지 않는다.
  리더 승인에는 전체 검토 영역의 파일·판단 근거, 필수 검사·반복 재현 결과, 지적 해결과 재검증 기록이 필요하다.
  리더는 다른 담당자의 성공 기록을 믿고 종료하지 않고 자신의 PC에서 verify-mvp를 새로 실행한다.
  코드·검토 기록·담당자 완료 기록이 바뀌면 기존 리더 승인도 무효화되어 새 검증이 필요하다.
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
