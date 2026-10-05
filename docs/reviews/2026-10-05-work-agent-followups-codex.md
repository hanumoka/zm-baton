# work/agent-followups Codex 검토

판정은 **병합 가능**이다. 병합 막음 지적은 **0건**이다. N8과 N9는 이번 후속 수정으로 해소됐다. 판정 범위는 Windows 설치·호환 시험의 후속 수정이다.

## 검토 신원과 범위

- 검토자 실행기: Codex CLI `0.160.0`.
- 모델: 세션 지침은 GPT-6 기반이라고 명시한다. 정확한 모델 식별자는 확인하지 못했다.
- 역할: 독립 검토자. 구현은 하지 않았다.
- 검토 세션: `01a10bd9-7567-7bd0-ad0a-ed67b148bea1`. `CODEX_THREAD_ID`에서 읽었다.
- 작성 실행기: Claude Code.
- 작성 세션: `893848f2-e1a3-4077-8809-4ef52b6cf2f2`.
- 독립성 근거: 작성자와 실행기가 다르다. 두 세션 번호도 다르다. 작성 대화 대신 검토 지시와 저장소 자료를 받았다.
- 검토 커밋: `35f322f5109a7f02ee2f19f6cc56aa8ad2f9675a`.
- 대상 브랜치: `work/agent-followups`.
- 시작 상태: HEAD가 대상 커밋과 일치했다. 작업 폴더에 변경이 없었다.
- 적용 기준: **zm-baton 검토 기준 v0.3**, `AGENTS.md`.
- 계약 대조: 외부 설계 정본의 협업 계약 v1에서 「프로세스 관리」 절을 읽었다. 설계 파일은 수정하거나 복사하지 않았다.

요청한 `git diff main..work/agent-followups`의 목록과 공개 정보 패턴을 확인했다. 로컬 `main`은 `7eca47e51d03d5438a6b9afa88fcf556475d5928`이다. 이 범위에는 PR #4의 기존 구현도 포함된다. 후속 수정은 `git diff 9101bb7..35f322f`의 4개 파일이다. 이 4개 파일과 관련 프로세스 구현·시험을 정독했다.

PR #4의 세 번째 검토 커밋 `78c22cc`와 후속 수정의 부모 `9101bb7`도 비교했다. 둘의 파일 차이는 세 번째 검토 기록뿐이다. 기존 구현 전체를 새로 정독한 것으로 주장하지 않는다. 이전 검토의 범위와 한계를 유지하면서 이번 후속 수정을 검토했다.

## 지적별 판정

| 번호 | 위치 | 검토 항목 | 판정과 근거 | 확신 | 병합 막음 | 고칠 방향 |
|---|---|---|---|---|---|---|
| N8 | `apps/agent/internal/proc/kill_windows_test.go:205`, `:226` | 1. 수락 조건, 4. 실패 안전, 7. 시험 | 해소. 긴 UTF-16 입력의 조회와 오류 시 종료 거부를 실제 시험했다 | 높음 | 아니오 | 추가 필수 수정 없음 |
| N9 | `apps/agent/internal/proc/kill_windows.go:45`, `apps/agent/README.md:16`, `:30`, `docs/stage1/compat-test-results.md:159` | 12. 문서 | 해소. 작업 객체 상속 범위와 조회 실패 시 거부 설명이 일치한다 | 높음 | 아니오 | 추가 필수 수정 없음 |

새 지적은 없다.

## N8에서 확인한 것

긴 명령줄 시험은 첫 4KB 버퍼로 처리할 수 없는 입력을 만든다. `strings.Repeat("x", 5000)`은 UTF-16에서 본문만 10,000바이트다. 실행 파일 경로와 나머지 인수는 여기에 더해진다. `commandLineOf`의 첫 버퍼는 4,096바이트다. 현재 코드에서 이 시험이 성공하려면 부족한 버퍼를 늘리는 경로를 지나야 한다.

한글과 이모지의 복원은 정확한 부분 문자열 비교로 확인한다. 기대 문자열은 `한글 표식 😀`다. 이모지는 UTF-16에서 서로게이트 쌍을 사용한다. 한글이 깨지거나 이모지가 대체 문자로 바뀌면 `strings.Contains`가 실패한다. 긴 ASCII 인수도 5,000자 전체를 비교한다.

표식은 긴 인수와 비ASCII 인수 뒤의 마지막 인수다. `startHelper`가 그 순서로 인수를 구성한다. 시험은 조회 결과의 끝에 표식이 있는지도 검사한다. 이어서 같은 표식으로 `Kill`을 호출한다. 자식 핸들의 종료 상태까지 확인한다. 실행 파일 경로와 모든 인용 부호를 포함한 명령줄 전체의 바이트 동일성을 검사하는 시험은 아니다.

조회 오류 시험은 생존 확인과 명령줄 조회를 구분한다. 도우미 부모의 두 번째 핸들은 `SYNCHRONIZE` 권한만 갖는다. `Kill`은 그 핸들로 생존 확인을 통과한다. 명령줄 조회에서는 오류가 발생한다. 시험은 반환 오류가 nil이 아닌지 확인한다. `ErrMarkerMismatch`로 바뀌지 않았는지도 확인한다. 오류에 `NTSTATUS`가 남는지도 확인한다. 현재 코드에서 이 문자열은 명령줄 조회 오류 경로에서 생성된다.

거부 후에는 원래 핸들로 부모와 자식이 살아 있는지 검사한다. 부모는 300밀리초 동안 기다린다. 자식은 이어서 즉시 확인한다. 핸들 대기 실패는 종료로 취급하지 않는다. 따라서 단순히 오류만 반환하면서 작업 객체를 끝내는 구현은 이 시험을 통과할 수 없다. 정확한 NTSTATUS 숫자까지 고정해 검사하지는 않는다.

작업 객체 공유는 이 시험의 현재 사용 방식에서 안전하다. `Track`이 새 이름 없는 작업 객체를 만든다. 일시 정지된 도우미 부모를 넣은 뒤 실행을 재개한다. 도우미가 띄우는 자식도 같은 시험 바이너리다. 시험용 `Run`은 이 작업 객체의 핸들 값만 빌린다. 잘못된 종료 호출이 일어나도 대상은 그 도우미 작업 객체다.

빌린 `Run`에는 `Close`를 호출하지 않는다. 약한 프로세스 핸들만 defer로 닫는다. 작업 객체는 원래 도우미의 cleanup에서 한 번 닫는다. 두 `Run`을 동시에 조작하는 goroutine이나 `t.Parallel`도 없다. 서로 다른 mutex가 같은 작업 객체를 보호하는 일반적인 공유 설계로 확대할 수는 없다. 현재 시험에서는 이중 닫기나 동시 사용 경로가 없다.

## N9에서 확인한 것

세 문서 위치는 일반적인 생성 경로로 작업 객체를 상속한 프로세스만 포함한다고 설명한다. WMI 같은 간접 생성의 예외도 일치한다. 이는 [Microsoft Job Objects 문서](https://learn.microsoft.com/en-us/windows/win32/procthread/job-objects)의 자식 상속과 `Win32_Process.Create` 예외 설명에 부합한다.

실제 종료 코드는 `TerminateJobObject`에 해당 실행의 작업 객체를 넘긴다. README와 결과 문서는 종료 호출이 성공한 경우에만 남은 하위 프로세스가 끝난다고 설명한다. 명령줄 조회가 실패하면 아무것도 끝내지 않는다는 문장도 코드의 조기 반환과 맞는다. `Run` 주석은 더 이상 실행기가 만든 모든 프로세스를 무조건 포함한다고 주장하지 않는다.

## 실행한 명령과 결과

시험 환경은 Windows NT `10.0.26300.0`의 amd64다. Go는 `go1.27.1 windows/amd64`다. PowerShell은 `5.1.26100.9549`다. Git은 `2.50.1.windows.1`이다.

Go 검증은 `apps/agent`에서 실행했다. 실행 파일은 `%LOCALAPPDATA%/Programs/go/bin/go.exe`다. gofmt도 같은 설치의 `bin/gofmt.exe`를 사용했다. 실행 명령 프로세스에만 `GOROOT=%LOCALAPPDATA%/Programs/go`를 지정했다. `GOCACHE`는 사용자가 지정한 외부 scratchpad의 `codex-go/cache`로 지정했다. `GOTMPDIR`는 같은 scratchpad의 `codex-go/tmp`로 지정했다. 저장소 안에 캐시나 빌드 산출물을 남기지 않았다.

| 명령·검사 | 결과 |
|---|---|
| `git status --short`, `git branch --show-current`, `git rev-parse HEAD`, `git worktree list` | 대상 작업 폴더·브랜치·커밋 일치. 시작 시 변경 없음 |
| `codex --version`, `git --version`, `go version`, OS·PowerShell 버전 조회 | 위 환경 확인 |
| `git diff main..work/agent-followups`, `git diff HEAD^ HEAD` | 전체 범위의 목록·공개 정보 패턴과 후속 수정 4개 파일 확인 |
| `git diff 78c22cc 9101bb7 --stat` | 기존 검토 커밋과 부모 커밋의 차이는 검토 기록 1개뿐 |
| `gofmt -l .` | 종료 코드 0. 출력 없음 |
| `go vet ./...` | 종료 코드 0. 출력 없음 |
| `go test -count=1 -timeout=5m ./...` | 종료 코드 0. 시험 있는 5개 패키지 통과. 실패 0 |
| `git diff --check main..work/agent-followups` | 종료 코드 0. 출력 없음 |
| `git diff HEAD^ HEAD --check` | 종료 코드 0. 출력 없음 |
| 변경 파일 줄 수와 경로·키·암호 패턴 검사 | 1,000줄 초과 없음. 공개 금지 정보를 발견하지 못함 |

Go 시험 출력 원문은 다음과 같다.

```text
?   	github.com/hanumoka/zm-baton/apps/agent/cmd/zm-baton-agent	[no test files]
ok  	github.com/hanumoka/zm-baton/apps/agent/internal/agent	2.269s
ok  	github.com/hanumoka/zm-baton/apps/agent/internal/api	0.052s
ok  	github.com/hanumoka/zm-baton/apps/agent/internal/claude	0.047s
ok  	github.com/hanumoka/zm-baton/apps/agent/internal/proc	1.120s
ok  	github.com/hanumoka/zm-baton/apps/agent/internal/report	0.875s
```

공개 정보 패턴에 걸린 사용자 경로는 기존 코드의 가상 긴 이름·짧은 이름 비교 예시였다. 이번 후속 수정에서는 소유자 PC 절대 경로·주소·비밀값·회사 정보·비공개 설계 내용의 복사본을 발견하지 못했다. 전용 비밀값 탐지기로 전체 이력을 감사하지는 않았다.

파일 위치 검색 한 번은 PowerShell에서 경로 와일드카드가 전달돼 실패했다. 오류 원문은 `IO error for operation on apps/agent/internal/proc/kill_windows*.go: 파일 이름, 디렉터리 이름 또는 볼륨 레이블 구문이 잘못되었습니다. (os error 123)`이다. `rg -n`에 디렉터리와 `-g 'kill_windows*.go'`를 넘겨 다시 검색했다. 재검색은 성공했다. Go 검증 명령의 실패는 없다.

## 확인하지 않은 것과 남은 범위

- 실제 Claude Code·서버·DB를 띄우는 통합 시험은 재현하지 않았다.
- `go test -race`는 실행하지 않았다. 기존 결과 문서는 CGO 비활성화와 C 컴파일러 부재를 미실행 사유로 기록한다. 이를 통과로 바꾸지 않았다.
- Linux·macOS·다른 Windows 버전·다른 비트 수는 시험하지 않았다.
- 생존 확인 직후의 자연 종료와 명령줄 변경을 강제로 만들지 않았다.
- 잘못된 반환 버퍼와 모든 NTSTATUS 종류를 주입하지 않았다.
- WMI·breakaway·중첩 작업 객체·연결 프로그램 강제 종료는 직접 시험하지 않았다.
- 새 긴 명령줄 시험은 부모 종료를 별도 핸들 검사로 단언하지 않는다. 기존 정상 종료 시험이 부모의 `Wait`와 자식의 종료를 확인한다.
- 후속 수정에는 새 의존성·계약·DB·배포 변경이 없다. 전체 코드의 출처나 라이선스를 다시 감사하지 않았다.
- 핵심 모듈 2·3의 기록 미작성 상태를 확인했다. 소유자의 설명·재현 기록은 대신 작성하지 않았다.
- 원격 PR 본문의 일곱 항목·현재 원격 커밋·CI·위임 표시·브랜치 보호는 확인하지 않았다. 병합하는 작성 세션이 검토 기준 v0.3의 「병합 직전」 항목을 별도로 확인해야 한다.

지정된 검토 기록만 작성했다. 구현 수정·브랜치 생성·커밋·push·원격 변경·main 변경·병합은 하지 않았다.
