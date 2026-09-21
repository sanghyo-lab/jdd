# JDD — 이커머스 VOC 조사 에이전트

개발팀이 받은 문의를 소스코드·로그·데이터와 연결해 원인 후보와 해결안을 찾는 내부 도구를 만든다.

- 해커톤: 3명, 2일
- 주 기술: Java, Spring Boot
- 구조 제안: Gradle 백엔드 모듈 7개, 커머스 앱과 조사 앱의 두 실행 단위
- 프론트 제안: `web`의 Next.js·React·TypeScript, Vercel 배포
- 조사 대상: 주문·결제·쿠폰·취소·재고에 관한 7개 문의 시나리오

현재 단계는 구조 설계다. 모듈별 코드와 실행 설정은 설계에 따라 구현할 대상이다.

## 설계 문서

- [멀티모듈 구조와 데이터 흐름](docs/architecture.md)
- [문의 화면과 Vercel 배포](docs/frontend-deployment.md)
- [앱과 에이전트가 확인할 업무 정책](docs/business-policy.md)
- [문의 시나리오·재현 조건·검증 기준](docs/voc-scenarios.md)
