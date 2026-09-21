# DISC-20260921-agent-002 — buildId별 정상 정책 원문 보관

| 항목 | 내용 |
| --- | --- |
| ID | DISC-20260921-agent-002 |
| 상태 | RESOLVED |
| 제안 버전 | P1 |
| 작성자 / 역할 | 한재홍 / agent |
| 정리 담당 | 한재홍 |
| 영향받는 역할 | commerce, agent, voc |
| 필수 합의자 | 이상효(정책·제공자), 김아름(공통 snapshot), 한재홍(조회) |
| 확인·답변 대기 | 없음 |
| 생성 시각 / 최종 갱신 | 2026-09-21T18:24:49+09:00 / 2026-09-21T22:46:02+09:00 |
| 다음 행동 / 담당 | P1 정책 보관·세 담당자 직접 인수 완료. 실제 모델 품질·화면은 별도 진행 |

## 결정할 질문

새 build부터 정상 정책 원문과 SHA-256을 buildId 스냅샷에 보관하고 Agent가 그 사본을 읽는 방식에 합의하는가?

## 배경과 확인 근거

- `f715885`의 실제 조회 도구와 [커머스 계약](../commerce-interface.md)은 manifest의 `policyVersion`과 현재 `docs/business-policy.md`를 대조한다. `scripts/jdd.py`의 snapshot은 소스 파일 해시를 보관하지만 정책 사본·정책 해시는 기록하지 않는다.
- 확인된 사실: 같은 buildId의 Java/DDL을 다시 읽을 수 있고 Agent에 이미 저장한 정책 원문도 재조회할 수 있다. 하지만 아직 조사하지 않은 과거 build의 정책은 현재 파일에 의존한다.
- 추론한 실패 상황: 현재 정책이 수정되면 과거 로그·소스와 다른 정책을 새 조사에 사용할 수 있다. 동일 버전을 실수로 유지해도 현재 형식만으로 감지할 수 없다. 실제 데모의 정책 불일치 사고가 발생했다는 주장은 아니다.
- 재현 기준: 정책 A로 build A 생성 → 정책 B로 새 build 생성 → Agent가 A를 조회할 때 A 원문·해시를 반환해야 한다. 현재 생성기에는 A 정책 사본이 없어 이 보장이 없다.

## P1 제안과 대안

- 새 manifest에 추가 필드 `policy: {version, path, sha256}`를 기록한다. 경로는 build 디렉터리 안의 고정 `policy/business-policy.md`이며 기존 `policyVersion`을 유지하고 두 버전이 일치해야 한다.
- snapshot은 정상 업무 정책만 복사하며 기존 build 내용을 덮어쓰지 않는다. Agent `searchCode`의 네 허용 소스 범위를 넓히지 않는다. `readBusinessPolicy`만 고정 경로·manifest·버전·해시를 검증한다.
- 현행 정책의 저장본을 읽는 하위 호환 모드는 조회 시점 원문임을 명시한다. 데모 실제 모델 검증은 새 archive가 있는 build로 수행한다. 기존 저장 근거 GET은 항상 저장된 원문을 반환하므로 영향을 받지 않는다.
- 공통 생성기·계약 변경은 voc 유지 범위다. 중복 작업을 피하기 위해 김아름이 구현 또는 한재홍에게 이 작은 공통 변경을 위임하는 의견을 부탁한다. Agent 소비·테스트는 한재홍이 맡고 이상효는 정책 버전 기준과 제공자 스냅샷을 확인한다.
- 대안은 정책을 영구 동결하는 것이지만 향후 변경 때 수동 절차에 의존한다. 현재 파일만 계속 사용하는 방식은 build별 재현을 보장하지 못한다.
- 합의 전에는 기존 근거 인수 결과를 유지하고 OpenAI 어댑터·모의 usage/장부 검증을 계속한다. 기존 manifest를 변조하거나 과거 사본을 소급해서 만든 것으로 표시하지 않는다.

## 해소 기준

- [x] 세 작업자가 P1의 영향과 구현 담당을 직접 확인·수락했다.
- [x] 새 manifest의 정책 사본·버전·해시와 기존 필드 호환이 구현됐다.
- [x] 정책 변경 후 과거 build 조회, 사본 변조·경로 이탈·버전 불일치의 거절, 저장 근거 재조회가 검증됐다.
- [x] 실제 새 snapshot을 Agent가 읽고 공유 커밋·문서/목록을 갱신했다.

## 답변 기록

### 이상효 — commerce / lead

2026-09-21T18:36:02+09:00 / 이상효 / commerce·lead / P1

- 의견: 수락. 정상 정책 원문을 buildId의 불변 사본으로 보관하고 `policyVersion`과 `policy.version`이 일치해야 한다. 업무 정책 내용을 바꿀 때는 버전을 올리며 같은 버전의 사본 변조도 SHA-256으로 거절한다.
- 현재 `scripts/jdd.py`는 정책을 build 내용 해시에 포함하지만 사본은 만들지 않는 것을 직접 확인했다. 과거 빌드의 원문 확보를 현재 파일로 소급 주장하지 않는다. 새 archive를 갖춘 빌드로 실제 데모를 수행하는 기준에 동의한다.
- 제공자 확인: commerce 업무 API·로그에는 변경이 없고, 소스 검색에 정답/fixtures를 포함하지 않는다. 새 생성기가 공유되면 이 PC의 실제 snapshot·재기동·Agent 소비에서 버전/경로/해시를 확인한다.
- 구현 조율: 공통 유지 담당 김아름이 P1 수락과 생성기 담당을 직접 답변해 달라. 기존 제안대로 한재홍에게 생성기 변경을 위임하는 경우 Agent 소비 변경과 같은 단위에서 검증할 수 있다. 나는 진행 중인 해당 경로를 중복 편집하지 않는다.
- 아직 VOC 답변·구현·과거 정책 조회/변조 거절의 검증이 남아 OPEN이다.

### 이상효 — commerce / lead 추가 인수

2026-09-21T20:52:00+09:00 / 이상효 / commerce·lead / P1

- 의견: 제공자·독립 소비 검증 완료. `6b9ca6f`의 전체 publish 후 실제 buildId `6b9ca6f0de96-e27548161776`에서 소스 35개·정책의 바이트/해시를 실제 Agent 조회 클래스로 대조했다. 현재 정책을 없는 경로로 지정해도 archive를 읽으며 이전 정책 사본 없는 build의 manifest·소스 36개 해시는 그대로다.
- 같은 build의 실제 PostgreSQL 재고 재현 1회로 8도구·25근거의 저장과 HTTP 재조회를 통과했다. 정책 `demo-v1`, `policy/business-policy.md`, `manifest-policy-snapshot-hash`와 보관본 22~29행 원문·SHA-256·커밋이 일치한다. 모의 모델 2회·유료 0회이며 실제 모델 판단 검증은 아니다.
- 자료·명령·XML: [commerce 상태](../status/commerce.md)의 같은 시각 기록과 로컬 `generated-policy-consumption-01/`, `generated-policy-handoff-01/`, `policy-generator-legacy-baseline.json`. 조사 인수는 1개 통과·선택 6업무 입력 없는 1개 건너뜀·실패 0이다. 한재홍의 직접 인수/정리 답변은 대신 작성하지 않아 AGREED를 유지한다.

### 한재홍 — agent

2026-09-21T18:24:49+09:00 / 한재홍 / agent / P1

의견: 제안·수락. 현재 도구는 관측한 정책 해시와 보장 범위를 저장하며 과거 원문 보관을 주장하지 않는다. 합의 후 archive가 있는 manifest를 우선 읽는 소비자와 변조/과거 조회 검증을 구현한다. 이 제안 자체는 구현·통합 검증 완료가 아니다.

2026-09-21T19:12:00+09:00 / 한재홍 / agent / P1

- 의견: Agent 소비자 구현·검증 공유. `a545332`에 고정 archive 경로·policyVersion 일치·SHA-256·정책 원문 버전 검사를 반영했다. policy가 선언되어 있으면 null/누락/변조/다른 경로에서 현재 파일로 대체하지 않는다. 기존 policy 필드 없는 manifest만 제한을 표시하는 호환 모드다.
- 검증: 파일/도구 14개에서 A/build A 후 B/build B, 현재 정책 변경/삭제에도 과거 정책 조회, 변조/경로/버전/symlink 거절, 커밋된 근거 재조회·다른 조사 접근 거절을 통과했다. 기존 실행기 8개·전체 publish도 통과했다. `policy-archive-final.log`, `policy-archive-stored.log`, `policy-archive-publish.log`에 원문을 보존했다.
- 모델 조회 연결: searchCode/readCode의 출처·요약에 실제 manifest policyVersion을 제공한다. 모델은 이 값으로 정책을 조회하며 파일 검색 허용 범위는 그대로다.
- 남은 조건: 김아름의 공통 생성기가 만든 새 실제 snapshot을 이 PC에서 읽고 저장/재조회한다. 합성 archive 단위 검증을 실제 생성기 인수로 표시하지 않으며 기존 snapshot을 변조하지 않는다.

### 김아름 — voc

2026-09-21T18:32:59+09:00 / 김아름 / voc / P1

- 의견: 수락. `scripts/jdd.py`의 snapshot과 `SourceEvidenceTools.readPolicy`를 읽어 현재 정책 버전만 고정되고 과거 원문은 보관되지 않는 차이를 확인했다.
- 담당 결정: 공통 snapshot 생성기·관련 계약·회귀 검증은 김아름이 맡는다. 한재홍의 Agent 조회 구현과 분리해 진행하며 공통 생성기를 중복 편집하지 않는다.
- 호환 기준: 새 build에만 고정 `policy/business-policy.md`의 사본·SHA-256·버전을 추가한다. 기존 policyVersion을 유지하고 사본의 버전과 일치시킨다. 기존 build를 덮어쓰거나 과거 정책을 소급 생성하지 않으며 searchCode의 소스 허용 범위를 넓히지 않는다.
- 확인한 추가 호환성 문제: Windows에서 생성한 `25e8979c9b2c-23329cda2fa8` manifest의 소스 경로 25개가 역슬래시인 반면 Agent 허용 경로는 슬래시다. 새 snapshot의 경로 직렬화도 POSIX 형식으로 맞추고 Linux 컨테이너 소비를 검증한다. 기존 snapshot은 변조하지 않는다.
- 검증 계획: 정책 A/build A 이후 정책 B/build B를 만들고 A 원문 유지, 변조·경로 이탈·버전 불일치 거절과 실제 Agent 소비를 확인한다. 현재는 검토·담당 수락이며 구현·검증 완료가 아니다. 이상효의 P1 답변과 실제 구현·소비가 남아 미해소다.

2026-09-21T18:44:47+09:00 / 김아름 / voc / P1

- 의견: 생성기 구현·검증 결과. 정책 바이트 사본·SHA-256·실제 선언 버전과 POSIX 경로를 기록하며, 기존 build·파일·정책 메타데이터의 불일치와 파일/상위 디렉터리의 링크를 거절한다. 정책 사본 없는 과거 build를 현재 정책으로 덮어쓰거나 소급 보완하지 않는다.
- 회귀 검증: 수정 전 38개 협업 테스트 중 새 정책/경로 검사 실패를 확인했다. 수정 후 Linux 전체 38개와 Windows 네이티브 정책 보존·경로·변조·구형 사본 보호 4개가 통과했다. 정책 A 이후 정책 B에서도 A 원문 유지, 잘못된 버전/UTF-8·변조·경로 이탈·링크 거절을 확인했다.
- 실제 경로 소비: Windows 개발 snapshot `c2bdb4e1542c-983995caabc9`의 허용 소스 25개를 Linux 컨테이너의 실제 SourceEvidenceTools로 읽고 원문·해시를 대조해 통과했다. 모델·DB 호출은 없다. 이것은 새 정책 사본의 Agent readBusinessPolicy 소비 완료를 뜻하지 않는다.
- 다음 행동: 생성기·계약·검증을 전체 publish로 공유한다. 원격과 통합하며 이상효의 수락·전원 합의를 확인해 AGREED를 유지했다. 한재홍의 archive 우선 조회·과거 형식 호환·저장 근거 재조회와 실제 새 build 소비 결과, 이상효의 새 snapshot 검증이 남아 있다.

## 결정·실행·검증

2026-09-21T20:39:02+09:00 / 김아름 / voc / P1 — 생성기 공유·실제 소비 결과:

- 0dd5bf9의 생성기·계약·회귀 검사를 전체 publish로 공유했고 8d80786의 원격 포함을 확인했다. 최신 빌드 8d8078685a0f-40824ff52249를 Windows에서 생성했다.
- 해당 빌드의 소스 35개 원문/해시와 보관 정책을 실제 Agent SourceEvidenceTools로 읽었다. 현재 정책 경로를 존재하지 않는 경로로 지정해도 manifest-policy-snapshot-hash로 보관 원문·해시가 일치했다. 모델 호출은 없다.
- 로컬 재현 도구 runtime/verification/SourceSnapshotProbe.java와 publication clone의 runtime/evidence/source 아래 실제 사본을 사용했다. 이는 이 PC의 실제 파일 소비 검증이며 다른 PC의 저장 근거·HTTP 재조회 성공을 대신하지 않는다. Agent·commerce 직접 인수가 남아 AGREED를 유지한다.

- 세 담당자가 P1을 직접 수락했다. 공통 생성기·계약은 김아름, 조회·저장 소비는 한재홍, 제공자 확인은 이상효가 맡는다. 김아름의 생성기·파일 소비와 이상효의 실제 파일/DB/HTTP 인수를 기록했다.
- 기존 구현 `f715885`, 새 Agent 소비 `a545332`와 [Agent 상태](../status/agent.md)를 제공했다. 아래 Agent 직접 인수로 마지막 조건을 충족했다.

## 해소 또는 재개 이력

2026-09-21T18:24:49+09:00 한재홍: 현재 정책 마운트와 과거 build 연결의 차이를 확인해 P1 등록. OPEN.

2026-09-21T18:32:59+09:00 김아름: P1 수락·공통 생성기 담당 확정. commerce 직접 답변과 구현·검증 대기, OPEN 유지.

2026-09-21T18:38:12+09:00 이상효: 동시 공유된 VOC 수락·생성기 담당과 내 답변을 모두 보존해 통합했다. 전원 P1 수락으로 AGREED, 구현·소비 검증은 남아 있다.

### 2026-09-21T22:46:02+09:00 — 한재홍 / agent / P1 직접 인수·해소

- 의견: 새 생성기 소비를 직접 인수했다. 공유 `8bd5699`를 인증 없는 별도 clone에 반영하고 test/mock으로 세 앱·PostgreSQL을 재빌드/기동했다. 실행 중 조사가 없음을 확인한 뒤 고유 합성 VOC-07을 한 번 재현했으며 기존 자료는 삭제하지 않았다.
- buildId `8bd5699533be-057bb12f48d9`에서 독립 두 트랜잭션·주문 두 건·stock -1을 확인했다. 실제 Agent 조회/저장/HTTP 재조회는 8도구·25근거, 모의 모델 2회·유료/OAuth 0회로 통과했다. 선택 VOC-01~06 입력을 주지 않은 추가 검사 1개는 건너뛰었으며 성공으로 세지 않는다.
- 정책 근거 `180f3882-facc-40f2-96bb-83b6c68fdb8e`는 `demo-v1`, `policy/business-policy.md`, `manifest-policy-snapshot-hash`, 22~29행과 SHA-256 `70d6b1b514e7edc1714e46b1d0542fc70663491c6d7432ca2739ba72b2156532`를 저장했다. 조사 `8ef25898-16ab-4e9d-81c7-1dfa5ccd9a82`의 저장 원문/출처를 HTTP로 다시 읽었다.
- 원문: `runtime/submission/agent-20260921/followup-policy.log`, `followup-policy-handoff/command.json`, `result.json`, JUnit XML. 재현 원문은 publication clone의 `20260921T134349.139201Z-inventory.json`이다.
- 앞선 `a545332`의 과거 정책·변조 거절/저장 재조회, 김아름 `0dd5bf9`와 이상효의 직접 인수에 이번 Agent 소비까지 확인했다. 정리 담당으로 P1을 RESOLVED로 갱신한다. 실제 모델 조사 품질·역할 DONE·팀 완료를 뜻하지 않는다.
