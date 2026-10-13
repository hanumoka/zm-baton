## AI 검토 결과

- 적용한 기준: zm-baton 검토 기준 v0.4. 기준 문서는 `c0341ff25bfc70b923d98c7d77c26c7b077bfbdd:docs/review-standard.md`다.
- 검토자: Codex CLI 0.162.0 / GPT-6(세부 모델 식별자는 제공되지 않음) / 세션 `01a12ef5-1eff-7d23-b620-d25b6a836994` / 2차 독립 검토자.
- 작성자: Claude Code / 세션 `409394de-ef95-46af-bf81-42c556a8b817`(검토 요청에서 제공). 검토 세션 번호는 `CODEX_SESSION_ID`와 `CODEX_THREAD_ID`로 확인했다. 실행기와 세션이 모두 다르며, 작성 대화 대신 검토 지시와 저장소 자료를 받았다.
- 검토한 커밋: `a741a6593574659ed918001538805675b831727c`(`work/sessions-web`). 기준과 공통 조상은 `c0341ff25bfc70b923d98c7d77c26c7b077bfbdd`다. 이번 검토의 중심은 `git diff 3eef7b1 HEAD`의 변경 파일 8개다.
- 판정: **병합 막음**. 기존 지적의 지정된 재현 조건은 수정됐지만, 새로 확인한 비밀 설정 파일의 빌드 유입 B2가 남는다. N3·N4는 비차단 지적이다.
- 소유자 호출 조건: **해당, 조건 2** — `deploy/compose/compose.yaml`, `deploy/compose/server.Dockerfile`, `deploy/compose/server.Dockerfile.dockerignore`, `deploy/compose/check-build-context.ps1`.
- 소유자 호출 근거: **확인 못 함**. GitHub 연결이 거부되어 PR 본문과 결정 댓글을 읽지 못했다. 호출 누락을 0건으로 계산하지 않는다. 병합 전 소유자 결정 댓글이 필요하다. 조건 3은 해당하지 않으며, 조건 4·5의 이견·면제 시도는 확인되지 않았다.

### 1차 지적의 처리

| 번호 | 판정 | 위치와 근거 |
|---|---|---|
| B1 | 해결 — 기존 `.env.*` 누락 범위 | `deploy/compose/server.Dockerfile.dockerignore:13`, `deploy/compose/server.Dockerfile.dockerignore:14`에서 `.env`와 변형을 함께 제외한다. `deploy/compose/check-build-context.ps1:18`에는 중첩 경로를 포함한 합성 사례가 있고, `:48`에서는 일반 파일 하나가 실제 도착했는지도 요구한다. Docker 실제 실행은 아래 사유로 재현하지 못했다. 다른 비밀 설정 이름의 누락은 B2로 별도 기록한다. |
| N1 | 해결 | `apps/web/src/problems.ts:14`, `:18`과 `apps/web/src/Shell.tsx:56`, `:72`, `:110`에서 출처별 실패를 저장하고 해당 출처만 해제한다. 수신함 조회 성공이 세션 조회·공지 판정 실패를 지우던 경로는 없어졌다. `Shell.tsx:133`의 닫기는 실제 버튼이다. |
| N2 | 해결 | `docs/stage1/sessions.md:59`에서 `cd $repo`로 복귀하며, `:100`, `:111`의 Compose 명령은 저장소 절대 경로를 쓴다. `apps/agent`에서도 새 Compose 경로의 `Test-Path`가 True였다. PATH 제거식의 별도 문제는 N4다. |

### 새 지적

| 번호 | 위치 | 검토 항목 | 근거 | 확신 | 병합 막음 |
|---|---|---|---|---|---|
| B2 · 높음(P1) | `deploy/compose/server.Dockerfile.dockerignore:13`, `:20`; `deploy/compose/server.Dockerfile:8`, `:13`; `deploy/compose/check-build-context.ps1:17` | 5. 비밀값 / 7. 거부 시험 | `apps/server/gradle.properties`, `apps/server/http-client.private.env.json`, `apps/web/http-client.private.env.json`은 현재 제외 패턴 어느 것에도 걸리지 않는다. 개발자가 소스 옆에 이 파일들로 저장소 비밀번호·API 토큰을 두면 디렉터리 전체를 복사하는 두 COPY 경로를 통해 빌드 단계와 캐시에 들어간다. 새 검사도 이 이름들을 심지 않아 통과할 수 있으며, 검사 자체의 `COPY . /ctx`도 같은 누락을 갖는다. Docker에 전달할 소스를 제한하거나 로컬 비밀 설정 제외 규칙을 보완하고, 이 이름들의 합성 거부 사례를 추가해야 한다. 실제 비밀 파일을 열거나 유출을 확인했다는 뜻은 아니다. | 높음: 전체 패턴·COPY 경로와 공식 파일 용도를 대조. Docker 실제 실행은 미확인 | 예 |
| N3 · 보통(P2) | `deploy/compose/check-build-context.ps1:30`, `:31`, `:32`, `:59` | 7. 시험 안전성 | 정리 대상 등록이 `Set-Content` 뒤에 있다. 파일을 만든 뒤 쓰기가 실패하면 그 파일은 `$planted`에 없어 finally에서도 남는다. 또한 `Test-Path`와 쓰기가 분리되어, 그 사이 다른 프로세스가 만든 파일은 덮어쓰고 finally에서 삭제할 수 있다. 원문 루프의 메모리 모의 검사에서 두 경로를 각각 재현했다. 실행 전에 이미 있던 파일을 발견하는 일반 경로는 보존됐다. 가능하면 별도의 합성 컨텍스트에서 검사하고, 작업 폴더에 써야 한다면 배타적 생성과 실패 시 정리·소유 확인을 함께 다뤄야 한다. | 높음: 코드와 명시적 실패·동시 생성 주입으로 확인. 실제 중단 시험은 아님 | 아니오 |
| N4 · 보통(P2) | `docs/stage1/sessions.md:118` | 12. 정리 절차 | `-notlike '*zm-baton\bin*'`은 설치한 항목 이외의 경로도 제거한다. 합성 PATH에 설치 경로와 `C:\tools\zm-baton\binary-tools`를 함께 넣으면 둘 다 사라졌다. 문자열 배열에 대한 `-notlike` 사용 자체는 올바르지만, 비교 범위가 넓다. 추가한 `$env:LOCALAPPDATA\zm-baton\bin` 항목과 정규화한 경로가 같은 경우만 제거해야 한다. | 높음: 실제 PowerShell 식에 합성 문자열을 넣어 재현 | 아니오 |

B2의 파일 용도는 [Gradle 자격 증명 문서](https://docs.gradle.org/current/userguide/supported_repository_protocols.html)와 [IntelliJ HTTP Client 비공개 환경 파일 문서](https://www.jetbrains.com/help/idea/http-client-variables.html)로 확인했다. 제외 파일의 우선순위와 패턴 의미는 [Docker 공식 문서](https://docs.docker.com/build/concepts/context/#dockerignore-files)와 대조했다. 파일명·복사 경로에 근거한 판단이며, 임의의 모든 파일 내용이 안전하다는 보증을 요구하는 지적은 아니다.

### 확인한 것

- React 상태: 함수형 상태 갱신이 다른 출처의 실패를 보존한다. `Shell.tsx:65`는 세션 탭을 떠날 때 세션 실패를 명시적으로 해제한다. `usePoll.ts:24`, `:31`의 중단 표시·abort와 `Shell.tsx:70`의 생존 검사가 이전 세션 조회의 늦은 실패·성공 반영을 막는다. 탭을 다시 열면 즉시 새 조회를 시작한다.
- 공지 판정 실패는 다른 조회의 성공으로 사라지지 않고, 판정 성공 또는 사용자의 닫기로 해제된다. 탭 이동 후에도 판정 실패가 남을 수 있지만 실패 이력을 사용자가 닫을 수 있으며, 이를 새 차단 결함으로 보지 않았다. 닫은 뒤 다음 실패가 발생하면 다시 표시되는 동작은 주석과 일치한다.
- 접근성: 오류는 `role="alert"` 안의 목록으로 표시되며 닫기는 이름이 있는 네이티브 버튼이다. 정적 렌더 시험도 목록과 버튼을 확인한다. 실제 키보드 초점 복귀·화면 읽기 도구 동작은 확인하지 않았다.
- 검사 스크립트: 기존 파일을 발견하면 그 파일을 쓰거나 정리 목록에 넣지 않고 중단한다. 앞서 심은 파일은 finally에서 정리한다. 일반 파일이 없으면 성공하지 않는 방어도 있다. 일반적인 Ctrl+C에서도 finally가 실행된다는 [PowerShell 규칙](https://learn.microsoft.com/en-us/powershell/module/microsoft.powershell.core/about/about_try_catch_finally#freeing-resources-using-finally)을 확인했다. 따라서 모든 중단에서 정리가 빠진다고 주장하지 않는다. N3의 등록 전 실패 구간과 프로세스 강제 종료는 별개다.
- 안내서: PowerShell 블록 7개 모두 구문 오류가 없다. 기본 설정 위치는 CLI의 `os.UserConfigDir()/zm-baton`과 일치하므로 `:120`의 `Remove-Item -Recurse "$env:AppData/zm-baton"`은 기본 Windows 설치의 설정·세션 파일을 함께 제거하는 명령이다. `:110`에서 훅을 먼저 해제하고 `:119`에서 실행 파일을 지우는 순서도 맞다. 실제 PATH·설정·훅은 변경하지 않았다.
- CLI `cf7b883cc68827f8337e9bb30d207024fc6da029`의 옵션 파싱·공유 상태 저장·알림 문구 필터·설정 변경 감지·off 재전송 코드와 안내서를 대조했다. 옵션을 요약 앞에 두는 예시, 이후 요약에도 유지되는 비공유 설정과 `--share`, 알림 문구의 제한된 예외, 두 실패 대응 설명이 코드와 맞는다. 서버 `37f231e4e097f74939104f171a51b47b184603dd`의 토큰 12시간 만료와 종료 처리도 읽었다. 두 별도 PR 전체를 승인한 것은 아니다.
- 검토 항목 2·3·4: 이번 차이에 API 계약·권한·상태 전이 변경은 없고, 폴링 취소 경로는 위 범위에서 확인했다. 항목 8: 이번 변경 파일의 최대 크기는 143줄이다. 항목 9·10: npm 의존성 변경은 없고 새 시험용 기반 이미지는 `busybox:1.37`이다. 제삼자 소스 반입은 보이지 않지만 기반 이미지 재배포 조건을 전부 조사하지는 않았다.
- 변경 종류는 일반 코드 / 보안 / 배포 설정 / 일반 문서 / 시험용 이미지 의존성이다. 소유자 호출 경로는 기준의 공통 조상·`git diff --no-renames --name-only` 방식으로 확인했다. 핵심 모듈 6의 「기록 미작성」 등록은 별도 서버 브랜치에 있다. 안내서가 명시한 세 PR 반영 전제는 유지해야 한다.

### 검증 결과

환경: Windows NT 10.0.26300.0 / PowerShell 7.6.6 / Node.js 24.16.0 / npm 11.13.0 / TypeScript 7.0.2 / React 19.3.0 / Vite 8.3.2 / Vitest 5.0.3 / Git 2.53.0.windows.2 / Docker CLI 29.8.2 / Docker Compose 5.5.1. 대상은 위 HEAD다.

| 실행 명령 또는 검사 | 실제 결과 |
|---|---|
| `npm test -- --no-cache --configLoader native --pool threads` (`apps/web`) | 3개 파일, 27개 시험 통과, 실패 0. |
| `npm run typecheck` (`apps/web`) | 종료 코드 0. |
| `node --input-type=module -e 'import { build } from "vite"; const r = await build({ configLoader: "native", build: { write: false } }); console.log("in-memory build output files:", r.output.length);'` (`apps/web`) | 종료 코드 0, 26개 모듈 변환, 출력 3개. 파일을 쓰지 않는 빌드이며 `npm run build` 원문 실행 결과는 아니다. |
| `[System.Management.Automation.Language.Parser]::ParseInput` | 안내서 PowerShell 블록 7개와 검사 스크립트 모두 구문 오류 0. |
| 검사 스크립트 원문 생성·정리 루프의 메모리 모의 검사 | 기존 파일 사례: 기존 합성 내용 보존, 앞서 심은 파일 제거. 생성 후 쓰기 실패 사례: 파일 1개 잔류. 확인과 쓰기 사이 생성 사례: 기존 합성 내용 덮어쓰기 후 삭제. 파일 명령을 메모리 함수로 대체했으며 실제 파일·Docker를 사용하지 않았다. |
| 안내서 PATH 제거식에 합성 경로 목록 적용 | 설치 경로와 별도의 `binary-tools` 경로가 함께 제거되어 N4 재현. 사용자 PATH는 바꾸지 않았다. |
| `apps/agent`에서 `Test-Path "$repo/deploy/compose/compose.yaml"` | True. |
| `git diff --check c0341ff HEAD` | 종료 코드 0. `Inbox.tsx` 끝의 빈 줄 경고도 해소됐다. |
| `docker version` | 종료 코드 1. 클라이언트 버전은 확인했지만 엔진은 `permission denied while trying to connect to the docker API at npipe:////./pipe/dockerDesktopLinuxEngine`로 거부됐다. 따라서 `check-build-context.ps1`의 실제 Docker 실행은 하지 못했다. |
| `gh pr view work/sessions-web --repo hanumoka/zm-baton --json number,title,body,headRefOid,url` | `proxyconnect tcp: dial tcp 127.0.0.1:9` 연결 거부로 실패. PR 필수 항목·소유자 결정·CI 확인 불가. |

### 부채 후보

- 새 오류 시험은 상태 함수와 정적 HTML 중심이다. 실제 Shell에서 출처별 실패·회복, 탭 이동 중 늦은 응답, 닫기 후 재실패, 버튼의 키보드 초점을 함께 확인하는 상호작용 시험이 있으면 회귀를 더 잘 잡을 수 있다.
- 로컬 작업 폴더를 통째로 보내면서 비밀 파일 이름을 계속 추가하는 방식은 누락 위험을 남긴다. 제품 빌드와 검사에서 필요한 입력만 보내는 방식의 검토가 필요하다. 부채의 최종 판정은 소유자 몫이다.

### 확인하지 않은 것

- Docker 실제 컨텍스트·빌드 캐시·최종 이미지, 세 PR 통합 실행, 실제 파일을 심은 검사, Ctrl+C·프로세스 강제 종료 시험은 실행하지 않았다. N3의 모의 검사를 이 시험들의 통과로 계산하지 않는다.
- 실제 브라우저의 1280·390px 화면, 서버 중단 후 오류 목록, 닫기 클릭·키보드·화면 읽기 도구·OS 알림은 재실행하지 않았다.
- 안내서의 실제 설치·훅 해제·설정 폴더 삭제·PATH 변경·볼륨 삭제, macOS·Linux·Windows ARM64는 시험하지 않았다. 비공개 설계 원문과 실제 비밀 파일·공급자 로그인 정보도 열지 않았다.
- 작성자가 보고한 `npm test` 27개, `npm run build`, 컨텍스트 검사 종료 코드 0, Docker 통합 22개, Chrome 화면·오류 목록 검사는 작성자 보고다. 독립 재현한 범위는 위 표뿐이다.
- 지정된 이 검토 기록만 새로 작성했다. 제품 파일 수정, 커밋·push·병합은 하지 않았다.
