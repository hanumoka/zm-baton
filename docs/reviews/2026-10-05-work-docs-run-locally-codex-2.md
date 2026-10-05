# 로컬 실행 문서 수정분 독립 검토

판정은 **병합 가능**이다. 병합 막음 지적은 **0건**이다. 앞선 R1~R3은 해소됐다. 새 비막음 지적은 1건이다.

## 검토자와 대상

- 검토자 실행기: Codex, `codex-cli 0.160.0`.
- 모델: GPT-6 기반이다. 정확한 모델 식별자는 확인하지 못했다.
- 역할: 독립 검토자다. 구현은 하지 않았다.
- 검토 세션: `01a10c1f-1962-7ff2-93f2-42d1c311f5c1`이다. `CODEX_THREAD_ID` 환경변수에서 확인했다.
- 작성자: Claude Code 세션 `893848f2-e1a3-4077-8809-4ef52b6cf2f2`다. 사용자 지시서에서 받았다.
- 독립성 근거: 작성자와 실행기가 다르다. 세션 번호도 다르다. 작성 대화 대신 검토 지시서와 저장소 자료를 받았다.
- 대상 브랜치: `work/docs-run-locally`.
- 검토한 커밋: `3edbad4e03c6b70e60d1bf2f8b77d7f2dde7114a` (`3edbad4`). 로컬 HEAD와 대상 브랜치가 이 커밋을 가리킨다.
- 주 검토 범위: `git diff 0b7faf2..3edbad4`의 실행 안내 수정이다.
- 보조 검토 범위: `main..work/docs-run-locally`의 README, 실행 안내, 호환 시험 결과, 앞선 검토 기록이다. 설정 이름과 동작을 관련 소스로 대조했다.
- 적용 기준: `docs/review-standard.md`의 **검토 기준 v0.3**, `AGENTS.md`, 사용자 검토 지시서다.
- 변경 종류: 일반 문서다. 핵심 모듈 구현의 첫 병합은 아니다.

## 앞선 지적별 처리 결과

### R1. 시작 폴더와 사용할 창 — 해소

- 위치: `docs/stage1/run-locally.md:25`, `:48`, `:59`, `:67`, `:85`, `:99`, `:128`, `:136`.
- 확신: 높음. 병합 막음: 아니오.
- 폴더에 의존하는 실행 블록은 모두 `$repo`를 기준으로 이동한다. 정리 명령과 재현 시험 표에도 같은 규칙이 적용됐다.
- 창 A는 DB 준비와 Web 빌드 뒤 서버를 유지한다. 창 B는 선택 단계인 데스크톱을 유지한다. 창 C는 상태 확인 뒤 연결 프로그램을 실행한다. 각 단계와 창 배정이 맞는다.
- 문자 그대로 모든 블록이 `Set-Location`으로 시작하는 것은 아니다. `$repo` 대입 블록과 77행의 상태 조회는 예외다. 두 블록은 현재 폴더에 의존하지 않으므로 앞선 실행 불능 문제를 되살리지 않는다.
- 35행은 「폴더에 의존하는 명령 묶음은 `$repo` 기준으로 이동한다」로 다듬을 수 있다. 이는 문구 개선이며 병합 조건은 아니다.

### R2. 포트 충돌 시 무관한 프로세스 종료 — 해소

- 위치: `docs/stage1/run-locally.md:118`.
- 확신: 높음. 병합 막음: 아니오.
- 이 절차에서 띄운 서버만 해당 창에서 Ctrl+C로 종료하도록 바뀌었다. 정체를 모르는 프로세스는 끄지 말라고 명시했다. 임의 PID 종료 안내는 제거됐다.
- 서버 설정 `ZM_BATON_SERVER_PORT`는 `apps/server/src/main/resources/application.properties:3`과 일치한다.
- 연결 프로그램 옵션 `--server`는 `apps/agent/cmd/zm-baton-agent/main.go:24`와 일치한다.
- 데스크톱 설정 `ZM_BATON_SERVER_URL`은 `apps/desktop/src/main.ts:7`과 일치한다. 이 값은 시작할 때 읽는다.
- 대체 포트에서 확인 주소도 맞추는 안내는 아래 R4로 남겼다. 앞선 프로세스 종료 안전 문제는 해소됐다.

### R3. 작업 폴더 오류 원인 단정 — 해소

- 위치: `docs/stage1/run-locally.md:120`.
- 확신: 높음. 병합 막음: 아니오.
- 오류 상세를 먼저 읽도록 바뀌었다. 없는 폴더와 파일 경로도 실패 원인으로 설명한다.
- `apps/agent/internal/agent/workdir.go:27`의 검사와 맞는다. `TempDirs`가 검사하는 `TMP`, `TEMP`, `TMPDIR`도 명시했다.
- 빈 입력과 경로 해석 실패를 모두 열거하지는 않는다. 오류 상세를 읽으라는 안내가 있으므로 원인을 임시 폴더 하나로 단정하던 문제는 없다.

## 새 지적

### R4. 대체 포트를 쓰는 경우 확인 주소도 함께 안내하면 좋다

- 위치: `docs/stage1/run-locally.md:118`. 관련 위치는 77·80·122행이다.
- 검토 항목: 12번 문서의 실패 대응이다.
- 확신: 높음.
- 병합 막음: 아니오.
- 근거: 대체 포트 안내는 서버와 두 클라이언트 설정을 바꾼다. 상태 조회와 브라우저 주소는 여전히 기본 포트를 가리킨다. 기다림 문제의 대응도 기본값인지 확인하라고 적혀 있다.
- 영향: 대체 포트를 고른 독자가 기본 주소로 상태를 확인하면 다른 서비스의 응답을 보거나 연결 오류를 볼 수 있다.
- 고칠 방향: 상태 조회와 브라우저 주소에도 선택한 포트를 적용한다고 적는다. 기다림 문제는 연결 프로그램과 화면이 같은 서버 주소를 쓰는지 확인하도록 바꾼다. 데스크톱 환경변수는 창 B에서 `npm start` 전에 설정한다고 명시하면 더 분명하다.
- 비막음 이유: 대체 주소와 실제 설정 이름은 정확하다. 선택한 주소로 확인할 수 있다. 기본 실행 순서에는 영향이 없다.

## 추가 확인 결과

- `.env` 보존 안내는 이 Compose 구성에 맞는다. 49행은 파일이 없을 때만 예제를 복사한다. `deploy/compose/compose.yaml`은 비밀번호를 `POSTGRES_PASSWORD`로 전달한다. 데이터는 명명된 볼륨에 남는다.
- 기존 DB에서 `.env`만 바꾸어도 DB 비밀번호가 갱신되지는 않는다. 공식 PostgreSQL 17 이미지의 진입점은 기존 데이터가 있으면 초기화를 건너뛴다. 따라서 새 값을 서버에 전달하면 인증이 실패할 수 있다. 「볼륨을 처음 만들 때」는 이 절차에서 빈 데이터 디렉터리를 초기화하는 시점을 뜻한다. [공식 이미지 진입점](https://raw.githubusercontent.com/docker-library/postgres/master/17/bookworm/docker-entrypoint.sh)을 대조했다.
- PATH 안내의 취지는 맞다. 기존 프로세스의 환경은 시스템 설정 변경만으로 자동 갱신되지 않는다. 새 창도 오래된 부모 프로세스에서 시작하면 옛 환경을 받을 수 있다. 이 경우 터미널 앱도 다시 시작해야 한다. 모든 설치에서 `go`를 반드시 찾지 못한다는 뜻으로 읽어서는 안 된다. [PowerShell 환경변수 설명](https://learn.microsoft.com/en-us/powershell/module/microsoft.powershell.core/about/about_environment_variables?view=powershell-7.5)을 참고했다.
- `$repo`의 `/` 구분자는 문서의 Windows 드라이브 경로 예시와 맞는다. `file:$repo/apps/web/dist/`는 서버가 받는 `spring.web.resources.static-locations` 설정으로 전달된다. 끝의 `/` 안내도 유지됐다. 모든 종류의 경로를 지원한다고 확대 해석하지 않았다.
- 실행 안내는 전제조건, 단계, 완료 확인, 실패 대응을 갖췄다. 정리 절도 있다.
- 5행은 백그라운드 서버와 API 호출로 대체한 부분을 밝힌다. 작성자의 실행 보고로만 취급했다. 화면 버튼과 창 전환을 독립 검증한 근거로 취급하지 않았다.
- 변경 문서에서 실제 비밀값, 소유자 PC의 실제 절대 경로, 사설 IP 주소, 회사 정보, 비공개 설계 본문을 발견하지 못했다. 경로 예시와 루프백 주소는 있다.
- 검토 기준 항목 1·4·5·6·7·8·12는 절차, 종료 안전, 공개 정보, 검증 주장과 문서 크기를 중심으로 확인했다. 변경된 네 파일은 모두 1,000행 미만이다.
- 항목 2·3·9·10의 계약 변경, 권한 구현 변경, 의존성 추가, 제삼자 코드 도입은 해당 없음이다. 기존 구현 전체의 안전성을 다시 판정한 것은 아니다.
- 항목 11의 별도 부채 후보는 없다. 문서 개선 후보는 R4에 남겼다.

## 실행한 명령과 결과

검토 환경은 Windows PowerShell `5.1.26100.9549`다. Git은 `2.50.1.windows.1`이다. Codex CLI는 `0.160.0`이다.

| 명령 또는 작업 | 결과 |
|---|---|
| `git status --short` | 검토 시작 시 출력 없음 |
| `git branch --show-current` | `work/docs-run-locally` |
| `git rev-parse HEAD`, `git rev-parse work/docs-run-locally` | 모두 위 대상 커밋과 일치 |
| `git diff --name-only 543d17f..0b7faf2` | 앞선 검토 기록만 변경 |
| `git diff --name-only 0b7faf2..3edbad4` | `docs/stage1/run-locally.md`만 변경 |
| `git diff 0b7faf2..3edbad4` | 수정분 전체 확인 |
| `git diff main..work/docs-run-locally -- README.md docs/stage1/compat-test-results.md` | 나머지 제품 문서 변경 확인 |
| `git diff --stat main..work/docs-run-locally`, `git diff --numstat main..work/docs-run-locally` | 4파일, 추가 258행, 삭제 7행 |
| `git diff --check main..work/docs-run-locally` | 출력 없음, 종료 코드 0 |
| `git diff --quiet HEAD` | 기록 작성 전 종료 코드 0 |
| `git check-ignore deploy/compose/.env` | 해당 파일이 무시됨 |
| `Get-Content -Encoding utf8`, `Select-Object`, `rg -n` | 위 문서와 설정 및 소스 대조 |
| 변경된 네 파일의 `Get-Content` 행 수 확인 | 각각 33·139·300·107행 |
| 초기 기본 인코딩의 `Get-Content` | 한글 표시가 깨졌다. UTF-8로 다시 읽었다 |
| 초기 복합 패턴의 `rg` | 기대한 검색 결과가 나오지 않았다. `-e`별 검색으로 다시 확인했다 |
| `codex --version`, `git --version`, `$PSVersionTable.PSVersion.ToString()` | 위 버전 확인 |
| `CODEX_THREAD_ID` 환경변수 조회 | 위 검토 세션 번호 확인 |
| 공개 공식 자료 조회 | PostgreSQL 이미지 초기화와 PowerShell 환경 설명 대조 |

## 확인하지 않은 것

- 서버, DB, Electron, 실행기를 띄우지 않았다. 빌드와 Gradle·Go 시험도 실행하지 않았다.
- 작성자의 종료 코드 0, `ended/submitted`, 파일 첫 줄 일치 보고는 독립 재현하지 않았다.
- 대체 포트 연결을 실제로 시험하지 않았다. PATH 갱신과 `.env` 보존도 실행으로 재현하지 않았다.
- 특수문자 경로와 UNC 경로에서 `file:` 자원이 열리는지는 시험하지 않았다.
- 원격 PR 본문의 일곱 항목, 현재 원격 head, CI 상태와 로그는 이번 검토에서 조회하지 않았다. 병합 직전 확인은 별도로 필요하다.
- 비공개 설계 원문과 공급자 로그인 정보 파일은 열지 않았다. 실제 `.env` 내용도 열지 않았다.
- 이 기록은 문서 수정분에 대한 독립 검토다. 기존 구현 전체와 앞선 호환 시험 결과를 다시 검증한 기록은 아니다.

지정한 검토 기록 파일만 작성했다. 브랜치 생성, 커밋, push, 원격 변경, main 변경, 병합은 하지 않았다.
