# VOC 티켓 API

현재 단위는 PostgreSQL에 저장하는 티켓 생성·목록·상세·수정과 담당자 조회다.
업무 상태와 버전을 관리하며 같은 버전의 동시 수정은 한 건만 성공한다.
분석 요청·Agent 연동·화면은 다음 구현 단위다. 상세의 analyses는 현재 빈 배열이며
businessReady=false를 유지한다. 실제 AI 분석 완료로 표시하지 않는다.

## 사용

저장소 루트에서 `scripts/dev up`으로 앱을 실행한다. VOC 기본 주소는 http://localhost:8082다.
요청·응답과 오류의 기준은 [v1 계약](../docs/integration-contract.md)이다.

- `GET /api/assignees`: 세 담당자의 내부 ID와 이름.
- `POST /api/tickets`: title·message와 선택적 context·assigneeId. 201, version=1.
- `GET /api/tickets`: status·assigneeId 필터와 limit·offset, 생성 시각·ID 내림차순.
- `GET /api/tickets/{ticketId}`: 저장된 ticket과 analyses.
- `PATCH /api/tickets/{ticketId}`: expectedVersion과 변경할 필드. 버전 충돌은 409.

PATCH에서 생략한 필드는 유지한다. assigneeId=null은 배정을 해제하며 context는 전체 교체한다.
빈 context 객체는 비우고 title·message·status·context 자체의 null은 거절한다.
AI 상태와 무관하게 티켓을 OPEN·IN_PROGRESS·RESOLVED로 변경하고 다시 열 수 있다.
조사 대상 시각은 UTC로 정규화하되 입력의 소수점 정밀도를 보존한다.

## 검증

`./gradlew :voc-app:test` 또는 Windows의 `gradlew.bat :voc-app:test`를 실행한다.
실제 HTTP 서버·Flyway·H2에서 저장, PATCH의 생략/null 구분, 동시 버전 충돌,
필터·페이지 정렬, 잘못된 입력을 확인한다.

동일한 HTTP 계약 테스트를 실제 PostgreSQL에서도 실행할 수 있다.
별도 빈 데이터베이스 `jdd_voc_contract_test`를 준비하고 다음 환경 변수를 설정한다.
암호는 로컬 환경에서 전달하며 Git·명령 로그에 기록하지 않는다.

- VOC_TEST_DB_URL: 해당 테스트 DB의 JDBC URL. 애플리케이션 DB 이름은 거부한다.
- VOC_TEST_DB_USER, VOC_TEST_DB_PASSWORD: 테스트 DB 전용 접속 정보.

`./gradlew :voc-app:test --tests com.jdd.voc.TicketHttpContractTest --rerun-tasks`를 실행한다.
이 모드는 voc 스키마를 생성하고 각 테스트 전에 이 별도 DB의 티켓 fixture를 비운다.
일반 앱 DB에 연결하지 않는다. 이 테스트의 성공은 실제 Agent·모델·MVP 검증을 의미하지 않는다.
외부 DB 모드에서는 `--rerun-tasks`를 생략해도 Gradle의 UP-TO-DATE·빌드 캐시를 재사용하지 않는다.
DB 모드만 검사 입력으로 구분하며 URL·비밀번호는 fingerprint에 포함하지 않는다.
