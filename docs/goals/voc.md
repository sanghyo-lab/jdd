# 김아름의 voc goal

## 목표

[담당 구현 범위](../roles/kim-areum-voc.md)와 [연동 계약](../integration-contract.md)에 따라
VOC 티켓·AI 분석 연동·리포트와 해결방안 화면을 구현하고 세 앱의 실제 전체 흐름을 검증한다.
시작 후 사용자에게 매 단계의 승인이나 다음 작업을 묻지 않고 AGENTS.md의 반복 절차로 진행한다.
예상 시간이 지나도 완료를 선언하지 않는다. [공통 필수 품질 기준](../team-completion.md)에 따라
실제 API·화면·정상/예외·연동 검증을 통과하고 알려진 미해결 오류를 모두 해결한다.

## 시작과 순서

1. AGENTS.md, 세 역할의 상태와 두 인터페이스를 읽고 원격 main을 반영한다.
2. `./scripts/dev up`, `./scripts/dev smoke`로 세 앱과 DB를 확인한다.
3. 티켓·담당자·버전·분석 입력 스냅샷·요청 전달·진행 상태·근거 조회를 구현한다.
4. web의 티켓·리포트·근거 화면을 계약 예제로 만들고 실제 Agent로 연결한다.
5. 이상효의 fixtures를 scenario-runner에 연결해 VOC-07을 먼저 통합하고 7개 시나리오로 확장한다.
6. 정상·정보 부족·재전송·통신 실패·재시작을 검증하고 실제 측정·한계를 해커톤 보고서에 반영한다.
7. web 실행·빌드와 main push CI를 공통 명령에 연결한다. [ngrok 로컬 데모 절차](../ngrok-local-demo.md)에 따라 로컬 web·접근 제어·계정이 준비되면 공개 URL의 티켓→조사→근거 시연을 검증한다. 일반 CI는 터널이나 유료 모델 호출을 자동으로 시작하지 않는다.

## 계속 공유할 것

docs/status/voc.md에 실제 연결 상태, 상대 API의 재현 가능한 실패, 실행 명령과 통합 검증 결과를 기록한다.
다른 API 구현을 기다릴 때에는 명시적인 개발용 예제 응답으로 독립 작업을 진행한다.
커밋한 뒤 `./scripts/dev publish`로 main에 공유한다. 통합 실패의 대상·입력·예상·실제를 해당 제공자가 확인할 수 있게 남긴다.

## 담당 기능 완료 조건

- 담당 문서의 모든 완료 조건과 실제 티켓→Agent→리포트·근거 화면을 검증했다.
- 업무 상태와 조사 상태, 전달 실패와 분석 실패, 재전송과 재조사를 구분한다.
- 세 앱이 이 PC에서 실행되며 web 빌드와 관련 테스트가 통과한다.
- scenario-runner는 `--report runtime/scenarios.json`을 받아 아래 형식으로 실제 검증 결과를 저장한다.
- `./scripts/dev verify-mvp`가 최신 buildId에서 통과하고 실제 보고서·화면·측정과 코드가 main에 공유됐다.

검증 결과는 buildId, mode=`live`, 실제 model, cases 배열을 포함한다.
cases의 id에는 VOC-01~07, NORMAL, NEEDS_INPUT, IDEMPOTENCY, RECOVERY가 각각 있어야 하며,
각 항목에 status=`PASSED`, mocked=false와 실행·근거 식별자를 남긴다. 실패 시 0이 아닌 종료 코드를 반환한다.
각 case의 행동 검증은 담당 계약을 따른다. 단순히 성공 문자열만 기록하지 않는다.
공통 실행 골격의 smoke 통과는 MVP 통과가 아니다. 외부 배포가 미검증이면 로컬 완료와 구분해 기록한다.

## 세 담당자 공통 goal 종료 조건

위 조건을 통과하고 코드를 공유한 뒤 `./scripts/dev role-done voc`로 실제 MVP를 검증한다.
생성된 자기 완료 JSON을 커밋·publish해 GitHub에 DONE을 전달한다. 이 시점에도 goal을 유지한다.
[세 담당자 완료 기준](../team-completion.md)에 따라 다른 담당자의 요청·변경을 확인하며 연동·수정·재검증을 계속한다.
소스·계약·검증 기준이 바뀌면 최신 코드에서 자기 완료를 갱신한다. 문제가 발견되면 role-reopen voc로 철회한다.
세 담당자의 DONE 이후에도 개발리더 이상효의 독립 검토·지적 처리·재검증을 지원한다.
docs/status/lead.md와 lead-review.json을 확인하고, 리더의 수정이 반영되면 최신 코드에서 자기 DONE을 갱신한다.
GitHub main의 세 유효한 DONE과 리더 APPROVED가 모두 있고 `./scripts/dev team-check`가 성공해야 goal을 완료한다.
