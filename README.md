# JDD — 이커머스 VOC 조사 에이전트

개발팀이 받은 문의를 소스코드·로그·데이터와 연결해 원인 후보와 해결안을 찾는 내부 도구를 만든다.

- 해커톤: 3명, 2일
- 주 기술: Java, Spring Boot
- 3인 분담: AI Agent / 이커머스 / VOC 티켓 관리·AI 연동
- GitHub 작업 방식: 각자 별도 clone의 `main`에서 개발하고, 검증한 변경을 커밋·push해 공유
- 실행 골격: Gradle 백엔드 모듈 10개, 커머스·조사·VOC의 세 실행 단위
- 프론트: `web`의 Next.js·React·TypeScript, 인증된 문의 작업실
- 조사 대상: 주문·결제·쿠폰·취소·재고에 관한 7개 문의 시나리오

현재는 **세 역할의 구현·통합 검증 진행 중**이다. 커머스 업무 API와 일곱 VOC의 실제 PostgreSQL 재현을 제공한다.
Agent는 조사 접수·영속 실행·8개 실제 조회 도구·근거/보고서 검증·비용 제어와 모델 연결 코드를 제공한다.
VOC는 티켓·분석 입력 사본을 저장하고 Agent 전달·조회·근거 중계를 실행한다. 로그인·문의·분석 요청/이력·리포트·근거 4종과 커머스 시연 화면, 실제 HTTP runner를 제공한다.
리더 PC에서 `d642878cc1df-8a2f24c62e13`의 각 VOC 3회 이상·재고 동시성 20회와 DB·로그·실행 소스를 확인했다. 후속 `86a046657350-023906d81950`에서는 실제 PC·모바일 문의 7개·주문 9개와 담당자가 제공한 모델 관측 HTTP/PostgreSQL 3개를 통과했다. 전체 AI 결과 검증은 남아 있다.
Agent 담당자 PC에서 실제 VOC-07 한 건을 검수했지만 정상 보고서의 직접 인용 보완, 나머지 VOC·리더 PC의 실제 모델 검증은 남아 있다.
실행별 결과와 한계는 [해커톤 보고서](docs/hackathon-report.md), 최종 팀 완료 여부는 `./scripts/dev team-check`로 확인한다.

## 각자 시작하기

**[세 PC의 최초 준비와 역할별 goal 시작 명령](docs/goals/README.md)** 을 먼저 읽는다.

- 이상효: [commerce·개발리더 goal](docs/goals/commerce.md), [상세 시작 프롬프트](docs/prompts/implement-commerce-and-lead.md), [리더 최종 검증 단계](docs/goals/lead.md)
- 김아름: [voc goal](docs/goals/voc.md)
- 한재홍: [agent goal](docs/goals/agent.md)

```bash
./scripts/dev up
./scripts/dev smoke
```

위 명령으로 각자의 PostgreSQL·커머스·Agent·VOC를 실행하고 연결을 확인한다.
Java 21·Docker·Python과 화면용 Node.js 24 LTS가 필요하다. 화면은 [web 실행 안내](web/README.md)에 따라
Git에서 제외된 `web/.env.local`에 접속 암호·서명 비밀·origin을 설정한 뒤 실행한다.

```bash
npm --prefix web ci
npm --prefix web run build
npm --prefix web run start -- --port 3000
```

`http://127.0.0.1:3000`에서 로그인해 문의를 등록한다. 설정한 실제 origin을 사용하며 같은 `.next`를 재빌드할 때는 web을 먼저 중지한다.
기본 Agent는 `test/mock`이며 실제 모델을 호출하지 않는다. 로컬 모델은 프로젝트 전용 Codex OAuth,
배포 모델은 OpenAI API를 별도로 설정한다. 로그인·명시 실행·모델 미검증 범위는
[LLM 실행 안내](docs/llm-runtime.md)와 [사용·비용 정책](docs/planning/demo-llm-policy.md)을 따른다.
설치 조건과 검증 범위는 [로컬 개발 안내](docs/local-development.md)에 있다.
자동 개발은 [AGENTS.md](AGENTS.md)와 [협업 반복 규칙](docs/autonomous-development.md)을 따른다.
커머스 업무 재현·입력·로그·복구 검증은 [Commerce 실행 안내](commerce-app/README.md)를 따른다. 모델 호출 없이 독립 실행할 수 있다.
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
- [협업 논의 목록·답변·해소 현황과 작성 규칙](docs/discussions/README.md)
- [VOC·Agent API·리포트·근거 인터페이스 v1](docs/integration-contract.md)
- [커머스 API·DB·로그·실행 소스 인터페이스 v1](docs/commerce-interface.md)
- [멀티모듈 구조와 데이터 흐름](docs/architecture.md)
- [문의 화면과 ngrok 로컬 데모](docs/frontend-deployment.md)
- [ngrok 로컬 데모 실행 절차](docs/ngrok-local-demo.md)
- [앱과 에이전트가 확인할 업무 정책](docs/business-policy.md)
- [문의 시나리오·재현 조건·검증 기준](docs/voc-scenarios.md)
- [해커톤 프로젝트 보고서 초안·검증 결과표](docs/hackathon-report.md)
