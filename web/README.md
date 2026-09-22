# JDD 문의 작업실

Next.js App Router·React·TypeScript로 만든 한국어 티켓 화면이다. 현재 로그인, 티켓 등록·목록·페이지 이동,
상태/담당자 필터, 상세 수정·배정·상태 전이·버전 충돌 비교와 분석 요청·진행·이력·보고서·근거 원문을 제공한다.
`/shop`의 실제 상품·재고·쿠폰·주문·모의 결제·전체 취소·환불 결과도 제공한다. 화면 구현만으로 실제 모델 품질이나 VOC MVP 완료를 선언하지 않는다.
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

## 분석·보고서 화면

- 현재 저장된 티켓 버전으로 새 키를 만들고 선택한 이전 조사와 연결한다. 작성 중인 입력은 먼저 저장한다.
- 접수 응답이 불확실하면 요청 키·버전·이전 조사 ID를 브라우저 localStorage에 보존한다. 기존 sessionStorage 기록도 이관하며, 탭을 닫았다 다시 열어도 “같은 요청으로 접수 확인”으로 복구한다. 저장소를 사용할 수 없거나 기록이 손상되면 중복 접수를 막기 위해 요청을 중단하고 안내한다. 서버에 저장된 분석은 탭을 닫아도 유지된다.
- 전달 실패 재시도는 저장 입력의 같은 키를 쓴다. 조사 자체의 실패는 기존 결과를 유지하고 명시적인 새 분석만 별도 실행한다. 자동 새 키 생성·모델 재실행은 없다.
- 상태 조회는 약 2초 간격이며 실패 시 최대 30초까지 늦추고 마지막 결과를 보존한다. 종료·14분 관측 창 이후 자동 조회를 멈추며 수동 조회는 서버의 refresh=true와 후속 GET만 수행한다.
- 전달·Agent 상태 조회·조사 오류와 티켓 업무 상태를 별도로 표시한다. 기존 잘못된 입력에는 문의 수정·저장·새 분석을 안내한다.
- 보고서의 사실·원인 후보/한계·사람 조치·재발 방지/검증·부족한 정보를 그대로 표시한다. 별도 원인이나 근거를 UI에서 생성하지 않는다.
- DATA·LOG·CODE·POLICY 원문을 소속 근거 API로 조회하며 선택 근거 ID/종류/관측 시각/출처와 응답이 다르면 원문을 표시하지 않는다. 빌드·파일·줄·레코드·정책 버전과 잘림을 표시한다. HTML 형태의 원문도 텍스트로만 렌더링한다.
- 선택한 분석은 URL의 analysis 값으로 새로고침 시 복원한다. 모바일에서 보고서 아래 근거 패널을 사용하며 근거 선택 시 패널로 이동한다.

개발 회귀에는 Node의 상태/키/근거 검사와 별도 합성 HTTP 브라우저 검증을 사용했다. 합성 보고서와 기본 test/mock Agent의 실제 설정 오류 소비는 실제 OAuth 모델 보고서 품질 검증을 대신하지 않는다.

## 커머스 시연 화면

`/shop`은 실제 commerce HTTP 응답으로 상품/현재 재고, 고객별 쿠폰/주문, 주문 상세와 결제·취소·환불 응답을 표시한다. 합성 고객 번호는 commerce fixtures의 시연 입력을 사용한다. 선택 상품 하나와 양의 정수 수량으로 주문하며 쿠폰 조건 거절도 실제 오류로 표시한다.

주문 입력은 첫 POST 전에 체크아웃 키와 함께 sessionStorage에 저장한다. 응답 유실 후에도 같은 요청을 수동 재전송할 수 있고, 새 주문 입력은 새 키를 만든다. 같은 주문의 중복 생성은 의도한 VOC-04 결함의 관측 대상이다. 결제·취소 재전송은 현재 화면의 같은 요청 키/입력을 보존하며 처리 뒤 주문 GET으로 최신 상태를 확인한다. 환불은 취소 응답의 별도 상태를 보여주고, 응답이 null이면 환불 완료로 해석하지 않는다. 주문 GET만으로 제공되지 않는 결제/환불 이력을 추정하지 않는다.
