## AI 검토 결과

- 적용한 기준: **zm-baton 검토 기준 v0.5**. 기준 커밋 `af264178348477f108dfb5f78f1fd6a1d3729fcd`의 `docs/review-standard.md`를 적용했다.
- 검토자: **Codex / codex-cli 0.162.0 / GPT-6**(실행 환경 안내 기준, 세부 모델 식별자 미확인) / 독립 검토자.
- 검토 세션: `01a132e8-6265-7c50-92ad-3dd6ec77c23a`. `CODEX_SESSION_ID`와 `CODEX_THREAD_ID`가 일치한다.
- 작성자: Claude Code / 세션 `409394de-ef95-46af-bf81-42c556a8b817`(사용자 제공). 작성자와 실행기·세션 번호가 다르며 구현에 참여하지 않았다.
- 검토 브랜치·커밋: `work/backlog-h10` / **`bf2a833366ebcc6a0f44ce8b2d1b370f1005fa06`**.
- 비교 기준: `origin/main` / `af264178348477f108dfb5f78f1fd6a1d3729fcd`. 공통 조상도 같다. 변경은 `docs/hardening-backlog.md`의 H-10·H-11 두 행뿐이다.
- 검토 범위: 두 행의 문제·현재 영향·해결 방향·출처·당기는 조건, 관련 코드와의 일치, 지키는 선 셋에 따른 유예 가능성만 확인했다.
- 판정: **병합 막음**. H-11에는 경로 안의 명령 실행으로 기존 자료를 덮어쓸 수 있는 경로가 있어, 단순 고도화 후보로 미룰 수 없다. H-10에는 병합 막음 지적이 없다.
- 소유자 호출 조건: **해당 — 조건 4**. H-11의 출처인 `work/sessions-cli` 3차 검토 C2의 유예 판정과 이번 판정이 다르다. 관련 위치는 `docs/hardening-backlog.md:32`, `apps/agent/cmd/zm-baton/main.go:207`, `:209`, `apps/agent/internal/session/plugin.go:38`이다. 이번 문서 변경 자체는 조건 1·2의 보호 경로에 해당하지 않는다.
- 소유자 호출 근거: **확인 못 함**. PR 본문과 소유자 결정 댓글은 이번 검토 범위에 포함하지 않았다.

| 번호 | 위치 | 검토 항목 | 근거 | 확신 | 병합 막음 |
|---|---|---|---|---|---|
| R1 | `docs/hardening-backlog.md:32` — H-11 | 7·12, v0.5 지키는 선 2 | 큰따옴표 안의 `$()`는 경로 문자로 보존되지 않고 PowerShell 명령으로 실행된다. `main.go:207`의 marketplace 명령과 `:209`의 프로젝트 이동 명령 형식을 그대로 재구성하여, 경로에 넣은 파일 덮어쓰기 명령이 호출됨을 모의 함수로 확인했다. 기본 설정·실행 파일 경로에 특수문자가 없더라도 `--project` 경로를 통해 도달한다. 기존 자료 손상 가능성을 특수문자 경로를 쓰게 될 때까지 미루는 분류는 선 2와 맞지 않는다 | 높음(명령 실행 재현), 중간(실제 파일 손상은 실행하지 않고 코드로 판정) | **예** |

### R1 근거와 필요한 조치

`main.go:180`은 `--project`를 절대 경로로 바꾸고, `:209`는 그 값을 큰따옴표로만 감싼 `cd` 명령으로 출력한다. 설정 폴더에도 같은 방식이 적용된다(`state.go:29`, `main.go:177`, `:207`). `plugin.go:38` 역시 실행 파일 경로를 큰따옴표로만 감싼다.

합성 폴더 이름 `Repo $(Set-Content -LiteralPath existing.txt -Value changed)`는 Windows 파일 이름 금지 문자를 포함하지 않는다. 이 이름을 포함한 프로젝트 경로로 출력 명령을 만들면, 사용자가 안내된 명령을 실행할 때 `cd`보다 먼저 `Set-Content`가 실행된다. 상대 대상 `existing.txt`는 당시 작업 폴더의 기존 자료일 수 있다. `hooks install` 자체가 명령을 자동 실행한다는 주장은 아니다. 설치 안내를 따르는 단계에서 발생한다.

다음은 실제 파일 쓰기와 디렉터리 이동을 모두 모의 함수로 대체한 재현이다. 경로는 합성 값이며 소유자 PC의 경로가 아니다.

```powershell
& {
    $state = @{ Called = $false; Target = ''; Value = ''; Received = '' }
    function Set-Content {
        param([string]$LiteralPath, [string]$Value)
        $state.Called = $true
        $state.Target = $LiteralPath
        $state.Value = $Value
    }
    function Set-Location {
        param([string]$Path)
        $state.Received = $Path
    }
    $project = 'C:\Review\Repo $(Set-Content -LiteralPath existing.txt -Value changed)'
    $printedCommand = '  cd "{0}"' -f $project
    & ([scriptblock]::Create($printedCommand))
    $state
}
```

결과는 `Called=True`, `Target=existing.txt`, `Value=changed`였으며 전달된 경로에서도 명령식이 사라졌다. marketplace 명령 형식에서도 같은 호출을 확인했다. 실제 파일 쓰기는 하지 않았다.

H-11의 문제·해결 방향·출처는 C2와 일치한다. 현재 기본 위치에 셸 확장 문자가 없다는 설명도 이 실행 환경에서 확인했다. 그러나 이 사실로 기존 자료를 바꿀 수 있는 경로까지 유예할 수는 없다. **#12에서 경로가 명령으로 평가되지 않도록 수정하고 거부·보존 시험으로 재검토받아야 한다.** H-11은 그 처리 상태를 반영해야 하며, 「특수문자가 들어갈 때」까지 기다리는 항목으로 승인하지 않는다. 기존 C2 판정과의 차이는 소유자 호출 조건 4에 따라 처리한다.

### 고도화 후보

- **H-10 유지 가능.** `commands.go:23`은 요약의 공백·빈 값·길이를 검사하지만 절대 경로는 검사하지 않는다. `:46`에서 `summary`·`refs`를 사건에 넣고 `:58`에서 전송한다. 자동 파일 경로는 `hook.go:225`에서 상대 경로로 바꾸며, 저장소 경로는 `git.go`에서 정리한다. 직접 입력 방어라는 문제, 허용할 참조 형식·경로 처리·거부 시험이라는 방향, 3차 검토 C1이라는 출처, 공유 확대 전이나 사용상 불편이 생길 때 당긴다는 조건은 일치한다.
- H-10의 현재 영향은 목록의 **이 PC 안에서 사용하는 1단계 환경**을 전제로 판정했다. 기본 서버는 `main.go:132`의 `http://127.0.0.1:18081`이며, 입력을 공개 저장소에 쓰거나 push하는 동작은 해당 전송 경로에 없다. 이 입력 방어 문제에서 기존 설정·자료 손상이나 서버 수신 범위 확대는 확인되지 않았다. 세 선에 따른 막음 대상은 아니다.
- 표시 범위는 구분했다. 현재 세션 현황과 다른 세션의 상태 카드에 나오는 것은 **공유된 요약**이다. `--private`이면 공유하지 않으며, 참조 목록 자체가 그 화면·카드에 표시되는 것은 아니다. H-10의 경로 전송 문제와 유예 가능성은 유지되지만, 두 입력이 모두 항상 표시된다는 뜻으로 확인한 것은 아니다.
- H-11은 R1에 따라 유예 대상에서 제외한다. 새로운 고도화 후보는 추가하지 않는다.

### 부채 후보

이번 범위에서 추가한 부채 후보는 없다.

### 확인한 것과 검증 기록

환경은 Windows `10.0.26300.0`, PowerShell `7.6.6`, Git `2.53.0.windows.2`, Codex CLI `0.162.0`이다.

| 명령·확인 | 결과 |
|---|---|
| `git diff af26417 HEAD -- docs/hardening-backlog.md` | H-10·H-11 두 행 추가 확인 |
| `git show af26417:docs/review-standard.md` | v0.5의 세 선·고도화 분류·기록 양식 확인 |
| `git show work/sessions-cli:docs/reviews/2026-10-13-work-sessions-cli-codex-3.md` | C1·C2와 출처 대조 |
| `git show work/sessions-cli:<파일>` | `commands.go`, `plugin.go`, `main.go`와 전송·설정·자동 경로 처리에 필요한 관련 부분 확인 |
| `git diff 559df13 work/sessions-cli -- apps/agent/internal/session/commands.go apps/agent/internal/session/plugin.go apps/agent/cmd/zm-baton/main.go` | 차이 없음. 출처 검토 당시와 대상 세 파일 동일 |
| PowerShell 합성 명령 해석 | 변수 확장에 따른 경로 변형, marketplace·프로젝트 이동 명령의 명령식 실행 재현. 모의 함수만 사용 |
| 공백 경로 인수 전달 | marketplace 경로가 한 인수로 보존됨. 이번 확인은 출력 형식의 합성 시험 |
| 기본 경로의 문자 검사 | `%AppData%\zm-baton`, `%LOCALAPPDATA%\zm-baton\bin` 확장 결과에 `$`, 백틱, 큰따옴표 없음. 실제 절대 경로는 기록하지 않음 |
| `git diff --check af26417 HEAD` | 종료 0 |
| 조건 1·2의 전체 경로 표에 대한 `git diff --no-renames --name-only af26417 HEAD -- ':(top,glob)…'` | 각각 출력 0줄 |

코드 대조 당시 `work/sessions-cli`는 `50f6b4291870ac4c93cf21c7baaeda4b009ef27e`였다. H-10의 표시 설명만 보조 확인하기 위해 `work/sessions-server`의 `159c6ce713a72866bf11857bef0d1e71ff345970`에서 `SessionService.kt:248`, `:440`, `:519`와 `work/sessions-web`의 `b0aeed4e2032c059c7522e7ad0ace103adbc63ab`에서 `Sessions.tsx:63`을 읽었다. 해당 PR들의 다른 동작은 검토하지 않았다.

### 확인하지 않은 것

- #12 전체, 다른 고도화 항목, 비공개 계약 전체, 원격 PR·CI·병합 절차. 이번 판정은 요청한 두 행에 한정한다.
- 실제 `zm-baton` 바이너리를 통한 재현, 실제 Claude 설정의 설치·제거, Git Bash에서의 훅 실행, Linux·macOS. 명령 실행 재현은 확인한 Go 출력 형식을 PowerShell 문자열로 재구성한 시험이다.
- 실제 기존 파일의 덮어쓰기·삭제. 손상 명령은 모의 함수로 대체했으며 재현 중 기존 자료를 변경하지 않았다.
- Go·서버·웹 전체 시험과 화면 실행. 원본 검토의 공백 경로 시험 결과는 원본 기록으로 확인했으며 이번 실행 결과로 바꾸어 적지 않았다.

지정된 검토 기록 파일만 작성했다. 구현과 백로그는 수정하지 않았으며 커밋하지 않았다.
