# VOC 티켓 API

PostgreSQL에 티켓·분석 요청·입력 스냅샷을 저장하고 티켓별 분석 이력을 제공한다.
업무 상태와 버전을 관리하며 같은 버전의 동시 수정은 한 건만 성공한다.
분석 요청은 저장 후 202와 PENDING을 반환한다. 서버 작업기가 저장된 입력을 Agent에 전달하고
조사 상태·리포트·근거 목록을 갱신한다. 근거 원문은 티켓 소속 확인 후 Agent에서 중계한다.
`/internal/runtime`의 businessReady는 도메인 저장소가 기동했고 실제 전달 worker가 연결됐는지를 나타낸다.
worker를 끄면 false다. 이 값은 Agent 인증·모델 품질·화면·전체 MVP 검증 완료를 뜻하지 않는다.
상대 서비스·근거 준비는 smoke, 실제 모델과 전체 업무 결과는 별도 verify-mvp에서 확인한다.

## 사용

저장소 루트에서 `scripts/dev up`으로 앱을 실행한다. VOC 기본 주소는 http://localhost:8082다.
요청·응답과 오류의 기준은 [v1 계약](../docs/integration-contract.md)이다.

- `GET /api/assignees`: 세 담당자의 내부 ID와 이름.
- `POST /api/tickets`: title·message와 선택적 context·assigneeId. 201, version=1.
- `GET /api/tickets`: status·assigneeId 필터와 limit·offset, 생성 시각·ID 내림차순.
- `GET /api/tickets/{ticketId}`: 저장된 ticket과 analyses.
- `PATCH /api/tickets/{ticketId}`: expectedVersion과 변경할 필드. 버전 충돌은 409.
- `POST /api/tickets/{ticketId}/analyses`: requestKey·ticketVersion·선택 previousInvestigationId. 저장 후 202와 AnalysisView.
- `GET /api/tickets/{ticketId}/analyses/{analysisRequestId}`: 해당 티켓에 속한 저장 기록. 다른 티켓의 분석은 404.
- 위 GET의 선택 `refresh=true`: 미종료 조사를 한 번 다시 조회하도록 서버 작업을 요청하고 현재 저장값을 즉시 반환한다.
- `GET /api/tickets/{ticketId}/analyses/{analysisRequestId}/evidence/{evidenceId}`: 저장된 조사 근거 목록에 속한 원문. 잘못된 소속은 Agent 호출 전 404.

PATCH에서 생략한 필드는 유지한다. assigneeId=null은 배정을 해제하며 context는 전체 교체한다.
빈 context 객체는 비우고 title·message·status·context 자체의 null은 거절한다.
AI 상태와 무관하게 티켓을 OPEN·IN_PROGRESS·RESOLVED로 변경하고 다시 열 수 있다.
조사 대상 시각은 UTC로 정규화하되 입력의 소수점 정밀도를 보존한다.

## 분석 저장과 재전송

첫 요청은 티켓 버전 확인과 입력 복사를 같은 트랜잭션에서 처리한다. 티켓 수정과 겹치면
해당 버전의 일관된 입력을 저장하거나 409 TICKET_VERSION_CONFLICT를 반환한다.
호출자는 새로운 분석마다 requestKey를 만들고 통신 재시도에는 같은 키·버전·이전 조사 ID를 사용한다.

같은 티켓·키는 기존 요청을 먼저 찾는다. 저장 당시 버전·이전 조사 ID가 같으면 티켓이 이후 수정됐어도
기존 ID·입력을 반환하며, 다르면 409 REQUEST_KEY_CONFLICT다. 동시 재전송도 한 기록에 연결된다.
새 요청에 이전 조사 ID를 지정하면 같은 티켓의 저장 분석에 연결되어 있어야 한다.
분석 이력은 생성 시각·ID 내림차순이며 현재 티켓 버전과 과거 입력을 구분한다.

전달 실패가 retryable=true인 저장 기록만 같은 POST로 PENDING에 되돌린다. 재시도 불가능한 전달 오류와
이미 SUBMITTED인 조사의 FAILED는 같은 키로 새 조사를 만들지 않는다. 기존 입력·조사 결과를 보존한다.

## 서버 전달·조회와 복구

V4는 전달 횟수·대기열 거절 횟수·다음 작업 시각·관측 종료 시각·작업 점유 토큰/기한을 저장한다.
서버 작업은 DB에서 점유를 확정한 뒤 HTTP를 호출하며, 결과 저장에는 유효한 같은 토큰이 필요하다.
브라우저 연결에 의존하지 않고 만료된 점유를 다시 가져온다. 응답 유실·서버 중단 뒤에도 같은 입력과 키를 보낸다.
새 버전의 티켓이나 새 키로 자동 교체하지 않으며, 늦게 끝난 이전 작업은 새 결과를 덮어쓰지 못한다.

- 일반 연결/5xx 실패: 최초 포함 최대 3회, 1초·2초 후 재전송. 영구적인 다른 4xx와 잘못된 응답은 자동 재전송하지 않는다.
- 대기열 429/INVESTIGATION_QUEUE_FULL: 최초 외 최대 3회. Retry-After 이상인 5/10/20초와 0~1초 jitter를 적용한다.
  중간에 다른 연결 오류가 섞여도 횟수를 초기화하지 않는다. 점유 후 중단된 시도도 보수적으로 횟수에 포함한다.
- 한도 소진: FAILED/submissionError를 보존한다. 수동 동일 키 POST가 retryable 실패만 원자적으로 PENDING으로 되돌리고
  새 전달 묶음의 횟수를 초기화한다. 기존 분석 ID·스냅샷을 유지한다.
- 접수 성공: SUBMITTED와 실제 investigationId를 저장한다. 별도 GET에서 확인한 조사만 investigation/lastSyncedAt에 기록한다.
- polling: 접수 후 저장된 14분 관측 창, 기본 5초 간격. 재시작·설정 변경으로 창을 연장하지 않는다.
  조회 실패에는 마지막 조사/시각을 보존하고 syncError만 갱신한다. 조사 FAILED나 티켓 RESOLVED로 바꾸지 않는다.
- 관측 종료: AGENT_OBSERVATION_EXPIRED와 현재 결과를 유지하고 자동 조회를 멈춘다. `refresh=true`는 기존 조사 GET 한 번만
  예약하며 창을 다시 열거나 조사 POST를 만들지 않는다. 같은 시각의 중복 새로고침은 하나로 모으고 처리 중 추가 요청도 보존한다.
- COMPLETED/NEEDS_INPUT/FAILED는 자동 polling 종료 상태다. 실패한 조사를 다시 실행하려면 사용자가 새 키의 별도 분석을 만든다.

Agent 응답의 티켓·버전·조사 ID, 상태별 보고서·오류 구조와 근거 참조를 검사한다. 잘못된 응답은
AGENT_PROTOCOL_ERROR로 표시하고 기존 캐시를 보존한다. 원문 근거는 고정 Agent 주소로만 조회하며 저장된 source와 대조한다.
오류 응답의 원문 진단·인증 값을 전달 오류에 그대로 저장하지 않는다.
HTTP 요청 제한은 헤더와 본문 수신 전체에 적용하며, 만료·호출 스레드 중단 시 실제 전송을 취소한다.
응답은 UTF-8 문자 수가 아닌 수신 바이트로 최대 4MiB까지만 보관한다. 초과하는 조각에서 즉시 취소해
AGENT_PROTOCOL_ERROR로 처리하며, 시간 초과는 기존 AGENT_UNAVAILABLE 재시도 정책을 따른다.

`AGENT_BASE_URL`은 기존 Compose 내부 주소 또는 로컬 Agent 주소다. 모델 키/OAuth는 VOC가 읽지 않는다.
현재 로컬 서버 간 연결에는 사용자 인증·서비스 토큰 검증이 아직 없으며 공개 web 단계에서 접근 제어를 연결한다.
기본 앱은 test/mock이고 실제 모델 성공을 이 작업기의 동작과 혼동하지 않는다.

Spring 설정 `jdd.voc.worker.*`의 기본값은 enabled=true, concurrency=4, tick-millis=1000,
connect-timeout-seconds=3, request-timeout-seconds=10, lease-seconds=30, poll-seconds=5,
observation-seconds=840, delivery-attempts=3, queue-retries=3이다. 전달·대기열 상한은 각각 3회/재전송 3회를 넘길 수 없다.
lease는 요청 제한보다 5초 이상 길어야 하며 polling 간격은 5초 이하로 설정한다.
기존 저장/티켓 계약 검사는 작업기를 명시적으로 꺼 저장 직후 상태를 검증하고, 작업기 검사는 별도로 실행한다.

## 검증

`./gradlew :voc-app:test` 또는 Windows의 `gradlew.bat :voc-app:test`를 실행한다.
실제 HTTP 서버·Flyway·H2에서 티켓 계약과 분석의 스냅샷·중복·동시 버전 충돌·다른 티켓 접근 차단·
이력 정렬·응답 유실 후 복구를 확인한다. 저장 전용 검사의 전달/조사 실패 분기에는 명시적인 합성 DB 결과를 사용하며
이 검사가 Agent 작업기나 모델을 실행한 것은 아니다.

동일한 HTTP 계약 테스트를 실제 PostgreSQL에서도 실행할 수 있다.
별도 빈 데이터베이스 `jdd_voc_contract_test`를 준비하고 다음 환경 변수를 설정한다.
암호는 로컬 환경에서 전달하며 Git·명령 로그에 기록하지 않는다.

- VOC_TEST_DB_URL: 해당 테스트 DB의 JDBC URL. 애플리케이션 DB 이름은 거부한다.
- VOC_TEST_DB_USER, VOC_TEST_DB_PASSWORD: 테스트 DB 전용 접속 정보.

`./gradlew :voc-app:test --tests 'com.jdd.voc.*HttpContractTest' --rerun-tasks`를 실행한다.
이 모드는 voc 스키마를 생성하고 각 테스트 전에 이 별도 DB의 분석·티켓 fixture를 비운다.
일반 앱 DB에 연결하지 않는다. 이 테스트의 성공은 실제 Agent·모델·MVP 검증을 의미하지 않는다.
외부 DB 모드에서는 `--rerun-tasks`를 생략해도 Gradle의 UP-TO-DATE·빌드 캐시를 재사용하지 않는다.
DB 모드만 검사 입력으로 구분하며 URL·비밀번호는 fingerprint에 포함하지 않는다.

AnalysisWorkerHttpContractTest는 실제 VOC HTTP·JDBC와 로컬 합성 Agent HTTP 서버를 연결한다.
백그라운드 실행, 접수 후 응답 절단, 수정 전 스냅샷 재전송, 429/Retry-After/수동 복구, 5xx/영구 4xx,
조회 실패와 마지막 상태/시각 보존, 모델 오류 분리, 관측 종료·수동 조회, 동시 점유·기한 복구·늦은 결과 차단,
다른 티켓의 근거 차단과 잘못된 보고서/소속 거절을 확인한다. 합성 보고서는 실제 모델의 출력이 아니다.

AgentHttpTransportTest는 실제 loopback HTTP로 헤더 뒤 멈춘 본문, UTF-8 바이트 초과, 끝나지 않은
chunked 응답의 크기 초과, 정확한 4MiB 경계, 스레드 중단·시간 초과 후 정상 요청 복구를 확인한다.
DB나 실제 모델을 호출하지 않으며 HTTP 전송 시도는 각 gateway 호출당 한 번이다.
