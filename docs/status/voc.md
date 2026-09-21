# 김아름 — VOC 티켓·AI 연동 작업 상태

- 상태: 실행 골격 준비. 티켓·화면·실제 분석 연동 미구현, 담당 goal 미시작.
- 담당자: 김아름 (역할 C)
- GitHub 계정: 공유받은 뒤 기입
- 작업 브랜치: `main`
- 시작 지침: [voc goal](../goals/voc.md), [공통 실행](../local-development.md)
- 작업 Issue·공유 커밋: 시작 후 기입
- 담당 경로: `voc-app/`, `voc-core/`, `voc-infra/`, `web/`, `scenario-runner/`
- 준비된 자료: [구현 범위](../roles/kim-areum-voc.md), [VOC·Agent 계약](../integration-contract.md), [커머스 계약](../commerce-interface.md), [프론트 설계](../frontend-deployment.md)
- 다음 작업: 준비된 공통 실행 틀 확인, v1 티켓·분석 요청 기록과 예제 JSON을 사용하는 화면 구현
- 필요한 입력: 한재홍의 실제 분석 API, 이상효의 시드·재현 방법, 배포 환경
- 검증 결과: 준비 PC에서 전체 Gradle check와 세 앱의 Docker 기동·smoke 통과. VOC에서 Agent·커머스 진단 API의 HTTP 200 확인. 실제 티켓·프론트·시나리오는 미검증.
- 연동 요청: 한재홍의 분석 API와 이상효의 재현 입력을 연결할 예정. scenario-runner는 미구현을 알리는 실패 종료 골격이다.

작업 단위가 끝날 때 제공 가능한 기능, 변경한 계약, 실제 검증 명령·결과, 다음 작업을 갱신한다. 실패와 막힌 이유도 함께 기록한다.
