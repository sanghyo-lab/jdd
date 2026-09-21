# 실제 VOC 시나리오 실행기

`./scripts/dev verify-mvp`가 준비한 현재 빌드에서 순차로 실제 티켓·조사·리포트·근거를 확인한다.
기본 `check`와 `publish`는 모의/loopback 검사만 실행하며 모델을 호출하지 않는다.
이 실행기는 모델 endpoint나 OAuth 파일, API 키, DB 암호를 읽지 않는다.

```bash
# 프로젝트 전용 local/codex_oauth와 모델을 먼저 준비한다.
COMMERCE_REPRODUCTION_ENABLED=true ./scripts/llm run-local
COMMERCE_REPRODUCTION_ENABLED=true JDD_MVP_LIVE=true ./scripts/dev verify-mvp
```

현재 Agent의 조사별 관측 API(`/internal/investigations/{id}/model-observations`)는
RUNNER-AGENT-OBS-001의 제공자 연동 요청이다. API·실제 모델·준비 상태가 없으면 실패하며
설정 모델명을 실제 응답 모델로 사용하거나 모의 결과로 통과시키지 않는다.

부모 실행기가 `scripts/prepare.py`의 `PreparedRun(repository, run_directory)`를 호출한다.
새 합성 prefix마다 기존 `fixtures/commerce`의 seed/reset와 실제 Commerce HTTP를 사용한다.
VOC-02는 기준금액 거절 후 같은 쿠폰을 사용하는 대조 주문을 만들지 않아 조사 시점의 AVAILABLE 상태를 보존한다.
VOC-07은 기존 제한된 장벽을 통해 실제 독립 DB 연결/트랜잭션 두 개와 커밋된 주문·재고·로그를 대조한다.
NORMAL은 별도 CARD 주문이고, 문의·식별자 외 DB snapshot·정답·준비 코드는 Agent 입력에 포함되지 않는다.

부모는 새 `prepared-cases.json`과 SHA-256, loopback coordinator URL, 한 번만 쓸 수 있는 복구 capability만
runner 환경에 추가한다. coordinator는 현재 project의 미리 식별한 VOC 컨테이너 하나만 stop/start한다.
다른 컨테이너 ID·명령·SQL·경로를 HTTP로 받지 않고, 실패 시 원래 컨테이너의 재기동을 시도해 결과를 보존한다.
Java runner의 직접 진입점은 `--report <새 JSON 경로>`이며 `JDD_MVP_LIVE=true`와 부모 준비가 없으면 거절한다.

검사 범위:

- VOC-01~07·NORMAL·NEEDS_INPUT 각각 한 건을 순차 접수하고 실제 terminal 상태까지 관측한다. 첫 실패 이후는 PENDING이며 새 키로 자동 재조사하지 않는다.
- 조사 GET과 VOC 캐시, 양쪽 근거 GET의 소속·원문을 대조한다. DATA는 준비 당시 실제 PostgreSQL 행/집계, LOG는 실제 JSONL 줄, CODE/POLICY는 동일 build의 불변 원문·해시와 비교한다.
- 보고서 항목별 참조·소속·필수 테이블/이벤트·조치/재발 방지 코드 인용을 검사한다. NORMAL에 장애 원인을 붙이지 않고, NEEDS_INPUT은 허용된 부족 정보 항목을 반환해야 한다.
- IDEMPOTENCY는 같은 키, 티켓 수정 후 옛 키의 입력 보존, 충돌/옛 버전 거절, 타 티켓 소속 접근 거절과 실제 모델 장부 불변을 확인한다.
- RECOVERY는 실제 VOC 컨테이너 종료로 HTTP 불가를 관측한 뒤 같은 컨테이너의 새 시작 시각·동일 build, 저장 티켓/이력/분석/근거와 모델 장부 불변을 확인한다. Agent 모델 실행을 재시작하지 않는다.

실행마다 새 결과 파일과 `<결과 파일>.artifacts/`에 실제 HTTP 응답 해시·조사·근거·모델 장부·복구 결과를 보존한다.
실패/기존 결과를 덮어쓰지 않고, 실패는 종료 코드 1, 잘못된 CLI/저장 불가는 2다. 모든 기존 필수 case가
PASSED이고 mocked=false일 때만 전체 결과를 성공으로 기록한다. observed model은 영속 호출 장부의 실제 응답으로 확인한다.
알 수 없는 usage는 null로 남기며 OAuth 사용량을 API 비용으로 환산하지 않는다.

이 자동 검사는 자연어 원인·조치의 모든 의미를 판정하지 않는다. 원문 보고서의 독립 검수와 실제 PC/모바일 화면 검증,
일곱 업무 결함 각 3회·재고 20회 반복은 별도 완료 기준이며 생략하지 않는다. 준비·loopback 통과는 실제 모델 품질이나 DONE이 아니다.

```bash
./gradlew --no-daemon :scenario-runner:check
```

위 명령의 Java/부모 경계 검사는 외부 모델·실제 Docker 변경 없이 임시 파일과 loopback HTTP를 사용한다.
실제 준비·복구 인수 결과는 리더 작업 기록과 각 실행의 runtime 산출물에서 별도로 확인한다.
