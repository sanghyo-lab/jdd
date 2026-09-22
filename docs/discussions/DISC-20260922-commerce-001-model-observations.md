# DISC-20260922-commerce-001 — 조사별 실제 모델 관측 연결

| 항목 | 내용 |
| --- | --- |
| ID | DISC-20260922-commerce-001 |
| 상태 | AGREED |
| 제안 버전 | P1 |
| 작성자 / 역할 | 이상효 / commerce·lead |
| 정리 담당 | 이상효 |
| 영향받는 역할 | agent 제공자, voc runner, commerce·lead 검증 |
| 필수 합의자 | 한재홍(장부 제공), 김아름(runner 소비), 이상효(리더) |
| 확인·답변 대기 | 김아름의 실제 API/LOG 소비 검증·각 PC의 최신 실제 모델 runner 검증 |
| 생성 시각 / 최종 갱신 | 2026-09-22T07:44:36+09:00 / 2026-09-22T09:09:00+09:00 |
| 다음 행동 / 담당 | 김아름은 실제 소비 검증, 한재홍은 세 번째 전체 실패 보존 후 v9 단계 분리 검증 |

## 결정할 질문

runner가 설정 모델명을 실제 응답으로 오인하지 않도록 비밀 없는 조사별 장부 조회를 연결할 것인가?

## 배경과 확인 근거

- [기존 runtime 합의](DISC-20260921-commerce-002-live-mvp-runtime.md)의 configuredModel은 설정값이다. 실제 모델·사용량 관측이 아니다.
- 리더 요청은 81fc1c4의 [lead 상태](../status/lead.md)에 먼저 공유했다. 소비 구현은 [ScenarioRunner](../../scenario-runner/src/main/java/com/jdd/scenario/ScenarioRunner.java)의 modelObservations/validateModel이다. 제공자가 없으면 live 검증은 실패하며 mock이나 설정값으로 대신하지 않는다.
- Agent 내부 구현은 사용자 지시대로 기존 담당 한재홍에게 맡긴다. 리더는 장부 API나 readiness 구현을 중복 편집하지 않는다.

## P1 제안과 대안

- 내부 GET `/internal/investigations/{id}/model-observations`는 해당 조사만 조회한다. 없는 ID는 기존 NOT_FOUND 오류, 실제 모델 호출·조사 생성·업무 변경은 0이다. web 중계 allowlist에 추가하지 않는다.
- 응답은 schemaVersion="1.0", investigationId, runtime, provider, calls 배열이다. calls는 실제 시각·동률 callId의 안정적인 순서이며 각 항목은 callId, investigationId, provider, requestedModel, actualModel, outcome, usage, createdAt, elapsedMillis를 제공한다. actualModel/usage/elapsedMillis 미관측은 명시적 null이다.
- actualModel은 실제 응답에서 저장한 값만 허용한다. requestedModel/configuredModel을 복사하지 않는다. outcome은 장부의 실제 전송/종료 상태를 유지하고 마지막 확인된 응답은 HTTP_200 계열이며 failed/incomplete가 아니다. API 예약의 미확정 상태·비용과 OAuth 관측은 섞지 않는다.
- 인증·키·토큰·프롬프트·모델 원문·개인 정보는 반환하지 않는다. provider가 다른 과거 호출을 숨기거나 0회/0 usage로 바꾸지 않는다. 저장 필드가 부족하면 누락을 명시하고 실제 검증을 미완료로 둔다.
- 한재홍이 영속 장부·조회 API/검사를 제공한 뒤 김아름과 리더가 runner의 실제 HTTP/장부 일치를 확인한다. 추가 필드는 허용하며 기존 조사/근거 공개 계약을 바꾸지 않는다. 필드 변경 제안은 답변 후 소비 코드를 같이 맞춘다.
- 대안인 runner의 직접 DB/인증 조회는 자식에 비밀과 제공자 저장 구조를 노출하므로 선택하지 않았다. 제공자를 기다리는 동안 fixture 준비·원문 비교·복구·화면 검증은 독립 진행한다.

## LOG 제공 형식 확인 요청 — LOG-AGENT-CONTRACT-001

실제 공유 LogEvidenceTools의 source.path는 `<buildId>/<file>.jsonl`, content는 `{raw: 원문 한 줄, entry: 파싱한 객체}`다. 제안 당시 공개 계약의 배열/빌드 폴더 아래 상대경로 설명과 달랐으며 한재홍이 [공개 근거 계약](../integration-contract.md)을 실제 형식으로 정정했다. runner와 web은 저장된 실제 제공 형식을 보존해 읽으며 합성 배열로 바꾸지 않는다. 리더는 실제 19개 LOG 원문·줄 대조와 정정된 문서를 인수했다. 김아름의 명시 소비 답변과 실제 모델 결과 연결은 남아 미해소다.

## 해소 기준

- [x] 세 작업자의 확인과 최신 P1 필수 합의자의 수락.
- [x] 한재홍의 제공자 구현·실제 PostgreSQL 소속/순서/null/무호출 검사.
- [ ] 김아름·리더의 실제 runner 소비·장부/응답 모델 일치, 잘못된 소속·mock·미관측 거절.
- [ ] LOG-AGENT-CONTRACT-001의 실제 제공/소비 형식 확인과 계약 문서 정합.
- [ ] 공유 커밋·직접 검증 결과·목록 갱신. 이 건 해소는 전체 모델 품질/DONE이 아니다.

## 답변 기록

### 이상효 — commerce / lead

2026-09-22T07:44:36+09:00 / 이상효 / commerce·lead / P1 / 의견: 수락·소비 구현

runner 및 부모 helper를 직접 검토해 통합했다. 위임 검사에서 실제 SELECT 도구의 DATA107/LOG19/CODE8/POLICY8, 총142근거가 실제 DB·줄·소스·정책과 일치했다. 실제 helper의 자료 준비/동일 VOC 컨테이너 재시작은 20.625초/종료0이며 조사/OAuth/API 장부 전후0이다. 이 결과는 모델 관측 API 인수나 실제 리포트 품질 성공이 아니다. 제공자 답변·실제 모델 검증을 기다린다.

### 한재홍 — agent

2026-09-22T07:50:51+09:00 / 한재홍 / agent / P1 / 의견: 수락·제공 구현

- 새 건 등록과 `ScenarioRunner.validateModel`을 확인하고 P1의 경로·응답 필수 필드·무호출/비밀 제외·미관측 null 보존을 수락한다. 동시 작업 중 만든 제공 구현에 각 call의 저장 investigationId를 추가하고 createdAt/callId/provider 순으로 맞췄다. 마지막 provider는 동일 callId 동률의 안정 순서만 정한다. 추가 ledgerState는 API의 예약/미확정 상태이며 OAuth는 null이다.
- API elapsedMillis는 측정·저장하지 않아 null이고, OAuth만 저장된 직접 측정값을 반환한다. API 시각 차이로 지연을 추정하지 않는다. actualModel은 원래 응답 관측이며 requestedModel로 대체하지 않는다. 현재 runtime/provider와 과거 행의 provider는 별개다. [정확한 관측 DTO](../llm-runtime.md#조사별-내부-모델-관측)를 참고한다.
- 실제 전용 PostgreSQL/HTTP 3개 검사를 두 번 통과했다. 두 번째는 최종 조사 ID·동률 정렬·nullable usage/지연·모든 API 장부 상태·타 조사/없는 조사 격리·GET 반복 장부 불변·민감 원문 제외를 확인했다. `runtime/submission/agent-20260921/followup-model-observations-contract-postgres.log/json`에 보존한다. 합성 장부 검사이며 모델 실제 호출은 0이다.
- LOG-AGENT-CONTRACT-001: 실제 `LogEvidenceTools`의 source.path=`<buildId>/<file>.jsonl`, startLine=endLine, eventId와 content의 raw/entry 제공을 확인한다. raw는 줄 끝 LF/CR을 제외한 실제 한 줄이며 entry는 같은 줄을 파싱한 객체다. [공개 근거 계약](../integration-contract.md)을 구현에 맞춰 정정했다. 같은 형식을 사용한 기존 실제 저장 근거 인수는 722c39f 및 후속 OAuth 기록에 있으며 이번 문서 정정을 새 모델 검사로 계산하지 않는다.
- 동시 작성한 기존 [DISC-commerce-002 P2](DISC-20260921-commerce-002-live-mvp-runtime.md)는 workerReady/실행 준비 의미 인수로 이어가고 조사별 API/LOG 소비 답변은 이 건에 모은다. 양쪽 기록과 기존 P1 수락을 보존한다. 전체 publish 및 새 실행 스택의 보존 조사/장부 GET 인수는 이어 수행하며 소비자·실제 runner/모델 품질 확인 전 미해소다.

### 김아름 — voc

2026-09-22T08:29:13+09:00 / 김아름 / voc / P1 / 의견: 수락·직접 실행 검증 대기

- 조사별 내부 GET과 call 소속·안정 순서·실제 응답 모델·미관측 null·ledgerState 보존을 수락한다. 설정 모델명을 actualModel로 대신하지 않으며 내부 경로를 web 중계에 추가하지 않는다. 공유된 ScenarioRunner의 소속/provider·실제 마지막 응답 검사와 EvidenceVerifier의 LOG raw/entry·한 줄 원문 비교를 읽었다.
- LOG의 source.path는 buildId를 포함하고 content는 실제 raw/entry 객체다. 이 형식과 기존 저장 근거를 보존하며 배열로 바꾸거나 경로에 buildId를 중복 추가하지 않는다. web 근거 패널의 객체 원문 표시를 확인했으며 실제 서버·화면 소비는 이 PC에서 이어 검증한다.
- VOC-LEAD-HANDOFF-001로 기존 runner/화면을 인계받았다. 제공자·리더의 PostgreSQL/실제 모델 결과를 본인 검증으로 계산하지 않는다. P1 합의는 완료됐지만 최신 모델 장부·보고서·원문 소비 검증이 남아 AGREED이며 RESOLVED/DONE은 아니다.

### 한재홍 — 공유 뒤 실제 저장 장부 직접 인수

2026-09-22T08:03:00+09:00 / 한재홍 / agent / P1 / 의견: 제공 검증

- `a9691bc`·`754528c` 전체 publish 종료0으로 제공 API/직접 인용/준비 상태를 공유했다. 현재 실제 스택의 기존 8조사·OAuth44행을 직접 SELECT와 GET으로 대조해 소속·필드·순서·시각·지연이 일치한다. usage 미관측1행을 null로 보존하고 requestedModel로 actualModel을 대체하지 않는다. mock 기동 중 과거 calls.provider는 codex_oauth로 유지됐다.
- 반복 GET과 없는 조사404 뒤 DB/장부 불변이며 이 읽기 검증의 새 모델/API 호출0이다. API의 예약/미확정/모든 상태는 별도 PostgreSQL 합성 장부3검사 범위다. 실제 사용하지 않은 API 상태를 실제 청구 결과로 만들지 않는다.
- production web의 실제 과거 LOG raw/entry, DATA·CODE·POLICY 조회도 확인했다. 원문/범위는 [Agent 상태](../status/agent.md)의 같은 시각과 `runtime/submission/agent-20260921/followup-observations-live-*/`에 보존한다. 현재 빌드의 명시 local OAuth runner는 진행 중이며 소비자 답변/전체 품질 전 미해소를 유지한다.

## 결정·실행·검증

Agent 제공 구현의 전체 공유·자기 PC의 실제 저장44행 대조와 리더 PC의 PostgreSQL/무호출 HTTP 인수에 이어 김아름도 P1을 직접 수락했다. 최신 제안은 AGREED이며 김아름의 실제 API/LOG 소비와 최신 실제 모델 장부·보고서를 포함한 runner 검증이 남아 미해소다. 실제 입력/출력 원문은 실행별 runtime에 보존한다.

2026-09-22T08:06:30+09:00 / 한재홍 / agent / P1 / 실제 runner 소비 결과: 고정 빌드754528c에서 VOC-01은 PASSED이며 실제 model-observations/원문 DATA·LOG·CODE·POLICY 비교를 통과했다. VOC-02도 실제 장부·15근거 비교까지 진행했으나 원인 항목의 DATA 직접 인용 누락으로 실패했다. 모델은 두 건 모두 gpt-5.6-luna, OAuth 각4회/API0이다. 이는 관측 DTO 실패가 아니며 보고서의 추가 직접 인용 결함도 원문 검수로 확인해 [Agent 상태](../status/agent.md)에 보존했다. 나머지 시나리오는 PENDING이고 전체 성공/DONE을 기록하지 않는다. 김아름/리더의 직접 인수는 대기한다.

## 해소 또는 재개 이력

2026-09-22T07:44:36+09:00 / 이상효: 요청을 건별 P1으로 등록. 타인의 수락·구현·DONE을 대신 기록하지 않는다.

2026-09-22T07:50:51+09:00 / 한재홍: P1 제공 수락·구현/전용 PostgreSQL 검사·LOG 계약 정정. 소비 인수와 실제 모델 검증 대기로 DISCUSSING.

### 2026-09-22T08:06:04+09:00 — 이상효 / commerce·lead / P1 제공 구현 직접 인수

- 의견: 수락·무호출 제공 계약 인수. `a9691bc`·`754528c`의 제공 코드·테스트·DTO·LOG 문서를 읽고 리더 통합 `86a0466`을 전체 publish했다. 각 call의 investigationId/ledgerState, createdAt/callId/provider 정렬, API elapsedMillis=null과 OAuth 직접 관측 보존을 수락한다. 요청 모델을 응답 모델로 복사하지 않음을 확인했다.
- 리더 PC의 별도 PostgreSQL `jdd_agent_budget_test`에서 `ModelObservationHttpTest` 3개를 새로 실행해 모두 통과했다(21.077초, 제외0). 합성 장부의 소속·순서·미관측 null·모든 API 상태·조회 불변·민감 원문 제외 검사이며 실제 모델 호출은0이다. 원문은 `runtime/submission/commerce-20260921-resumed/agent-observations-postgresql-current/`다.
- 같은 buildId `86a046657350-023906d81950`의 실제 서버에서 기존 조사 `0159c0cd-bf61-45f2-b414-abc2fb6f97ea`의 내부 조회200/calls=[]와 no-store, 반복 동일 응답·없는 조사404를 확인했다. API/OAuth 장부0/0·조사13행이 전후 동일하다. `agent-observations-running-http.json`에 원문을 보존했다. 0회 관측을 모델 성공으로 계산하지 않는다.
- LOG raw/entry·빌드 포함 경로는 리더의 실제 도구142개 중 LOG19개 원문 대조와 일치한다. 제공자 정정 계약과 runner 소비 구현을 인수한다. 김아름의 답변과 실제 응답 모델·저장 보고서를 포함한 runner 검증은 아직 남아 DISCUSSING을 유지한다.

### 2026-09-22T08:37:00+09:00 — 한재홍 / agent / P1 후속 소비 결과

- 김아름의 P1 직접 수락과 리더 인계 접수를 확인했다. AGREED를 유지하며 상대 PC의 실제 소비를 대신 완료하지 않는다.
- `27a7040`의 최종 인용 검수 보완 뒤 실제 UI VOC-02 조사 `e9be5f20-2d07-4996-ac51-bc3940a125a7`가 COMPLETED/47근거다. 서버의 DATA 누락 거절→기존 보정→도구 없는 최종 검수 경로를 실제 OAuth6회로 확인했다. 원인 자체의 DATA/LOG/CODE/POLICY와 빈 조회 문장의 범위는 개선됐다. 원문·조치 구체성의 남은 한계는 [Agent 기록](../status/agent.md)에 있다. 전체 최신 시나리오 성공으로 계산하지 않는다.
- 현재 이 스택 OAuth67행(기존 usage미관측1)/API0/진행조사0을 DB와 반복 내부 GET로 직접 대조했다. 검수 호출도 기존 장부에 입력25,088/출력1,382·26,233ms로 남으며 별도 무관측 호출로 숨기지 않는다. 최신 통합 전체 runner와 김아름의 원문/모델 관측 소비 결과까지 이어 검증한다.

### 2026-09-22T08:52:18+09:00 — 한재홍 / agent / P1 후속 실패와 보완

- 전체 runner9fa3e87은 VOC-01 통과 뒤 VOC-02의 실제 사건 LOG 인용 불일치로 종료1이다. 제공 근거에 COUPON_REJECTED가 있었지만 보고서는 INVENTORY_READ를 인용했다. DTO/원문 비교 기준을 낮추지 않았고 이후9개 case는 PENDING이다. 이 스택 OAuth78행/API0/기존usage미관측1을 대조했다.
- 별도 영상 환경의 DEMO-AGENT-001 최종 재작성 인용 누락도 접수했다. v8 검수는 원문 중심 입력과 기존 항목의 부분 수정으로 변경하며 나머지 내용/인용과 기존 전체 검증·호출/시간 한도를 보존한다. 모의81개 통과와 실제 효과 미검증을 구분한다. 상세 원문/실패/비용은 [Agent 상태](../status/agent.md)에 있고 실제 소비 완료·해소는 계속 대기한다.

### 2026-09-22T08:59:09+09:00 — 한재홍 / agent / P1 v8 focused 실검증

- `6e684ca` 전체 publish 후 앞선 VOC-02의 같은 업무 데이터로 새 조사 `21add8b5-c286-4abb-a517-073125fa3a30`를 한 번 실행해 실제 COUPON_REJECTED와 원인 자체의 DATA/LOG/CODE/POLICY를 직접 확인했다. 최종 부분 검수는 항목1개 교체·삭제0·요약유지였고 기존 인용을 다시 빠뜨리지 않았다. 46근거/실제OAuth5회/API0, 별도 검수 호출 입력11,180/출력844·17,778ms다.
- 이 스택83행(기존usage미관측1)을 DB·내부 관측으로 다시 대조했다. focused 결과와 이전 실패·전체 runner 실패는 [Agent 상태](../status/agent.md)에 보존한다. 최신 빌드 전체 runner와 김아름/영상 환경의 직접 재검증이 남아 AGREED를 유지한다.

### 2026-09-22T09:09:00+09:00 — 한재홍 / agent / P1 전체 실패 보존과 94행 대조

- build d835c35의 전체 실제 runner는 VOC-01 통과·VOC-02 보고서 보정 실패 뒤 종료1이다. 원인 DATA 누락 다음 응답의 필수 사실 부재로 실패했고 report=null을 유지한다. 나머지9개 case는 PENDING이다. 입력 단계 혼동은 원문 미보존으로 가설이며 확정 원인으로 기록하지 않는다.
- 이 스택94행/API0/기존usage미관측1/진행0의 DB·내부 GET 일치를 다시 확인했다. v9은 일반 전체 보고서와 부분 검수 지침을 분리하고 기존 검증·호출/시간 한도를 유지한다. 모의82개 통과와 전체 publish/실제 효과 미검증을 구분한다. 원문·두 사례 사용량은 [Agent 상태](../status/agent.md)에 보존한다. 김아름의 실제 소비 검증과 전체 모델 검증 전 AGREED를 유지한다.
