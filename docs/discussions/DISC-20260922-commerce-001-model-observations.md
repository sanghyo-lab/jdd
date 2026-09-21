# DISC-20260922-commerce-001 — 조사별 실제 모델 관측 연결

| 항목 | 내용 |
| --- | --- |
| ID | DISC-20260922-commerce-001 |
| 상태 | OPEN |
| 제안 버전 | P1 |
| 작성자 / 역할 | 이상효 / commerce·lead |
| 정리 담당 | 이상효 |
| 영향받는 역할 | agent 제공자, voc runner, commerce·lead 검증 |
| 필수 합의자 | 한재홍(장부 제공), 김아름(runner 소비), 이상효(리더) |
| 확인·답변 대기 | 한재홍·김아름 |
| 생성 시각 / 최종 갱신 | 2026-09-22T07:44:36+09:00 / 2026-09-22T07:44:36+09:00 |
| 다음 행동 / 담당 | RUNNER-AGENT-OBS-001 제공자 응답·구현·실제 장부 소비 확인 |

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

실제 공유 LogEvidenceTools의 source.path는 `<buildId>/<file>.jsonl`, content는 `{raw: 원문 한 줄, entry: 파싱한 객체}`다. [현재 공개 근거 계약](../integration-contract.md)의 배열/빌드 폴더 아래 상대경로 설명과 다르다. runner와 web은 저장된 실제 제공 형식을 보존해 읽으며 합성 배열로 바꾸지 않는다. 한재홍·김아름에게 이 형식의 제공/소비를 확인하고 계약 문서도 같은 내용으로 갱신할 것을 요청한다. 실제 19개 LOG 원문·줄 대조는 통과했지만 문서 합의로 대신하지 않으며 확인 전 미해소다.

## 해소 기준

- [ ] 세 작업자의 확인과 최신 P1 필수 합의자의 수락.
- [ ] 한재홍의 제공자 구현·실제 PostgreSQL 소속/순서/null/무호출 검사.
- [ ] 김아름·리더의 실제 runner 소비·장부/응답 모델 일치, 잘못된 소속·mock·미관측 거절.
- [ ] LOG-AGENT-CONTRACT-001의 실제 제공/소비 형식 확인과 계약 문서 정합.
- [ ] 공유 커밋·직접 검증 결과·목록 갱신. 이 건 해소는 전체 모델 품질/DONE이 아니다.

## 답변 기록

### 이상효 — commerce / lead

2026-09-22T07:44:36+09:00 / 이상효 / commerce·lead / P1 / 의견: 수락·소비 구현

runner 및 부모 helper를 직접 검토해 통합했다. 위임 검사에서 실제 SELECT 도구의 DATA107/LOG19/CODE8/POLICY8, 총142근거가 실제 DB·줄·소스·정책과 일치했다. 실제 helper의 자료 준비/동일 VOC 컨테이너 재시작은 20.625초/종료0이며 조사/OAuth/API 장부 전후0이다. 이 결과는 모델 관측 API 인수나 실제 리포트 품질 성공이 아니다. 제공자 답변·실제 모델 검증을 기다린다.

### 한재홍 — agent

아직 답변 없음.

### 김아름 — voc

아직 답변 없음.

## 결정·실행·검증

소비 구현의 계약 제안이며 제공자 수락 전 OPEN이다. 실제 모델·장부 HTTP 소비는 미검증이고 실제 입력/출력 원문은 실행별 runtime에 보존한다.

## 해소 또는 재개 이력

2026-09-22T07:44:36+09:00 / 이상효: 요청을 건별 P1으로 등록. 타인의 수락·구현·DONE을 대신 기록하지 않는다.
