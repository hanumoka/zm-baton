# work/ci 독립 검토

판정은 **병합 막음**이다. 병합 막음 지적은 **2건**이다. 형식 검사 오류가 성공으로 처리된다. 기준이 요구하는 Go 경합 검사도 새 CI에 없다.

## 검토자와 범위

- 적용 기준: `docs/review-standard.md`의 검토 기준 v0.3과 `AGENTS.md`.
- 검토자 실행기: Codex, `codex-cli 0.160.0`.
- 모델: 세션 지침상 GPT-6 계열이다. 정확한 배포 모델 식별자는 확인하지 못했다.
- 역할: 독립 검토자. 구현은 하지 않았다.
- 검토 세션: `01a10c08-aa01-7d81-afe4-d7d04432031f`. 환경 변수 `CODEX_THREAD_ID`로 확인했다.
- 작성자: Claude Code 세션 `893848f2-e1a3-4077-8809-4ef52b6cf2f2`.
- 독립성 근거: 실행기가 다르다. 세션 번호도 다르다. 작성 대화 대신 검토 지시서와 저장소 자료를 받았다.
- 대상: `work/ci`, `9eaeec9ef8a3e7d41fce3cafc2774177d2114650` (`9eaeec9`). 로컬 HEAD와 브랜치 끝이 일치했다.
- 변경 범위: `git diff main..work/ci`의 `.github/workflows/ci.yml` 추가와 `apps/server/gradlew` 실행 권한 변경.
- 변경 종류: 배포 설정(CI 설정). 사람 필수 변경이다. 핵심 모듈의 첫 구현 병합은 아니다.

## 병합 막음 지적

### R1. gofmt 실행 오류를 실패로 전파해야 한다

- 위치: `.github/workflows/ci.yml:53`.
- 검토 항목: 6번 실행 보고의 진실성, 7번 실패 시험, 배포 설정의 검사 강도.
- 확신: 높음. CI와 같은 Bash 옵션으로 실패 대역을 실행해 재현했다.
- 병합 막음: 예.
- 근거: `test -z "$(gofmt -l .)"`는 출력이 비었는지만 검사한다. 명령 치환 안의 종료 코드는 검사하지 않는다. `gofmt`가 표준 출력 없이 오류 코드 2를 내면 `test`가 성공한다. `-e -o pipefail`도 이 오류를 막지 못했다.
- 영향: 구문 오류나 읽기 실패를 형식 검사 통과로 표시할 수 있다. 뒤의 `go vet`가 모든 대상을 대신 검사한다고 볼 수 없다. 예를 들어 `gofmt -l .`가 읽는 `testdata` 안의 Go 파일은 일반 패키지 검사 대상과 다르다.
- 고칠 방향: 먼저 `gofmt` 출력을 변수에 받고 종료 코드를 확인한다. 그 다음 출력이 비었는지 판정한다. 도구 오류와 미정리 파일을 모두 실패시켜야 한다.

재현은 실제 Go 도구 대신 셸 함수를 썼다. 저장소 파일은 만들거나 바꾸지 않았다.

```powershell
@'
gofmt() { return 2; }
test -z "$(gofmt -l .)" || { gofmt -l .; exit 1; }
'@ | bash --noprofile --norc -e -o pipefail -s
$LASTEXITCODE
```

실제 결과는 `0`이었다. 기대 결과는 0이 아닌 종료 코드다. 대역이 `sample.go`를 출력하면 종료 코드가 `1`이었다. 대역이 출력 없이 정상 종료하면 종료 코드가 `0`이었다.

### R2. Go 경합 검사가 CI 검증 범위에서 빠져 있다

- 위치: `.github/workflows/ci.yml:55`.
- 검토 항목: 7번 시험의 충분성, Go 추가 기준, 배포 설정의 검사 강도.
- 확신: 높음. 명령과 기준의 명시적 요구를 대조했다.
- 병합 막음: 예.
- 근거: `docs/review-standard.md:253`은 `go test -race` 실행을 요구한다. 두 OS 작업 모두 `go test -count=1 -v ./...`만 실행한다. `-count=1`은 시험 결과 캐시를 막지만 경합 탐지기를 켜지는 않는다.
- 영향: CI 전체가 성공해도 기준의 경합 검사를 수행했다는 근거가 생기지 않는다.
- 기존 한계: `docs/stage1/compat-test-results.md:129`는 로컬 PC의 CGO 비활성화와 C 컴파일러 부재를 미실행 사유로 적는다. 이 기록은 새 Ubuntu CI에서도 검사를 제외하는 근거가 아니다.
- 고칠 방향: 최소한 Ubuntu 작업에서 C 도구 체인과 CGO를 확인하고 `go test -race -count=1 -v ./...`를 실행한다. Windows 일반 시험은 유지할 수 있다. 경합 검사를 제외하려면 기준 변경 절차로 예외를 확정해야 한다.

## 확인한 것

### 보안

현재 YAML에서 비밀값이나 저장소 쓰기 권한을 전달하는 설정은 찾지 못했다. 최상위 권한은 `contents: read`뿐이다. 작업별 권한 확대는 없다. 시크릿 참조나 배포 환경 참조도 없다.

트리거는 `pull_request`와 `main`의 `push`다. `pull_request_target`은 없다. GitHub 호스팅 실행기를 사용한다. 셸 `run` 본문에는 `${{ }}` 치환이 없다. `github.ref`는 동시 실행 그룹에만 사용한다. `matrix.os`는 YAML에 고정된 두 값이다.

포크 PR의 코드는 빌드와 시험 과정에서 실행된다. 따라서 코드가 신뢰된다는 뜻은 아니다. 현재 설정에서는 읽기 토큰보다 큰 권한을 부여하지 않는다. `checkout`의 자격 증명 유지 기본값은 바꾸지 않았다. 더 좁히려면 `persist-credentials: false`를 고려할 수 있다. 이는 비차단 개선 후보이며 위치는 각 checkout 단계다. 확신은 중간이다.

권한과 셸 동작은 [GitHub 공식 워크플로 문법](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax)과 대조했다. 실제 저장소의 Actions 관리자 설정은 확인하지 못했다.

액션 네 종류는 모두 전체 40자리 커밋 해시로 고정돼 있다. 공식 릴리스의 커밋 링크가 YAML 해시와 일치했다.

| 액션 | 주석의 판 | 확인한 전체 해시 | 공식 출처 |
|---|---|---|---|
| actions/checkout | v7.0.1 | `3d3c42e5aac5ba805825da76410c181273ba90b1` | [릴리스](https://github.com/actions/checkout/releases/tag/v7.0.1) |
| actions/setup-java | v6.0.1 | `de7274f081f381c8f8158605e0321c36c376e2e6` | [릴리스](https://github.com/actions/setup-java/releases/tag/v6.0.1) |
| actions/setup-go | v7.0.0 | `b7ad1dad31e06c5925ef5d2fc7ad053ef454303e` | [릴리스](https://github.com/actions/setup-go/releases/tag/v7.0.0) |
| actions/setup-node | v7.0.0 | `820762786026740c76f36085b0efc47a31fe5020` | [릴리스](https://github.com/actions/setup-node/releases/tag/v7.0.0) |

### 실제 명령과 환경

- 서버: JDK 25는 `build.gradle.kts`의 도구 체인과 맞는다. `./gradlew test`는 JUnit Platform 시험을 실행한다. 시험 설정은 PostgreSQL Testcontainers를 사용한다. Gradle wrapper 설정은 9.7.1이다.
- Go: `go-version-file`은 저장소 루트 기준 `apps/agent/go.mod`를 가리킨다. 선언은 Go 1.27이다. 외부 모듈 요구와 `go.sum`이 없다. `setup-go` 캐시를 끈 주석은 이 상태와 맞는다. 캐시 비활성화는 시험을 끄는 설정이 아니다.
- Windows: 형식 검사는 명시한 Git Bash에서 돈다. `go vet`와 `go test`는 기본 PowerShell에서 돈다. 두 명령은 각각 별도 단계의 단일 외부 명령이다. GitHub의 기본 PowerShell 래퍼는 마지막 외부 명령 종료 코드를 전달한다.
- Web: `npm test`는 `vitest run`이다. `npm run build`는 TypeScript 검사 뒤 Vite 빌드를 한다. npm 캐시의 잠금 파일 경로와 작업 디렉터리가 맞는다.
- Desktop: `npm run build`는 `tsc -p .`다. 실행 파일 다운로드를 생략해도 타입 컴파일의 목적과 양립한다. `npm test`는 컴파일 뒤 Electron을 실제로 띄우는 `test/probe.mjs`를 실행한다. 현재 CI에서는 이 시험을 실행하지 않는다.
- Go 종료 시험: Windows proc 시험 함수 여섯 개를 확인했다. Unix 시험에는 `/proc` 부재와 `sleep` 실행 불가 시 건너뛰는 분기가 있다. `-v`는 이를 로그에 보이게 하지만 건너뜀 자체를 실패로 바꾸지는 않는다.

### 검사를 끄거나 약하게 하는지

기존 CI를 삭제하는 변경은 아니다. 새 CI의 자동 검증 범위는 전체 로컬 검증 범위보다 좁다.

Electron 거부 시험 제외는 `.github/workflows/ci.yml:75`에 명시돼 있다. `docs/stage1/compat-test-ui.md`의 대상은 Windows PC다. 기존 결과 문서도 PC에서 수행한 거부 시험을 따로 기록한다. 따라서 컴파일 전용 CI를 추가하는 것 자체는 해당 계획과 충돌하지 않는다. 다만 CI 성공을 거부 시험 성공으로 해석해서는 안 된다. 향후 데스크톱 보안 변경에는 별도 실행 근거가 필요하다.

Go 경합 검사 누락은 R2다. 형식 검사 오류 은폐는 R1이다. `continue-on-error`나 시험 경로 필터는 없다. 새 커밋이 오면 이전 실행을 취소하는 설정은 현재 커밋의 검사를 생략하는 설정이 아니다.

### 변경 범위와 공개 정보

`gradlew`의 blob은 양쪽 모두 `249efbb032ce46a80c687c0723eb172e85f6a136`이다. 모드만 `100644`에서 `100755`로 바뀌었다. 스크립트 내용과 줄바꿈은 바뀌지 않았다.

변경된 파일은 두 개다. CI 파일은 92줄이다. 변경 내용에서 비밀값, 소유자 PC 절대 경로, 사설 IP 주소, 회사 정보, 비공개 설계 본문을 찾지 못했다. `git diff --check main..work/ci`는 종료 코드 0으로 통과했다.

검토 항목 2번 제품 계약, 4번 중복 실행의 업무 의미, 10번 제품 코드 재배포는 이번 변경에서 해당 없음이다. 제품 계약과 제품 의존성은 바뀌지 않는다. 8번 변경 크기는 기준 안이다. 9번 CI 도구는 위 표의 공식 액션으로 고정된다. 11번 부채 후보는 Electron 거부 시험의 CI 자동화다. 12번 문서 확인은 설정 주석과 기존 시험 계획의 일치 여부로 한정했다.

## 실행한 명령과 결과

검토 환경은 Windows와 PowerShell `5.1.26100.9549`다. Git은 `2.50.1.windows.1`이다. Bash는 `5.2.37(1)-release (x86_64-pc-msys)`다. GitHub CLI는 `2.100.0`이다.

| 명령 또는 확인 | 결과 |
|---|---|
| `git status --short` | 검토 시작 시 변경 없음 |
| `git rev-parse work/ci`, `git rev-parse HEAD` | 둘 다 대상 전체 커밋과 일치 |
| `git diff main..work/ci`, `git diff --stat main..work/ci`, `git diff --name-only main..work/ci` | CI 92줄 추가와 wrapper 모드 변경만 확인 |
| `git diff --check main..work/ci` | 종료 코드 0 |
| `git ls-tree main apps/server/gradlew`, `git ls-tree work/ci apps/server/gradlew` | 같은 blob, 모드만 다름 |
| `Get-Content -Encoding UTF8`, `rg` | 기준, AGENTS, PR 양식, 패키지 명령, Gradle 설정, Go 시험, 화면 시험 계획과 결과 대조 |
| `$env:CODEX_THREAD_ID`, `codex --version` | 위의 검토 세션 번호와 CLI 버전 확인 |
| `go version` | PATH에서 Go를 찾지 못함 |
| `gh pr view 7 --json body,headRefOid,isDraft,url` | 네트워크 접근 제한으로 실패 |
| 웹 도구로 PR #7 읽기 | Internal Error로 실패 |
| 웹 도구로 액션 릴리스와 커밋 링크 읽기 | 네 종류의 전체 해시 일치 확인 |
| Bash 실패 대역 재현 | 오류 코드 2를 반환한 대역이 검사 종료 코드 0으로 처리됨 |
| Bash 출력 대역과 정상 대역 재현 | 각각 종료 코드 1과 0 |

첫 Bash `-c` 재현은 PowerShell 인수 전달의 인용 문제로 종료 코드 2를 냈다. 이를 제품 결함의 근거로 쓰지 않았다. 표준 입력으로 같은 코드를 보내 재현을 완료했다. 첫 문서 읽기는 기본 인코딩 때문에 한글이 깨졌다. UTF-8로 다시 읽었다. `apps/desktop/README*` 검색도 Windows 경로 처리 오류가 있어 성공으로 세지 않았다.

## 확인하지 않은 것과 작성자 보고

GitHub PR 본문과 CI 실행 로그는 독립적으로 읽지 못했다. 액션 공식 릴리스 페이지는 웹 도구로 읽을 수 있었다. 두 접근 결과를 구분한다.

작성자는 실행 `37309179993`과 `37309441130`이 모두 통과했다고 보고했다. 작성자는 두 번째 실행에서 Windows proc 시험 여섯 개와 Ubuntu의 `TestKillChecksTheMarkerBeforeStopping`이 PASS였다고 보고했다. 이 사실은 작성자 보고로만 기록한다. 검토자가 확인한 시험 통과 수로 세지 않는다.

서버, Web, Desktop, Go 전체 시험은 이번 검토에서 다시 실행하지 않았다. 실제 Go 도구의 오류 사례도 실행하지 않았다. Bash 재현은 종료 코드 전달 구조만 검증한다. Linux 실행과 Windows CI 실행도 독립 재현하지 않았다.

액션 내부 코드 전체와 전이 의존성은 감사하지 않았다. Electron 다운로드 생략 동작은 CI에서 재현하지 않았다. 비공개 설계 문서와 결정 기록 원문은 이번 CI 검토에서 읽지 않았다.

PR의 필수 일곱 항목, 실제 원격 끝 커밋, 초안 해제, Actions 관리자 설정, 브랜치 보호는 확인하지 못했다. 사람 필수 변경의 판단 이유와 「위임받은 AI의 판단」 표시도 확인하지 못했다. 작성 세션은 지적을 고친 뒤 다시 검토받아야 한다. 이 기록은 병합 직전 확인을 대신하지 않는다.
