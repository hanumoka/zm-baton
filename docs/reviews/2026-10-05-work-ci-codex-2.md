# work/ci 수정 후 독립 검토

판정은 **병합 가능**이다. 병합 막음 지적은 **0건**이다. 앞선 R1과 R2는 해소됐다. 새 결함이나 검사 약화는 발견하지 못했다. 이 판정은 아래 범위의 변경 검토 결과다. 원격 CI 통과와 PR 절차의 최종 확인은 작성 세션이 해야 한다.

## 검토자와 범위

- 적용 기준: `docs/review-standard.md`의 검토 기준 v0.3과 `AGENTS.md`.
- 실행기와 버전: Codex, `codex-cli 0.160.0`.
- 모델: 세션 지침상 GPT-6 계열이다. 정확한 배포 모델 식별자는 확인하지 못했다.
- 역할: 독립 검토자. 구현은 하지 않았다.
- 검토 세션: `01a10c10-25c3-7170-865e-957725092063`. 환경 변수 `CODEX_THREAD_ID`로 확인했다.
- 작성 세션: Claude Code `893848f2-e1a3-4077-8809-4ef52b6cf2f2`.
- 독립성 근거: 작성자와 실행기가 다르다. 세션 번호도 다르다. 작성 대화 대신 검토 지시서와 저장소 자료를 받았다.
- 검토한 커밋: `b4bb75e796fdc47e7732feea3e9130d281262953` (`b4bb75e`). 로컬 HEAD와 `work/ci`가 모두 이 커밋이었다.
- 집중 범위: `git diff 1dfc39c..b4bb75e`의 gofmt 오류 처리와 경합 검사 추가.
- 전체 확인 범위: `git diff main..work/ci`의 CI 설정, Gradle wrapper 실행 권한, 앞선 검토 기록.
- 앞선 검토: `docs/reviews/2026-10-05-work-ci-codex.md`. `9eaeec9..1dfc39c`는 그 기록 파일만 추가했다.
- 변경 종류: 배포 설정(CI). 사람 필수 변경이다. 핵심 모듈 첫 구현 병합은 해당 없음이다.

## 앞선 지적별 처리 결과

### R1. gofmt 오류 무시 — 해소

- 위치: `.github/workflows/ci.yml:55`, `.github/workflows/ci.yml:56`.
- 검토 항목: 1번 수락 조건, 6번 실행 보고, 7번 실패 시험.
- 확신: 높음.
- 병합 막음: 아니오.
- 근거: 변수 대입의 종료 코드를 먼저 검사한다. gofmt 실행 오류는 명시적인 `exit 1`로 끝난다. 정상 실행 뒤 출력이 있으면 역시 `exit 1`로 끝난다.
- 직접 재현: 출력 없이 2를 반환한 대역은 종료 코드 1이었다. 파일 이름을 출력한 대역은 종료 코드 1이었다. 출력 없이 성공한 대역은 종료 코드 0이었다.
- 추가 수정: 필요 없음.

다음 명령을 PowerShell에서 실행했다. 저장소 파일을 만들지 않고 Bash 표준 입력으로 보냈다.

```powershell
$body = @'
out=$(gofmt -l .) || { echo "gofmt failed"; exit 1; }
if [ -n "$out" ]; then echo "not formatted:"; echo "$out"; exit 1; fi
'@
foreach ($stub in @('gofmt() { return 2; }','gofmt() { echo sample.go; return 0; }','gofmt() { return 0; }')) {
  ($stub + "`n" + $body) | bash --noprofile --norc -e -o pipefail -s
  Write-Output "CASE=$stub EXIT=$LASTEXITCODE"
}
```

### R2. 경합 검사 누락 — 해소

- 위치: `.github/workflows/ci.yml:59`.
- 검토 항목: 7번 시험, Go 추가 기준, 배포 설정의 검사 강도.
- 확신: 높음. 두 OS가 공유하는 단계에 명령이 추가된 것을 직접 확인했다.
- 병합 막음: 아니오.
- 근거: Ubuntu와 Windows 모두 `go test -race -count=1 ./...`를 실행하도록 설정됐다. 기존 일반 시험의 `-v`도 유지됐다. `continue-on-error`나 경합 검사를 제외하는 조건은 없다.
- 추가 수정: 필요 없음.

CI 실제 성공은 독립 확인하지 못했다. 작성자는 실행 `37310279129`가 `b4bb75e`에서 성공했다고 보고했다. 작성자는 두 OS 각각 다섯 패키지가 `ok`였다고 보고했다. 작성자는 `DATA RACE`가 없었다고 보고했다. 이 내용은 작성자 보고이며 검토자가 직접 읽은 로그가 아니다.

## Windows 실패 전파와 환경 변수 범위

Windows 단계의 실패는 실패로 전파되는 구조다. 확신은 중간이다. 로컬 pwsh 재현은 실행 제한 때문에 완료하지 못했다.

경합 검사 단계에는 단일 외부 명령만 있다. 뒤에서 종료 코드를 덮는 명령은 없다. Windows 기본 셸은 pwsh다. GitHub는 기본 PowerShell 스크립트에 마지막 외부 명령의 종료 코드를 반환하는 처리를 붙인다. 따라서 이 단계의 Go 실패 코드는 단계 실패로 전달된다. 공식 동작과 YAML을 대조한 판단이다. [GitHub 공식 셸 문법](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax#jobsjob_idstepsshell)

`CGO_ENABLED: '1'`은 경합 검사 단계에만 적용된다. 확신은 높음이다. 위치는 `.github/workflows/ci.yml:60`이다. `env`는 해당 단계 아래에 있다. `GITHUB_ENV` 기록이나 `go env -w`는 없다. 앞선 일반 시험이나 다른 작업의 환경을 바꾸지 않는다. [GitHub 공식 단계 환경 변수 문법](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax#jobsjob_idstepsenv)

## 새 결함·약화와 공개 정보

새 지적은 없다. R1 수정은 오류 은폐를 없앤다. R2 수정은 검사 범위를 늘린다. 기존 시험을 삭제하거나 검사 강도를 낮추는 변경은 없다.

전체 변경에서 비밀값, 로그인 정보, 소유자 PC 절대 경로, 사설 IP 주소, 회사 정보, 비공개 설계 본문을 발견하지 못했다. PR 본문과 원격 로그는 이 확인 범위에 포함하지 못했다.

CI 권한은 `contents: read`로 유지된다. 시크릿 참조와 권한 확대는 없다. 트리거와 액션 고정 해시는 앞선 검토 이후 바뀌지 않았다. `gradlew`는 동일 blob의 실행 권한만 바뀐다. 전체 변경은 CI 작업 하나에 연결된다. 추가된 CI 파일은 99줄이다. 앞선 검토 파일은 131줄이다.

검토 항목 2번 제품 계약과 4번 업무 중복 실행은 해당 없음이다. 3번 권한과 5번 비밀값은 CI 설정과 공개 변경 범위에서 확인했다. 8번 변경 크기는 기준 안이다. 9번 의존성은 수정 커밋에서 추가되지 않았다. 10번 제품 코드 재배포는 해당 없음이다. 11번 새 부채 후보는 없다. 앞선 Electron 거부 시험의 CI 자동화 후보는 유지된다. 12번 문서는 앞선 검토 기록과 새 설정 주석의 일치 여부를 확인했다.

## 실행한 명령과 결과

검토 환경은 Windows다. 기본 셸은 PowerShell `5.1.26100.9549`다. Git은 `2.50.1.windows.1`이다. Bash는 `5.2.37(1)-release (x86_64-pc-msys)`다. GitHub CLI는 `2.100.0`이다. pwsh 버전은 실행 거부로 확인하지 못했다. Go는 PATH에서 찾지 못했다.

| 명령 또는 확인 | 결과 |
|---|---|
| `git status --short` | 검토 시작 시 변경 없음 |
| `git rev-parse HEAD work/ci b4bb75e` | 세 값 모두 대상 전체 커밋과 일치 |
| `git diff 1dfc39c..b4bb75e` | gofmt 처리 수정과 경합 검사 추가만 확인 |
| `git diff main..work/ci`, `git diff --stat main..work/ci` | CI 99줄, wrapper 모드, 앞선 검토 131줄 확인 |
| `git diff --name-only 9eaeec9..1dfc39c` | 앞선 검토 기록만 확인 |
| `git diff --check main..work/ci` | 종료 코드 0 |
| `git ls-tree main apps/server/gradlew`, `git ls-tree work/ci apps/server/gradlew` | 동일 blob `249efbb032ce46a80c687c0723eb172e85f6a136`, 모드만 변경 |
| `Get-Content -Encoding UTF8` | AGENTS, 기준, PR 양식, CI, 앞선 검토 읽음 |
| 위 Bash 대역 재현 | 오류 1, 미정리 1, 정상 0 |
| `$env:CODEX_THREAD_ID`, `codex --version`, `git --version`, `bash --version`, `gh --version` | 위 세션과 버전 확인 |
| `go version` | 명령을 찾지 못함 |
| `pwsh -NoLogo -NoProfile -Command '$PSVersionTable.PSVersion.ToString()'` | `Access is denied`로 실행 실패 |
| `pwsh -NoLogo -NoProfile -NonInteractive -EncodedCommand $encoded` | 외부 명령 종료 코드 2·0의 래퍼 시험을 시도했으나 실행 거부 |
| `powershell.exe -NoLogo -NoProfile -NonInteractive -EncodedCommand $encoded` | 같은 보조 시험을 시도했으나 실행 거부 |
| `gh run view 37310279129 --json headSha,conclusion,jobs` | 네트워크 접근 제한으로 실패 |
| `gh pr view 7 --json body,headRefOid,isDraft,url` | 네트워크 접근 제한으로 실패 |
| 웹 도구의 GitHub 공식 문서 열기와 검색 | pwsh 종료 코드 전달과 단계 env 범위 확인 |

PowerShell 보조 시험의 `$encoded`는 다음 본문을 UTF-16LE Base64로 변환한 값이었다. `N`에는 2와 0을 각각 넣었다.

```powershell
$ErrorActionPreference = 'stop'
& $env:ComSpec /d /c exit N
if ((Test-Path -LiteralPath variable:\LASTEXITCODE)) { exit $LASTEXITCODE }
```

실행 거부 뒤 출력된 `WRAPPER_EXIT`는 유효한 시험 결과가 아니다. 이전 `$LASTEXITCODE` 값 또는 빈 값이었으므로 판정 근거에서 제외했다. 첫 문서 읽기는 기본 인코딩으로 한글이 깨졌다. UTF-8로 다시 읽었다.

## 확인하지 않은 것과 병합 직전 조건

실제 Go 경합 시험은 로컬에서 실행하지 않았다. 실제 gofmt 도구의 오류 사례도 실행하지 않았다. Bash 시험은 종료 코드와 출력 처리만 검증했다. GitHub의 Ubuntu·Windows 실행 환경과 C 컴파일러는 직접 확인하지 못했다. 로컬 PowerShell 보조 시험도 완료하지 못했다.

서버·Web·Desktop 전체 시험은 다시 실행하지 않았다. 액션 내부 구현과 전이 의존성은 감사하지 않았다. 앞선 검토의 공식 릴리스 해시 확인도 다시 수행하지 않았다. 비공개 설계 문서와 결정 기록 원문은 열지 않았다.

PR 필수 일곱 항목, 초안 해제, 실제 원격 끝 커밋, CI 실행 로그, 관리자 설정과 브랜치 보호는 확인하지 못했다. 사람 필수 변경의 판단 이유와 「위임받은 AI의 판단」 표시도 확인하지 못했다. 작성 세션은 기준의 병합 직전 다섯 조건을 별도로 확인해야 한다. 이 기록은 해당 절차를 완료했다는 뜻이 아니다.

검토 기록 외의 파일은 수정하지 않았다. 브랜치 생성, 커밋, push, 원격 변경과 병합은 수행하지 않았다.
