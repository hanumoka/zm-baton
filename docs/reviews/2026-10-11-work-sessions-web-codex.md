## AI 검토 결과

- 적용한 기준: zm-baton 검토 기준 v0.4. 기준 문서는 `c0341ff25bfc70b923d98c7d77c26c7b077bfbdd:docs/review-standard.md`를 읽었다.
- 검토자: Codex CLI 0.162.0 / GPT-6(세부 모델 식별자는 제공되지 않음) / 세션 `01a12a6c-cf00-73a1-84ef-e835e78b19e6` / 독립 검토자.
- 작성자: Claude Code / 작성 세션 `409394de-ef95-46af-bf81-42c556a8b817`(검토 요청에서 제공). 검토 세션은 `CODEX_SESSION_ID`와 `CODEX_THREAD_ID`로 확인했다. 실행기와 세션 번호가 모두 다르며, 작성 대화 대신 검토 지시와 저장소 자료를 받았다.
- 검토한 커밋: `7321f6f21c9cb8f957525dac1fe7d808ec650b5b`(`work/sessions-web`). 비교 기준과 공통 조상은 모두 `c0341ff25bfc70b923d98c7d77c26c7b077bfbdd`다.
- 판정: **병합 막음**. 아래 B1을 수정하고 새 커밋을 독립 재검토해야 한다. N1·N2는 비차단 지적이다.
- 소유자 호출 조건: **해당, 조건 2** — `deploy/compose/compose.yaml`, `deploy/compose/server.Dockerfile`, `deploy/compose/server.Dockerfile.dockerignore`.
- 소유자 호출 근거: **확인 못 함**. PR 본문과 결정 댓글을 읽지 못했다. 호출 누락을 0건으로 계산하지 않는다. 배포 설정은 되돌릴 수 있는지와 관계없이 조건 2에 해당하므로, 병합 전에 소유자 결정 댓글이 필요하다.

| 번호 | 위치 | 검토 항목 | 근거 | 확신 | 병합 막음 |
|---|---|---|---|---|---|
| B1 · 높음(P1) | `deploy/compose/server.Dockerfile.dockerignore:8`, `deploy/compose/server.Dockerfile:8`, `deploy/compose/server.Dockerfile:13` | 5. 비밀값 / 7. 거부 시험 | 제외 패턴이 `**/.env`뿐이다. `apps/web/.env.local`, `apps/web/.env.production`, `apps/server/.env.local` 같은 파일은 루트 `.gitignore`에 의해 Git에서는 빠지지만 Docker 컨텍스트에서는 빠지지 않는다. 해당 파일이 있는 작업 폴더에서 빌드하면 `COPY web/ ./` 또는 `COPY server/ ./`를 통해 빌드 단계와 캐시에 들어간다. 최종 이미지가 다단계라는 사실로 컨텍스트 노출이 해소되지는 않는다. `**/.env.*`도 제외하고, 합성 파일로 컨텍스트·빌드 단계에 포함되지 않는지 검증해야 한다. 실제 비밀 파일의 존재나 유출을 확인했다는 뜻은 아니다. 패턴 해석은 [Docker 공식 문서](https://docs.docker.com/build/concepts/context/#dockerignore-files)와 대조했다. | 높음: 제외 규칙과 COPY 경로를 직접 확인 | 예 |
| N1 · 보통(P2) | `apps/web/src/Shell.tsx:54`, `apps/web/src/Shell.tsx:61` | 1. 실패 경로 / 6. 상태 표시 | 세션 조회와 공지 판정 오류를 공통 `error`에 저장하지만, 수신함 조회가 성공할 때마다 이를 무조건 지운다. 예를 들어 `GET /api/sessions`만 실패하고 `GET /api/inbox`는 성공하면, 세션 오류가 해결되지 않았는데도 다음 수신함 응답이 오류 표시를 없앤다. 조회·판정별 오류를 분리하고 해당 동작의 성공으로만 해제해야 한다. | 높음: 상태 갱신 경로로 확인 | 아니오 |
| N2 · 보통(P2) | `docs/stage1/sessions.md:48`, `docs/stage1/sessions.md:98`, `docs/stage1/sessions.md:107` | 12. 실행·실패·정리 절차 | 3단계에서 `apps/agent`로 이동한 뒤 저장소 루트로 돌아오는 명령이 없다. 같은 PowerShell에서 순서대로 진행하면 실패 대응과 정리의 `-f deploy/compose/compose.yaml`을 찾지 못해 로그 조회·종료가 실패한다. 실제로 루트에서는 해당 경로의 `Test-Path`가 True, `apps/agent`에서는 False였다. 해당 절차에 `cd $repo`를 넣거나 `-f "$repo/deploy/compose/compose.yaml"`로 명시해야 한다. | 높음: 경로 검사로 재현 | 아니오 |

### 변경 종류와 소유자 호출

- 변경 종류: 일반 코드 / 보안(빌드 컨텍스트의 비밀값 제외 규칙) / 배포 설정 / 의존성(새 빌드·실행 기반 이미지) / 일반 문서. npm 의존성과 잠금 파일, DB 구조, 정책 문서, 계약 정의 자체는 바뀌지 않았다.
- 기준에 지정된 `git merge-base`와 `git diff --no-renames --name-only`, 저장소 루트 기준 `:(top,glob)` 경로 패턴으로 검사했다. 조건 1의 보호 경로 변경은 없고, 조건 2에는 위 배포 파일 세 개가 걸린다. 보안 변경 종류에 따른 소유자 호출도 함께 적용된다.
- 조건 3은 해당하지 않는다. 이번 판정은 「판정 불가」가 아니다. 조건 4·5의 이견·면제 시도는 제공된 정보에서 확인되지 않았다. 이후 작성자가 B1이나 호출 판정에 동의하지 않으면 조건 4로 소유자에게 넘겨야 한다.
- 핵심 모듈 6(마스터 관찰과 공지)에 해당한다. 현재 HEAD의 `docs/core-modules/README.md`는 이 모듈을 「코드 없음」으로 두고, `work/sessions-server`는 「기록 미작성」 등록을 포함한다. 서버 PR 선병합을 전제로 확인했으며, 이 PR이 모듈 6의 첫 병합이 된다면 기록 미작성 등록이 먼저 필요하다. 소유자 설명·재현 기록은 대신 작성하지 않았다.
- 현재 HEAD에는 새 서버 API와 `apps/agent/cmd/zm-baton`이 없다. 별도 서버·CLI PR이 반영된 통합 상태에서 안내서의 전체 절차가 성립한다. 이 검토를 현재 HEAD 단독의 전체 기능 통과로 사용하면 안 된다.

### 확인한 것

- 변경 파일 15개를 읽었다. 비교한 서버 브랜치는 `work/sessions-server`의 `98c9898c20b4a698bf24631dc64377b3b164f8bb`, CLI 브랜치는 `work/sessions-cli`의 `328f7810ff17ed6ee4aa8fbcffc1fe6785887f27`다. 다른 브랜치는 API와 안내서 대조에 필요한 부분만 읽었으며 이번 승인 대상이 아니다.
- 설계 근거는 협업 계약 v1의 「첫 화면 「나를 기다리는 것」」·「직접 연 AI 세션을 서버에 등록한다」·「충돌을 찾아 관계자에게 알린다」와 interaction-design의 「첫 화면은 「나를 기다리는 것」이다」·「공통 상태와 접근성을 화면 전반에 적용한다」다. 비공개 설계 문서의 본문과 절대 경로는 기록에 옮기지 않았다.

| 검토 항목 | 확인 결과와 범위 |
|---|---|
| 1. 수락 조건 | 다섯 묶음의 순서, 오래 기다린 항목 우선, 충돌 판정 세 종류, 직접 연 세션의 터미널 안내, 세션 목록과 실행 시험 탭을 확인했다. 첫 묶음에서 제외한 기능은 안내서에 표시돼 있다. 실패 표시는 N1, 설치 후 정리는 N2가 남는다. |
| 2. 계약 | 세 API의 경로·메서드·snake_case 필드·null 허용·상태 값·판정 본문을 서버 DTO와 대조했다. `not_a_conflict`에는 공백을 제거한 이유를 보내고, 빈 이유에서는 입력을 요구한다. 서버가 판정 성공과 수신함 닫기를 같은 트랜잭션에서 처리한다. |
| 3. 권한 | Web에 세션 토큰을 저장하거나 넣는 변경은 없다. 판정은 서버 API에 보낸다. 별도 서버의 세션 토큰 거부 경로를 읽었으나 거부 시험을 재실행하지 않았다. 사람 인증 전의 loopback 제한이 유지된다. |
| 4. 중복·취소 | 최초 성공 조회는 알리지 않고, 이후 새 ID의 immediate 항목만 알린다. 서버의 해결 후 재개방은 새 수신함 ID를 생성하므로 다시 알림 대상이 된다. 같은 열린 항목의 반복은 새 알림 대상이 아니다. `usePoll`의 중첩 방지·cleanup 시 abort·늦은 응답 차단을 일회성 검사로 확인했다. 여러 브라우저 탭 사이 중복은 검증하지 않았다. |
| 5. 비밀값 | 렌더링은 React 텍스트이며 외부 HTML 삽입이 없다. 서버의 원격 주소 자격 증명 제거와 상대 경로 검사를 대조했다. 자유 입력 요약 전체가 비밀값을 포함하지 않는다고 보증하지는 않는다. Docker 컨텍스트에는 B1이 있다. |
| 6. 로그·보고 | 비공유 작업은 「공유 안 함」, 충돌 파일은 「겹침」, 세션 상태는 글자로 표시한다. 버튼·입력 label·nav 이름·현재 화면 표시가 있다. 처음 조회 중인 상태와 빈 결과를 구별하는 UI, 오류 해제의 키보드 조작은 보완 대상이다. |
| 7. 시험 | 기존 25개 시험이 통과했다. 추가 일회성 검사는 합성 값과 가짜 fetch·타이머를 사용했다. 실제 계정·훅·기기 설정은 변경하지 않았다. 사용자 클릭과 브라우저 알림의 통합 시험은 빠져 있다. |
| 8. 책임·크기 | 폴링은 기존 구현을 그대로 분리했고 실행 시험의 나머지 동작은 유지했다. 변경 파일에 1,000줄 초과는 없다. 추가 827줄·삭제 39줄이므로 400줄 초과 분할 검토 이유는 PR 본문에서 확인해야 한다. |
| 9. 의존성 | npm 패키지 추가는 없다. 새 Docker 단계는 Node 24, Temurin JDK/JRE 25를 사용한다. 이미지의 재배포 고지와 선택 이유는 별도 확인이 필요하다. |
| 10. 라이선스 | 변경에서 제삼자 소스 반입은 확인되지 않았다. 기반 이미지의 라이선스·재배포 조건을 완전히 조사한 것은 아니다. |
| 11. 부채 | 아래 후보를 남겼다. 최종 부채 판정은 소유자 몫이다. |
| 12. 문서 | 전제조건·번호 순서·완료 확인·실패 대응·정리가 있다. PowerShell의 볼륨 인자, Windows amd64 빌드 대상, 사용자 PATH 덧붙임, 훅 확인·백업 절차를 코드와 대조했다. `down -v`에는 자료 폐기 조건이 적혀 있다. 실제 설치는 수행하지 않았다. |

Docker 정적 검토에서는 세 단계 빌드, `server-0.0.1-SNAPSHOT.jar`와 Gradle 프로젝트명·버전의 일치, `ZM_BATON_WEB_DIR=file:/app/web/`와 Spring 설정의 연결, UID 10001의 `USER zmbaton`을 확인했다. Compose 해석 결과 기본 서비스는 postgres 하나이고, app 프로필에서 server가 추가된다. 두 포트의 host_ip는 모두 `127.0.0.1`이며 기존 DB 볼륨·이름·포트는 유지된다. PostgreSQL healthcheck와 server의 `service_healthy` 의존 관계도 확인했다. 이는 컨테이너 실행 검증과 구별한다.

### 검증 결과

환경: Windows NT 10.0.26300.0 / PowerShell 7.6.6 / Node.js 24.16.0 / npm 11.13.0 / TypeScript 7.0.2 / React 19.3.0 / Vite 8.3.2 / Vitest 5.0.3 / Git 2.53.0.windows.2 / Docker CLI 29.8.2 / Docker Compose 5.5.1. 대상은 위 HEAD다. 소스·설정·테스트 파일을 고치지 않기 위해 캐시를 끄고 빌드는 메모리에서 수행했다.

| 실행 명령 또는 검사 | 실제 결과 |
|---|---|
| `npm test -- --no-cache --configLoader native --pool threads` (`apps/web`) | 3개 파일, 25개 시험 통과, 실패 0. |
| `npm run typecheck` (`apps/web`) | 종료 코드 0. |
| `node --input-type=module -e 'import { build } from "vite"; const r = await build({ configLoader: "native", build: { write: false } }); console.log("in-memory build output files:", r.output.length);'` (`apps/web`) | 종료 코드 0, 25개 모듈 변환, 출력 3개. 파일은 쓰지 않았다. `npm run build` 원문을 그대로 실행한 결과는 아니다. |
| `node --input-type=module`의 일회성 인라인 검사 (`apps/web`) | 모델·API 검사 9개 확인: 최초 무알림, 동일 ID 중복 억제, immediate 필터, 재개방 ID, AbortSignal, 종료 필터, ID 인코딩, 이유 JSON, HTTP 503 전달. 폴링 별도 검사 4개 통과: 중첩 억제, abort·alive·timer 정리, 정리 시 오류 없음, 실패 전달. React 효과·타이머를 대체한 검사로 실제 DOM 시험은 아니다. |
| 합성 비밀번호 환경 변수와 `--env-file NUL`을 사용한 `docker compose -f deploy/compose/compose.yaml config --services`, `--profile app config --services`, `--profile app config --format json` | 모두 성공. 기본 postgres, app 프로필 postgres·server, loopback 포트·빌드 컨텍스트·healthcheck 확인. 실제 `.env`를 읽지 않았다. |
| 루트 및 `apps/agent`에서 `Test-Path deploy/compose/compose.yaml` | 각각 True / False. N2 재현. |
| `git diff --check c0341ff HEAD` | `apps/web/src/Inbox.tsx:124: new blank line at EOF.` 경고. 기능상 차단 사유로 삼지 않는다. |

실패·재시도도 남긴다.

- `--configLoader runner`를 사용한 테스트와 메모리 빌드는 picomatch 로딩에서 각각 `ReferenceError: require is not defined`, `ERR_AMBIGUOUS_MODULE_SYNTAX`로 실패했다. native 로더로 변경해 위 검증을 완료했다.
- `npm test -- --no-cache --configLoader native`는 기본 forks 작업자 생성의 `spawn EPERM`으로 시험 0개·오류 3개였고 종료 대기 시간도 초과했다. 이를 통과로 계산하지 않았다. threads 풀에서 25개를 다시 실행했다.
- 일회성 인라인 검사의 첫 합본은 앞의 모델·API 검사 뒤 TypeScript API의 `ModuleKind.CommonJS` 접근에서 실패했다. 폴링 부분은 Node의 `stripTypeScriptTypes`로 다시 검사해 통과했다. 그 API의 실험 기능 경고는 있었다.
- 저장소 루트에서 잘못 실행한 `npm run typecheck`는 `ENOENT`로 실패했다. `apps/web`에서 다시 실행해 종료 코드 0을 확인했다.
- `docker info --format '{{.ServerVersion}}'`는 `permission denied while trying to connect to the docker API at npipe:////./pipe/dockerDesktopLinuxEngine`로 실패했다. 이미지 빌드·기동·health 재검증은 하지 못했다.
- 기본 `gh pr view`는 원격 호스트 별칭을 인식하지 못했다. `gh pr view work/sessions-web --repo hanumoka/zm-baton --json number,title,body,headRefOid,url`로 다시 시도했지만 `proxyconnect tcp: dial tcp 127.0.0.1:9` 연결 거부로 실패했다. PR 필수 여덟 항목, 소유자 결정 댓글, CI 상태는 확인 못 했다.

### 부채 후보

- `apps/web/src/firstScreen.test.tsx`의 새 시험 6개는 모델 함수와 정적 HTML 위주다. 실제 이유 입력·공백 제출 방지·실패 후 재시도·진행 중 중복 클릭, Shell의 알림 생성 횟수·최초 조회 실패 후 회복·권한 변경, 세션 필터 전환 중 늦은 응답, N1의 서로 다른 API 성공·실패 조합을 자동 시험으로 보완할 필요가 있다.
- `Shell`의 초기 빈 배열을 곧바로 「없음」으로 보여 준다. 조회 중·조회 실패·빈 결과·오래된 자료를 구별하고 마지막 성공 시각을 보여 줄 필요가 있다. 오류를 닫는 `p onClick`도 키보드로 조작할 수 있게 해야 한다.
- `docs/stage1/sessions.md:86`에는 비공유 명령을 `zm-baton session work --private "<무엇을, 어디서>"`처럼 완성형으로 적는 편이 안전하다. 별도 CLI는 Go `flag`를 사용하므로 요약 뒤에 붙인 `--private`는 옵션으로 처리되지 않는다. [Go 공식 flag 문서](https://pkg.go.dev/flag#hdr-Command_line_flag_syntax)와 CLI 코드를 대조했다. 일반 PowerShell에서는 세션 식별자를 명시하는 방법도 안내할 필요가 있다.
- 안내서에 서버·CLI PR 선행 조건과 확인한 통합 커밋을 적고, 정리에서 추가한 PATH·실행 파일의 유지 또는 제거 방법을 명시할 필요가 있다.
- Web API 타입은 서버 DTO와 별도로 손으로 정의되어 있다. 이번 형식은 일치하지만 서버 직렬화 결과와의 계약 시험이 없으므로 변경 시 어긋날 수 있다.

### 확인하지 않은 것

- 실제 브라우저의 OS 알림 표시, 여러 창·탭 사이 중복, 알림 API 예외, 브라우저 접근성 트리·키보드 초점·화면 읽기 도구, 실제 사용자 클릭 흐름은 실행하지 않았다.
- Docker 이미지 전체 빌드, JAR 실제 실행, 최종 이미지 파일·계층 검사, 합성 `.env.*`의 컨텍스트 제외 거부 시험, DB를 띄운 통합 시험은 실행하지 못했다. B1은 코드와 공식 패턴 규칙에 근거한 판단이다.
- 안내서의 `docker run` 빌드, 기존 사용자 PATH 변경, 기기 등록, Claude Code 훅 설치·해제, 실제 설정 백업·복구, `down -v`는 실행하지 않았다. macOS·Linux·Windows ARM64는 시험하지 않았다.
- 별도 서버·CLI PR 전체와 그 검토 결과, 실제 운영 자료, 비밀값 파일, 로그인 정보는 검토 범위에 넣지 않았다.
- 작성자가 전달한 `npm test` 25개 통과, `npm run build` 성공, Compose 빌드·health UP, 세 PR 통합 화면 성공은 작성자 보고로 구분한다. 독립 재현한 범위는 위 표뿐이다.
- 이 기록만 새로 작성했다. 커밋·push·병합과 제품 파일 수정은 하지 않았다.
