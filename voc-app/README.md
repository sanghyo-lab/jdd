# VOC 티켓 API

PostgreSQL에 티켓·분석 요청·입력 스냅샷을 저장하고 티켓별 분석 이력을 제공한다.
업무 상태와 버전을 관리하며 같은 버전의 동시 수정은 한 건만 성공한다.
분석 요청은 저장 후 202와 PENDING을 반환한다. 서버의 Agent 전달·조회 작업과
근거 중계·화면은 다음 구현 단위이며 businessReady=false를 유지한다.

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
현재 서버 작업기는 아직 없으므로 새 요청은 PENDING에 머무른다. 이 API 제공을 실제 Agent 조사 완료로 계산하지 않는다.

## 검증

`./gradlew :voc-app:test` 또는 Windows의 `gradlew.bat :voc-app:test`를 실행한다.
실제 HTTP 서버·Flyway·H2에서 티켓 계약과 분석의 스냅샷·중복·동시 버전 충돌·다른 티켓 접근 차단·
이력 정렬·응답 유실 후 복구를 확인한다. 전달/조사 실패 분기에는 명시적인 합성 DB 결과를 사용하며
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
