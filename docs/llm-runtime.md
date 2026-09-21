# 로컬 Codex OAuth와 배포 OpenAI API 분리

2026-09-21 최신 사용자 지시를 구현한 실행 계약이다. 기존 조사 API·UI 계약·영속 실행기·8개 읽기 전용 도구·근거와 보고서 검증은 유지한다. 별도 프록시/마이크로서비스, Codex SDK, 추론마다 CLI를 실행하는 구조는 없다.

## 명시적 설정

| 실행 | APP_RUNTIME | LLM_PROVIDER | 필수 설정 |
| --- | --- | --- | --- |
| 로컬 개발·수동 반복·영상 데모 | `local` | `codex_oauth` | `CODEX_MODEL`, `CODEX_AUTH_FILE` |
| 배포·배포 미리보기 | `deployed` | `openai_api` | `OPENAI_MODEL`, `OPENAI_API_KEY`, `JDD_AGENT_API_PROFILE` |
| 자동 테스트·기본 Compose | `test` | `mock` | 인증 없음 |

`test/mock`은 네트워크 클라이언트를 생성하지 않는다. 기본 mock은 조사 성공을 꾸미지 않고 `LLM_CONFIGURATION_ERROR`로 종료하는 실패 대역이다. 도구·보고서 성공 흐름은 별도 합성 모델 테스트가 검증한다. 누락/오타/다른 조합은 시작 시 `AI configuration error`다. 모델명은 필수이며 `NODE_ENV`, URL, 키 존재 여부로 추론하지 않는다. 로컬 production 빌드도 `local/codex_oauth`다.

local은 `OPENAI_API_KEY`를 조회하지 않고 deployed는 `CODEX_AUTH_FILE`을 조회하지 않는다. 인증 실패·만료·429·모델 권한·시간 초과·취소 모두 다른 provider로 전환하지 않는다. 자동 재시도와 리다이렉트도 없다. 기존 `JDD_AGENT_MODEL_MODE`, `JDD_AGENT_DEMO_MODE`, `JDD_AGENT_PAID_CALLS_ALLOWED`, API 키 파일 설정은 활성화 방법에서 제거했다.

## 최초 로그인과 재로그인

설치된 Codex CLI 0.155.1의 `codex login --help`와 공식 인증 문서를 확인했다. 다음은 **개발자 터미널**에서 실행한다. Java 21과 Docker는 기존 개발 절차대로 준비한다.

```bash
export APP_RUNTIME=local
export LLM_PROVIDER=codex_oauth
export CODEX_AUTH_FILE="$HOME/.local/share/jdd/codex-auth/auth.json"
export CODEX_MODEL='replace-with-your-accessible-workspace-model'
./scripts/llm login
./scripts/llm diagnose
```

모델 placeholder를 실제 워크스페이스에서 접근 가능한 모델 ID로 바꿔야 한다. Codex 모델 목록/지원 여부를 추측한 기본값은 없다. 브라우저에서 본인 계정과 해커톤 워크스페이스를 선택한다. 워크스페이스 ID를 알고 있으면 `./scripts/llm login --workspace-id <workspace-id>`로 고정할 수 있다. 관리자가 허용하는 경우 `--device-auth`도 제공한다. 접근 제한을 우회하지 않는다.

로그인 명령은 별도 프로젝트 디렉터리를 만들고 해당 자식 프로세스에만 `CODEX_HOME`을 지정한다. `cli_auth_credentials_store="file"`, `forced_login_method="chatgpt"`를 CLI 설정 인수로 전달한다. 기존 `~/.codex/auth.json`을 읽거나 복제하지 않으며 현재 개발용 Codex 세션의 home을 변경하지 않는다. 저장소 내부/기존 Codex home은 거절한다. 디렉터리 0700, 생성 파일 0600이며 토큰을 채팅에 붙여 넣지 않는다.

앱은 매 모델 요청마다 `CODEX_AUTH_FILE`의 ChatGPT access token과 선택된 account ID를 다시 읽는다. refresh token을 사용/수정하지 않는다. 만료 또는 401/403이면 `로컬 로그인 명령 ./scripts/llm login 을 다시 실행하세요`가 포함된 설정 오류를 반환한다. 같은 명령으로 재로그인하면 서버 재시작 없이 다음 요청에 반영된다. 실패한 기존 조사 결과는 유지하며 재조사는 새 요청 키로 접수한다.

## 로컬 실행과 영상 촬영

위 export와 로그인을 마친 뒤:

```bash
./scripts/llm run-local
./scripts/llm smoke-local
# 같은 로컬 OAuth 설정으로 데모 실행:
./scripts/llm demo
```

`run-local`/`demo`는 기존 세 앱을 빌드·기동한 다음 Agent에만 `compose.local-oauth.yaml`을 적용한다. 두 명령은 같은 실행 방식이며 ngrok나 모델 호출을 자동 시작하지 않는다. 활성화 뒤 대기 중 조사는 실행될 수 있으므로 기존 조사 상태를 확인한다. 진행 중 조사/근거 저장이 끝난 후 앱을 재생성한다. 로그인 디렉터리 전체를 읽기 전용으로 마운트하므로 CLI의 원자적 파일 교체가 컨테이너에 반영된다. 컨테이너는 로컬 개발자 UID/GID로 실행하며 API 키는 전달하지 않는다.

`smoke-local`만 명시적으로 실제 OAuth 모델을 **한 번** 호출한다. 기존 버전 프롬프트·구조화 보고서 요청으로 정보 부족 합성 입력을 보내고, 연결 확인과 사용량/지연을 출력한다. VOC 품질 평가가 아니며 자동 `check/test/publish`에 연결되지 않는다. 재로그인/모델 권한이 없으면 명확하게 실패하고 API key를 사용하지 않는다.

web/화면 구현과 ngrok 접근 설정은 김아름 담당 연동이다. [ngrok 절차](ngrok-local-demo.md)에 따라 인증된 web 진입점만 공개한다. 현재 없는 web·공개 접속 검증을 완료로 표시하지 않는다. Agent·DB·`/internal/*`·모델 호출 경로를 직접 터널로 공개하지 않는다. ngrok 파일 경로와 허용 계정 YAML은 사용자가 추후 추가한다.

기본 오프라인 환경으로 돌아갈 때는 진행 중 조사가 끝난 후 `./scripts/dev up`을 실행한다. 일반 up/check/publish는 test/mock이며 OAuth·유료 호출·터널을 자동 활성화하지 않는다.

## 실제 MVP 검증과 완료 명령

실제 모델 범위와 인증을 준비한 뒤 최신 소스의 앱을 먼저 기동한다. 로컬은 위 local/codex_oauth 환경을 유지하며 다음 순서를 쓴다.

```bash
./scripts/dev sync
./scripts/llm run-local
JDD_MVP_LIVE=true ./scripts/dev verify-mvp
# 모든 필수 기능·검증을 갖춘 뒤 자기 완료 또는 리더 검증:
JDD_MVP_LIVE=true ./scripts/dev role-done commerce
# 리더의 전 영역 검토와 세 담당자의 유효한 DONE 이후:
JDD_MVP_LIVE=true ./scripts/dev lead-approve
```

`verify-mvp`는 일반 `verify/up`을 호출하지 않는다. 현재 Git 커밋·빌드 입력의 buildId, 세 앱 businessReady,
Agent worker·실제 어댑터 모드와 선택한 runtime/provider/설정 모델을 먼저 확인한다. 일반 자동 검사 뒤와
시나리오 실행 뒤에도 같은 코드·실행 환경인지 확인한다. 오래된 실행, mock, 설정 누락/불일치는 실패이며 자동 로그인·fallback·배포·키 활성화를 하지 않는다.
`JDD_MVP_LIVE=true`는 이미 허용된 범위의 명시 실행 표시이며 새 유료 예산 승인이나 모델 접근을 제공하지 않는다.

Agent 내부 관측은 `llm: {runtime, provider, configuredModel}`로 연결한다([DISC-commerce-002](discussions/DISC-20260921-commerce-002-live-mvp-runtime.md)).
이 필드는 선택된 설정이며 실제 응답 모델·사용량은 아니다. runner의 기존 `model`은 실제 모델 응답/장부로 확인해야 한다.
현재 제공자 관측 필드 인수·runner·실제 모델 검증이 남아 있어 이 명령으로 DONE을 기록할 준비가 완료된 것은 아니다.

자동 check 자식은 test/mock, runner 자식은 앱의 로컬 포트·선택한 실행 정보만 받아 동작한다. OAuth 파일·토큰·API 키·DB 암호를 자식 환경에 전달하지 않는다.
runner는 서비스 HTTP를 호출하며 직접 모델에 연결하거나 CLI 로그인을 실행하지 않는다. Windows 네이티브에서는 위 명령을
`python scripts/jdd.py ...` 또는 `python scripts/llm ...`으로 실행하며 runner의 Gradle wrapper도 Windows에 맞게 선택한다.

배포 검증은 배포 호스트에서 미리 준비한 deployed/openai_api와 확정한 scope·모델·예산·만료 아래에서만 명시 실행한다.
로컬에서 프로모션 API를 검증하는 대체 경로가 아니다. 모델 변경/재기동이나 동시 소스 갱신이 필요하면 진행 중 조사가 끝난 뒤 새 빌드를 준비한다.
일반 publish는 mock으로 되돌리므로 다음 live 검증 전에 다시 준비해야 한다.

매 runner 실행의 `runtime/mvp/<실행 ID>/`에 전후 관측·새 결과·이전 결과를 보존한다. 실패·불완전 JSON·mock·빌드 변경을 통과 결과로 쓰지 않는다.
`runtime/scenarios.json`은 전체 검증이 통과했을 때만 갱신하며 이전 파일도 실행 디렉터리에 보관한다. 파일이 있다는 사실만으로 이번 실행이 성공한 것은 아니다.

## 배포 설정과 별도 smoke

배포 서비스의 Agent에 다음 환경변수만 설정한다. 실제 키는 배포 secret으로 주입하며 `CODEX_AUTH_FILE`, OAuth 파일/볼륨은 배포하지 않는다.

```dotenv
APP_RUNTIME=deployed
LLM_PROVIDER=openai_api
OPENAI_MODEL=replace-with-approved-api-model
OPENAI_API_KEY=replace-in-secret-store
JDD_AGENT_API_PROFILE=/run/jdd-api/profile.json
```

현재 등록된 API 가격 후보는 기존 설정의 `gpt-5.6-luna`, `gpt-5.6-terra`다. 접근/품질 합격 모델을 의미하지 않는다. `OPENAI_MODEL`은 [API 예산 profile 예제](../agent-app/config/api-profile.example.json)의 `model`과 일치해야 한다. 예제는 만료/placeholder 상태다. schemaVersion/catalogVersion, 범위, 배정 예산·총 호출 수, 조사당/동시 호출 수, 24시간 이내 승인 만료, 입력/출력/시간 상한을 실제 운영 범위로 준비한다. 가격표가 확인 후 7일을 넘으면 재검토가 필요하다. 기존 보수적 유료 비용 통제를 유지하므로 키만 넣어 활성화되지 않는다. profile에는 비밀 값을 넣지 않는다.

Compose 배포는 `JDD_API_PROFILE_FILE`을 위 JSON의 배포 호스트 경로로 지정하고 `compose.yaml`에 `agent-app/compose.deployed-api.yaml`을 적용한다. 호스트 `.env`와 `runtime/build.env`를 지정하며 실제 secret이 있는 `docker compose config` 전체를 공유 로그로 출력하지 않는다. 다른 배포 서비스는 같은 환경변수/비밀 주입/프로필 파일을 제공한다. 배포 미리보기도 `deployed/openai_api`로 지정한다.

배포 후 **배포 호스트에서** 다음 별도 명령으로 저장·조사 흐름을 확인한다. 기존 Agent 내부 loopback 포트를 사용하며 로컬 개발 PC의 프로모션 키 검증 명령이 아니다.

```bash
./scripts/llm smoke-deployed --base-url http://127.0.0.1:8081
```

한 합성 조사를 접수하고 저장된 ID를 조회한다. 기존 실행기 상한(기본 모델 호출 8회)과 영속 예산을 적용한다. 성공 기준은 `NEEDS_INPUT`이며 실패/timeout 때 새 유료 조사를 자동 생성하지 않는다. 인증·모델 품질/전체 VOC 검증을 대신하지 않는다.

## 전송과 기능 차이

| 항목 | local / Codex OAuth | deployed / API key |
| --- | --- | --- |
| HTTP endpoint | `https://chatgpt.com/backend-api/codex/responses` | `https://api.openai.com/v1/responses` |
| 인증 | Bearer access token + `ChatGPT-Account-Id` | Bearer API key |
| 텍스트·대화·엄격한 보고서 schema·서버 function calls | 구현, 실제 계정/모델 검증 별도 | 구현, 실제 계정/모델 검증 별도 |
| 전달 형식 | `store=false`, `stream=true`, `text.format`, function call/output | 동일 공통 기능 + API용 output cap/tier/reasoning 설정 |
| 출력 토큰 상한 | 공식 Codex request에 해당 필드 없음: 보내지 않음 | profile의 `max_output_tokens` |
| 모델 선택 | 명시적 CODEX_MODEL; 지원 목록 하드코딩 없음 | OPENAI_MODEL과 검토된 가격 profile 일치 |
| 사용량/비용 | `agent.oauth_model_calls`: 실제 nullable usage·지연, API USD로 환산하지 않음 | 기존 `agent.model_calls` + 영속 예산·예약·정산 |
| embeddings·음성·이미지 | 구현/대체 대상 아님, 숨은 API 호출 없음 | 현재 앱 미사용 |

공통 `InvestigationModel` 인터페이스에 두 HTTP 어댑터를 연결했다. 기존 실행기가 허용한 여덟 읽기 전용 함수만 실행하며 모델/SDK가 도구를 자체 실행하지 않는다. 후속 요청에 도구 call ID·서버 저장 근거·응답 output items와 암호화된 reasoning context를 보존한다. 이러한 내부 context를 보고서 근거로 인용하거나 브라우저에 노출하지 않는다.

SSE는 UTF-8 네트워크 chunk와 이벤트 경계를 분리해서 해석한다. text delta, 완료, failed/incomplete/error, 종료 전 EOF, 취소를 구분한다. 현재 UI는 저장 상태 polling 방식이므로 delta를 새로운 공개 API로 노출하지 않고 최종 응답만 기존 보고서 검증으로 전달한다. 요청 128 KiB, 로컬 HTTP 45초, stream 4 MiB 문자 상한과 기존 조사 제한을 적용한다. 중간 텍스트만으로 성공을 만들지 않는다.

OAuth는 **Codex 클라이언트용 백엔드의 직접 연동**이다. 공식 범용 OpenAI API와 같은 지원/호환성을 보장하지 않는다. `User-Agent: jdd-agent/0.1`로 식별하고 공식 클라이언트 originator를 사칭하지 않는다. 고정 endpoint가 허용하지 않는 계정/조직 라우팅은 오류로 남기며 다른 edge나 API key로 우회하지 않는다. 워크스페이스 조건·사용 한도·프로모션 적용·무제한 혜택을 단정하지 않는다.

## 비용과 검증

$50 프로모션 API 크레딧은 배포 환경에만 사용한다. $50 제공을 예산 자동 증액으로 해석하지 않고 기존 누적 $30 상한/배분 장부를 유지한다. API별 입력·출력·캐시·reasoning의 실제 usage를 한 번만 정산하며 미확정 비용은 0으로 만들지 않는다. OAuth 관측 장부는 API 예산을 차감하거나 초기화하지 않는다. ngrok 비용은 별도다.

```bash
./gradlew :agent-app:test :agent-core:test
python3 -m unittest discover -s scripts/tests -v
```

`LlmRuntimeIsolationTest`, `ResponsesSseTest`, `OpenAiTransportTest`, `OpenAiDeploymentConfigurationTest`는 네트워크 없는 mock 또는 합성 loopback HTTP만 사용한다. 자동 테스트는 실제 OAuth/API 모델 사용량을 소모하지 않는다. local API key 무시·장애 fallback 0건·deployed OAuth 미조회·누락/잘못된 설정·SSE 경계·중단·취소·usage/비용 회귀를 확인한다. 실제 호출 여부/결과는 [Agent 상태](status/agent.md)에 모의 결과와 구분해 기록한다.

## 확인한 공식 근거

- [Codex 인증 문서](https://learn.chatgpt.com/docs/auth): 초기 login, file 저장, CODEX_HOME, 강제 ChatGPT 로그인/워크스페이스 설정.
- [Codex agent loop](https://openai.com/index/unrolling-the-codex-agent-loop/): ChatGPT 로그인/API key의 다른 Responses endpoint.
- 공개 소스 확인 commit `ebc05da3bdb76f25861e7cb418bd06d28cadc609`: [인증 헤더](https://github.com/openai/codex/blob/ebc05da3bdb76f25861e7cb418bd06d28cadc609/codex-rs/model-provider/src/bearer_auth_provider.rs), [파일 구조](https://github.com/openai/codex/blob/ebc05da3bdb76f25861e7cb418bd06d28cadc609/codex-rs/login/src/auth/storage.rs), [token/account](https://github.com/openai/codex/blob/ebc05da3bdb76f25861e7cb418bd06d28cadc609/codex-rs/login/src/token_data.rs), [요청 타입](https://github.com/openai/codex/blob/ebc05da3bdb76f25861e7cb418bd06d28cadc609/codex-rs/codex-api/src/common.rs), [요청 구성](https://github.com/openai/codex/blob/ebc05da3bdb76f25861e7cb418bd06d28cadc609/codex-rs/core/src/client.rs), [SSE](https://github.com/openai/codex/blob/ebc05da3bdb76f25861e7cb418bd06d28cadc609/codex-rs/codex-api/src/sse/responses.rs).
- OpenAI [API 인증](https://developers.openai.com/api/reference/overview), [SSE](https://developers.openai.com/api/docs/guides/streaming-responses), [function calling](https://developers.openai.com/api/docs/guides/function-calling), [구조화 출력](https://developers.openai.com/api/docs/guides/structured-outputs), [대화 상태](https://developers.openai.com/api/docs/guides/conversation-state).
