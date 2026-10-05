# work/compat-agent 두 번째 Codex 검토

판정은 **판정 불가**다. 새로 확정한 병합 막음 지적은 **0건**이다. 이는 병합 승인이 아니다.

R2는 해소됐다. R1의 PID 재사용 위험을 막는 구조도 확인했다. 다만 이 검토 환경에서는 CIM 접근이 거부됐다. Windows 종료 시험 두 개가 실패했고 agent 시험은 시간 초과로 끝났다. 핵심 종료 동작의 독립 실행 검증을 완료하지 못했다. 검토 기준 v0.3과 AGENTS.md에 따라 이 판정은 소유자에게 넘긴다. 환경 제약을 제품 결함으로 확정하지 않는다.

## 검토 신원과 범위

- 검토자 실행기: Codex CLI `0.160.0`.
- 모델: GPT-6 계열이라는 세션 지침만 확인했다. 정확한 모델 식별자는 확인하지 못했다.
- 역할: 독립 검토자. 구현하지 않았다.
- 검토 세션: `01a10bac-274d-7bc3-a744-756eabd045d9`. `CODEX_THREAD_ID`에서 읽었다.
- 작성 실행기: Claude Code와 그 하위 작업자.
- 작성 세션: `893848f2-e1a3-4077-8809-4ef52b6cf2f2`.
- 독립성 근거: 실행기가 다르고 세션 번호도 다르다. 이 세션은 검토 지시와 저장소 자료를 받았다. 작성 대화나 구현 역할을 이어받지 않았다.
- 검토 커밋: `cef7fb02224bb6772f7ab38ff5406dc6d814b7c5`.
- 수정 범위: `git diff e971ae7..cef7fb0`. 13개 파일이다.
- 전체 범위: `git diff main..work/compat-agent`. 앞선 검토 기록과 현재 구현·시험·문서를 함께 대조했다.
- 적용 기준: **zm-baton 검토 기준 v0.3**, `AGENTS.md`, `docs/stage1/compat-test.md`.
- 계약 대조: 같은 PC의 설계 저장소에서 협업 계약 v1과 데이터 모델을 읽었다. 그 내용은 복사하지 않았다.

## 앞선 지적별 결과

| 지적 | 결과 | 근거와 한계 |
|---|---|---|
| R1 | 구조상 개선 확인. 실행 검증 미완료 | Windows는 별도 프로세스 핸들과 작업 객체를 유지한다. Linux는 오래된 자손 PID 목록을 없앴다. Windows의 정상 종료 경로 시험은 이 환경에서 실패했다 |
| R2 | 해소 | `Result`가 종료 이유와 전달 오류를 분리한다. 서버 오류·영구 거절·개별 거절 시험이 추가 실행에서 모두 통과했다 |
| N1 | 해소 | `Close` 시한이 진행 중인 전송의 context를 취소한다. 해당 시험을 포함한 report 패키지가 통과했다 |
| N2 | 개선 확인 | proc 시험은 핸들 대기 결과로 생존을 판정한다. 조회 실패를 종료로 판정하던 코드는 제거됐다. 다만 종료 후 거절 시험에는 아래 N6의 검증 한계가 있다 |
| N3 | 문서 범위 해소 | 결과 문서 65행이 작업 행 하나·보고 연결 하나로 범위를 좁혔다. 서버 시험 주석에는 여전히 일반적인 표현이 남아 있다 |
| N4 | 해소 유지 | 두 Future를 각각 10초 제한으로 기다린다는 설명이다 |
| N5 | 코드와 문서 개선 확인 | CLI는 `ctx.Err()`를 먼저 확인해 130을 반환한다. 결과 문서는 실제 OS 신호를 시험하지 않았다고 밝힌다. 실제 Ctrl+C와 CLI 종료 코드는 이번에도 실행 검증하지 않았다 |

## R1: 프로세스 관리

열린 Windows 핸들은 PID 재사용을 막는다. `cmd.Start()`가 가진 핸들이 있는 동안 `OpenProcess`를 호출한다. 이후 Go의 `Wait`가 자기 핸들을 닫아도 `Run.process`는 남는다. `Kill`과 `Close`는 같은 mutex를 사용한다. Runner는 종료 감시의 판단이 끝난 뒤 핸들을 닫는다. 따라서 앞선 검토의 「표식 조회 뒤 PID가 다른 프로세스에 재사용됨」 경로는 제거됐다. PID의 수명은 [Microsoft PROCESS_INFORMATION 문서](https://learn.microsoft.com/en-us/windows/win32/api/processthreadsapi/ns-processthreadsapi-process_information)와 대조했다.

작업 객체 등록은 실행 재개보다 먼저다. 실행기는 `CREATE_SUSPENDED`로 시작한다. `AssignProcessToJobObject`가 성공한 뒤 `NtResumeProcess`를 호출한다. 정상 경로에서는 등록 전에 실행기가 자손을 만들 수 없다.

새 작업 객체에는 breakaway 허용 플래그를 설정하지 않는다. 중첩 작업 객체를 만들더라도 이 작업 객체의 경계를 자동으로 벗어나는 것은 아니다. 작업 객체를 종료하면 그 아래 작업 객체의 프로세스도 종료된다. 기존 상위 작업 객체의 제약으로 등록에 실패하면 재개하지 않는다. 이 의미는 [Microsoft Nested Jobs](https://learn.microsoft.com/en-us/windows/win32/procthread/nested-jobs)와 대조했다. 실제 중첩·breakaway 조합 시험은 하지 않았다.

작업 객체의 핸들은 상속되지 않는다. `CreateJobObjectW(0, 0)`은 이름 없는 객체와 비상속 핸들을 만든다. `OpenProcess`도 상속 인자를 false로 준다. 자손의 작업 객체 소속 상속은 핸들 상속과 별개다. [Microsoft CreateJobObjectW](https://learn.microsoft.com/en-us/windows/win32/api/jobapi2/nf-jobapi2-createjobobjectw)의 설명과 맞는다.

모든 간접 실행까지 작업 객체에 잡히는 것은 아니다. 예를 들어 WMI의 `Win32_Process.Create`로 생성되는 프로세스는 통상적인 자손 소속 규칙의 예외다. 따라서 「실행기가 띄우는 하위 프로세스도 모두」는 일반적인 CreateProcess 자손으로 범위를 좁히는 편이 정확하다. [Microsoft Job Objects](https://learn.microsoft.com/en-us/windows/win32/procthread/job-objects)에 이 예외가 있다. 이 구현을 악성 실행기의 격리 경계로 인정하지 않는다.

Track 실패 뒤 정리는 Go가 보유한 원래 프로세스 핸들로 한다. `runner.go:210`에서 `cmd.Process.Kill()` 뒤 `cmd.Wait()`를 부른다. PID를 다시 찾지 않는다. 이는 실행 전 정지 프로세스의 정리 경로다. 정상 실행 중 종료와 달리 명령줄 표식 검사는 없다. 이 차이를 계약의 프로세스 관리 절과 대조해 기록한다. 다른 프로세스를 선택하는 위험은 발견하지 못했다. 다만 Kill 오류를 버리므로 종료 실패 때 Wait가 무기한 기다릴 가능성은 남는다. 실패 주입은 하지 않았다.

종료된 실행기에 대한 CIM 응답은 이번 환경에서 검증하지 못했다. 코드에는 핸들을 통한 별도의 생존 검사가 없다. 빈 응답이면 거절한다. 표식이 든 응답이면 작업 객체를 종료한다. 조회 직후 실행기가 끝나는 경쟁에서도 후자의 경로는 가능하다. 이때 대상은 여전히 같은 작업 객체의 자손이다. PID가 재사용된 다른 실행기로 바뀌지는 않는다. 따라서 R1의 오종료 위험과 「종료된 실행기에는 아무것도 하지 않는다」는 문서 주장은 구분해야 한다.

`Close`는 남은 자손을 종료하지 않는다. `KILL_ON_JOB_CLOSE`를 설정하지 않았기 때문이다. 실행기가 먼저 끝난 뒤 남은 자손이나 연결 프로그램의 비정상 종료는 자동 정리되지 않는다. 결과 문서 마지막 항목은 이 제한을 밝힌다.

Linux는 시작한 `os.Process`에만 Kill을 보낸다. 설치된 Go의 `src/os/exec_unix.go`에서 pidfd 경로와 PID 대체 경로의 Wait·Signal 동기화를 확인했다. pidfd를 쓸 수 없는 경우도 있으므로 주석의 pidfd 설명을 모든 Linux 환경의 보장으로 읽어서는 안 된다. 현재 호출 방식에서 자손 PID 재조회 위험은 제거됐다. 자손 종료 기능은 없다.

macOS는 `/proc` 명령줄 조회가 실패하면 종료를 거부한다. 이것은 실행기 시작 자체를 거부한다는 뜻이 아니다. 취소나 시간 제한 뒤에도 실행기가 스스로 끝날 때까지 기다릴 수 있다. Linux·macOS 제한은 README와 결과 문서에 적혀 있다. Windows PC의 설치·호환 시험 범위와는 맞지만 다중 OS 종료 기능의 완성으로 인정하지 않는다.

Windows 호출에는 정수 PID와 고정 DLL·함수 이름만 들어간다. 새 권한 상승이나 공급자 로그인 정보 접근은 없다. `NtResumeProcess`는 네이티브 API 의존이다. 함수 조회 실패를 오류로 처리하지만 모든 Windows 버전의 호환성을 증명하지는 않는다. SYSTEM 권한이나 디버그 특권을 요구하도록 변경한 코드는 없다.

## R2와 N1: 전달 결과

실행기의 성공과 서버 전달 성공은 분리됐다. `Execute`는 `EndReason: submitted`를 보존하면서 `Delivery` 오류를 반환한다. `Close`는 영구 거절의 dropped와 HTTP 200의 rejected를 오류로 만든다. 일시 오류의 재시도도 종료 시한을 넘기면 오류다. 세 경우 모두 추가 실행 시험에서 확인했다.

`--once`는 전달 오류가 있으면 코드 1을 반환한다. `main.go:75`의 취소 검사가 앞서므로 Ctrl+C가 함께 있으면 130이다. 이 부분은 코드 검토 결과다. `cmd/zm-baton-agent`에는 시험 파일이 없다.

계속 실행 모드는 전달 실패 뒤 다음 청구로 진행한다. `Run`은 `Execute`가 돌려준 Delivery를 누적하거나 호출자에게 전달하지 않는다. 오류는 Execute의 로그에 남는다. 실패한 사건은 다음 실행에서 재전송되지 않는다. 디스크 대기열이 시험 범위 밖이라는 계획과는 맞는다. 다만 계속 실행 모드에서 오류가 반환되거나 연결 프로그램이 중단된다고 읽어서는 안 된다. README에 이 동작을 명시하는 것이 좋다.

Close의 시한은 진행 중인 HTTP 요청에도 적용된다. loop의 context를 취소한 뒤 완료를 기다린다. 현재 HTTP Sender는 요청 context를 사용하므로 취소가 전달된다. context를 무시하는 임의의 Sender까지 강제로 중단하는 보장은 없다. `TestCloseDeadlineBoundsTheTimersSendInFlight`는 context를 따르는 느린 Sender로 이를 확인한다.

## 새 비막음 지적

### N6. CIM 응답을 생존 판정으로 단정하지 말아야 한다

- 위치: `apps/agent/internal/proc/kill_windows.go:99`, `:124`; `apps/agent/internal/proc/kill_windows_test.go:170`; `apps/agent/README.md:29`.
- 검토 항목: 7. 시험, 12. 문서.
- 확신: 높음. 생존 검사가 없다는 사실과 조회 거부를 직접 확인했다. 종료된 프로세스의 실제 CIM 응답은 미확인이다.
- 병합 막음: 아니오. PID 오종료 경로와는 별개다.
- 근거: Kill은 명령줄만 검사한다. 종료 후 거절 시험은 어떤 오류든 성공 조건을 충족한다. 이 환경에서는 살아 있는 도우미의 조회도 실패했으므로 그 시험의 성공만으로 종료 후 동작을 입증할 수 없다.
- 고칠 방향: 조회가 가능한 환경임을 먼저 확인한다. 종료 전·후 상태와 CIM 응답을 별도로 기록한다. 살아 있을 때만 동작한다는 정책이 필요하면 핸들 상태 검사도 둔다. 조회 뒤 자연 종료되는 경쟁까지 막았다는 표현은 피한다.

### N7. CIM 조회 오류의 원인이 표식 불일치로 가려진다

- 위치: `apps/agent/internal/proc/kill_windows.go:126`, `apps/agent/internal/agent/runner.go:315`.
- 검토 항목: 1. 실패 경로, 7. 시험, Go 오류 처리.
- 확신: 높음. 직접 CIM 조회가 접근 거부로 실패했다. proc 시험은 빈 명령줄과 표식 불일치를 보고했다.
- 병합 막음: 아니오. 조회 실패 때 종료를 거부하는 방향은 안전하다.
- 근거: PowerShell의 비종결 오류는 프로세스 실패 코드로 전파되지 않을 수 있다. 현재 Output 호출은 성공 코드일 때 stderr를 검사하지 않는다. 조회 프로세스 자체에도 시한이 없다.
- 고칠 방향: 조회 오류를 종결 오류로 처리한다. 빈 명령줄과 조회 실패를 구분한다. 조회에 시한을 주고 오류를 보존한다. 실행기를 끝낼 수 없을 때 계속 기다리는 정책도 문서에 적는다.

N3의 서버 시험 주석(`RunServiceConcurrencyTests.kt:129`)은 보고 연결 하나라는 전제를 함께 적는 것이 좋다. README에는 계속 실행 모드의 전달 실패 처리도 덧붙이는 것이 좋다. 두 항목은 기존 문구 범위 지적의 후속 정리다.

## 실행한 명령과 결과

환경은 Windows NT `10.0.26300.0`, PowerShell `5.1.26100.9549`, Git `2.50.1.windows.1`, Go `go1.27.1 windows/amd64`다.

Go 명령은 `apps/agent`에서 실행했다. 실행 파일은 `%LOCALAPPDATA%/Programs/go/bin`의 것을 사용했다. 해당 명령 프로세스에만 `GOROOT=%LOCALAPPDATA%/Programs/go`, `GOCACHE=<허용된 캐시 폴더>`, `GOTMPDIR=<허용된 임시 빌드 폴더>`를 지정했다. `GOFLAGS=-mod=mod`는 사용하지 않았다. 저장소 안으로 캐시나 빌드 산출물을 옮기지 않았다.

| 명령 | 결과 |
|---|---|
| `git status --short`, `git branch --show-current`, `git rev-parse HEAD` | 시작 시 깨끗함. 대상 브랜치와 커밋 일치 |
| `git diff --stat e971ae7..cef7fb0`, `git diff --numstat main..work/compat-agent` | 수정 범위와 전체 파일 크기 확인. 변경 파일에 1,000줄 초과 없음 |
| `Get-Content -Encoding utf8`, 범위별 `git diff`, `rg -n` | 기준·앞선 검토·계약·데이터 모델·코드·시험·문서 대조 |
| `go version`, `codex --version`, `git --version`, OS·PowerShell 버전 조회 | 위 버전 확인 |
| `gofmt -l .` | 종료 코드 0. 출력 없음 |
| `go vet ./...` | 종료 코드 0. 출력 없음 |
| `go test -count=1 ./...` | 종료 코드 1. api·claude·report 통과. proc 실패. agent 시간 초과 |
| `go test -v -count=1 -timeout=30s -run 'TestExecuteReportsASuccessfulRun\|TestUndeliveredEventsAreReportedEvenAfterASubmit' ./internal/agent` | 종료 코드 0. 최상위 시험 2개와 전달 실패 하위 시험 3개 통과 |
| 현재 PowerShell PID에 대한 `Get-CimInstance Win32_Process` | 접근 거부. 아래 진단 참조 |
| `git diff --check main..work/compat-agent` | 종료 코드 0. 공백 오류 없음 |
| diff의 경로·비밀값 패턴 검색과 내용 검토 | 소유자 절대 경로·실제 비밀값·회사 정보·사설 주소를 발견하지 못함. 경로 예시는 가상 사용자 이름이었다 |
| 설치된 Go 소스 및 Microsoft 공식 문서 조회 | 핸들 수명·Wait/Signal·작업 객체 소속과 상속 의미 대조 |

실패 출력은 다음과 같다. 통과로 집계하지 않는다.

```text
*** Test killed: ran too long (11m0s).
exit status 1
FAIL github.com/hanumoka/zm-baton/apps/agent/internal/agent 1371.441s
--- FAIL: TestKillRefusesWithoutAMatchingMarker (0.49s)
    kill_windows_test.go:123: helper command line "" does not carry the marker
--- FAIL: TestKillStopsTheExecutorAndItsChildrenWhileWaitRuns (0.46s)
    kill_windows_test.go:153: Kill with the right marker: refusing to kill pid 20184: command line does not contain the run marker
FAIL github.com/hanumoka/zm-baton/apps/agent/internal/proc 1.770s
FAIL
```

별도 조회의 진단 핵심 원문은 다음과 같다. 소유자 경로와 호출 위치 표시는 생략했다.

```text
Get-CimInstance : 액세스가 거부되었습니다.
CategoryInfo          : PermissionDenied: (:) [Get-CimInstance], CimException
FullyQualifiedErrorId : HRESULT 0x80041003,Microsoft.Management.Infrastructure.CimCmdlets.GetCimInstanceCommand
```

agent 전체 시험의 정지 위치는 출력에 없었다. CIM 실패만으로 시간 초과의 모든 원인을 확정하지 않는다. 종료 시험은 조회 거부 뒤 실행기를 끝내지 못할 수 있다. 추가 실행은 성공 및 전달 실패 경로만 검증했다.

## 검토 항목별 범위

수락 조건은 Windows 설치·호환 시험 범위로 보았다. 종료 조건은 독립 실행 검증이 미완료다. 계약의 상태·세대·사건 형식은 현재 코드와 대조했다. 임대·재개·디스크 보관은 계획의 제외 항목이다. 설정 격리와 비밀값 가리기의 미구현도 문서에 드러나 있다. 이를 완성 제품의 계약 준수로 인정하지 않는다.

권한 우회 옵션이나 공급자 자격 정보 접근을 추가한 코드는 발견하지 못했다. 이번 수정은 DB 구조나 업무 상태 전이 구현을 바꾸지 않는다. 서버 변경은 앞선 동시성 시험 보강이다. UI·접근성 항목은 해당 없음이다. 새 외부 Go 의존성은 없다. 제삼자 코드의 전체 출처는 독립 감사하지 않았다. 핵심 모듈 2·3은 기록 미작성 목록에 있다. 소유자의 설명 기록은 대신 쓰지 않았다.

결과 문서의 실제 Claude Code 시험과 「일부러 망가뜨려 확인」 표는 작성자 실행 보고로만 취급한다. 이번에 재현하지 않았다. 문서의 전체 Go 시험 통과 수치는 이 검토 환경의 결과와 다르다. 그 차이만으로 작성자 기록을 허위라고 판단하지 않는다. 단위 시험 개수와 시험 대상은 소스를 대조했다. 공개 금지 정보의 절대 부재를 보증하는 전용 비밀값 검사는 하지 않았다.

## 확인하지 않은 것과 판정 해소에 필요한 근거

- CIM 접근이 가능한 환경의 Windows 종료 시험 통과 결과를 독립 확인하지 못했다. 같은 커밋에서 proc 정상 종료·틀린 표식 거절·종료 후 거절과 agent 종료 시험을 확인해야 한다.
- 종료된 프로세스의 CIM 명령줄 반환 여부를 실측하지 못했다. 조회 자체가 거부되므로 빈 결과의 의미를 구분할 수 없었다.
- Track 실패 주입, 중첩 작업 객체, breakaway, WMI 간접 실행, 연결 프로그램 강제 종료는 실험하지 않았다.
- 실제 Claude 실행 파일을 띄우지 않았다. 실제 Ctrl+C·CLI 종료 코드·작성자의 변이 시험도 재현하지 않았다.
- Linux·macOS 실행, 교차 컴파일, race 검사, 별도 빌드 명령은 이번에 실행하지 않았다.
- 서버 Gradle·PostgreSQL 시험과 원격 PR의 일곱 항목·CI·위임 표시를 확인하지 않았다.
- 위협 모델·질문 경로·과금 문서·개별 결정 기록 전체는 이번에 정독하지 않았다.
- 자격 정보 파일과 환경변수 전체는 열지 않았다.

지정된 검토 기록만 작성했다. 구현 수정·브랜치 생성·커밋·push·원격 변경·main 변경·병합은 하지 않았다.
