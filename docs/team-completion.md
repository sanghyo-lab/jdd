# GitHub에서 세 담당자의 완료를 확인하는 기준

사용자가 정한 공통 goal 종료 조건은 **이상효·한재홍·김아름이 GitHub main에 모두 완료를 기록하는 것**이다.
자기 기능을 끝낸 뒤에도 자신의 goal을 완료 처리하지 않는다. 세 담당자의 유효한 완료 기록이 모일 때까지
새 변경 확인, 연동 요청 처리, 데이터·회귀·통합 검증을 계속한다.
사람이 중간에 다시 승인할 필요 없이 각 담당자의 PC에서 위임받은 개발 에이전트가 검증 후 자기 기록을 작성한다.
다른 담당자의 완료를 대신 선언하지 않는다.

## 역할 완료와 goal 종료

| 담당자 | 역할 | GitHub main에 공유할 완료 기록 |
| --- | --- | --- |
| 이상효 | commerce | [commerce.json](status/commerce.json) |
| 한재홍 | agent | [agent.json](status/agent.json) |
| 김아름 | voc | [voc.json](status/voc.json) |

각 파일의 상태는 IN_PROGRESS 또는 DONE이다. 설명·진행 상황·연동 요청은 기존 역할별 Markdown에 기록한다.
IN_PROGRESS는 최초 준비·개발·재작업·외부 입력 대기를 포함하며 구체적인 이유는 Markdown으로 공유한다.
자기 기능이 끝나도 전체 실제 통합 검증을 통과하기 전에는 DONE을 기록하지 않는다.

DONE에는 검증한 전체 커밋 SHA, 저장소 내용의 해시, 검증 시각, 명령, buildId, 실제 모델명,
VOC-01~07·NORMAL·NEEDS_INPUT·IDEMPOTENCY·RECOVERY의 검증 요약이 필요하다.
실제 데이터·소스·로그·보고서·화면에 대한 역할별 완료 조건도 모두 만족해야 한다.
원본 로그·프롬프트·DB 내용·API 키는 완료 기록에 포함하지 않는다.

## 자기 완료를 공유하는 순서

아래 commerce는 자기 역할로 바꾼다. agent는 한재홍, voc는 김아름이다.

1. 담당 기능·데이터·문서·검증을 완료하고 자기 상태 Markdown에 결과와 미해결 요청을 정리한다.
2. 변경을 명시적으로 커밋하고 `./scripts/dev publish`로 공유한다. 먼저 소스·검증 기준을 원격에 올린다.
3. 깨끗한 main에서 아래 명령을 실행한다.

```bash
./scripts/dev role-done commerce
git add -- docs/status/commerce.json
git commit -m "chore(commerce): record verified role completion"
./scripts/dev publish
./scripts/dev team-check
```

role-done은 최신 main을 동기화하고 미공유 구현 커밋이 없는지 확인한 뒤 verify-mvp를 실제 실행한다.
실패하면 DONE을 작성하지 않는다. 통과하면 자기 JSON만 갱신한다. commit·push 전 로컬 기록은 팀 완료에 포함되지 않는다.
publish 도중 다른 사람이 소스를 바꾸면 방금 올린 기록도 STALE로 판정될 수 있다. 최신 코드에서 검증 후 다시 기록한다.

## 세 명이 끝날 때까지 반복

```bash
./scripts/dev status
./scripts/dev team-status
```

status와 watch에도 원격의 세 완료 상태가 표시된다. team-status는 최신 GitHub main 한 커밋에서
세 JSON을 읽어 DONE·IN_PROGRESS·STALE·MISSING·INVALID와 전체 판정을 출력한다. 로컬 파일은 판정에 사용하지 않는다.

- 자기 기록이 아직 IN_PROGRESS이면 남은 구현·데이터·검증을 진행한다.
- 자기 기록이 유효한 DONE이면 goal을 유지하고 상대방의 요청, 통합 실패, 새 커밋을 확인한다.
  불필요한 기능을 추가하지 않고 약 60초 간격으로 원격을 확인한다. 변경이 없다면 비용이 큰 동일 검증을 반복하지 않는다.
- 새 코드·설정·계약·검증 기준이 반영되면 기존 DONE은 자동으로 STALE 판정되어 전체 완료에서 제외된다.
  main을 반영하고 영향 범위를 점검한 후 role-done으로 실제 검증을 다시 수행한다.
- 코드가 그대로여도 실제 실패나 미해결 요청을 발견하면 자기 DONE을 즉시 철회한다.

```bash
./scripts/dev role-reopen commerce
```

철회 이유를 자기 Markdown에 기록하고 JSON과 함께 커밋·publish한다. 다른 사람의 JSON을 대신 수정하지 않는다.
요청받은 사람은 자신의 상태에 접수·조치·확인 결과를 남긴다.

## 종료 판정

깨끗한 최신 main에서 `./scripts/dev team-check`가 **종료 코드 0**을 반환할 때만 goal을 complete로 처리한다.
이 검사는 fetch한 원격 main의 세 담당자가 모두 DONE이고, 그 기록들이 모두 현재 저장소 내용을 검증했으며,
필수 실제 모델 시나리오의 성공 요약이 있는지 확인한다. 로컬 미커밋·미공유 변경이나 미반영 원격 변경도 허용하지 않는다.
하나라도 미완료·누락·오래된 기록·잘못된 결과이면 0이 아닌 종료 코드로 실패한다.

완료 기록 자체를 커밋하면 커밋 SHA가 바뀌므로, 세 기록의 SHA가 같을 필요는 없다.
대신 검증한 커밋이 원격 main 이력에 있어야 하고 **docs/status/를 제외한 모든 추적 파일의 내용·경로·모드**가
현재 원격과 같아야 한다. 코드·테스트·시드·스크립트·설정·계약·goal 기준이 바뀌면 재검증한다.
진행·완료 기록만 바뀌는 커밋은 다른 담당자의 완료를 무효화하지 않는다. docs/status에는 실행 코드나 검증 기준을 두지 않는다.

이 검사는 공유 기록과 검증 요약의 일관성을 확인한다. 실제 실행을 대신하거나 GitHub 작성자의 신원을 강제하는 인증 장치는 아니다.
각 담당자는 자기 PC에서 실제 검증을 실행하고 사실대로 기록하며, 타인의 DONE을 대필하거나 성공 결과를 만들어 넣지 않는다.
종료는 마지막 fetch 시점의 main에 대한 판정이다. 종료 후 새 작업을 시작하면 새 goal을 실행한다.

사용자의 중단 지시, 실행기 사용량 제한·정책, 인증·장비 중단은 별도로 적용된다.
외부 입력 때문에 실제로 진행할 수 없으면 원인을 기록하고 실행기의 blocked 규칙을 따르며, 이를 완료로 표시하지 않는다.
한 세션은 다른 PC의 goal을 자동 시작하거나 재시작하지 않는다.
