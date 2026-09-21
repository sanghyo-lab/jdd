# DISC-20260921-commerce-001 — 재고 재현 제어와 업무 근거 소비

| 항목 | 내용 |
| --- | --- |
| ID | DISC-20260921-commerce-001 |
| 상태 | OPEN |
| 제안 버전 | P1 |
| 작성자 / 역할 | 이상효 / commerce·lead |
| 정리 담당 | 이상효 |
| 영향받는 역할 | commerce, agent, voc |
| 필수 합의자 | 이상효(제공), 김아름(재현 runner), 한재홍(근거 조회) |
| 확인·답변 대기 | agent, voc |
| 생성 시각 / 최종 갱신 | 2026-09-21T17:35:00+09:00 / 2026-09-21T17:58:54+09:00 |
| 다음 행동 / 담당 | 두 소비자의 P1 접수·연결 검증 |

## 결정할 질문

VOC-07 runner는 opt-in 재현 장벽을 사용하고, Agent는 커밋 후 JSONL·실행 소스의 같은 buildId를 연결하는 P1을 수용하는가?

## 배경과 확인 근거

- 요청 추적: COMMERCE-001(voc 재현), COMMERCE-002(agent 조회). 기존 전달 요구는 [commerce 상태](../status/commerce.md), 소비자 착수는 `f466d375dd98`의 [VOC 상태](../status/voc.md)에 있다.
- [현재 계약](../commerce-interface.md)의 업무 DTO·DB 최소 컬럼·로그 이벤트를 유지한다. 새 내부 준비 API는 고객 업무 API를 변경하지 않는다.
- [VOC-07 실행법](../../fixtures/commerce/VOC-07/README.md)에 합성 입력·SQL·기대값과 구현을 제공한다. `e080390`에 구현을 공유하고 실제 PostgreSQL 20회·대조·복구를 통과했다. 소비자 확인은 진행 중이다.

## P1 제안과 대안

- `COMMERCE_REPRODUCTION_ENABLED=true`로만 `/internal/reproduction/inventory-barrier`를 노출한다. POST에 productId·두 checkoutKeys·timeoutMs, GET으로 도착의 backendPid·transactionId, DELETE로 종료·실패 대기를 해제한다. web은 이 경로를 중계하지 않는다.
- 동기화 구현은 `src/reproduction/java`에 두어 기존 소스 허용 범위 밖으로 분리한다. core의 관측 포트는 기본 no-op이다. 단순 sleep에 의존하는 대안은 반복 재현을 보장하지 못해 사용하지 않는다.
- runner는 실행별 합성 접두어로 초기화하고 조사·근거 저장이 끝날 때까지 관련 데이터를 유지한다. 평가 정답·제출 자료는 Agent에 전달하지 않는다.
- 업무 쓰기 이벤트는 같은 DB 트랜잭션의 outbox에 저장하고 100ms 간격으로 커밋된 행을 JSONL로 내보낸다. 관측 로그 INVENTORY_READ는 즉시 기록한다. 로그가 아직 없으면 소비자가 제한 시간 내 다시 조회한다.
- 추가 eventId는 기록 식별자다. 파일 쓰기 직후 프로세스가 종료되면 같은 eventId가 재출력될 수 있어 중복 관측을 두 번의 업무 처리로 세지 않는다. 기존 필드는 제거하지 않는다.
- 순서: commerce 구현·단독 검증 → voc 재현 제어/DTO 소비 검증 → agent SELECT/로그/소스 연결 검증. 합의 대기 중 각 역할의 독립 구현은 계속한다.

## 해소 기준

- [x] commerce가 P1에 수락했다.
- [ ] agent·voc가 P1을 직접 확인·수락했다.
- [x] 실제 PostgreSQL 독립 연결·트랜잭션 20회, 정상 대조·시간 초과·해제를 통과했다.
- [ ] voc runner가 같은 HTTP·DB 관측을 확인하고 agent가 동일 buildId의 SELECT·로그·소스를 조회했다.
- [ ] 공유 커밋·실행 근거를 확인하고 목록과 본문의 상태를 함께 갱신했다.

## 답변 기록

### 이상효 — commerce / lead

2026-09-21T17:35:00+09:00 / 이상효 / commerce·lead / P1

의견: 수락. 기존 v1 필드와 조사 경계를 유지한다. HTTP·H2의 입력/롤백 검증을 통과했으며 실제 PostgreSQL 재현을 이어 실행한다.
COMMERCE-001/002는 소비자 확인 전까지 미해소로 유지한다.

2026-09-21T17:58:54+09:00 / 이상효 / commerce·lead / P1

의견: 검증 결과. `e080390`의 전체 publish와 buildId `e080390b7157-083e2c0c180d`의 실제 PostgreSQL 20/20회·대조·복구를 통과했다. 전용 DB·HTTP의 독립 backendPid/txid·실제 주문/재고·JSONL·불변 소스가 일치했다. 원문과 명령은 [commerce 상태](../status/commerce.md)의 20회 기록을 따른다. 두 소비자의 직접 확인 전에는 OPEN을 유지한다.

### 한재홍 — agent

아직 답변 없음.

### 김아름 — voc

아직 답변 없음.

## 결정·실행·검증

- 구현과 제공자 검증은 `e080390`에 공유했다. 소비자 접수·구현·검증 완료를 가정하지 않는다.
- 실제 커밋과 실행 결과는 완료되는 단위마다 이 문서와 역할 상태에 추가한다.

## 해소 또는 재개 이력

2026-09-21T17:35:00+09:00 이상효: P1 등록, OPEN. 소비자 직접 답변과 실제 검증 대기.
