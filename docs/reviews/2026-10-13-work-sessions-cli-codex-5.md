## AI 검토 결과

- 적용한 기준: **zm-baton 검토 기준 v0.5**, 검토 시 `origin/main`인 `02c1e6995f6085ed4d9fdd19acdab80bf0c0a618`의 `docs/review-standard.md`.
- 검토자: **Codex / codex-cli 0.162.0 / GPT-6**(실행 환경 안내 기준, 세부 모델 식별자 미확인) / PR #12의 5차 독립 코드 검토자.
- 검토 세션: `01a13300-c141-76f2-9158-ac18c4ba656e`. `CODEX_SESSION_ID`와 `CODEX_THREAD_ID`의 일치를 확인했다.
- 작성자: Claude Code / 작성 세션 `409394de-ef95-46af-bf81-42c556a8b817`(사용자 제공). 실행기와 세션이 모두 다르며 구현에 참여하지 않았다.
- 검토 브랜치·커밋: `work/sessions-cli` / **`9c583d37b272de7c2ca8b2109dd0ef4e78140fdd`**.
- 검토 범위: **`git diff 9b6db52 HEAD`**의 `apps/agent/cmd/zm-baton/main.go`, `apps/agent/cmd/zm-baton/main_test.go` 두 파일. 4차 기록을 읽고 변경되지 않았음을 확인했다. 이전 구현 전체는 재검토하지 않았다.
- 판정: **병합 가능 — 이번 변경분에서 R11 해소 확인, 새 병합 막음 지적 없음.** 아래의 기존 소유자 호출과 병합 직전 확인 의무는 유지한다.
- 소유자 호출 조건: **해당 — 4차의 조건 1 관련 보안 변경 종류 확장 적용 판정 유지**. 이번 `apps/agent/cmd/zm-baton/main.go` 변경은 명령 삽입 방지 수정이다. 두 변경 파일 자체는 조건 1·2의 보호 경로 표에 해당하지 않으며, 이전 검토의 호출 판정을 철회하지 않는다.
- 소유자 호출 근거: **확인 못 함**. GitHub 조회가 프록시 연결 거부로 실패해 PR 본문과 소유자의 결정 댓글을 확인하지 못했다.
- 기록 범위: 저장소에서는 이 검토 기록만 작성했으며 커밋하지 않았다. 합성 폴더·빌드 산출물·Go 캐시는 OS 임시 폴더에 두었다.

| 번호 | 위치 | 검토 항목 | 근거 | 확신 | 병합 막음 |
|---|---|---|---|---|---|
| R11 재검토 | `apps/agent/cmd/zm-baton/main.go:218`, `:224`, `:225`; `apps/agent/cmd/zm-baton/main_test.go:82`, `:86` | 1·7, v0.5 「지금도 지키는 선」 2 | 작은따옴표 구분자 다섯 문자를 각각 같은 문자로 두 번 쓴다. 현재 빌드가 출력한 명령에서 4차 주입이 차단되고 원래 경로가 보존됐다. 확장된 pwsh 회귀 시험도 통과했다 | 높음 | **아니요 — 해소** |

### R11 재현과 구분자 확인

4차와 같은 방식으로 아래 폴더 이름을 실제로 만들었다. `0x2019` 자리에 `0x27`, `0x2018`, `0x201A`, `0x201B`도 각각 넣었다.

```powershell
$leaf = ([char]0x2019).ToString() + '; Write-Output R4_INJECTED; #'
```

현재 커밋으로 빌드한 CLI의 `hooks uninstall --project <합성 폴더>`가 출력한 `cd` 줄을 그대로 실행했다. 같은 폴더를 `ZM_BATON_HOME`으로 지정하고 `hooks install --project <합성 폴더>`의 marketplace 줄도 실행했다. 이때 `claude`는 받은 인수만 반환하는 합성 함수로 대체했다.

| 입력 | 현재 빌드의 결과 | ASCII만 두 번 쓰는 이전 인용 방식의 대조 결과 |
|---|---|---|
| U+0027 | `cd`와 marketplace 경로 일치, 별도 표식 출력 없음 | 주입 없음 |
| U+2018·U+2019·U+201A·U+201B 각각 | 네 경우 모두 `cd`와 marketplace 경로 일치, 별도 표식 출력 없음 | 네 경우 모두 별도 `R4_INJECTED` 출력 |

현재 `cd` 명령의 구문 오류는 다섯 경우 모두 0개였다. 추가로 다섯 작은따옴표의 인접 조합 25개에 `$(Write-Output MIXED_INJECTED)`, 백틱, `#`, U+201C·U+201D·U+201E를 섞은 실제 폴더에서도 `cd`가 정확한 경로로 이동했고 별도 출력은 없었다. 실제 Claude 설정은 변경하지 않았다.

공식 언어 명세 §2.3.5.2의 `single-quote-character`는 **U+0027, U+2018, U+2019, U+201A, U+201B**다. 이 다섯 문자 외에 작은따옴표 문자열을 닫는 구분자는 명세에 없다. 큰따옴표 문자는 작은따옴표 문자열 내부에서 특별한 의미가 없다. [PowerShell 문자열 리터럴 명세](https://learn.microsoft.com/en-us/powershell/scripting/lang-spec/chapter-02?view=powershell-7.5#2352-string-literals)

이 명세는 과거 버전 기준이므로 PowerShell **v7.6.6** 구현도 대조했다. `IsSingleQuote`는 같은 다섯 문자만 인식하며, `ScanStringLiteral`은 연속된 작은따옴표 두 개 중 두 번째를 값에 넣는다. 따라서 각 문자를 자기 자신으로 두 번 쓰는 `main.go:225`는 문자 종류도 보존한다. 이 변경에서 누락된 추가 문자열 구분자는 발견하지 못했다. [문자 분류 구현](https://github.com/PowerShell/PowerShell/blob/v7.6.6/src/System.Management.Automation/engine/parser/CharTraits.cs#L253), [문자열 파서 구현](https://github.com/PowerShell/PowerShell/blob/v7.6.6/src/System.Management.Automation/engine/parser/tokenizer.cs#L2135)

### 검증 기록

대상은 `9c583d3`이다. Windows `10.0.26300`, PowerShell `7.6.6`, Go `go1.27.2 windows/amd64`, Git `2.53.0.windows.2`, Codex CLI `0.162.0`에서 확인했다. Go는 PATH에 없어 설치된 실행 파일을 직접 호출했고, `GOCACHE`·`GOTMPDIR`는 별도 임시 폴더로 지정했다. 아래 Go 명령의 작업 폴더는 `apps/agent`다.

| 명령·시험 | 결과 |
|---|---|
| `go test ./cmd/zm-baton -run '^TestPrintedPathsReachPowerShellUnchanged$' -count=1 -v` | 시험 1개 통과, 내부 입력 6개 모두 일치, 건너뜀 없음 |
| `go build -o <임시 폴더>/zm-baton.exe ./cmd/zm-baton` | 성공 |
| `go vet ./cmd/zm-baton` | 진단 출력 없음 |
| `gofmt -l cmd/zm-baton/main.go cmd/zm-baton/main_test.go` | 출력 없음 |
| `git diff --check 9b6db52 HEAD` | 종료 0 |
| 현재 CLI 출력 명령의 4차 주입 재현 | 작은따옴표 5종 × `cd`·marketplace 2경로 모두 통과 |
| 이전 ASCII 전용 인용 방식의 메모리 내 대조 실행 | 유니코드 4종 모두 주입 재현. 저장소 코드는 수정하지 않음 |
| 인접 작은따옴표와 특수문자 혼합 경로 | 25개 모두 경로 보존, 별도 명령 출력 없음 |
| `gh pr view 12 --repo hanumoka/zm-baton --json title,body,headRefOid,comments,statusCheckRollup` | **실패**: `proxyconnect tcp: dial tcp 127.0.0.1:9` 연결 거부 |

### 고도화 후보

- 이번 diff에서 새 후보 없음.
- 4차의 `canonical()` 정션 경계 한계와 호출 비용, H-09 관련 파일시스템 조회·세션 잠금 메모는 **기존 비차단 판정 그대로 유지**한다. 관련 코드는 이번 diff에서 바뀌지 않았다. 해당 진단 시험과 측정을 다시 수행하지 않았다.

### 부채 후보

- 이번 diff에서 새 부채 지적 없음.

### 확인하지 않은 것

- 원격 PR 필수 항목, 원격 HEAD·CI 상태, 소유자 호출·병합 결정 댓글. 조회 실패를 누락이나 통과로 판정하지 않았다.
- Linux·macOS, Windows PowerShell 5.1, 실제 Claude 설치·제거와 훅 실행, 전체 패키지 시험과 race 시험. 이번 독립 검증은 Windows의 변경된 인용 함수·회귀 시험·출력 명령에 한정했다.
- 작성자가 보고한 이전 코드 대상 Go 시험 실패는 직접 재실행하지 않았다. 위 대조 실행은 이전 인용 방식을 메모리에서 재현한 별도 시험이다.
- 변경되지 않은 이전 구현·설계 문서 전체. 이전 검토의 미확인 범위와 병합 전 확인 의무는 유지한다.
