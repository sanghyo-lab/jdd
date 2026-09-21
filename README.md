# JDD — 이커머스 VOC 조사 에이전트

개발팀이 받은 문의를 소스코드·로그·데이터와 연결해 원인 후보와 해결안을 찾는 내부 도구를 만든다.

- 해커톤: 3명, 2일
- 주 기술: Java, Spring Boot
- 3인 분담: AI Agent / 이커머스 / VOC 티켓 관리·AI 연동
- GitHub 작업 방식: 각자 별도 clone의 `main`에서 개발하고, 검증한 변경을 커밋·push해 공유
- 실행 골격: Gradle 백엔드 모듈 10개, 커머스·조사·VOC의 세 실행 단위
- 프론트 제안: `web`의 Next.js·React·TypeScript, Vercel 배포
- 조사 대상: 주문·결제·쿠폰·취소·재고에 관한 7개 문의 시나리오

현재는 **각 PC에서 역할별 개발을 시작할 준비 단계**다. 공통 Spring Boot 실행 골격·DB·동기화 도구를 준비했고, 주문·티켓·AI 분석 기능은 각 담당 goal에서 구현한다. 실제 goal은 아직 시작하지 않았다.

## 각자 시작하기

**[세 PC의 최초 준비와 역할별 goal 시작 명령](docs/goals/README.md)** 을 먼저 읽는다.

- 이상효: [commerce·개발리더 goal](docs/goals/commerce.md), [리더 최종 검증 단계](docs/goals/lead.md)
- 김아름: [voc goal](docs/goals/voc.md)
- 한재홍: [agent goal](docs/goals/agent.md)

```bash
./scripts/dev up
./scripts/dev smoke
```

위 명령으로 각자의 PostgreSQL·커머스·Agent·VOC를 실행하고 연결을 확인한다.
설치 조건과 검증 범위는 [로컬 개발 안내](docs/local-development.md)에 있다.
자동 개발은 [AGENTS.md](AGENTS.md)와 [협업 반복 규칙](docs/autonomous-development.md)을 따른다.
원격 확인은 `./scripts/dev status`, 소스 반영은 `./scripts/dev sync`, 검증 후 공유는 `./scripts/dev publish`다.
모든 goal은 **GitHub main에 세 담당자의 유효한 DONE과 개발리더 이상효의 독립 검토·최종 APPROVED가 모두 있을 때** 종료한다.
세 DONE 이후에도 이상효는 전체 코드를 직접 검사하고 실제 통합 검증·수정·보완을 계속한다.
자기 기능 완료 후에도 연동·검증·수정을 계속하며, 최종 판정은 `./scripts/dev team-check`로 확인한다.
완료 공유·재검증 절차는 [세 담당자 완료 기준](docs/team-completion.md)을 따른다.

## 담당자별 구현 문서

| 담당자 | 만들 결과 | 작업 기준 |
| --- | --- | --- |
| 이상효 | 이커머스·조사 근거, 전체 코드 검사·수정·최종 검증 | [이커머스](docs/roles/lee-sanghyo-commerce.md), [개발리더](docs/roles/lee-sanghyo-lead.md) |
| 김아름 | VOC 티켓 관리, AI 분석 연동, 리포트·해결방안 화면 | [VOC·연동 구현 범위](docs/roles/kim-areum-voc.md) |
| 한재홍 | 소스·로그·DB 기반 원인 분석과 해결안 생성 | [AI Agent 구현 범위](docs/roles/han-jaehong-agent.md) |

[역할·인터페이스 전체 보기](docs/roles/README.md)에서 제공자와 사용자, 첫 통합 순서를 확인한다. 각 담당 문서에 기능·소유 모듈·전달 자료·구현 순서·완료 기준을 정리했다.

## 설계 문서

- [3인 랄프톤 GitHub 협업 가이드](docs/collaboration.md)
- [VOC·Agent API·리포트·근거 인터페이스 v1](docs/integration-contract.md)
- [커머스 API·DB·로그·실행 소스 인터페이스 v1](docs/commerce-interface.md)
- [멀티모듈 구조와 데이터 흐름](docs/architecture.md)
- [문의 화면과 Vercel 배포](docs/frontend-deployment.md)
- [앱과 에이전트가 확인할 업무 정책](docs/business-policy.md)
- [문의 시나리오·재현 조건·검증 기준](docs/voc-scenarios.md)
- [해커톤 프로젝트 보고서 초안·검증 결과표](docs/hackathon-report.md)
