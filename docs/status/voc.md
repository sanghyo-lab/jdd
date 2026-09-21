# 김아름 — VOC 티켓·AI 연동 작업 상태

- 상태: 티켓 API와 분석 요청·불변 입력·이력 저장을 main에 공유하고 실제 PostgreSQL·앱 재시작 보존을 검증했다. Agent 전달/조회 worker·화면·runner는 진행 대상이다.
- 담당자: 김아름 (역할 C)
- GitHub 계정: `AhReumKim-ar`
- 작업 브랜치: `main`
- 완료 선언: [voc.json](voc.json)의 IN_PROGRESS. 실제 검증 후 자기 DONE을 공유하고 [세 담당자 완료 기준](../team-completion.md)이 충족될 때까지 goal을 유지한다.
- 시작 지침: [voc goal](../goals/voc.md), [공통 실행](../local-development.md)
- 공유 커밋: 티켓 `d8246e9`, 검증 안내 `d5d5484`, 주문 시각 정밀도 수정 `1d29d20`, 분석 요청 저장 `4de1a98`
- 담당 경로: `voc-app/`, `voc-core/`, `voc-infra/`, `web/`, `scenario-runner/`
- 준비된 자료: [구현 범위](../roles/kim-areum-voc.md), [VOC·Agent 계약](../integration-contract.md), [커머스 계약](../commerce-interface.md), [프론트 설계](../frontend-deployment.md)
- 다음 작업: 서버 Agent 전달/조회·근거 중계 단위 전체 publish와 실제 상대 앱 재시작 인수, 이후 한국어 화면·runner 구현
- 제공받은 입력: Agent 조사 API·실행기·8개 조회 도구, commerce VOC-07/02/03 재현 자료. 실제 모델 검증 허용 범위·배포 환경은 별도다.
- 검증 결과: 티켓·분석 HTTP/H2와 실제 PostgreSQL 계약, 전체 Gradle check, 세 앱 Docker 기동·smoke와 앱 재생성 후 티켓·분석 입력/이력/동일 키 보존 통과. 프론트·VOC runner·실제 모델은 미검증.
- 연동 요청: 아래 논의의 P1 수락과 공통 생성기 책임을 기록했다. scenario-runner는 아직 미구현을 알리는 실패 종료 골격이다.

작업 단위가 끝날 때 제공 가능한 기능, 변경한 계약, 실제 검증 명령·결과, 다음 작업을 갱신한다. 실패와 막힌 이유도 함께 기록한다.

## 2026-09-21 — 건별 논의와 답변 공유 체계

- 요청 범위: 공통 AGENTS와 Agent 상태의 소통 경로, [논의 목록·작성 규칙](../discussions/README.md), [건별 양식](../discussions/TEMPLATE.md)을 추가한다.
- 제공 내용: 각 작업자가 같은 논의 Markdown에 직접 답변하고 답변 작성자가 목록의 상태·대기자·해소 근거·집계를 함께 갱신하는 절차. 최신 제안 합의와 실제 해소 검증을 구분한다.
- 기존 협업 문서의 역할 상태에만 답변하던 안내를 새 경로와 맞췄다. 작업 단위마다 검증 후 즉시 main 커밋·publish하는 사용자 규칙도 반영했다.
- 새 논의 건·타인의 답변·합의 결과는 임의로 만들지 않았다. 목록은 등록 0건이며 기존 요청의 해결을 의미하지 않는다.
- 검증: Windows의 Python으로 scripts/check_docs.py와 git diff --check 통과. 전체 publish 시도는 Windows 심볼릭 링크 권한 부족을 확인해 중단했다. 전체 테스트·앱 연동 성공으로 기록하지 않는다.
- 후속 조정: 문서 공유가 환경 준비를 기다리지 않도록 협업 지침·논의·상태 설명만 변경한 단위는 최신 main 통합·범위 확인·문서 검증 후 즉시 일반 push한다. 코드·설정·API/업무 계약·검증 기준·완료 JSON은 기존 전체 검증 절차를 유지한다.
- 이번 변경은 소통 문서와 규칙이며 서비스 업무 기능 구현·실제 모델 검증·역할 DONE은 수행하지 않았다.
- 공유 결과: 논의 체계 `0196fc4`, 문서 즉시 공유 절차 `5b11710`을 최신 원격 변경과 통합해 origin/main에 일반 push했고 포함 여부를 확인했다. 공유 시 문서 검사 39개 Markdown·294개 링크·8개 JSON 예제와 미공유 diff 검사를 통과했다.
- 실행 환경 확인: 별도 Gradle 검사는 services.gradle.org 이름 조회 실패로 중단됐다. Windows 협업 테스트는 심볼릭 링크 권한 부족을 확인해 완료 전에 중단했다. Linux 테스트 이미지는 준비됐지만 전체 테스트·앱 smoke 통과는 주장하지 않는다. 이 제한은 협업 문서 공유와 분리해 이후 코드 검증 시 해결한다.

## 2026-09-21 — VOC 개발 Goal 착수

- 첫 구현: v1 티켓·담당자 API, 실제 DB 저장, 필터·정렬, PATCH의 생략/null 구분과 낙관적 버전 충돌을 구현한다.
- 접수: JDD-VOC-001/002/007/009를 담당 범위로 수용한다. 티켓 이후 비동기 분석 전달·조회·근거 소속, 순차 runner, 비용/인증 오류 표시를 이어 구현한다. 수용은 구현·검증 완료가 아니다.
- AGENT-NGROK-001 수용: 로컬 Next.js web의 고정 API 중계·화면/API 접근 제어·127.0.0.1:3000 수신을 구현한다. 내부 Compose 주소와 web 호스트 주소를 구분한다. 공개 터널 검증은 계정·정책·실제 URL이 준비된 뒤 수행한다.
- 연동 대기: commerce의 시드·초기화·VOC-07 재현, Agent의 실제 실행기·API·근거를 수령하면 소비자 검증을 수행한다. 대기 중 계약 기반 독립 구현을 계속한다.
- 남은 논의: JDD-VOC-003의 오류 매핑과 JDD-VOC-008의 선택적 runner 메타데이터는 건별 논의로 정리한다. 기존 v1 계약을 임의로 바꾸지 않는다.
- 모델 제한: 개발·CI에서 데모 키를 호출하지 않는다. 실제 모델·공개 URL·MVP·DONE은 미검증이다.

## 2026-09-21 — Agent 오류 처리 제안 답변

- [DISC-20260921-agent-001](../discussions/DISC-20260921-agent-001-llm-errors.md)의 P1을 수락했다. 설정·일시 장애·예산 오류를 구분하고 자동 재조사·예산 우회 없이 전달/조회 오류와 조사 실패를 별도 표시한다.
- 건별 문서에 김아름의 답변을 남기고 목록을 함께 갱신했다. 동시 공유된 이상효의 수락도 보존해 전원 P1 수락을 확인했다. AGREED이며 실제 구현·소비자 검증이 남아 있으므로 해소하지 않았다.
- 원격 `b2b46ef`의 실제 조사 접수·조회 API 제공을 확인했다. QUEUED 상태의 영속 접수이며 조사 실행·리포트 완성은 아니다. 티켓 다음 단위에서 실제 접수 연결을 진행한다.
- 현재 티켓 단위 검증: HTTP/H2 계약 5개·기존 앱 테스트 1개와 Linux의 기존 협업 테스트 31개 통과. PostgreSQL과 전체 앱 smoke는 진행 중이다. 이 문서 공유를 코드·모델·MVP 완료로 기록하지 않는다.

## 2026-09-21 — 영속 티켓 API 첫 전달 단위

- 제공: [VOC API 안내](../../voc-app/README.md)의 생성·목록·상세·수정·담당자 API. PostgreSQL 저장, 상태/담당자 필터·안정적 정렬, PATCH의 생략/null/빈 context, 버전 비교 SQL을 구현했다.
- 저장 안전성: 같은 expectedVersion의 동시 수정은 200 한 건·409 한 건이며 덮어쓰지 않는다. 조사 대상 occurredAt은 UTC로 정규화하고 소수점 정밀도를 그대로 저장한다. 생성/수정 시각은 DB 정밀도에 맞춘다.
- 실제 검증: `gradlew.bat :voc-app:test`의 HTTP/H2 계약 5개·기존 앱 1개, 별도 PostgreSQL 17.6의 동일 HTTP 계약 5개 통과. 시각 정밀도 보존을 보강한 뒤 PostgreSQL 5개를 다시 통과했다. 테스트 DB는 `jdd_voc_contract_test`로 한정했다.
- 공통 환경: 공식 SHA-256으로 검증한 Gradle 9.3.1 배포본을 Wrapper 캐시에 준비했다. Windows의 심볼릭 링크 권한 제한은 테스트를 생략하지 않고 Linux에서 기존 협업 테스트 31개를 그대로 실행해 통과했다.
- 공유 절차: 사용자 미추적 `docs/ralphthon-readiness.md`는 보존한다. 깨끗한 main 복사본에서 기존 publish의 전체 검사·3개 앱 기동·smoke·일반 push를 수행한다. 전체 실행·공유 결과는 확인 후 추가한다.
- 범위: analyses는 아직 빈 배열이고 businessReady=false다. Agent 실제 조사·분석 요청·웹·MVP·DONE은 완료하지 않았다. 데모 모델 호출은 수행하지 않았다.
- 소비자 인계: 티켓 수정 응답의 version을 새 분석에 사용한다. 재전송 키·스냅샷·조사 이력은 다음 단위에서 제공한다. 원격 Agent 접수 및 실행 저장소 변경을 통합했다.

## 2026-09-21 — 통합 빌드의 주문 시각 정밀도 수정

- 원격 `66b04cc`까지 통합한 전체 check에서 CommerceHttpTest.createsPersistsListsAndExportsCommittedEvidence가 실패했다. 생성 응답의 createdAt/updatedAt `09:05:11.124257700Z`가 DB 조회 후 `09:05:11.124258Z`로 달라졌다. 당시 commerce-app/build/test-results의 실패 내용을 확인했다. 해당 경로는 재검증 시 최신 보고서로 갱신된다.
- 공통 빌드를 막는 작은 호환성 수정으로 commerce OrderService의 저장·응답 시각을 DB의 마이크로초 정밀도로 맞췄다. 의도한 재고·재전송 결함과 업무 계약은 변경하지 않는다. 다른 담당자의 상태·완료 기록도 수정하지 않는다.
- 회귀 테스트는 운영체제의 시계 해상도와 관계없이 재현되도록 기존 HTTP 테스트에 나노초가 있는 고정 Clock을 주입했다. 생성 응답과 재조회 전체 응답의 동일성 단언을 그대로 유지한다. 수정 후 관련 테스트·전체 publish 검증을 다시 수행한다.
- 동시 공유된 쿠폰 적용 코드와 충돌한 한 구간은 쿠폰 계산·사용 기록과 시각 정규화를 모두 보존해 통합했다. 추가된 쿠폰 테스트의 유효기간 입력도 동일한 테스트 Clock을 기준으로 맞춰 실제 날짜에 따라 결과가 달라지지 않도록 했다.

## 2026-09-21 — 선택적 runner 메타데이터 범위 제안

- JDD-VOC-008을 [DISC-20260921-voc-001](../discussions/DISC-20260921-voc-001-runner-metadata.md)로 등록했다. 기존 필수 완료 계약을 유지하고 실제 관측 전달 방식 합의 전에는 modelsUsed·usageSummary·verificationProfileId 구현과 필수 판정 추가를 보류하는 P1이다.
- 김아름의 제안·수락만 기록했으며 한재홍·이상효 답변을 기다린다. 기존 티켓·분석·순차 runner 구현은 이 선택 확장 때문에 보류하지 않는다. 실제 사용량·모델 결과·완료 JSON을 만들지 않았다.
- 문서 검사와 diff 검사를 통과했다. 코드 publish가 끝나는 안전한 경계에서 원격 변경을 통합하고 협업 문서 공유 절차로 즉시 전달한다.

## 2026-09-21 — 티켓 단위 실제 공유와 협업 회신

- 전체 publish 결과: 원격 `92dc81a`와 통합한 `1d29d20`을 일반 push했고 원격 포함을 확인했다. 코드 `d8246e9`와 주문 시각 회귀 수정이 함께 공유됐다. 동시 push에 따른 재통합·검증을 수행했으며 실패한 검증을 생략해 공유하지 않았다.
- 검증 환경: 새 Agent 파일 테스트도 Windows 심볼릭 링크 권한 부족으로 실패했다. 로컬 게시 도구에서 동일한 전체 Python/Gradle 검사를 Linux로 실행했다. Python 31개 통과, Java 79개 중 73개 통과·외부 DB/스택 환경을 요구하는 조건부 6개 건너뜀·실패 0개다. 이 6개를 실제 검증 통과로 계산하지 않는다. 티켓의 별도 PostgreSQL 계약 5개 통과는 위 기록과 같다.
- 실행 결과: buildId `1d29d20e8a54-e2ffbd8aa690`의 PostgreSQL과 세 앱 기동·마이그레이션·VOC HTTP 연결·Agent SELECT 전용 권한·근거 마운트 smoke 통과. 앱 재생성 후 같은 합성 티켓 ID·버전 2·필드·소수점 정밀도·시각 보존을 다시 확인했다. 원문은 로컬 `runtime/publication/voc/runtime/smoke.json`, `runtime/verification/linux-gradle-check`, `runtime/verification/ticket-restart.json`에 있다.
- [DISC-20260921-commerce-001](../discussions/DISC-20260921-commerce-001-inventory-evidence.md): P1 수락과 runner 책임을 답변했다. 세 역할의 수락이 모여 AGREED다. 이 PC의 VOC runner 소비 검증은 남아 있으며 다른 담당자의 20회 결과를 자기 검증으로 기록하지 않는다.
- [DISC-20260921-agent-002](../discussions/DISC-20260921-agent-002-policy-snapshot.md): P1 수락, 공통 snapshot·계약·검증을 맡았다. Windows manifest 경로의 역슬래시와 Agent 허용 경로 불일치도 확인해 함께 수정할 예정이다. 현재 smoke는 마운트 존재만 검사하므로 이를 소스·정책 소비 성공으로 해석하지 않는다.
- Agent `71d74aa` 이후 기본 worker는 모델 비활성 상태에서 조사 FAILED/LLM_CONFIGURATION_ERROR를 저장한다. 다음 VOC 연동은 QUEUED 유지로 가정하지 않고 실제 실패·전달 오류·조회 오류를 구분한다. 실제 모델 호출은 0회이며 분석·화면·MVP·DONE을 완료하지 않았다.

## 2026-09-21 — 정책 보관과 Windows 소스 경로 호환

- [DISC-20260921-agent-002](../discussions/DISC-20260921-agent-002-policy-snapshot.md)의 공통 생성기·계약을 구현했다. 새 build의 정책 원문을 바이트 그대로 보관하고 선언 버전·고정 경로·SHA-256을 연결한다. manifest 소스 경로를 POSIX 형식으로 통일하고 정책은 소스 검색 목록에서 제외한다.
- 불변성: 기존 build의 소스·정책·manifest 불일치와 경로 이탈·링크를 실패로 알린다. 과거 정책 사본을 현재 파일로 소급 생성하거나 손상 파일을 자동 교체하지 않는다. 기존 소비자 필드와 정책 마운트는 유지한다.
- 실제 검증: 수정 전 새 회귀 테스트 실패 확인 → Linux 협업 테스트 38개 통과 → Windows 네이티브 핵심 4개 통과. 개발 snapshot `c2bdb4e1542c-983995caabc9`의 허용 소스 25개를 실제 Agent 조회 클래스로 Linux에서 읽어 원문·해시 일치를 확인했다. 모델·DB 호출은 없다.
- 원문: 로컬 `runtime/verification/snapshot-before.log`, `snapshot-after.log`; Windows 검사와 실제 소스 조회 결과는 작업 실행 출력에 있다. 전체 publish 결과는 공유 후 추가한다.
- 남은 연동: Agent 담당자의 정책 사본 우선 조회·구형 manifest 호환·실제 사본 소비 검증과 commerce의 새 snapshot 검증. 동시 공유된 commerce 수락도 보존해 전원 P1 합의를 확인했다. 생성기 제공만으로 논의를 RESOLVED나 역할 DONE으로 처리하지 않는다.

## 2026-09-21 — 선택 메타데이터 전원 합의의 계획 반영

- [DISC-20260921-voc-001](../discussions/DISC-20260921-voc-001-runner-metadata.md)의 P1에 세 담당자가 직접 수락한 것을 `1b5adc8`·`c1276d4`에서 확인했다. 선택 메타데이터 생성·필수 승격 보류, 기존 실제 모델·완료 계약 유지와 후속 관측 제안의 담당·조건을 비용 계획 및 연동 위험 검토에 반영했다.
- 실제 모델 결과·usage를 만들지 않았고 scripts의 판정·역할 JSON은 변경하지 않았다. 계획 반영과 문서 검증을 공유한 뒤 원격 포함을 확인해 이 문서 결정 건을 해소한다. 필수 runner 구현·실제 MVP 검증은 별도로 계속한다.

## 2026-09-21 — 선택 식별자 공백 입력 불일치 수정

- AGENT-VOC-003의 실제 201 → 400 불일치를 확인하고 P1 방향을 적용한다. VOC 생성/PATCH에서 명시적인 빈 문자열·공백만인 context 값을 거절한다. 생략/null은 정보 없음으로 유지하고 유효한 식별자 문자를 trim하거나 바꾸지 않는다.
- 회귀 검증: HTTP 테스트에서 빈 customerId가 수정 전 201로 저장되는 실패를 재현했다. 수정 후 `gradlew.bat --no-daemon :voc-app:test` 7개를 통과했다. 다섯 선택 ID의 빈 문자열·공백·탭/개행·유니코드 공백, 실패 시 버전/저장 내용 보존, null 생략과 정상 문자의 보존을 확인했다.
- 실패 원문은 로컬 `runtime/verification/blank-context-before.log`·`blank-context-before.xml`, 성공은 `blank-context-after.log`다. 계약에 화면의 빈 입력 생략과 기존 잘못된 티켓의 전달 오류·수정·새 키 안내 기준을 추가했다. 화면·영속 분석 전달의 구현과 실제 VOC → Agent 재검증은 아직 남아 있다.

## 2026-09-21T20:39:02+09:00 — 미공유 단위 게시 완료와 실제 소비 확인

- 공유: 정책 생성기 0dd5bf9, 선택 메타데이터 계획 eafb8ac, 입력 경계 8d80786을 일반 main push했다. fetch 후 작업 폴더와 origin/main의 ahead/behind 0/0, 통합 복사본과 전체 파일 일치를 확인했다. 사용자 미추적 readiness 문서는 원래 해시 그대로 보존했다.
- 지연 원인: 동시 main 변경·공동 README 충돌을 양쪽 기록 보존으로 통합했다. GitHub DNS와 Docker Hub DNS/TLS 실패, 실행 승인 검토의 시간 초과도 있었다. 실패 결과를 숨기거나 강제 push하지 않았다.
- 로컬 게시 도구는 transient Git 전송만 최대 3회 재시도하고, Docker 내장 해석기를 사용해 외부 frontend 다운로드를 제외했다. 동일 JDK·Dockerfile 빌드/실행 명령과 전체 검사를 유지했다. 보조 도구·로그는 runtime에만 두며 전역 설정·공유 검사 기준은 바꾸지 않았다.
- 최종 검사: Linux Python 45개 통과, 문서 검사 통과, 전체 Java 133개 중 124개 통과·외부 환경 조건부 9개 건너뜀·실패/오류 0. 이 9개는 실제 검증 성공에 포함하지 않는다. 빌드 8d8078685a0f-40824ff52249의 세 앱·PostgreSQL·마이그레이션·HTTP·읽기 전용 근거 연결 smoke를 통과했다.
- 같은 공유 빌드의 실제 HTTP 추가 확인: 공백 POST/PATCH 40건 거절, 정상/생략/null 11건의 VOC 생성→Agent 접수와 같은 키 동일 ID, 기존 공백 티켓 불변·Agent의 재시도 불가 400, 재생성 후 기존 티켓 ID/버전/필드/시각 보존을 통과했다. 모델 DISABLED에서 수행한 수동 HTTP 연결이며 아직 VOC 영속 전달 worker 검증은 아니다.
- 실제 Agent SourceEvidenceTools로 Windows 소스 35개와 보관 정책의 원문·해시를 대조했다. 현재 정책 파일이 없는 경로에서도 보관 정책을 읽었다. Agent·commerce PC의 직접 인수 결과는 각자 기록해야 하므로 정책 논의는 AGREED다.
- 원문: runtime/verification/policy-context-publish-local-frontend.log, linux-gradle-check, context-handoff.json, ticket-restart.json, SourceSnapshotProbe.java와 publication clone의 runtime/smoke.json·runtime/evidence/source. 테스트 입력은 합성이며 실제 모델·web·MVP·DONE은 미검증이다.
- 논의: 선택 메타데이터 결정은 원격 포함을 확인해 RESOLVED. 데모 배분 P1과 대기열 P1에 직접 수락하고 본문·목록을 갱신했다. 유료 활성화 조건, 큐 수용량/429·VOC 영속 전달/조회/화면의 구현·검증이 남아 둘은 AGREED다.
- Agent 요청 VOC-AGENT-EXPORT-001: agent-app/scripts/export_model_calls.py의 Windows Compose 탐색/실행 환경을 보완해 달라. 동일 허용 환경에서 docker compose version은 하위 명령 없음으로 종료 1, DOCKER_CONFIG 지정 후 export도 종료 1이었다. 별도 Agent 계정 READ ONLY SQL에서는 예산/모델 호출/대기·실행 모두 0을 확인했다. 요청 없이 진행 가능한 다음 작업은 영속 분석 요청 API다.

## 2026-09-21 — 분석 요청·입력 스냅샷·이력 영속 저장

- 제공 단위: 분석 POST는 V3 analysis_requests에 저장 후 202/PENDING을 반환한다. 티켓별 분석 GET·상세 이력과 nullable 조사/전달/조회 오류 필드를 제공한다. 입력 message/context/버전/키/이전 조사 ID는 기존 티켓이 바뀌어도 그대로 유지한다.
- 원자성: 티켓 행 잠금과 같은 트랜잭션에서 기존 키 확인·버전 비교·입력 복사를 수행한다. 같은 키·버전·이전 조사 ID는 기존 기록을 반환하고 다른 입력이면 REQUEST_KEY_CONFLICT다. 새 키의 오래된 버전은 TICKET_VERSION_CONFLICT이며 혼합된 스냅샷을 저장하지 않는다.
- 소속·이력: 이전 조사 ID는 같은 티켓에 연결되어 있어야 하며 다른 티켓의 분석 조회는 404다. 생성 시각·ID 내림차순 이력을 제공하고 기존 분석을 덮어쓰지 않는다. 재시도 가능한 전달 실패만 같은 키로 원자적으로 PENDING에 되돌린다. SUBMITTED의 조사 FAILED는 자동 재실행하지 않는다.
- 실제 검사: Windows 네이티브 전체 VOC HTTP/H2·앱 16개(신규 분석 8개, 기존 티켓 7개, 앱 1개) 통과. 같은 분석/티켓 HTTP 15개를 격리 PostgreSQL 17.6에서 --rerun-tasks로 새로 실행해 통과했고 실패/건너뜀은 0이다. V2가 있던 DB에 V3를 적용했다.
- 검증 범위: 동일 키 동시 8요청, 티켓 수정/스냅샷 경쟁 12회, 이전 조사·다른 티켓 접근, 잘못된 입력, 고정 시각에서 ID 정렬, HTTP 응답을 읽지 않은 클라이언트의 같은 키 복구를 검사했다. 전달/조사 실패의 분기 테스트에는 명시적인 합성 저장 기록을 사용했다.
- 원문: runtime/verification/analysis-http-h2.log·analysis-h2-results와 analysis-http-postgresql.log·analysis-postgresql-results. 실제 모델 호출은 없으며 이 결과를 Agent 작업기·화면·MVP 성공으로 계산하지 않는다.
- 현재 제한: 전달/상태 조회 worker와 근거 중계·UI는 아직 없어 새 분석은 PENDING에 머문다. 실제 Agent로 보내고 오류·조회 상태를 영속 갱신하는 다음 단위를 계속한다. businessReady=false와 역할 IN_PROGRESS를 유지한다.
- 통합: 리더 6b9ca6f의 외부 PostgreSQL 검사 캐시 차단 변경을 확인했다. 편집 중 rebase하지 않았고 이번 외부 검사는 --rerun-tasks로 실행했다. 커밋 경계에서 리더 변경을 보존해 통합하고 전체 publish를 수행한다.

## 2026-09-21T21:16:00+09:00 — 분석 저장 게시·재시작 검증과 P2 답변

- 공유: 분석 저장 단위 `4de1a98`를 전체 publish 종료 0으로 GitHub main에 공유했다. 두 차례 동시 원격 변경을 보존해 통합·재검증한 뒤 일반 push가 성공했다. 개발 checkout도 같은 커밋으로 통합해 당시 ahead/behind 0/0을 확인했다. 사용자 미추적 docs/ralphthon-readiness.md의 SHA-256은 기존 값과 같다.
- 공통 검사: Python 57개 통과, 전체 Gradle check의 JUnit 154개 중 성공 145·조건부 건너뜀 9·실패/오류 0, 세 앱·각 DB 마이그레이션·HTTP 연결·Agent SELECT 전용/근거 마운트 smoke 통과. 기록은 runtime/verification/analysis-publish.log에 있다. 건너뛴 검사는 통과로 계산하지 않는다.
- 결과 파일 정확성: 이전 고정 디렉터리에 이름이 바뀐 테스트 XML이 남아 있었다. 현재 커밋의 동일 Docker 검사 캐시에서 새 디렉터리로 다시 내보내 22개 suite만 집계했다. runtime/verification/latest-linux-gradle-check.json의 testedCommit은 4de1a986fe6b688aad18554b48ef95ba48cdf7aa다. 오래된 파일이 섞인 160개 집계는 사용하지 않는다.
- 실제 복구: buildId 4de1a986fe6b-32fc61debb7b에서 합성 티켓 v1 분석을 저장하고 티켓을 v2로 수정해 별도 분석을 저장했다. 같은 이미지·DB를 유지하며 VOC 컨테이너만 재생성했다. 컨테이너 ID 변경, 티켓 v2와 두 입력 스냅샷·분석 ID·이력·nullable 필드, 두 키 재전송의 동일 응답과 총 2건을 실제 HTTP로 확인했다. 원문 runtime/verification/analysis-restart.json. Agent는 MOCK이며 모델 호출은 없다.
- Windows 인수: 리더 `0c07ca9`의 exporter를 원래 명령으로 다시 실행해 종료 0을 확인했다. plugin docker.EXE compose 선택, READ ONLY on / REPEATABLE READ, API 장부 budget=null·calls=0이다. 원문 runtime/verification/voc-ledger-export-portable.json, SHA-256 8e76e2c14ee65f36a2b0af6bbe15f393e1a9aea1f1f90bae17020befa9d82b77. VOC-AGENT-EXPORT-001의 소비자 실패는 해소됐고 LEAD-015의 최종 분류와 Agent 확인은 담당자에게 남긴다.
- [DISC-20260921-agent-004](../discussions/DISC-20260921-agent-004-demo-allocation.md)의 P2에 직접 답변했다. 로컬 OAuth/배포 API/test 구분, 기존 오류·재조사·근거 계약, 인증된 web 한정 공개를 수용한다. P1의 로컬 API 배분을 재사용하지 않는다. OAuth 사용량과 API 비용을 구분하며 배포 범위가 남아 DISCUSSING이다.
- 현재 한계와 다음 단위: 저장 API의 재시작 보존까지 확인했다. 새 분석은 아직 PENDING이고 실제 Agent 자동 전달/조회·근거 중계·web·runner가 남아 있다. 프로젝트 전용 OAuth 로그인·실제 모델·공개 ngrok·MVP·DONE은 미검증이다. Agent worker 소비를 독립적으로 계속한다.

## 2026-09-21T21:21:00+09:00 — 새 대기열 계약과 실제 MVP 실행 경로 수용

- 원격 f1d6082의 수용량/429 제공자 변경과 integration-contract의 동일 키·영속 재전송 횟수·Retry-After·14분 관측 규칙을 읽고 통합했다. 다음 VOC worker에 적용한다. 리더의 PostgreSQL 인수 결과를 이 PC의 소비자 검증으로 계산하지 않는다.
- [DISC-20260921-commerce-002](../discussions/DISC-20260921-commerce-002-live-mvp-runtime.md) P1을 직접 수락했다. 준비된 실제 모델 런타임을 verify-mvp가 기본 mock으로 교체하는 경로와 Windows wrapper 고정 호출을 소스로 확인했다. 리더가 공통 MVP 실행기·회귀·실행 안내를 보완하고, VOC는 worker/화면/runner를 구현한 뒤 소비자 인수를 수행한다. 동일 파일을 중복 편집하지 않는다.
- 일반 publish는 모델 호출 없이 유지하고 실제 MVP의 명시 실행·전후 빌드/환경/모델 일치·자격증명 제외·실패 산출물 보존을 수용한다. 한재홍의 관측/실행 답변과 실제 구현·검증이 남아 DISCUSSING이며 완료로 표시하지 않는다.

## 2026-09-21T21:45:00+09:00 — 영속 Agent 전달·조회·근거 소비 구현

- 구현: V4에 전달 횟수·대기열 거절 횟수·다음 작업/관측 종료 시각·점유 토큰/기한·수동 조회 요청을 저장했다. DB 점유를 확정한 뒤 고정 Agent 주소에 HTTP를 호출하며 유효한 같은 토큰만 결과를 저장한다. 만료된 점유를 복구하고 늦은 이전 작업의 저장을 거절한다. 요청 스레드·브라우저 생존 여부에 의존하지 않는다.
- 전달: 저장된 입력·키로 접수한다. 일반 연결/5xx는 최초 포함 최대 3회(1/2초), 429/INVESTIGATION_QUEUE_FULL은 최초 외 최대 3회(Retry-After 이상 5/10/20초+jitter)다. 재시작·다른 오류가 횟수를 초기화하지 않고 점유 후 중단도 시도에 포함한다. 한도 소진 후 같은 키 수동 POST만 동일 분석을 다시 PENDING으로 바꾼다. 영구 4xx·잘못된 응답은 자동 재전송하지 않는다.
- 조회: 실제 접수 ID를 저장한 뒤 조사 GET을 수행한다. 기본 14분 관측/5초 polling과 마지막 결과·lastSyncedAt을 저장한다. 조회 실패·관측 종료를 조사 FAILED나 티켓 RESOLVED로 바꾸지 않는다. GET의 선택 refresh=true는 기존 조사의 서버 GET 한 번을 예약하며 새 키/조사 POST/관측 창 연장을 하지 않는다. 종료 조사와 이전 이력은 보존한다.
- 근거/응답: 티켓·버전·조사 소속, 상태별 보고서/오류와 근거 참조를 검사한다. 다른 티켓/근거는 원격 호출 전 404이며 원문 응답은 관측한 근거의 source와 대조한다. 잘못된 조사/보고서는 AGENT_PROTOCOL_ERROR로 기록하고 캐시를 유지한다. 전달 오류에 원격 진단 원문·비밀 값을 복사하지 않는다.
- 실제 검사: Windows Java 21에서 VOC HTTP/H2·앱 25개 통과. 격리 PostgreSQL 17.6의 전체 HTTP 24개 중 첫 실행은 6개가 실패했다. 결과 저장 CASE 식의 next_work_at이 text로 추론되는 차이를 확인하고 TIMESTAMP WITH TIME ZONE을 명시했다. 수정 후 동일 외부 DB에서 24개(신규 worker 9·저장 8·티켓 7)가 모두 통과했고 실패/건너뜀 0이다. 전체 publish에서 최종 H2/공통 회귀를 다시 확인한다.
- 검증 내용: 실제 VOC HTTP/JDBC와 명시적인 합성 Agent HTTP를 연결했다. 접수 후 응답 절단, 수정 전 입력 재전송, 429 간격/한도/동시 수동 복구, 503/영구 400, 조회 오류/모델 실패 분리, 관측 종료/수동 GET, 16개 동시 점유, 만료 후 재점유/이전 토큰 거절, 소속·리포트 근거 오류를 검사했다. 자동 작업기 실행도 브라우저 조회 없이 완료됨을 확인했다. 합성 보고서를 실제 모델 결과로 계산하지 않는다.
- 증거: runtime/verification/worker-h2.log·worker-h2-results, worker-postgresql.log·worker-postgresql-before-fix, worker-postgresql-fixed.log·worker-postgresql-results. 실패와 수정 후 결과를 별도 보존했다. 일반 앱 DB를 초기화하지 않았다.
- 남은 범위: 한재홍의 실제 앱과 재시작·429 소비 인수, 한국어 web·접근 제어·runner·실제 모델·ngrok/MVP 검증이다. businessReady=false/IN_PROGRESS를 유지하며 일반 publish에서 실제 모델을 호출하지 않는다. 리더의 공통 live MVP 실행기 경로를 중복 수정하지 않는다.

## 2026-09-21T21:54:00+09:00 — 공통 MVP 실행기 Windows 인수 요청

- [DISC-commerce-002](../discussions/DISC-20260921-commerce-002-live-mvp-runtime.md)에 VOC-LEAD-MVP-001을 기록했다. scripts/tests/test_live_mvp.py의 네이티브 8개 중 7개는 통과했으며 97행의 ./gradlew 고정 기대값이 실제 gradlew.bat와 달라 1개 실패했다. 리더 소유 회귀 파일의 OS별 기대값 수정과 공유 후 재검증을 요청한다. 기본 선택·비밀 제외·결과/실패 보존 기준을 낮추지 않는다.
- 실제 JDD_MVP_LIVE=false verify-mvp는 모델/runner 실행 전에 명시 선택 오류로 종료 1이다. worker-live-mvp-windows.log와 worker-live-mvp-cli-rejection.log에 보존했다. 이를 실제 MVP 또는 Windows live 모델 성공으로 표시하지 않는다.
