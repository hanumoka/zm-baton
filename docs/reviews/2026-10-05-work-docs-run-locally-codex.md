# 로컬 실행 문서 독립 검토

판정은 **병합 막음**이다. 병합 막음 지적은 **2건**이다.

## 검토자와 대상

- 검토자 실행기: Codex, `codex-cli 0.160.0`.
- 모델: GPT-6 계열이다. 정확한 모델 식별자는 확인하지 못했다.
- 역할: 독립 검토자다. 구현은 하지 않았다.
- 검토 세션: `01a10c18-df28-7222-9c1e-31cae81eadf9`다. `CODEX_THREAD_ID` 환경변수에서 확인했다.
- 작성자: Claude Code 세션 `893848f2-e1a3-4077-8809-4ef52b6cf2f2`다. 사용자 지시서에서 받았다.
- 독립성 근거: 실행기가 다르다. 세션 번호도 다르다. 작성 대화가 아닌 검토 지시서와 저장소 자료를 받아 검토했다.
- 대상 브랜치: `work/docs-run-locally`.
- 검토한 커밋: `543d17f17b3c3c9436a357041dbebf876dc564bc` (`543d17f`).
- 범위: `git diff main..work/docs-run-locally`의 세 문서다. 명령의 근거가 되는 설정과 소스도 읽었다.
- 변경 종류: 일반 문서다. 핵심 모듈 구현의 첫 병합은 아니다.
- 적용 기준: `docs/review-standard.md`의 **검토 기준 v0.3**, `AGENTS.md`, 사용자 검토 지시서다.

## 지적

### R1. 단계마다 시작 폴더와 사용할 창을 명시해야 한다

- 위치: `docs/stage1/run-locally.md:40`. 같은 문제는 48·66·80·117~119행에도 있다.
- 검토 항목: 1번 수락 조건, 12번 문서 절차다.
- 확신: 높음.
- 병합 막음: 예.
- 근거: 1단계의 `cd deploy/compose` 뒤에 루트로 돌아가는 명령이 없다. 2단계의 `cd apps/web`은 `deploy/compose/apps/web`을 가리킨다. 그 경로는 없다. 2단계를 따로 바로잡아도 다음 `cd apps/server`은 `apps/web/apps/server`을 가리킨다. 그 경로도 없다. 문서는 각 블록을 저장소 루트에서 시작하라고 명시하지 않는다. 서버와 Electron은 전경 실행이므로 후속 단계에 사용할 창도 구분해야 한다.
- 영향: 문서 순서대로 실행하면 Web 빌드 단계부터 진행할 수 없다.
- 고칠 방향: 모든 명령 블록의 시작 위치를 저장소 루트로 명시한다. 단계 전환에 루트 복귀 명령을 넣거나 루트를 기준으로 이동하도록 한다. 서버를 유지한 채 데스크톱과 연결 프로그램을 실행할 별도 창도 명시한다. 재현 시험 표에도 같은 시작 위치 규칙을 적용한다.

### R2. 포트 충돌만으로 다른 프로세스를 종료하도록 안내하면 안 된다

- 위치: `docs/stage1/run-locally.md:99`.
- 검토 항목: 4번 실행기 관리, 12번 실패 대응이다.
- 확신: 높음.
- 병합 막음: 예.
- 근거: 문서는 포트를 점유한 다른 프로세스의 PID를 확인한 뒤 끄라고 안내한다. `AGENTS.md` 6절 3항은 직접 띄운 PID와 그 하위 프로세스만 종료하도록 제한한다. 같은 조항은 종료 전에 명령줄 표식을 확인하도록 요구한다. PID 확인만으로는 두 조건을 충족하지 않는다.
- 영향: 문서를 따르면 이 작업과 무관한 프로세스를 종료할 수 있다.
- 고칠 방향: 이 절차에서 직접 띄운 서버인지 확인한 경우 해당 서버 창에서 Ctrl+C로 종료하도록 안내한다. 소유 관계를 확인하지 못한 프로세스는 종료하지 않도록 한다. PID로 종료하는 안내를 유지한다면 명령줄 표식 확인 조건도 적는다.

### R3. 작업 폴더 오류의 원인을 임시 폴더 하나로 단정하지 않는 편이 좋다

- 위치: `docs/stage1/run-locally.md:101`.
- 검토 항목: 12번 실패 대응이다.
- 확신: 높음.
- 병합 막음: 아니오.
- 근거: `apps/agent/internal/agent/workdir.go`의 `CheckWorkdir`는 빈 값과 없는 경로도 거부한다. 디렉터리가 아닌 파일도 거부한다. 임시 폴더 검사는 그 뒤에 수행한다.
- 고칠 방향: 오류 상세를 확인하도록 안내한다. 폴더의 존재 여부와 임시 폴더 포함 여부를 각각 확인하도록 적는다.

## 확인한 것

- 변경은 README, 실행 안내, 호환 시험 결과의 세 문서에 한정된다.
- 실행 안내에는 전제조건, 단계, 완료 확인, 실패 대응, 정리 절이 있다. 단계 연결의 결함은 R1에 적었다.
- Compose 프로젝트 이름은 `zm-baton-dev`다. 서비스 이름은 `postgres`다. 이미지와 포트는 `postgres:17`, `127.0.0.1:15432:5432`다. 비밀번호 변수는 `ZM_BATON_DB_PASSWORD`다. 자료는 명명된 볼륨에 남는다.
- `.env.example` 파일이 있다. `.env`는 Git 무시 규칙에 해당한다. 변경 문서는 실제 비밀번호를 문서에 기록하도록 요구하지 않는다. 서버에 넘기는 값은 자리표시자다.
- Web의 `build` 스크립트가 있다. 데스크톱의 `start` 스크립트는 빌드 뒤 Electron을 실행한다. 두 앱에 잠금 파일 경로가 CI 설정으로 지정돼 있다.
- 서버의 `gradlew.bat`가 있다. 프로젝트 이름 `server`와 버전 `0.0.1-SNAPSHOT`은 문서의 JAR 이름과 맞는다. JDK 도구 체인은 25다.
- 서버 기본 포트는 18081이다. 기본 바인딩 주소는 루프백이다. DB 기본 포트는 15432다. `ZM_BATON_DB_PASSWORD`와 `ZM_BATON_WEB_DIR` 이름은 설정과 맞는다. 정적 자원 위치 설정은 `file:` 자원 위치를 받는다. 문서는 끝의 `/`도 안내한다.
- 에이전트에 `--device-id`, `--workdir`, `--prompt`, `--once`, `--claude` 플래그가 있다. 기본 서버 주소는 문서와 같다. `--once`의 성공 종료 판정도 문서와 맞는다.
- 작업 폴더 검사는 OS 임시 폴더와 `TMP`, `TEMP`, `TMPDIR`을 대상으로 한다. 링크를 해석한 경로를 검사한다. 실행기 경로는 PATH에서 찾는다. `.cmd`와 `.bat`는 거부한다.
- 에이전트는 공식 실행기를 셸 없이 실행한다. 로그인 정보를 읽지 않는다는 설명은 `AGENTS.md` 6절 1항과 일치한다. 로그인 정보 파일은 열지 않았다.
- `*RunServiceTests*concurrent*`에 대응하는 시험은 `RunServiceTests.kt:44`의 `concurrent claims on one run have exactly one winner`다.
- `*RunServiceTests*older generation*`에 대응하는 시험은 같은 파일 89행의 `an older generation report is stored but never changes state`다. 소스에서 두 선택 문자열에 대응하는 메서드는 각각 하나다. Gradle로 선택 결과를 재실행하지는 않았다.
- `go test -v -run TestKill ./internal/proc`에 대응하는 Windows 시험들이 있다. Linux 시험에는 틀린 표식 거부와 맞는 표식 종료 확인이 있다. Linux 시험에는 실행 환경에 따라 건너뛰는 경로도 있다.
- 화면의 작업 생성 버튼과 보고 상태 문구는 Web 소스에 있다. 데스크톱은 서버 주소 밖으로의 이동을 차단한다.
- 로컬 `main` 이력에는 서버 PR #3, 에이전트 PR #4·#5, 화면 PR #6, CI PR #7의 커밋이 있다. README의 구성 설명은 이 이력 및 파일 구성과 맞는다.
- CI 설정은 모든 PR을 대상으로 한다. Go 작업은 Ubuntu와 Windows에서 일반 시험 및 경합 검사를 실행하도록 돼 있다. 설정의 존재를 실제 실행 성공으로 간주하지 않았다.
- README의 설치·호환 시험 완료 서술은 기존 결과 문서의 기록과 부합한다. 결과 문서는 실제 Claude Code의 Linux 실행을 확인하지 않았다고 구분한다. macOS 미시험도 명시한다.
- 변경에서 소유자 PC의 실제 절대 경로나 사설 IP 주소는 발견하지 못했다. 루프백 주소와 경로 자리표시자는 있다.
- 일반 문서 변경이므로 계약 형식 변경, 권한 구현 변경, 의존성 추가, 제삼자 코드 도입은 해당 없음이다. 핵심 모듈 설명·재현 기록을 대신 작성한 변경도 아니다.
- 별도 부채 후보는 제안하지 않는다. 실행 절차의 수정 사항은 위 지적으로 남겼다.

## 실행한 명령과 결과

검토 환경은 Windows PowerShell `5.1.26100.9549`다. Git은 `2.50.1.windows.1`이다. GitHub CLI는 `2.100.0`이다. Codex CLI는 `0.160.0`이다. 빌드나 런타임 시험은 실행하지 않았다.

| 명령 또는 읽기 작업 | 결과 |
|---|---|
| `git status --short` | 검토 시작 시 출력 없음 |
| `git rev-parse HEAD`, `git rev-parse work/docs-run-locally` | 둘 다 검토 대상 전체 커밋과 일치 |
| `git diff --quiet HEAD` | 종료 코드 0 |
| `git diff --stat main..work/docs-run-locally` | 3파일, 추가 132행, 삭제 7행 |
| `git diff main..work/docs-run-locally` | 변경 전체를 읽음 |
| `git diff --check main..work/docs-run-locally` | 출력 없음, 종료 코드 0 |
| `git log main -15 --oneline` | PR #1~#7에 대응하는 로컬 이력 확인 |
| `git check-ignore deploy/compose/.env` | 해당 경로가 무시됨 |
| `Test-Path deploy/compose/apps/web`, `Test-Path apps/web/apps/server` | 모두 `False` |
| `rg --files`, `rg -n`, `Get-Content -Encoding utf8` | 위 설정·소스·시험·문서 대조 |
| `rg`로 `apps/agent/internal/config` 검색 | 경로 없음으로 실패. 실제 경로를 찾아 다시 읽음 |
| `Get-Content`로 `apps/agent/internal/agent/paths.go` 읽기 | 파일 없음으로 실패. `workdir.go`와 `runner.go`를 찾아 다시 읽음 |
| 초기 `Get-Content`로 기준 문서 읽기 | 한글 인코딩이 깨짐. UTF-8로 다시 읽음 |
| `gh pr view work/docs-run-locally --json number,body,headRefOid,state` | 네트워크 접근 제한으로 실패 |
| `gh run view 37310279129 --json conclusion,headSha,event,jobs` | 네트워크 접근 제한으로 실패 |
| `codex --version`, `git --version`, `gh --version`, `$PSVersionTable.PSVersion.ToString()` | 위 도구 버전 확인 |
| `CODEX_THREAD_ID` 환경변수 조회 | 위 검토 세션 번호 확인 |

## 확인하지 않은 것

- 문서 명령으로 서버, DB, Electron, Claude Code를 띄우지 않았다.
- Gradle 시험과 Go 시험을 실행하지 않았다. 작성자가 두 Gradle 선택 문자열로 각 한 시험을 실행했다는 진술은 독립 재현하지 않았다.
- CI 실행 `37310279129`의 실제 로그와 시험별 통과·건너뜀 여부를 확인하지 못했다. 결과 문서의 CI 성공 주장을 독립 검증한 것으로 보지 않는다.
- 원격 PR 본문 일곱 항목과 현재 원격 head를 확인하지 못했다. 대상 커밋의 현재 CI 상태도 확인하지 못했다. 병합 직전 확인을 대신하는 기록이 아니다.
- 소유자 PC에서 기존에 수행한 실제 실행 결과를 다시 재현하지 않았다. 다른 OS에서도 실행하지 않았다.
- 비공개 설계 문서 원문은 열지 않았다. 이번 일반 문서 검토의 로그인 정보·프로세스 종료 기준은 `AGENTS.md`에 명시된 조항을 사용했다.
- 언급한 대조 파일 외의 전체 구현을 재감사하지 않았다. 공급자 로그인 정보 파일과 로컬 `.env`는 열지 않았다.

검토 기록 외의 파일은 수정하지 않았다. 브랜치 생성, 커밋, push, 원격 변경, main 변경, 병합은 하지 않았다.
