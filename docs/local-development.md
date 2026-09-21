# 각 PC에서 앱 3개 실행하기

각 컴퓨터에 같은 저장소를 clone하고 자기 PostgreSQL과 commerce·agent·voc를 모두 실행한다.
다른 팀원의 PC 주소는 사용하지 않는다. GitHub로 소스와 문서를 공유하며 DB·로그·비밀 값은 각 PC에 따로 둔다.

## 준비물

- Git, Java 21, Python 3.9 이상
- 실행 중인 Docker Desktop 또는 Docker Engine/Colima와 Docker Compose
- 저장소 읽기·쓰기 인증
- web 구현 후에는 팀이 package.json에 고정한 Node/npm

명령은 macOS·Linux·Windows WSL에서 저장소 루트 기준으로 실행한다.
Gradle은 저장소의 Wrapper 9.3.1, Spring Boot는 4.1.1을 사용한다.
Spring AI 2.0.1은 버전 기준만 기록했으며 실제 모델 연결은 agent goal의 구현 범위다.

## 실행·확인

```bash
./scripts/dev setup
./scripts/dev up
./scripts/dev smoke
```

setup은 .env가 없을 때만 로컬 DB 암호를 생성한다. 기존 .env는 유지한다.
up은 실행 버전의 소스 스냅샷을 만들고 DB·앱 3개를 빌드해 시작한다. 첫 실행에는 컨테이너와 의존성 다운로드가 필요하다.
Docker Compose plugin과 독립 docker-compose 명령을 모두 지원한다.

| 서비스 | 로컬 주소 | DB 권한 |
| --- | --- | --- |
| commerce | http://localhost:8080 | commerce 스키마 소유 |
| agent | http://localhost:8081 | agent 스키마 소유 + 별도 commerce SELECT 계정 |
| voc | http://localhost:8082 | voc 스키마 소유, Agent·커머스 HTTP 호출 |
| PostgreSQL | localhost:5432 | 각 PC 전용 데이터 볼륨 |

각 앱의 /actuator/health는 기동·DB 연결, /internal/runtime은 실행 빌드·담당 스키마를 보여준다.
agent와 voc의 /internal/dependencies로 읽기 권한·근거 볼륨·서버 간 HTTP 연결을 확인한다.
초기 구현은 stage=BOOTSTRAP, businessReady=false다. 주문·티켓·LLM 분석 API는 담당별 goal에서 구현한다.
포트 충돌 시 .env의 *_PORT를 변경한다. 호스트 포트는 127.0.0.1에만 바인딩한다.

## 검증과 종료

```bash
./scripts/dev check
./scripts/dev verify
./scripts/dev down
```

check는 Python 협업 도구 테스트·문서 검증·전체 Gradle check와 구현된 web의 npm ci/build를 실행한다.
verify는 check 후 세 앱을 재빌드·기동하고 smoke를 수행한다.
down은 컨테이너를 내리고 DB 볼륨은 보존한다. 자동 동기화·검증에서는 DB 볼륨을 삭제하지 않는다.
업무 데이터 초기화는 각 시나리오의 합성 데이터 범위에서만 수행한다.

## 업데이트 반영

```bash
./scripts/dev status
./scripts/dev sync
./scripts/dev up
./scripts/dev smoke
```

sync는 깨끗한 main에서만 실행된다. 편집 중인 파일이 있으면 먼저 보존·로컬 커밋한다.
소스만 갱신하고 기존 프로세스를 계속 사용하면 이전 코드가 실행되므로 up으로 앱도 갱신한다.
실행 빌드가 다른 앱이 섞이면 smoke가 실패한다.

## GitHub의 팀 완료 확인

```bash
./scripts/dev team-status
./scripts/dev team-check
```

team-status는 원격 main의 세 담당자 완료 기록을 읽는다. team-check는 깨끗한 최신 main에서
현재 저장소 내용을 검증한 세 DONE이 모두 있을 때만 종료 코드 0을 반환한다.
자기 기능 완료는 role-done <role>의 실제 MVP 검증 후 JSON을 커밋·publish해 알린다.
자기 DONE 이후에도 세 명이 모두 끝날 때까지 연동·검증·수정을 계속한다.
명령·철회·오래된 완료 처리 기준은 [세 담당자 완료 기준](team-completion.md)에 있다.

## 근거와 실행 산출물

- runtime/evidence/source/<buildId>: commerce의 허용된 Java·마이그레이션만 복사한 소스와 manifest
- runtime/evidence/logs/commerce/<buildId>: 업무 로그 생성 경로. 초기 골격은 업무 이벤트를 아직 생성하지 않음
- runtime/smoke.json: 실제 로컬 기동·연동 검사 결과
- runtime/scenarios.json: scenario-runner 구현 후 실제 MVP 검증 결과

buildId는 커밋과 로컬 빌드 입력 해시를 포함한다. 개발 중 미커밋 변경은 manifest의 workingTreeDirty에 표시된다.
같은 buildId의 기존 스냅샷은 덮어쓰지 않는다. 공식 시연·평가는 커밋된 코드에서 실행한다.
비밀 값·런타임 파일은 Git에서 제외된다. Agent에는 소스·로그·정책 볼륨을 읽기 전용으로 제공한다.

## 최초 환경에서 막히는 경우

Docker 연결 실패는 해당 PC의 Docker/Colima가 켜져 있는지 확인한다.
이미 생성한 DB의 .env 암호를 바꾸면 기존 DB 역할 암호와 달라지므로 기존 값을 복원하거나 명시적인 마이그레이션으로 맞춘다.
모델 키가 없어도 공통 앱 기동은 가능하다. 실제 AI 검증에는 해당 제공자의 인증을 별도로 준비한다.

## 준비 단계에서 확인한 결과

2026-09-21에 준비 작업 PC에서 Java 21·Gradle check(앱 테스트 3개), 협업 도구 테스트 10개,
세 Docker 앱과 PostgreSQL의 동시 기동 및 smoke를 통과했다.
smoke는 VOC의 두 HTTP 연결, Agent의 commerce SELECT·쓰기/생성 권한 없음, 소스·로그·정책 볼륨을 확인했다.
이 PC는 기존 DB와의 충돌을 피하려고 로컬 .env의 POSTGRES_PORT만 15432로 설정했다.
다른 두 PC의 설치·실행과 업무 기능·실제 LLM 분석은 각 담당 goal에서 확인한다.
