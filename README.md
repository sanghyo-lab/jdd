# JDD — 이커머스 VOC 조사 에이전트

개발팀이 받은 문의를 소스코드·로그·데이터와 연결해 원인 후보와 해결안을 찾는 내부 도구를 만든다.

- 해커톤: 3명, 2일
- 주 기술: Java, Spring Boot
- 3인 분담: AI Agent / 이커머스 / VOC 티켓 관리·AI 연동
- GitHub 작업 방식: 각자 별도 clone의 `main`에서 개발하고, 검증한 변경을 커밋·push해 공유
- 구조 제안: Gradle 백엔드 모듈 10개, 커머스·조사·VOC의 세 실행 단위
- 프론트 제안: `web`의 Next.js·React·TypeScript, Vercel 배포
- 조사 대상: 주문·결제·쿠폰·취소·재고에 관한 7개 문의 시나리오

현재 단계는 구조 설계다. 모듈별 코드와 실행 설정은 설계에 따라 구현할 대상이다.

## 설계 문서

- [3인 랄프톤 GitHub 협업 가이드](docs/collaboration.md)
- [VOC·Agent·커머스 연동 계약 초안](docs/integration-contract.md)
- [멀티모듈 구조와 데이터 흐름](docs/architecture.md)
- [문의 화면과 Vercel 배포](docs/frontend-deployment.md)
- [앱과 에이전트가 확인할 업무 정책](docs/business-policy.md)
- [문의 시나리오·재현 조건·검증 기준](docs/voc-scenarios.md)
- [해커톤 프로젝트 보고서 초안·검증 결과표](docs/hackathon-report.md)
