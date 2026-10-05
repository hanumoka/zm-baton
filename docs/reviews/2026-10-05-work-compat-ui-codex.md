# work/compat-ui 독립 검토

판정은 **병합 막음**이다. 병합 막음 지적은 **5건**이다.

## 검토 신원과 대상

- 적용 기준: `docs/review-standard.md`의 **zm-baton 검토 기준 v0.3**, `AGENTS.md`.
- 검토자 실행기: Codex CLI `0.160.0`.
- 모델: 세션 안내상 GPT-6 계열이다. 정확한 모델 식별자는 확인하지 못했다.
- 역할: 독립 검토자. 구현은 하지 않았다.
- 검토 세션: `01a10bd5-dcee-7bd3-943f-dddf9980e694`. `CODEX_THREAD_ID`에서 확인했다.
- 작성자: Claude Code 세션 `893848f2-e1a3-4077-8809-4ef52b6cf2f2`.
- 독립성 근거: 실행기가 다르다. 세션 번호가 다르다. 작성 대화 대신 검토 지시서와 저장소 자료를 받았다.
- 검토 브랜치: `work/compat-ui`.
- 검토 커밋: `de37ff4d47b439ec8536a528ffd05fec4e397d16`.
- 비교 기준: 요청에 지정된 `9101bb7`.
- 범위: 두 커밋 사이의 23개 변경 파일 전체다. 서버의 기존 사건 처리와 DB 제약도 대조했다.
- 변경 종류: 일반 코드, 보안, 의존성, 일반 문서다. 사람 필수 변경에 해당한다.

로컬 `main`은 검토 시점에 `7eca47e51d03d5438a6b9afa88fcf556475d5928`이었다. 따라서 `git diff main..work/compat-ui`는 요청에 적힌 기준과 일치하지 않았다. 본 검토는 `git diff 9101bb7..de37ff4`로 범위를 고정했다. 작업 트리의 `apps`와 `docs/stage1`이 검토 커밋과 같은 것도 확인했다.

협업 계약 v1과 데이터 모델은 `AGENTS.md`가 지정한 외부 정본을 읽기만 했다. 이 기록에는 해당 문서의 내용을 복사하지 않았다.

## 병합 막음 지적

### R1. 선택이 바뀐 뒤 이전 폴링 응답이 새 선택을 오염시킨다

- 위치: `apps/web/src/App.tsx:6`, `:35`, `:43`, `:49`.
- 검토 항목: 1 수락 조건, 6 보고의 정확성, 7 실패 경로 시험.
- 확신: 높음. 비동기 완료 경로를 코드로 직접 확인했다.
- 병합 막음: 예.

`usePoll`의 `stopped`는 오류 전달만 막는다. 이미 시작된 요청의 `setRuns`, `setRunId`, `setEvents`는 막지 않는다.

실행 A의 사건 요청을 지연시킨 뒤 실행 B를 고르면 문제가 발생한다. B의 사건을 받은 다음 A의 응답이 도착하면 `mergeEvents`가 두 실행의 사건을 합친다. 사건에는 `run_id`가 없어 합치는 함수도 이를 구분하지 못한다. A의 마지막 순번이 100이면 B의 다음 요청도 `after_seq=100`을 쓴다. B의 사건이 빠지고 다른 실행의 종료 상태가 표시될 수 있다.

작업 목록에서도 같은 문제가 있다. 작업 A의 실행 목록 요청을 지연시킨 뒤 B를 고르면 A의 응답이 B의 실행 목록을 덮는다. B의 `runId`가 비어 있으면 A의 실행을 선택하기도 한다. 선택 직후 첫 사건 요청에는 이전 렌더의 `events`로 계산한 커서가 쓰일 수도 있다.

고칠 방향: 선택별 요청 세대나 취소 신호를 두어 오래된 응답의 모든 상태 갱신을 막는다. 사건과 커서는 실행 번호에 함께 묶는다. 작업 변경 시 실행 목록도 함께 초기화한다. A의 응답을 B보다 늦게 완료시키는 시험을 추가한다.

### R2. 화면이 서버가 확정한 종료 이유를 뒤집는다

- 위치: `apps/web/src/runState.ts:14`, `:19`.
- 대조 위치: `apps/server/src/main/kotlin/io/github/hanumoka/zmbaton/server/RunService.kt:102`, `:125`, `:164`.
- 검토 항목: 2 계약, 6 보고의 정확성.
- 확신: 높음. 화면 계산은 실제 함수를 실행해 재현했다. 서버 판정은 조건부 갱신 코드로 확인했다.
- 병합 막음: 예.

화면의 첫 `ended`는 가장 작은 `seq`다. 서버의 첫 `ended`는 상태 변경에 처음 적용된 사건이다. 서버는 한 묶음 안에서는 순번으로 정렬하지만 서로 다른 묶음의 도착 순서를 바꾸지는 않는다.

먼저 `seq=10, ended/lost`를 받은 뒤 다른 묶음으로 `seq=9, ended/submitted`를 받으면 차이가 난다. 서버의 종료 이유는 `lost`로 유지된다. 화면을 다시 열어 두 사건을 읽으면 `submitted`로 표시된다. 실제 `displayState` 호출에서도 `lost`가 `submitted`로 바뀌었다.

첫 종료 뒤 새 실행이 청구되어 작업 세대가 올라간 경우에도 문제가 남는다. 옛 실행의 두 번째 보고는 서버에서 기록만 되는 늦은 보고다. 화면은 이 구분 없이 작은 순번의 종료 사건을 적용한다. 현재 읽기 API는 사건별 상태 적용 여부나 확정적인 수신 순서를 제공하지 않는다.

고칠 방향: 서버가 실제로 적용한 사건 순서와 늦은 보고 처리 의미를 화면 재생에도 전달한다. 저장된 실행 상태 열을 읽는 방식으로 우회하지 않는다. 수신 시각만 사용할 경우 같은 시각과 트랜잭션 순서 문제도 다룬다. 서로 다른 묶음의 역순 종료와 세대 변경 뒤 늦은 종료를 함께 검증한다.

### R3. 최대 순번 커서가 나중에 저장된 작은 순번 사건을 영구 누락한다

- 위치: `apps/web/src/App.tsx:47`, `apps/server/src/main/kotlin/io/github/hanumoka/zmbaton/server/ReadApi.kt:81`.
- 검토 항목: 1 수락 조건, 2 계약, 6 보고의 정확성.
- 확신: 높음. 쓰기 조건과 읽기 조건을 직접 대조했다.
- 병합 막음: 예.

서버는 이미 저장된 최대 순번보다 작은 사건도 빈 순번이면 저장한다. 화면은 지금까지 받은 최대 순번만 다음 요청의 커서로 쓴다.

예를 들어 1번과 3번을 읽은 뒤 서버에 2번이 저장되면 이후 요청은 계속 `seq > 3`을 읽는다. 2번은 새로고침 전까지 나타나지 않는다. 사건 수가 서버와 달라지고 2번이 중요한 수명주기 사건이면 상태도 달라질 수 있다.

기존 시험 계획은 연결 프로그램에 빈 순번을 다시 요청하는 기능을 미뤘다. 이 지적은 재요청 기능의 부재와 다르다. 이미 서버에 저장된 사건을 새 화면의 증분 조회가 놓치는 문제다.

고칠 방향: 수신 순서에 따라 증가하는 안정적인 커서를 제공하거나 누락 구간을 다시 조회한다. 1·3을 읽은 뒤 2를 저장하는 조회 시험을 추가한다.

### R4. 서버 밖으로 향하는 리디렉션을 차단하지 않는다

- 위치: `apps/desktop/src/main.ts:92`.
- 관련 문서: `docs/stage1/compat-test-ui.md:20`, `docs/stage1/compat-test-results.md:251`.
- 검토 항목: 3 권한, 4 보안, 7 거부 시험, 12 문서.
- 확신: 높음. 코드와 Electron API 명세를 대조했다. Electron 실행 재현은 하지 않았다.
- 병합 막음: 예.

`will-navigate`만으로 서버 출처 제한을 보장할 수 없다. 허용된 서버 URL이 외부 URL로 302 응답을 보내는 경우를 처리하는 `will-redirect` 검사가 없다. 초기 `loadURL`도 최종 출처를 검사하지 않는다.

Electron은 주 프레임 이동과 리디렉션을 별도 사건으로 알린다. `will-navigate`는 iframe 이동도 다루지 않는다. 이 구분은 [Electron webContents 문서](https://www.electronjs.org/docs/latest/api/web-contents#navigation-events)와 설치된 `electron.d.ts`에서 확인했다.

CSP의 `default-src 'self'`는 주 프레임 이동의 출처 제한을 대신하지 않는다. 외부 페이지가 최상위 문서로 열리면 그 응답에 붙인 CSP의 `self`는 외부 페이지의 출처가 된다. 따라서 모든 응답에 같은 CSP를 붙이는 것으로 이 누락을 보완할 수 없다.

고칠 방향: 리디렉션에도 허용 출처 검사를 적용한다. iframe이 필요 없다면 명시적으로 차단한다. 필요하다면 프레임 이동도 검사한다. 초기 로드 리디렉션과 페이지 이동 리디렉션의 거부 시험을 남긴다. 문서의 이동 차단 주장은 검증한 범위로 맞춘다.

### R5. 권한 요청만 거부하여 전체 권한 처리를 완성하지 못했다

- 위치: `apps/desktop/src/main.ts:84`.
- 관련 문서: `docs/stage1/compat-test-results.md:251`.
- 검토 항목: 3 권한, 4 보안, 7 거부 시험.
- 확신: 중간. 공식 명세와 설치된 타입 선언으로 판단했다. 실제 권한별 동작은 실행하지 않았다.
- 병합 막음: 예. 검토 기준 v0.3은 보안 지적에 중간 확신의 병합 막음을 허용한다.

`setPermissionRequestHandler`는 있지만 `setPermissionCheckHandler`는 없다. 권한 확인 단계가 허용되면 요청 거부 콜백만으로 처리되지 않는 경로가 남는다. Electron은 완전한 권한 처리를 위해 두 핸들러를 함께 구현하도록 명시한다. 근거는 [Electron session 문서](https://www.electronjs.org/docs/latest/api/session#sessetpermissionrequesthandlerhandler)와 설치된 `electron.d.ts:13547`이다.

고칠 방향: 권한 확인과 권한 요청에 모두 기본 거부 정책을 적용한다. 확인 단계와 요청 단계의 거부 시험을 각각 남긴다. 현재 검토에서는 특정 장치나 자료 접근이 실제로 성공했다고 주장하지 않는다.

## 확인한 것

- 실행 표시 상태는 `displayState(events)`에서 계산한다. 별도의 표시 상태를 DB에 저장하지 않는다. 새 읽기 API의 `RunView`에는 `state`와 `end_reason`이 없다. 사건 기반 계산 방식 자체는 요구에 맞지만 계산의 일관성은 R2를 해결해야 한다.
- 새 읽기 API의 ID와 수치 인자는 SQL 바인딩으로 전달한다. `exists`의 테이블 문자열은 비공개 함수에 전달되는 두 고정 문자열뿐이다. 현재 호출 경로에는 사용자 입력을 테이블 이름으로 넣는 곳이 없다.
- 작업 목록의 크기는 1~100으로 제한한다. 사건 목록의 크기는 1~500으로 제한한다. 실행 시도 목록에는 제한이 없다.
- 존재하지 않는 작업과 실행 시도는 `NotFoundException`으로 처리한다. 해당 예외의 404 매핑과 HTTP 시험 두 개를 읽었다. HTTP 시험은 실행하지 않았다.
- 읽기 응답은 `@JsonProperty`로 필요한 snake_case 필드를 지정한다. JSONB를 객체로 읽으므로 `payload`가 JSON 문자열로 한 번 더 감싸지는 구조가 아니다. 시각은 `OffsetDateTime`으로 읽는다. 실제 HTTP 직렬화 재검증은 하지 않았다.
- `ZM_BATON_WEB_DIR`의 기본값은 `classpath:/static/`이다. 현재 소스 자원에는 그 위치의 화면 파일이 없다. 기본 설정만으로 새 Web 화면을 제공하는 것은 아니다. 문서는 빌드 폴더를 명시하도록 안내한다. 저장소 루트나 DB 설정 파일을 기본 정적 경로로 노출하지 않는다.
- 서버의 루프백 기본 바인딩은 유지된다. 인증과 권한은 기존 설치·호환 시험에서 제외한 항목이다. 이 검토를 운영용 API의 보안 승인으로 해석하면 안 된다.
- React는 제목과 사건 요약을 텍스트 자식으로 그린다. `dangerouslySetInnerHTML`, `innerHTML`, 동적 `eval` 경로는 발견하지 못했다. 사건 데이터로 HTML이나 스크립트를 만드는 곳도 없다.
- HTTP 실패와 JSON 해석 실패는 오류 표시로 전달된다. 폴링은 이후에도 계속된다. 선택 변경 시 성공 응답 처리에는 R1이 남는다.
- Electron은 `contextIsolation`, `sandbox`, `webSecurity`를 켠다. `nodeIntegration`은 끈다. preload와 IPC 브리지는 없다. 새 창 요청은 `setWindowOpenHandler`에서 거부한다.
- 확인 모드는 환경변수가 있을 때만 실행한다. 일반 모드에서는 화면 DOM 검사와 결과 파일 쓰기가 실행되지 않는다. `executeJavaScript`에는 코드에 고정된 문자열만 전달한다. 사건 본문이나 파일 경로를 실행할 코드에 끼워 넣지 않는다.
- 확인 모드는 지정한 결과 파일을 덮어쓴다. 웹 페이지가 이 파일 경로를 정하는 경로는 없다. 종료 시간의 잘못된 값과 DOM 검사 실패의 처리는 별도 보완 후보다.
- 두 `package.json`의 직접 의존성 버전은 모두 정확한 버전으로 고정되어 있다. 잠금 파일과 설치된 패키지의 직접 의존성 버전이 모두 일치했다. 잠금 파일의 내려받기 호스트는 공개 npm 레지스트리였다.
- 직접 의존성의 라이선스 서술은 설치된 패키지 메타데이터와 일치했다. React·React DOM·Vite·플러그인·Vitest·Electron·타입 패키지는 MIT다. TypeScript는 Apache-2.0이다. packager는 BSD-2-Clause다.
- Web의 실행 의존성은 React와 React DOM이다. 빌드·시험 도구는 `devDependencies`다. Electron 패키지의 도구도 전부 `devDependencies`다. 설치된 packager CLI의 기본값은 `prune: true`, `asar: true`다. 현재 명령은 이를 끄지 않는다. 개발 도구가 앱의 런타임 의존성으로 묶이는 구조는 아니다. Electron 런타임 자체는 배포물에 필요하다.
- 화면 시험 문서는 실제 확인 모드가 창을 숨긴다고 밝힌다. 이동 차단과 권한 거부의 부정 시험을 하지 않았다는 제한도 명시한다. 이 제한은 정직하지만 차단 구현의 누락까지 해결하지는 않는다.
- 두 TypeScript 설정은 `strict`를 켠다. 새 손작성 파일 중 1,000줄을 넘는 파일은 없다. 큰 두 잠금 파일은 생성물이다.
- 변경된 줄의 정적 검색과 육안 검토에서 소유자 PC 절대 경로, 사설망 주소, 명백한 비밀값을 발견하지 못했다. 로그인 정보 파일은 읽지 않았다. 이 결과는 모든 형태의 비밀값 부재를 보증하는 검사가 아니다.
- 핵심 모듈 2·3은 이미 기록 미작성 목록에 있다. 이번 읽기 API와 화면은 새 핵심 모듈의 첫 구현으로 보지 않았다.

## 부채 후보와 비차단 의견

| 번호 | 위치 | 검토 항목 | 근거와 고칠 방향 | 확신 | 병합 막음 |
|---|---|---|---|---|---|
| N1 | `apps/server/src/main/kotlin/io/github/hanumoka/zmbaton/server/ReadApi.kt:59` | 1, 8 | 실행 시도 목록이 무제한이다. 오래 사용하면 모든 실행을 반복 조회한다. 페이지 제한과 안정적인 정렬 키를 추가할 후보로 남긴다. | 높음 | 아니오 |
| N2 | `apps/desktop/src/main.ts:85` | 4, 12 | 모든 응답에 CSP를 붙인다. 같은 이름의 기존 헤더는 덮는다. 서버가 더 엄격한 정책을 추가해도 약해질 수 있다. 기존 정책을 보존하고 적용 대상 응답을 명시할 후보로 남긴다. 현재 서버가 더 엄격한 CSP를 보내는 경로는 확인하지 못했다. | 높음 | 아니오 |
| N3 | `apps/web/src/api.ts:4`, `apps/web/src/runState.ts:3` | 2, 11 | API 타입을 화면에 직접 정의했다. 현재 저장소에는 `packages/contracts`가 없다. 본구현에서 정본 타입으로 옮길 후보로 남긴다. | 높음 | 아니오 |
| N4 | `apps/web/src/runState.ts:3` | 2, 12 | 화면 시험은 세 가지 수명주기 상태만 표시한다. 질문·오류·무응답을 포함한 계약의 전체 보고 상태는 구현하지 않았다. 이 시험 결과를 전체 보고 상태 구현 완료로 확대하지 않는다. | 높음 | 아니오 |
| N5 | `apps/desktop/src/main.ts:47` | 1, 7 | 확인 모드의 DOM 검사 예외는 로드 오류 처리 밖에 있다. 제한 시간이 유한한 양수인지도 검사하지 않는다. 확인 실패를 명시적인 결과와 종료 코드로 남길 후보로 둔다. | 높음 | 아니오 |

현재 CSP는 외부 스크립트와 연결을 제한하지만 이동 제한 전체를 대신하지 않는다. `frame-ancestors 'none'`은 이 문서가 다른 프레임 안에 들어가는 것을 막는 설정이다. `frame-src`와는 역할이 다르다. 일반 API JSON 응답에 붙은 CSP를 API 인증이나 접근 제어로 보지 않았다.

전이 의존성의 전체 고지와 재배포물 검사는 하지 않았다. 작성 문서도 이를 미확인으로 남겼다. packager의 기본 동작은 확인했지만 실제 `app.asar` 크기와 Chromium 고지 파일의 포함 여부는 재확인하지 않았다.

## 실행한 명령과 결과

환경은 Windows `10.0.26300.0`, PowerShell, Node.js `24.4.0`, npm `11.8.0`이다. 설치된 TypeScript는 `7.0.2`다. Vitest는 `5.0.3`이다. 네트워크 설치 없이 기존 `node_modules`를 사용했다.

| 위치 | 명령 | 결과 |
|---|---|---|
| 저장소 | `git rev-parse HEAD`, `git rev-parse main work/compat-ui` | 대상 커밋을 확인했다. 로컬 main과 요청 기준의 차이를 확인했다. |
| 저장소 | `git diff --stat 9101bb7..de37ff4`, `git diff --name-only 9101bb7..de37ff4`, `git diff --numstat 9101bb7..de37ff4` | 23개 변경 파일을 확인했다. |
| 저장소 | `git diff --check main..work/compat-ui` | 통과. 종료 코드 0. |
| 저장소 | `git diff --check 9101bb7..de37ff4` | 통과. 종료 코드 0. |
| 저장소 | `git diff --quiet de37ff4 -- apps docs/stage1` | 차이 없음. 종료 코드 0. |
| `apps/web` | `npx --no-install tsc --noEmit -p .` | PowerShell 실행 정책으로 실행 전 실패했다. |
| `apps/web` | `npx --no-install vitest run` | PowerShell 실행 정책으로 실행 전 실패했다. |
| `apps/desktop` | `npx --no-install tsc --noEmit -p .` | PowerShell 실행 정책으로 실행 전 실패했다. |
| `apps/web` | `npx.cmd --no-install tsc --noEmit -p .` | 통과. 출력 없음. 종료 코드 0. |
| `apps/desktop` | `npx.cmd --no-install tsc --noEmit -p .` | 통과. 출력 없음. 종료 코드 0. |
| `apps/web` | `npx.cmd --no-install vitest run` | 기본 임시 폴더 쓰기 권한으로 실패했다. 시험은 0개 실행되었다. |
| `apps/web` | 허용된 scratchpad로 `TEMP`와 `TMP`를 지정한 프로세스에서 `npx.cmd --no-install vitest run` | 시험 파일 1개 통과. 시험 8개 통과. 종료 코드 0. |
| 저장소 | `node --input-type=module --disable-warning=ExperimentalWarning`에 아래 재현 코드를 표준입력으로 전달 | 종료 이유 뒤집힘과 서로 다른 실행의 커서 혼합을 확인했다. |
| 저장소 | `codex --version`, `node --version`, `npm.cmd --version`, `[Environment]::OSVersion.Version.ToString()` | 위 환경과 버전을 확인했다. `npm --version`의 첫 시도도 PowerShell 실행 정책으로 실패했다. |

실패 로그는 경로 부분만 가렸다. 공개 저장소에 절대 경로를 쓰지 말라는 검토 제약을 적용했다. 나머지 핵심 오류 원문은 다음과 같다.

```text
npx : <Node 설치 폴더>\npx.ps1 파일을 로드할 수 없습니다. <Node 설치 폴더>\npx.ps1 파일이 디지털 서명되지 않았습니다.
현재 시스템에서 이 스크립트를 실행할 수 없습니다.
CategoryInfo          : 보안 오류: (:) [], PSSecurityException
FullyQualifiedErrorId : UnauthorizedAccess

FAIL  src/runState.test.ts [ src/runState.test.ts ]
Error: EPERM: operation not permitted, mkdir '<기본 임시 폴더>\4JCs7RiSBixB_lgy0w8za\ssr'
Test Files  1 failed (1)
Tests  no tests
```

재실행에서는 시스템 실행 정책을 바꾸지 않았다. `.cmd` 진입점을 사용했다. 임시 폴더 환경변수는 해당 시험 프로세스에만 적용했다. 재실행 결과는 다음과 같다.

```text
Test Files  1 passed (1)
Tests  8 passed (8)
```

별도 재현에는 저장소의 실제 `displayState`와 `mergeEvents`를 사용했다. 서버는 실행하지 않았다.

```javascript
import { displayState, mergeEvents } from './apps/web/src/runState.ts';
const ev = (id, seq, reason, time) => ({
  event_id: id, generation: 1, seq, kind: 'lifecycle',
  payload: { phase: 'ended', end_reason: reason },
  occurred_at: null, received_at: time,
});
const first = ev('first_received', 10, 'lost', '2026-10-05T00:00:01Z');
const later = ev('later_received', 9, 'submitted', '2026-10-05T00:00:02Z');
console.log('before_late=', JSON.stringify(displayState([first])));
console.log('after_reload=', JSON.stringify(displayState([first, later])));
const mixed = mergeEvents(
  [ev('run_B', 1, 'failed', '2026-10-05T00:00:01Z')],
  [ev('run_A', 100, 'submitted', '2026-10-05T00:00:02Z')],
);
console.log('mixed_run_cursor=', mixed.at(-1).seq);
```

```text
before_late= {"state":"ended","endReason":"lost"}
after_reload= {"state":"ended","endReason":"submitted"}
mixed_run_cursor= 100
```

이 재현의 혼합 부분은 잘못 전달된 응답을 합쳤을 때의 결과를 검증한다. React에서 요청을 지연시키는 통합 시험을 수행한 것은 아니다.

추가로 `Get-Content -Encoding UTF8`, `rg`, `git diff`로 변경 파일과 관련 기존 코드를 읽었다. Node의 읽기 전용 스크립트로 직접 의존성 메타데이터와 잠금 파일을 비교했다. 같은 방식으로 추가된 줄의 절대 경로·사설망 주소·대표 비밀값 패턴을 검색했다. 세 분류 모두 일치 0건이었다. `packages/contracts/README.md` 조회와 `rg --files packages`는 해당 경로가 없어 실패했다.

## 확인하지 않은 것과 판정의 한계

- `vite build`, `npm install`, Electron 실행, Gradle 실행은 요청에 따라 하지 않았다.
- 서버 HTTP·DB 시험은 재실행하지 않았다. 작성자의 서버 시험 16개 통과를 독립 재검증 결과로 쓰지 않았다.
- 실제 Claude Code 실행과 Electron 확인 모드는 재현하지 않았다. 상태 전환 12초와 사건 7건은 작성자가 기록한 결과다.
- Windows 배포물을 다시 만들지 않았다. 실행 파일 크기와 포함 파일은 재확인하지 않았다.
- 일반 브라우저, 접근성, macOS·Linux는 시험하지 않았다.
- 이동 차단, iframe, 권한 거부, CSP를 실제 Electron에서 공격 입력으로 시험하지 않았다.
- 전이 의존성 전체의 라이선스와 최신 취약점은 검사하지 않았다. 문서의 취약점 0건은 작성자가 설치할 때 얻은 결과다.
- 원격 PR 본문, 일곱 필수 항목, CI, 브랜치 보호 설정은 확인하지 않았다. 사람 필수 변경에 대한 판단 이유와 「위임받은 AI의 판단」 표시는 병합하는 작성 AI가 확인해야 한다.
- 이 검토에서 push, 원격 변경, main 변경, 브랜치 생성, 커밋, 병합은 하지 않았다. 구현 파일은 변경하지 않았다.

R1~R5를 수정한 커밋은 다시 독립 검토를 받아야 한다. 현재 커밋에는 병합 승인을 주지 않는다.
