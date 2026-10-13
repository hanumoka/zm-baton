## AI 검토 결과

- 적용한 기준: zm-baton 검토 기준 **v0.5**. 기준과 고도화 목록은 `af264178348477f108dfb5f78f1fd6a1d3729fcd:docs/review-standard.md`, 같은 커밋의 `docs/hardening-backlog.md`다. 작업 폴더의 v0.4 대신 검토 요청이 지정한 v0.5를 적용했다.
- 검토자: Codex CLI 0.162.0 / GPT-6(세부 모델 식별자는 제공되지 않음) / 세션 `01a132dc-c321-7d20-a6cb-430e54f941d3` / 3차 독립 검토자.
- 작성자: Claude Code / 세션 `409394de-ef95-46af-bf81-42c556a8b817`(검토 요청에서 제공). 검토 세션은 `CODEX_SESSION_ID`와 `CODEX_THREAD_ID`로 확인했다. 실행기와 세션이 모두 다르며 작성 대화 대신 검토 지시와 저장소 자료를 받았다.
- 검토한 커밋: **`bd39a4a2ffa92ca717843e0ca7cf7e3c7f8d8212`**, `work/sessions-web`, PR #13. 전체 차이의 기준은 공통 조상 `c0341ff25bfc70b923d98c7d77c26c7b077bfbdd`다. 재검토 중심은 `git diff 512ecea HEAD`의 두 파일과 세 커밋(`ca2b5bf`, `aebee22`, `bd39a4a`)이다.
- 판정: **병합 가능**. v0.5로 재분류한 뒤 현재 커밋에 남은 차단 지적은 없다. B2는 H-05로 추적하고, N3·N4는 해결됐다. 이 판정은 아래 소유자 결정·PR·CI 확인을 대신하지 않는다.
- 소유자 호출 조건: **해당, 조건 2** — `deploy/compose/compose.yaml`, `deploy/compose/server.Dockerfile`, `deploy/compose/server.Dockerfile.dockerignore`, `deploy/compose/check-build-context.ps1`. 공통 조상 기준으로 `git diff --no-renames --name-only`와 v0.5의 pathspec을 적용했다. 조건 1의 경로 일치는 없고, 조건 3은 해당하지 않는다. 조건 4·5의 이견·면제 시도는 확인되지 않았다.
- 소유자 호출 근거: **확인 못 함**. GitHub API 연결 거부로 PR 본문·결정 댓글·CI를 읽지 못했다. 호출 누락을 0건으로 계산하지 않는다. 병합 전 작성 AI가 PR 필수 항목, 현재 커밋의 CI와 소유자 결정 댓글을 확인해야 한다.

### 2차 미해결 지적의 v0.5 분류와 처리

| 번호 | 위치 | 검토 항목 | 근거와 분류 | 확신 | 병합 막음 |
|---|---|---|---|---|---|
| B2 | `deploy/compose/server.Dockerfile.dockerignore:13`, `deploy/compose/server.Dockerfile:8`, `:13` | 5. 비밀값 / 7. 시험 | **고도화 후보 H-05, 유지.** `gradle.properties`, `http-client.private.env.json` 등의 이름은 여전히 제외되지 않는다. 다만 이미지를 이 PC에서만 만들고 쓰며 외부에 올리지 않는 현재 전제에서는 v0.5가 명시한 로컬 빌드 위생에 해당한다. 실제 비밀값이 공개 저장소에 들어갔다는 근거는 없다. | 높음: 패턴·COPY 경로와 H-05 대조. Docker 재현은 미실행 | 아니오 |
| N3 | `deploy/compose/check-build-context.ps1:23`, `:29`, `:33`, `:57` | 7. 시험 안전성 | **선 2에 따른 차단 대상이었으나 해결(H-06).** 쓰기·삭제 대상이 새 GUID 임시 폴더 안으로 옮겨졌다. 작업 폴더에서 읽는 것은 ignore 파일뿐이다. 파일 생성 뒤 쓰기가 실패해도 작업 폴더의 파일을 남기거나 덮어쓰지 않으며, finally가 임시 폴더 전체를 정리한다. 부분 쓰기 실패를 포함한 메모리 모의 검사에서도 이 범위를 확인했다. H-06의 「고치는 중(#13, 병합 대기)」 상태와 맞는다. | 높음: 코드·모의 검사. 실제 파일 시스템 실패 주입은 미실행 | 아니오 |
| N4 | `docs/stage1/sessions.md:56`, `:58`, `:134`, `:135` | 12. 설치·정리 절차 | **선 2에 따른 차단 대상이었으나 해결.** 추가는 `-notcontains`, 제거는 `-ne`로 전체 항목을 비교한다. PowerShell의 기본 대소문자 무시 비교와 `TrimEnd('\')`가 적용된다. 설치 경로와 다른 루트, 하위 경로, `binary-tools`, 공백을 포함한 다른 항목은 보존된다. | 높음: 원문 식에 합성 PATH 8종을 넣은 추가·제거 16건 통과 | 아니오 |

2차에서 이미 해결된 B1·N1·N2를 다시 열린 지적으로 취급하지 않았다. 관련 ignore 규칙·웹 오류 처리·Compose 경로 복귀는 이번 세 커밋에서 되돌려지지 않았다. 2차의 부채 후보 두 건도 아래에 각각 분류했다.

### 확인한 것

- **플러그인 설치와 출력 대조:** `docs/stage1/sessions.md:79`부터의 순서는 플러그인 생성 → 로컬 마켓플레이스 추가 → 대상 저장소로 이동 → `--scope local` 설치다. `559df1377bbcf92864fe2e4ccd045af2b899829b:apps/agent/cmd/zm-baton/main.go:164`의 출력과 이름·경로·순서가 맞는다. `--project`를 생략하면 `user`가 되는 설명도 맞다. 설치된 Claude Code 2.1.296의 각 하위 명령 도움말에서 명령과 범위를 독립 확인했다.
- **플러그인 위치와 재생성:** CLI의 `session.Dir()` 기본값과 `claude-plugin` 하위 경로가 안내서의 Windows 기본 경로와 일치한다. CLI는 그 폴더에 마켓플레이스·플러그인·훅 JSON을 쓰고, Claude Code 설정 변경은 출력한 공식 명령에 맡긴다. 로컬 마켓플레이스의 상대 경로 플러그인은 원본 위치에서 읽으며 다음 세션에 변경이 반영된다는 [Claude Code 공식 로딩 규칙](https://code.claude.com/docs/en/plugins/loading#in-place-and-copied-plugins)도 확인했다. 따라서 실행 파일 이동 후 `hooks install`로 훅 경로를 다시 만드는 설명에 캐시 갱신 누락을 지적하지 않는다. 실제 설치·이동 시험을 했다는 뜻은 아니다.
- **정리 순서:** `docs/stage1/sessions.md:121`부터는 세션 해제 → 설치한 저장소·범위에서 플러그인 해제 → 저장소 복귀 → Compose 중지 순서다. 모든 곳에서 플러그인을 뗀 뒤 마켓플레이스를 제거하고 PATH·실행 파일·zm-baton 설정 폴더를 정리한다. `hooks uninstall`이 직접 설정을 지우지 않고 같은 해제 명령을 보여 준다는 설명도 CLI와 일치한다. 사용자 범위로 설치했다면 같은 범위에서 해제하라는 조건을 따라야 한다.
- **실패 안내:** `docs/stage1/sessions.md:116`은 로컬 해제 표시 저장 실패 때 보고가 계속될 수 있음을 밝힌다. CLI `main.go:278`과 `internal/session/commands.go`에 남은 H-02를 문서 수정으로 해결됐다고 표시하지 않았다. 정상적인 로컬 해제 후 서버 통지 실패의 재실행 안내도 코드와 맞는다.
- **검사 스크립트:** 합성 파일 12개와 일반 파일 하나를 임시 `apps/web`·`apps/server` 구조에 만든다. 실제 ignore 파일을 `probe.Dockerfile.dockerignore`로 복사하며, 빌드·실행의 종료 코드를 각각 확인한다. 일반 파일 하나만 도착해야 성공하고, 일반 파일이 없으면 빈 목록도 성공으로 인정하지 않는다. 쓰기·빌드·실행 실패 경로의 finally 진입은 아래 모의 검사로 확인했다. 이 검사는 열거한 이름의 제외 규칙을 검사하며 H-05까지 해결했다는 보장은 아니다.
- **검토 항목 1~12의 범위:** 이번 변경은 안내서와 시험 스크립트에 한정된다. API·DB·상태 전이·권한·제품 로그·의존성 변경이나 확장 불가능한 공개 인터페이스를 새로 만들지 않았다. Compose의 두 공개 포트는 계속 `127.0.0.1`에 묶여 있다. 새 제삼자 코드 반입은 보이지 않으며 전체 변경 파일의 최대 길이는 157줄이다. 핵심 모듈 6은 이미 #11에서 첫 병합됐고 `af26417:docs/core-modules/README.md`에 「기록 미작성」으로 등록돼 있다.

### 검증 결과

환경: Windows NT 10.0.26300.0 / PowerShell 7.6.6 / Git 2.53.0.windows.2 / Node.js 24.16.0 / npm 11.13.0 / TypeScript 7.0.2 / React 19.3.0 / Vite 8.3.2 / Vitest 5.0.3 / Docker CLI 29.8.2 / Docker Compose 5.5.1 / Claude Code 2.1.296. 별도 표시가 없으면 대상은 위 HEAD다.

| 실행 명령 또는 검사 | 실제 결과 |
|---|---|
| `git diff 512ecea HEAD`, `git diff --check c0341ff HEAD` | 재검토 변경은 두 파일뿐이다. 공백 검사 통과. |
| `[System.Management.Automation.Language.Parser]::ParseInput` | 안내서 PowerShell 블록 8개와 검사 스크립트의 구문 오류 0. |
| 안내서 PATH 원문 식의 메모리 검사 | null·빈 값·다른 항목 하나·정확히 같은 항목·대소문자와 끝 역슬래시 변형·비슷한 이름·혼합 목록·다른 루트/하위 경로/공백 사례, 추가·제거 총 16건 통과. 실제 환경 변수 읽기·쓰기를 합성 값·결과 변수로 대체했다. 사용자 PATH는 변경하지 않았다. |
| 검사 스크립트 원문의 메모리 모의 검사 | 정상 목록·비밀 이름 포함·일반 파일 누락에서 각각 0·1·2 판정을 확인했다. 부분 파일 쓰기 실패·Docker build 실패·Docker run 실패는 성공으로 처리되지 않고 정리 경로에 들어갔다. 총 6건 통과. 파일 명령과 Docker를 메모리 함수로 대체하고 마지막 exit는 결과 변수에 저장했다. 실제 Docker 패턴 처리나 디스크 정리의 시험은 아니다. |
| `claude plugin --help`, `claude plugin marketplace add --help`, `claude plugin install --help`, `claude plugin uninstall --help`, `claude plugin marketplace remove --help`, `claude plugin list --help` | 안내서가 쓰는 하위 명령과 `local`·`user` 범위를 확인했다. 설정을 바꾸는 명령은 실행하지 않았다. |
| `git show work/sessions-cli:apps/agent/cmd/zm-baton/main.go` 및 관련 session 소스 읽기 | 대조한 브랜치 커밋은 `559df1377bbcf92864fe2e4ccd045af2b899829b`. 위 설치·해제 출력 및 실패 안내를 확인했다. CLI 자체를 빌드·실행한 결과는 아니다. |
| `npm test -- --no-cache --configLoader native --pool threads` (`apps/web`) | 3개 파일, 27개 시험 통과, 실패 0. |
| `npm run typecheck` (`apps/web`) | 종료 코드 0. |
| `docker version` | 클라이언트만 확인됐다. 엔진 접근은 `permission denied while trying to connect to the docker API at npipe:////./pipe/dockerDesktopLinuxEngine`로 거부됐다. `check-build-context.ps1`의 실제 Docker 검사는 실행하지 않았다. |
| `gh pr view 13 --repo hanumoka/zm-baton --json number,title,body,headRefOid,url,comments` | `proxyconnect tcp: dial tcp 127.0.0.1:9` 연결 거부로 실패했다. PR 본문·소유자 결정·CI 확인에 성공했다고 계산하지 않는다. |

### 고도화 후보

- **H-05 유지:** 2차 B2와 「필요한 빌드 입력만 보내기」 부채 후보는 같은 원인의 빌드 위생 문제다. v0.5에서는 고도화 후보로 함께 분류한다. `af26417:docs/hardening-backlog.md`에 이미 등록돼 있으므로 중복 항목은 필요 없다. 이미지를 레지스트리에 올리거나 다른 PC에 배포하기 전에 필요한 입력만 복사하는 방식으로 보완하고 거부 시험을 맞춘다. 현재 전제에서 병합을 막지 않는다.
- **H-02 유지:** 안내서는 드문 로컬 해제 실패의 한계를 설명하도록 고쳐졌지만 CLI의 결과 구분 문제는 남는다. 이미 등록된 CLI 고도화 항목이며 이번 PR의 새 차단 지적이 아니다.
- **H-06:** N3 수정은 확인했다. #13 병합 전이므로 기존 목록의 병합 대기 상태를 유지하면 된다. 새로 추가할 고도화 후보는 없다.

### 부채 후보

- **2차의 웹 상호작용 시험 부채 유지:** `apps/web/src/firstScreen.test.tsx:10`은 상태 함수와 정적 HTML 중심이다. 실제 Shell에서 출처별 실패·회복, 탭 전환 중 늦은 응답, 닫기 후 재실패, 키보드 초점을 함께 확인하는 시험은 여전히 없다. 현재 기능 결함을 재현한 지적이 아니므로 병합을 막지 않는 검증 부채 후보다. 최종 부채 판정은 소유자가 한다.
- 2차의 빌드 입력 선택 부채는 H-05로 재분류했으며 중복 부채로 남기지 않는다.

### 확인하지 않은 것

- Docker 컨텍스트·빌드 캐시·최종 이미지, 실제 임시 파일 생성·정리, Ctrl+C·강제 종료·동시 실행 시험은 하지 않았다. 작성자가 보고한 「이전 ignore에서는 비밀 이름 10개 유입, 새 ignore에서는 일반 파일만 유입·종료 코드 0」은 작성자 보고이며 독립 재현 결과가 아니다.
- 플러그인의 실제 추가·설치·해제·실행 파일 이동, 기존 Claude Code 설정 보존, 실제 훅 호출·세션 등록·서버 통합 동작은 시험하지 않았다. 안내서와 CLI 출력·코드·공식 문서의 대조로 범위를 한정했다. #12 전체를 승인한 것은 아니다.
- 사용자 PATH·설정·실행 파일·Docker 볼륨을 변경하지 않았다. 실제 브라우저 화면·알림·키보드 접근성, 제품 전체 빌드, macOS·Linux·Windows ARM64는 재시험하지 않았다.
- 비공개 설계 원문, 실제 비밀 파일과 공급자 로그인 정보는 열지 않았다. 이번 수락 범위는 검토 요청과 공개 안내서를 기준으로 판단했다.
- 지정한 `docs/reviews/2026-10-13-work-sessions-web-codex-3.md`만 새로 작성했다. 제품 파일 수정, 커밋·push·병합은 하지 않았다.
