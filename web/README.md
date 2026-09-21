# JDD 문의 작업실

Next.js App Router·React·TypeScript로 만든 한국어 티켓 화면이다. 현재 로그인, 티켓 등록·목록·페이지 이동,
상태/담당자 필터, 상세 수정·배정·상태 전이·버전 충돌 비교, 저장된 분석 이력 요약을 제공한다.
분석 요청·진행·보고서/근거 패널과 `/shop` 화면은 다음 구현 단위이며 이 화면만으로 VOC MVP 완료를 선언하지 않는다.
실제 서버 API를 사용하고 성공 응답이나 분석 결과를 프론트에서 만들어 넣지 않는다.

## 실행

Node.js 24 LTS를 사용한다. 저장소 루트에서 다음을 실행한다.

```bash
npm --prefix web ci
npm --prefix web test
npm --prefix web run build
npm --prefix web run start -- --port 3000
```

start/dev는 기본 `127.0.0.1`에 수신한다. Docker 백엔드는 [공통 실행](../docs/local-development.md)로 별도 준비한다.
Windows는 `npm.cmd`를 사용해도 된다. 개발 시 `npm --prefix web run dev`를 사용한다.
production 서버 실행 중 같은 `.next`를 다시 빌드하지 말고 web을 중지한 뒤 build/start한다.

`web/.env.example`을 참고해 Git에서 제외된 `web/.env.local`에 다음 서버 전용 값을 설정한다.
빌드 자체는 설정 없이 가능하며, 실행 요청은 암호/서명 설정이 없으면 닫힌 상태로 거절한다.

| 설정 | 의미 |
| --- | --- |
| WEB_ORIGIN | 실제 브라우저의 단일 origin. 로컬 예: http://127.0.0.1:3000. 끝 `/` 없이 지정 |
| WEB_ACCESS_PASSWORD | 팀에 별도로 전달할 충분히 무작위인 접속 암호, 최소 16자. 기본 암호 없음 |
| WEB_SESSION_SECRET | 별도의 무작위 서명 비밀, 최소 32자. 접속 암호와 다른 값 |
| VOC_API_BASE_URL | 실제 VOC origin. 기본 Compose는 http://127.0.0.1:8082 |
| COMMERCE_API_BASE_URL | 실제 commerce origin. 기본 Compose는 http://127.0.0.1:8080 |
| BACKEND_SERVICE_TOKEN | 수신 서비스가 동일 토큰을 검증하도록 구성했을 때만 쓰는 서버 전용 Bearer 값 |

위 값에 `NEXT_PUBLIC_`를 붙이지 않는다. 실제 암호/토큰·OAuth/모델·DB 설정을 Git, 브라우저 코드,
빌드 설정에 넣지 않는다. web은 모델 키나 OAuth 인증 파일을 읽지 않는다.
기존 백엔드의 서버 간 토큰 검증은 별도 연동 과제이며 web의 선택 토큰 전송만으로 구현됐다고 주장하지 않는다.

## 접근·중계 경계

- 공통 팀 암호로 로그인한 뒤 서명된 8시간 HttpOnly·SameSite=Strict 세션을 사용한다. HTTPS origin에서는 Secure도 적용한다.
- 페이지와 API가 각각 세션을 검증한다. 인증 없는 API는 401, 설정 누락은 503이다. 암호/서명 비밀 변경 시 기존 세션은 무효다.
- 쓰기 요청은 WEB_ORIGIN과 Origin이 정확히 같아야 한다. `refresh=true` GET도 Origin 또는 같은 출처의 Referer/Fetch Metadata를 확인한다.
- 로그인은 단일 web 프로세스에서 분당 20회로 제한한다. 이 MVP는 개별 계정·역할별 권한·다중 인스턴스 로그인 제한 저장소를 제공하지 않는다.
- 브라우저 요청은 같은 출처 `/api`만 사용한다. VOC의 티켓/담당자/분석/소속 근거와 commerce의 문서화된 업무 경로·메서드만 중계한다.
- Agent·DB·`/internal`·`/actuator`·임의 URL은 중계하지 않는다. 사용자 Cookie/Authorization/전달 주소 헤더를 백엔드로 보내지 않는다.
- JSON 요청은 64KiB, 응답은 스트림 기준 4MiB, 백엔드 요청은 12초로 제한한다. redirect는 따라가지 않으며 POST 자동 재전송은 없다.
- 202/409/429와 계약 오류를 보존한다. 연결 실패는 502로 구분하며 내부 URL·예외 원문을 브라우저에 노출하지 않는다. 응답은 no-store다.
- 티켓 생성의 응답을 받지 못하면 목록에서 접수 여부를 확인한다. 티켓 생성에는 분석 요청과 같은 requestKey 계약이 없으므로 자동 재전송하지 않는다.
- PATCH 충돌 시 작성 중인 입력을 유지하고 최신 버전과 비교한다. 명시적으로 최신 내용으로 교체한 뒤 다시 수정한다.

공개 사용은 [ngrok 절차](../docs/ngrok-local-demo.md)의 별도 허용 계정 정책과 실제 HTTPS origin을 준비한 뒤 검증한다.
터널은 자동으로 열지 않으며 백엔드/DB 포트는 공개하지 않는다. 공개 URL·실제 모델 조사는 아직 미검증이다.

## 검증

`npm test`는 세션 변조/만료·설정 실패·Origin·크기·중계 허용 목록·인증 전달 차단·오류 경계를 검증한다.
`scripts/dev check`는 npm ci → test → build를 포함하고 Windows에서는 npm.cmd를 선택한다.
main push CI도 Java 21·Node 24의 같은 check를 실행한다. CI/일반 publish는 test/mock이며 실제 모델·터널을 열지 않는다.
실제 브라우저/백엔드 검증 범위와 결과는 [VOC 상태](../docs/status/voc.md)에 기록한다.

프레임워크 기준은 [Route Handler](https://nextjs.org/docs/app/api-reference/file-conventions/route),
[서버 환경 변수](https://nextjs.org/docs/app/guides/environment-variables)를 따른다.
