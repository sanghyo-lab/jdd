# 이상효 — 개발리더 검토 상태

- 상태: 커머스 일곱 업무 재현·복구와 Agent/VOC 단위 인수·지적 보완을 진행했다. 실제 모델·화면·최종 전 영역 검토/승인은 남아 있다.
- 담당자: 이상효. commerce 구현과 개발리더를 겸한다.
- 작업 브랜치: main
- 최종 승인: [lead.json](lead.json)의 IN_PROGRESS. 세 담당자 DONE 후에도 독립 검토·전체 검증이 필요하다.
- 검토 기록: [lead-review.json](lead-review.json). 단위 지적·수정 근거를 기록하며 최종 전 영역 검토와 필수 검사는 아직 미완료다.
- 작업 기준: [리더 역할](../roles/lee-sanghyo-lead.md), [리더 단계](../goals/lead.md), [공통 완료 기준](../team-completion.md)
- 다음 작업: 각 담당 구현이 준비되면 전체 영역의 코드·실제 동작을 검사하고 문제를 수정·재검증한다.
- 지적·수정 기록: 실제 검토를 시작하면 LEAD ID, 담당 역할, 경로·재현 방법, 예상·실제 결과, 수정 커밋과 검증 근거를 기록한다.

다른 담당자의 DONE을 대신 작성하지 않는다. 전체 코드 수정 권한으로 직접 보완한 내용과 영향을 여기에 공유한다.

## 2026-09-21T20:19:00+09:00 — 필수 소비자 연동·실제 데모 조건 재확인

- `f0ddbb6` 이후 원격 변경·직접 답변이 없음을 확인했다. VOC 분석 연결은 빈 목록, web 없음, runner 미구현 골격이므로 전체 흐름·화면 검증을 시작할 구현이 아직 공유되지 않았다. 해당 담당자의 접수 기록과 실제 코드 상태를 구분한다.
- 이 PC의 ngrok 설정 파일과 실제 모델 허용 범위/비밀 설정도 준비되지 않았다. Agent DISABLED와 세 앱 buildId를 실제 HTTP로 재확인했고 관측·재개 조건은 [commerce 상태](commerce.md)의 같은 시각 기록에 연결했다. 이미 요청한 범위의 답변을 승인으로 대신하지 않는다.
- 독립 검사 완료와 남은 외부 입력을 분리한다. 알려진 필수 연동/검증이 남아 있으므로 세 역할 DONE·최종 전체 검토·리더 APPROVED는 미작성이다. 이번 회차는 새 구현 진척이나 살아 있는 외부 작업의 확인으로 기록하지 않으며 goal은 active로 유지한다.

## 2026-09-21T20:15:20+09:00 — LEAD-013 공유와 영속 대기 기한 독립 인수

- `1c02f85`를 전체 publish 종료 0으로 공유했다. 데모 전용 worker=1 구성만 조정했고 기본 앱/유료 호출 활성화 조건은 유지했다. 기본 Java 132개(실패 0·조건부 건너뜀 9), Python 38개와 실제 3앱/DB/근거 연결을 통과했다. buildId `1c02f85b4391-64991e1f84a7`, 모델 DISABLED.
- Agent `104761f`의 V6·접수 기한 저장·선점 배제·포화 중 만료 정리·재시작 변경과 테스트를 직접 읽고 이 PC에서 PostgreSQL 12개를 실행해 실패/건너뜀 없이 통과했다. 별도 JVM 4개의 재시작/소유권 검증, 기존 V4 조사 4건·근거 1건의 V6 이관 digest 보존도 새로 확인했다. 타 PC의 성공 기록을 재사용하지 않았다.
- 자료·명령·실행 시간은 [commerce 상태](commerce.md)의 같은 시각 기록과 `runtime/submission/commerce-20260921-resumed/agent-queue-postgresql-01/`, `agent-worker-queue-02/`, `agent-queue-worker-migration-after.json`에 연결했다. 실제 HTTP/DB/worker를 사용했으며 추론은 모의 2회·유료 0회다. 기존 앱 조사 0건의 마이그레이션 관측은 기록 보존 검증과 구분했다.
- [DISC-agent-005](../discussions/DISC-20260921-agent-005-queue-limits.md)의 commerce/lead 인수만 갱신한다. 수용량/429·VOC 소비 합의, 화면/runner·정책 snapshot과 실제 모델 검증은 남아 있다. 모든 역할은 IN_PROGRESS이며 타인의 완료 기록·최종 승인 항목은 작성하지 않았다.

## 2026-09-21T20:05:21+09:00 — LEAD-013 명시 데모 worker 설정

- 리더가 `agent-app/compose.openai-demo.yaml`의 worker 동시성만 1로 지정했다. 기본 worker 2와 모델 호출 한도 1의 불일치를 실제 Compose 구성 렌더링으로 확인하고 1/1로 맞췄다. 기본 개발 Compose와 유료 활성 조건, 비용 장부의 제한을 바꾸지 않는다.
- Agent 상태의 진행 대상은 대기 만료/선점이며 해당 Java·저장소는 편집하지 않았다. 계약 필드 변경은 없다. 변경 이유와 대상은 DISC-agent-005의 내 답변에 먼저 기록했다. 설정 전후 원문은 commerce 상태에 연결했다.
- 검증 범위는 구성 정합성이다. 실제 유료 조사·동시 요청이나 공개 데모를 실행했다고 기록하지 않는다. 사용자 모델/비용 범위·비밀 설정과 팀 배분 확인은 여전히 필요하다.

## 2026-09-21T20:02:00+09:00 — 대기열 P1 영향 확인과 데모 구성 정합성

- `145f404`의 LEAD-012 전체 publish 성공을 확인했다. `d0a5dff`의 대기열 P1과 필수 문서 요구를 읽고 commerce 영향 없음·제안 수락·독립 인수 기준을 건별 논의에 답변했다. 만료·선점·동시 수용량과 VOC 소비는 아직 미완료다.
- 별도 설정 렌더링에서 데모 profile의 동시 호출 1과 worker 기본 2가 맞지 않음을 확인했다. 활성화를 위한 작은 보완 대상으로 `agent-app/compose.openai-demo.yaml`의 worker 동시성만 1로 고정할 예정이다. Agent의 진행 중인 worker/저장소 대기 만료 구현과 중복하지 않으며 표준 Compose·업무 계약·유료 활성 조건은 유지한다.

## 2026-09-21T19:56:00+09:00 — LEAD-012 검증 캐시의 외부 상태 누락

- Agent의 `620654f`를 검토하며 내 커머스 검사에도 같은 외부 DB 모드 반복의 캐시 문제가 있는지 직접 확인했다. 테스트 DB를 변경한 뒤 두 번째 Gradle 명령이 실제 실행 없이 성공·이전 XML을 재사용하는 반례를 얻었다. 원문 실패를 보존했다.
- `commerce-app/build.gradle`에서 외부 DB 모드의 UP-TO-DATE/빌드 캐시를 차단했다. 강제 옵션 없이 두 번 새 실행·XML·DB 초기화/단언 통과를 확인했다. 검사 축소나 테스트 삭제 없이 실행 정책만 보완했고 기존 강제 실행 자료와 구분했다.
- 대상·재현·명령·결과는 commerce 상태와 lead-review의 LEAD-012에 연결한다. 최종 전 영역 검사/승인·실제 모델·소비자 화면 완료를 의미하지 않는다.

## 2026-09-21T19:48:00+09:00 — 연결 유실 후 조사 지속 확인

- `fbb43be`의 테스트·안내·상태 전체를 읽고 새 전용 PostgreSQL에서 실제 TCP 응답 유실과 동일 키 복구를 직접 실행했다. 실제 worker·영속 조사/근거·새 HTTP 클라이언트를 사용한 1개 검사가 통과했고 조사 1건·근거 1건·모의 모델 2회·유료 0회가 유지됐다.
- 테스트 대역 도구/모델의 관측이며 실제 모델 원인 판단이나 공개 터널·화면 성공이 아니다. 원문과 명령은 commerce 상태의 `agent-client-disconnection-01/`에 연결했다. 기존 완료 조건과 미검증 항목은 그대로 남아 있다.

## 2026-09-21T19:44:00+09:00 — 현재 코드의 완료 게이트 확인

- `67ec034` 전체 publish와 세 앱 buildId `67ec034fa347-c076e59e00d0`의 연결을 직접 확인했다. `4c20c9a`의 새 Agent 전송 변경도 전체 검토했으며 원문 검증·조건부 건너뜀과 별도 PostgreSQL 실행은 commerce 상태에 구분했다.
- 깨끗하고 동기화된 main의 `team-check`는 종료 1이다. 세 역할 DONE과 리더 APPROVED가 없고 실제 VOC/web/runner·모델·PC/모바일/공개 URL 검증이 남았다. lead-review의 최종 검토·반복·필수 검사 필드는 PENDING으로 유지하며 단위 지적의 VERIFIED를 최종 승인으로 사용하지 않는다.
- VOC가 진행 중인 분석/화면/runner·정책 snapshot과 관련 소비자 답변을 기다린다. 새 구현/요청을 확인하면 전체 변경을 읽고 필요한 실행을 이어간다. 실제 모델 데모의 구체적인 사용 범위는 사용자 답변·팀 배분·비밀 설정 확인 전까지 미승인이다.

## 2026-09-21T19:38:00+09:00 — 커머스 경계·롤백의 PostgreSQL 확인

- 기존 H2 HTTP 계약을 변경 없이 별도 PostgreSQL 17.6 DB에서도 실행할 수 있게 확장했다. 18개 모두 통과했고 애플리케이션 DB 오지정의 사전 거절, 이후 기본 H2 19개 통과를 직접 확인했다. 무효 대상의 종료 1/실패 XML을 의도적 거절 근거로 별도 보존했으며 삭제·건너뜀으로 성공 처리하지 않았다.
- 변경 경로는 commerce 테스트/Gradle·소유 fixture 실행기·실행 안내이며 실제 앱 계약·DDL·의도한 결함은 그대로다. 새 운영 결함 발견이나 최종 전 영역 승인으로 기록하지 않는다. 상세 명령·분리 DB·출처는 commerce 상태에 있다.
- `85e3f72`의 모델 장부 읽기 전용 내보내기도 직접 실행했다. 로컬 기본 장부의 호출 0건을 확인했으며 실제 유료 사용은 시작하지 않았다.

## 2026-09-21T19:32:00+09:00 — 일곱 업무의 근거 소비 독립 인수

- `0ba2862`의 공통 근거 소비 검사 전체를 읽고 내 PC의 실제 PostgreSQL 재현 데이터로 실행했다. VOC-01~07 총 300근거의 도구 실행·영속 저장·HTTP 원문 재조회와 01~06의 DB 333필드·로그 원문 일치, 동일 키/새로고침 추가 호출 없음이 통과했다. Java 2개/실패 0/건너뜀 0, 유료 0·모의 모델 총 20회다. 조사별 원문은 commerce 상태의 `seven-agent-handoff-01/`에 있다.
- LEAD-011 보강은 `5edef99`로 전체 publish 종료 0 후 공유했다. 모든 완료/승인 기록은 IN_PROGRESS를 유지한다. 이 단위는 실제 AI의 원인/조치 품질이나 VOC 화면·최종 독립 전 영역 검토를 대신하지 않는다.
- `de34e9d`의 데모 예산 P1을 commerce·lead 합산 $15·최대 176회·단일 장부 계획으로 수락했다. 기본 Agent DB의 budget/call/진행 조사 0행을 직접 확인했다. 실제 사용 범위·비밀 설정·VOC의 직접 답변은 남아 있으며 다른 PC 장부를 확인했다고 기록하지 않는다.

## 2026-09-21 — 첫 커머스 전달과 공통 설정 영향

- commerce DDL·HTTP·재고·로그·재현 제어를 구현하고 HTTP/H2 8개, 전용 실제 PostgreSQL의 첫 동시 재현·정상 대조·시간 초과/복구·SELECT 전용 권한을 검증했다. 상세 원문과 buildId는 [commerce 상태](commerce.md)에 기록했다. 최종 독립 검토·승인 단계는 아직 아니다.
- 공통 변경은 `compose.yaml`, `.env.example`에 commerce 전용 `COMMERCE_REPRODUCTION_ENABLED=false` 기본값을 추가한 것이다. 명시적으로 true인 로컬 합성 재현에서만 테스트 API를 노출한다. Agent·VOC 업무 DTO·포트·DB 역할은 변경하지 않는다. 김아름의 원격 착수 범위(티켓 API)와 중복하지 않는다.
- 재현 제어는 `commerce-app/src/reproduction/java`에 분리해 Agent의 실행 소스 검색에서 제외했다. 업무 소스·마이그레이션과 시드/정답/제출 산출물의 경계는 유지했다.
- 원격 Agent 접수·영속 실행 변경 `b2b46ef`·`e60fa86`의 코드·테스트·문서와 모델 오류 논의의 세 답변을 읽었다. 실행기·모델·도구 연결은 담당자가 계속 구현 중이며 대신 구현하거나 완료를 선언하지 않는다.
- 남은 검사: VOC-07 최소 20회, 01~06, 실제 티켓/Agent/화면/모델 연동, 전 영역 최종 검토. 최초 Docker 빌드에서 소스 변경 시 Gradle·의존성을 재다운로드하는 비용을 관측했다. buildx를 로컬에 준비했으며 공유 빌드 캐시 개선은 다음 변경에서 검증한다.

## 2026-09-21 — 반복 빌드의 의존성 캐시

- LEAD-001: `Dockerfile`의 소스 COPY 뒤 Gradle 다운로드가 매번 반복되어 첫 세 앱 갱신이 1010.09초 걸렸다. 빌드 중 변경으로 기존 레이어도 재사용되지 않았다. 공통 빌드 유지 담당 VOC의 진행 중 티켓 API와 경로가 겹치지 않음을 확인하고 리더 권한으로 보완한다.
- 변경: BuildKit cache mount로 `/root/.gradle`만 빌드 간 보존하고 `sharing=locked`로 병렬 Compose 빌드의 캐시 접근을 보호한다. Java·Gradle·앱 버전과 업무 계약은 그대로다. Buildx 준비 기준을 로컬 실행 문서에 추가했다.
- 검증: 변경 전 개발 스냅샷의 `scripts/dev up`과 `scripts/dev smoke`는 각각 종료 0이었다. 캐시 변경 후에는 첫 publish의 전체 check·재빌드·세 앱 smoke 결과를 기록한다. 시간 개선 수치는 두 번째 실제 빌드 전까지 주장하지 않는다.

- LEAD-001 후속: `e080390`의 전체 publish가 종료 0이었다. 첫 BuildKit 빌드에서 3개 이미지가 하나의 Gradle 빌드 단계를 공유했고 실제 재기동·smoke를 통과했다. 이후 소스 변경 시 캐시 재사용은 다음 빌드에서 확인한다. Agent `943e961`의 비용 게이트·장부·회복·테스트·문서 전체 변경을 읽었고 현재 PC의 전체 check에서도 통과했다. 실제 OpenAI 호출과 최종 독립 검토는 수행하지 않았다.

## 2026-09-21 — LEAD-002 초기화 권한 의존 제거

- 대상: `fixtures/commerce/VOC-07/reset.sql`. 최소 권한의 기본 DB에서 임시 테이블 생성이 permission denied였고 재현 HTTP를 시작하지 못했다. 전용 DB의 성공과 실제 기본 배포 권한 차이를 발견했다.
- 수정 `878f602`: commerce 계정의 TEMP/DDL 권한 추가 없이 psql의 안전하게 인용된 ID 배열 변수로 초기화한다. 기존 주문이 있는 접두어와 신규 접두어, 실제 컨테이너의 20회·대조·복구로 재검증했다. 계약/소비자 API 변경은 없다. 상세 실패·성공 원문은 commerce 상태에 연결했다. 최종 전체 리더 검증과 역할 완료 갱신은 별도로 남아 있다.

## 2026-09-21 — LEAD-003 잘못된 HTTP 방식의 서버 오류 분류

- 실제 DELETE /api/orders가 일반 예외 처리기로 들어가 500 INTERNAL_ERROR를 반환했다. `CommerceErrors`에서 잘못된 방식 405와 media type 415를 명시적으로 처리하고 같은 오류 DTO를 유지했다.
- 실제 실패 원문과 HTTP/H2 재검증 13개는 commerce 상태에 연결했다. 배포 후 실제 요청으로 최종 확인하며 최종 리더 승인과 분리한다.
- `6b646a0`까지 초기화 수정의 전체 publish·세 앱 기동·smoke가 종료 0이었다. Docker의 기존 단계 재사용을 확인했고, 쿠폰 Java 소스 변경 때는 Gradle cache mount의 다운로드 재사용을 검증한다.

## 2026-09-21 — 커머스 업무 재현과 Agent 실행기 통합

- `71d74aa`의 Agent 비동기 실행·소유권·버전 프롬프트·도구 반복·한도·HTTP 오류·복구 검증 전체 변경을 읽고 통합했다. 기본 모델 DISABLED와 유료 호출 0회를 유지하며 실제 조회 도구·OpenAI 어댑터는 담당자가 구현 중이다.
- 새 커머스 `c09694c`의 실제 PostgreSQL에서 VOC-01~06 각 3회, VOC-07 20회, 정상/잘못된 입력/쿠폰 거절/재고 대조·동시 결제/취소·프로세스 복구를 확인했다. 상세 buildId와 원문은 commerce 상태에 연결했다. 전체 UI·실제 모델·최종 독립 검토를 대체하지 않는다.
- 공급 API와 새 오류 분류·최초 응답 재전송 의미를 커머스 계약/README에 맞췄다. 루트 README의 개발 미시작 문구를 실제 진행으로 바로잡았다. 타 담당자의 DONE은 작성하지 않았고 공개 터널도 아직 검증하지 않았다.

## 2026-09-21 — LEAD-004/005 초기화 범위와 테스트 환경

- LEAD-004: 정상 API로 만든 두 접두어의 혼합 주문을 초기화할 때 이웃 재고 이력이 사라졌다. `fixtures/commerce/VOC-01~07/reset.sql`에 외부 상품·쿠폰·이력 의존 시 삭제 전 거절을 추가했다. 새 실제 HTTP/DB 검사 `check_fixture_isolation.py`의 9개가 데이터 불변/정상 초기화를 통과했다. 처음 psql 종료 코드 처리 실패도 보존했다. 계약 API에는 영향이 없으며 초기화가 모호한 경우 명시적으로 실패한다.
- LEAD-005: 재현을 켠 publish 환경에서 테스트가 같은 설정을 상속해 비노출 404 검사가 실패했다. CommerceHttpTest·CommerceApplicationTest의 비활성 설정을 명시했다. 기대 응답을 완화하지 않았고 같은 환경변수에서 19개를 재통과했다. 전체 공유 검증은 다시 실행한다.
- 원문·buildId·실패와 재검증은 [commerce 상태](commerce.md)에 연결했다. 최종 리더 전 영역 검토/재현은 여전히 남아 있다.

## 2026-09-21 — LEAD-006 결제·환불 저장 시각 일치

- `1d29d20`의 주문 시각 수정과 고정 Clock을 내 결제/환불 구현에 통합한 뒤 두 응답과 DB 시각이 다른 것을 회귀 테스트로 확인했다. PaymentService의 두 시각 생성 지점을 마이크로초로 정규화했고 동일성 검사를 유지한 19개 전체 commerce 테스트가 통과했다. 실패/성공 원문은 commerce 상태에 기록했다.
- `d8246e9`의 VOC 티켓/버전 CAS·입력·오류·저장·5개 HTTP 계약 테스트와 담당 검증 기록 전체를 읽었다. Agent 연결·화면은 담당자가 이어 구현하는 범위이며 중복 구현하지 않는다. 다음 통합에서는 이 PC의 PostgreSQL 티켓 계약과 실제 Agent 근거 저장도 검증한다.
- 정책 사본 P1을 commerce/lead로 수락하고 새 스냅샷 제공자 검증을 맡았다. VOC의 직접 합의·공통 생성기 담당 답변과 소비 구현은 남아 있다.

## 2026-09-21 — LEAD-007/008 HTTP 오류와 LEAD-009 복구 대기

- 실제 기본 앱에서 Agent의 text/plain POST가 500, VOC의 text/plain POST·지원하지 않는 DELETE·없는 경로가 모두 500이었다. 비교한 commerce는 각각 415·405·404였다. 실패 응답/buildId는 `runtime/submission/commerce-20260921-resumed/http-protocol-errors-before.json`에 보존했다.
- LEAD-007(agent): `InvestigationExceptionHandler`에 415 INVALID_REQUEST를 추가했다. `InvestigationApiTest`에 유효한 조사 본문을 잘못된 content type으로 보내도 조사/비용 예약 행이 추가되지 않는 HTTP 회귀를 추가했다.
- LEAD-008(voc): `ApiExceptionHandler`에 405·415·404를 구분하고 기존 code/message/retryable DTO를 유지했다. `TicketHttpContractTest`가 티켓 미생성까지 검사한다. 업무·모델 오류와 클라이언트의 방식/형식 오류를 구분한다.
- 담당 진행과 경계: 최신 원격 상태에서 VOC는 정책 snapshot, Agent는 모델 어댑터를 진행 중이다. 이 작은 API 오류 처리·테스트만 리더 권한으로 직접 보완했고 타인의 상태/DONE은 수정하지 않았다. 새로운 DTO 필드는 없으며 재시도 가능 여부는 false다.
- `./gradlew :voc-app:test --tests com.jdd.voc.TicketHttpContractTest :agent-app:test --tests com.jdd.agent.InvestigationApiTest` 종료 0: VOC 6개·Agent 9개, 건너뜀 0. 원문 `commands/20260921T094719.048664Z-lead-http-protocol-regression.log`, `http-protocol-regression/` XML. 전체 publish 뒤 실제 컨테이너에서도 상태를 확인한다.
- LEAD-009(commerce): check_recovery의 재시작 직후 연결 실패를 제한 있는 준비 대기로 고치고 같은 컨테이너 buildId의 재시작 후 영속 상태를 실제로 재확인했다. 실패·성공과 동시 배포를 피한 재검증 조건은 commerce 상태에 기록했다.
- 최종 승인 전 단위 검증이다. 이 PC의 실제 PostgreSQL VOC/Agent 계약 10개, Agent JVM 소유권/복구, 커머스 기본 환경 38회 재현·정상 대조를 확인했지만 실제 AI·화면·최종 전체 검토는 남아 있다.

## 2026-09-21T19:08:10+09:00 — 실제 HTTP 후속과 모델 장부 독립 검사

- `5d59fee`로 LEAD-007/008/009를 전체 publish 후 공유했다. 배포 중간 빌드 `72f11203f5f8-01d782b91e1a`의 실제 HTTP 오류 9건은 모두 405/415/404이며 실패/성공 원문을 보존했다. 추가로 Agent의 방식·없는 경로 오류 본문이 공통 DTO가 아닌 것을 확인해 다음 작은 수정 단위로 보완한다.
- 원격 `07aeadd` OpenAI 전송과 `1e179f3` 명시 활성화·V5 누적 호출 한도의 전체 코드·테스트·설정·상태를 읽었다. 단일 전송/예약·실제 usage·미확정 차단과 기본 DISABLED를 대조했다. 최종 독립 전 영역 검토·실제 모델 품질 완료를 뜻하지 않는다.
- 이 PC의 별도 PostgreSQL에서 ModelCallLedgerTest 10개와 InvestigationExecutionTest 7개, 실패/건너뜀 0이었다. 원문 `agent-postgresql-state-01/`와 `commands/20260921T095748.070459Z-independent-agent-postgresql-state.log`. 이는 총 호출 한도 V5 이전 검증이며 새 12개 장부 검사는 별도로 이어 수행한다. 실제 유료 호출은 0회다.
- 선택 ID 공백 불일치는 [DISC-20260921-agent-003](../discussions/DISC-20260921-agent-003-empty-context.md)에서 P1을 수락했다. 담당자의 진행 중 입력/화면·소비 작업과 중복 수정하지 않는다.

## 2026-09-21T19:11:17+09:00 — LEAD-010 오류 본문과 누적 호출 제한

- 대상: Agent의 지원하지 않는 DELETE·없는 경로 GET은 405/404였지만 공통 code/message/retryable 대신 Spring 기본 오류 본문이었다. 실제 응답은 `http-protocol-errors-after.json`, 먼저 실패한 HTTP 회귀는 `agent-error-envelope/before-fix.xml`·`commands/20260921T100845.593157Z-agent-error-envelope-before-fix.log`에 보존했다.
- 수정: controller 선택 전에 발생한 오류도 처리하도록 Agent 예외 advice를 전역에 적용하고 405 INVALID_REQUEST·404 NOT_FOUND를 명시했다. 기존 415/400/409/500 의미를 유지하며 API DTO 추가/삭제는 없다. 한재홍이 진행 중인 정책 소비 경로와 겹치지 않는 API·테스트만 리더 권한으로 보완했다.
- 검증: InvestigationApiTest 10개와 별도 실제 PostgreSQL ModelCallLedgerTest 12개 모두 실패/건너뜀 0. 오류 요청의 조사/비용 행 불변과 V5 총 호출 한도의 8개 동시 예약·새 조사/설정 우회 거절을 확인했다. `agent-postgresql-ledger-transport-02/`, `commands/20260921T100922.967043Z-agent-error-envelope-and-pg-call-limit.log`(21.027초). 실제 모델 호출은 0회다.
- LEAD-006 후속: 기존 실제 컨테이너 복구 기록의 결제 2건·환불 1건 생성/변경 시각 6개를 대조해 HTTP와 PostgreSQL이 일치함을 확인했다. `payment-refund-container-precision-review.json`은 원본 SHA를 포함한 보존 자료 검토이며 새 실행인 것처럼 기록하지 않는다.
- ngrok CLI 3.39.11 설치·버전 확인 완료. `ngrok config check`는 기본 설정 파일 없음으로 종료 1이었다. 인증·접근 정책·공개 URL·PC/모바일 화면·실제 모델 통합은 미검증이다.

## 2026-09-21 — LEAD-011 재현 증거 검사 강화

- 본인 commerce의 두 재현 검사기가 손상된 완료 로그를 조용히 제외하고 manifest에 적힌 해시만 보존하는 문제를 합성 파일로 재현했다. 실제 7개 업무 결과를 위조한 것은 아니지만 잘못된 근거를 놓칠 수 있는 검사 누락이므로 보완했다.
- 공유 helper와 Python 회귀 7개로 로그/소스의 실제 바이트를 검사한다. `scripts/tests/test_commerce_evidence.py`만 공통 검증 경로에 추가했고 김아름의 진행 중 snapshot 생성기/테스트 파일은 수정하지 않았다. 생산 API·업무 정책·의도한 7개 결함의 의미는 바뀌지 않는다.
- 이 PC에서 강화한 검사기로 실제 VOC-01~06 각 3회·재고 동시성 20회와 대조를 다시 통과했다. 기존 실패·변경 전 손상 입력 수락·변경 후 검사·실제 원문/빌드는 commerce 상태에 연결했다.
- 원격 `a545332` 정책 사본 소비, `6214fc8` 인수 요청 갱신, `cfd36d1` 무근거 완료 거절과 프롬프트 v2의 전체 변경을 읽었다. 생성기 실물 인수·VOC 화면·실제 모델 품질은 남아 있다. 타인의 DONE과 최종 전체 리더 검사 결과는 작성하지 않았다.
