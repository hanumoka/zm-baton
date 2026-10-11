## AI 검토 결과

- 적용한 기준: **zm-baton 검토 기준 v0.3**, 기준 커밋 `ff5e5ab7e1390e600a0f1423e4188c58e004103d`의 `docs/review-standard.md`. v0.3의 새 기준 적용 절차에 따라 v0.4는 검토 대상이며, 사용자가 제공한 DEC-0096 1항과 대조했다.
- 검토자: Codex / CLI `0.162.0`(`CODEX_VERSION`) / GPT-6(실행 환경 표기, 세부 모델 식별자 미확인) / 독립 검토자, 2차.
- 검토 세션: `01a129d8-9489-7463-b325-5c4d261318c4`. `CODEX_SESSION_ID`와 `CODEX_THREAD_ID`에서 확인했다.
- 작성 세션: `409394de-ef95-46af-bf81-42c556a8b817`(사용자 제공). 작성 실행기는 1차 기록의 Claude Code / Opus를 따랐다. 검토자는 작성자와 실행기·세션이 다르며, 작성 대화를 이어받지 않고 검토 지시와 저장소 자료를 받았다.
- 검토한 브랜치·커밋: `work/owner-escalation` / `5460c5cecf4d8e48e31d1096b896422d8a9cdc1d`.
- 비교 기준: `origin/main` / `ff5e5ab7e1390e600a0f1423e4188c58e004103d`. 공통 조상도 이 커밋이다.
- 판정: **병합 막음**. 아래 **R2-1·R2-2는 심각도 높음**으로 병합을 막는다. R2-3·R2-4는 병합을 막지 않는다.
- 소유자 호출 조건: **해당 — 조건 1**. `AGENTS.md`, `docs/review-standard.md`, `.github/pull_request_template.md`가 정책 경로다. 제공된 DEC-0096에 따른 판단이며, 미병합 v0.4를 이 PR의 적용 기준으로 삼은 것은 아니다.
- 소유자 호출 근거: **확인 못 함**. 원격 PR 조회가 실패하여 실제 호출·소유자 결정·병합 시점을 확인하지 못했다. 호출 누락을 0건으로 판정하지 않는다.
- 기록: `docs/reviews/2026-10-11-work-owner-escalation-codex-2.md`. 이 파일만 작성하고 커밋하지 않는다. 1차 기록은 `9cac3a8` 이후 변경되지 않았음을 확인했다.

위치는 검토 대상 HEAD 기준이다. 검토 항목 번호는 v0.3의 열두 가지 항목을 따른다.

### 1차 지적 1~6의 처리 상태

| 1차 번호 | 상태 | 위치와 확인 결과 |
|---|---|---|
| 1 | **일부 해결** | `docs/review-standard.md:154`, `:157`, `:158`, `:159`, `:160`: 중첩 `.gitignore`, agent `runner.go`·`main.go`, desktop `main.ts`, `RunService.kt`가 모두 매칭된다. 주요 보안 경로 누락은 해결됐다. `:177`은 `apps/server/build.gradle.kts`와 각 앱의 `package.json`을 의도적으로 제외하고 검사 축소를 「정책 문서」로 분류한다. 검사 정의 파일까지 경로로 보호하는 부분은 남았으나, 이제 명시된 범위 제안이므로 이것만으로 병합을 막지는 않는다. |
| 2 | **일부 해결** | `docs/review-standard.md:167`, `:168`, `:169`: 공통 조상, `--no-renames`, glob·대소문자 정의를 추가했다. 이름 변경의 이전·이후 경로 출력도 재현했다. 다만 `:169`·`:170`의 명령은 저장소 뿌리에 고정되지 않아 R2-1이 남는다. |
| 3 | **해결** | `docs/review-standard.md:394`: 소유자 호출 대상은 소유자 결정, 그 밖의 계약·의존성 변경은 AI의 판단 이유·위임 표시로 분리했다. `:16`, `:27`, `:208`, `:419` 및 `AGENTS.md:42`·`:43`과 일치한다. |
| 4 | **일부 해결** | `docs/review-standard.md:187`, `:189`, `:386`, `:403`: 호출 근거·미확인 상태와 집계 담당·시점·기록 위치가 생겼다. 그러나 `:188`의 새 집계 규칙에는 실제 누락을 잘못 세는 R2-2가 있다. |
| 5 | **해결** | `docs/review-standard.md:172`, `:174`, `:175`, `:177`: 경로 밖 변경 종류로 호출하기, DB·배포 변경 전체를 비가역으로 보기, 그 밖의 비가역 변경의 미정 범위가 제안과 소유자 확인 대상으로 드러난다. 표시 누락은 해결됐으며 범위의 채택 여부는 소유자가 정한다. |
| 6 | **해결** | `AGENTS.md:38`, `docs/review-standard.md:69`·`:78`: PR 준비 요건이 실제로 8개가 됐다. `:378`·`:386`: 검토 결과 요건도 7개로 맞췄다. `.github/pull_request_template.md:1`부터의 제목 10개는 준비 요건 수와 다른 구분이며, AGENTS도 양식의 「모든 항목」이라고 명시한다. |

### 수정으로 새로 생긴 지적

| 번호 | 위치 | 검토 항목 | 근거 | 확신 | 병합 막음 |
|---|---|---|---|---|---|
| R2-1 | `docs/review-standard.md:169`, `:170` | 1. 수락 조건 / 3. 권한 / 12. 문서 절차 | **높음 — 경로 판정 명령이 실행 위치에 따라 호출을 놓친다.** `:(glob)`는 현재 디렉터리 기준이다. 표는 저장소 뿌리 기준이라고 하지만 명령에는 `top`도 뿌리로 이동하는 단계도 없다. 표 전체로 뿌리에서 실행하면 이번 정책 변경 3개가 나오지만, `git -C apps/server diff`로 같은 비교를 실행하면 **출력 0줄·종료 코드 0**이다. `git ls-files`도 뿌리에서는 23개, 해당 하위 폴더에서는 `.gitignore` 하나만 반환한다. 문구대로 빈 결과를 비해당으로 보면 필수 호출을 건너뛴다. `:(top,glob)`로 고정하거나 명시적으로 저장소 뿌리에서 실행하게 해야 한다. `top`을 붙인 재시험에서는 하위 폴더에서도 변경 3개·추적 파일 23개가 나온다. | 높음 | **예** |
| R2-2 | `docs/review-standard.md:188` | 1. 수락 조건 / 6. 보고의 사실성 / 12. 문서 절차 | **높음 — 새 집계식이 호출 누락과 검토자의 분류 오류를 혼동하며 병합 시점도 보존하지 않는다.** 두 번째 문장은 검토가 「해당 없음」이어도 재매칭되면 1건으로 세면서 소유자 결정의 부재를 확인하지 않는다. 작성자가 조건 1을 발견해 소유자 결정을 받고 `:419`를 지켜 병합했어도 검토자의 표기가 틀리면 누락 1건이 된다. 반대로 첫 문장은 집계할 때 PR 칸에 결정이 있는지만 보므로, 호출 없이 병합한 뒤 결정을 추가하면 실제 누락이 사라진다. 두 분기 모두 **병합 전에 필요한 호출·결정이 있었는지**로 판정하고, 집계 기록에 대상 PR·검토 커밋·시점이 확인되는 근거와 확정/미확인 결과를 남겨야 한다. 현재 규칙으로는 DEC-0096의 누락 건수를 신뢰할 수 없다. | 높음 | **예** |
| R2-3 | `docs/review-standard.md:69`, `:78`, `:182`, `:183`; `AGENTS.md:38`, `:42`; `.github/pull_request_template.md:24` | 12. 문서의 내부 일관성 | **중간 — 소유자 결정이 필요한 시점이 앞뒤에서 다르다.** 새 8번을 검토 요청 전 필수 항목으로 두면서 소유자의 결정까지 요구한다. 반면 호출 절차는 독립 검토 뒤 결정을 남기며, AGENTS도 독립 검토 뒤 8번에서 호출을 확인한다. 「요청함·결정 대기」로 검토를 시작할 수 있다는 상태가 없어 정상 대기와 필수 항목 누락을 구분하기 어렵다. 검토 전에는 조건·호출 상태를, 병합 직전에는 병합 결정을 요구하도록 정리하면 된다. 승인 우회를 허용하는 결함은 아니므로 병합을 막지 않는다. | 높음 | 아니오 |
| R2-4 | `docs/review-standard.md:170`, 대조 `:144`, `:145`, `:162`, `:163` | 12. 문서의 내부 일관성 | **낮음 — 표 전체의 매칭을 모두 조건 1로 기록하게 한다.** 표에는 조건 2용 마이그레이션·배포 경로도 포함된다. 예를 들어 마이그레이션만 바뀐 PR도 `:170`대로면 조건 1로 적게 된다. 호출 자체는 이루어지므로 병합을 막지는 않지만, PR 양식이 요구하는 조건 번호가 부정확해진다. 출력 경로를 표의 분류와 대응시켜 조건 1·2를 구분해야 한다. | 높음 | 아니오 |

R2-2는 문서 규칙에 반례를 적용한 결과이며, 실제 PR에서 누락이나 사후 승인이 일어났다는 주장은 아니다. 집계 담당자가 소유자이고 결과를 `docs/reviews/`에 남기도록 한 개선은 유효하다.

### 경로 명령과 검증 결과

환경: Windows `10.0.26300`, PowerShell `7.6.6`, Git `2.53.0.windows.2`, Codex CLI `0.162.0`, GitHub CLI `2.93.0`. 아래 저장소 검사는 검토 대상 `5460c5c`에서 수행했다.

표의 pathspec 21개를 작은따옴표로 각각 넘긴 요청 형태의 명령과, 표에서 직접 추출한 배열을 넘긴 명령을 모두 실행했다. `NOTICE`가 두 번 나와 고유 패턴은 20개다. 배열 재현 방법은 다음과 같다.

```powershell
$rows = (Get-Content docs/review-standard.md)[151..162]
$patterns = @($rows | ForEach-Object {
    [regex]::Matches(($_ -split '\|')[2], '`([^`]+)`')
} | ForEach-Object { $_.Groups[1].Value })
$specs = @($patterns | ForEach-Object { ':(glob)' + $_ })
git diff --no-renames --name-only $(git merge-base origin/main HEAD) HEAD -- @specs
git ls-files -- @specs
```

| 실행·확인 | 결과 |
|---|---|
| 저장소 뿌리에서 위 `git diff`와 `git ls-files` | 모두 종료 코드 0. diff는 `.github/pull_request_template.md`, `AGENTS.md`, `docs/review-standard.md` 3개. 추적 파일 98개 중 23개 매칭 |
| 고유 패턴마다 `git ls-files -- <pathspec>` | 의도하지 않은 0개 매칭은 뿌리에서 발견하지 않았다. `NOTICE`, `**/auth/**`, `**/security/**`, `**/permission/**`만 0개이며 `docs/review-standard.md:161`이 미래 경로로 명시한다 |
| `**/.gitignore` 및 새 보안 경로 확인 | 루트 `.gitignore`와 `apps/server/.gitignore` 모두 매칭. 추가된 agent·desktop·`RunService.kt`도 각각 매칭 |
| `git -C apps/server diff --no-renames --name-only $(git merge-base origin/main HEAD) HEAD -- @specs` 및 같은 위치의 `git ls-files` | diff 0줄·종료 코드 0, ls-files는 `.gitignore` 1개. R2-1 재현 |
| 같은 하위 폴더 명령에서 모든 `:(glob)`를 `:(top,glob)`로 교체 | diff 3개, ls-files 23개. 저장소 뿌리 결과와 일치 |
| `git diff -M --name-status HEAD:apps HEAD:apps/agent -- '*runner.go'` | `agent/internal/agent/runner.go` → `internal/agent/runner.go`의 `R100` 확인 |
| `git diff --no-renames --name-only HEAD:apps HEAD:apps/agent -- ':(glob)agent/internal/agent/runner.go' ':(glob)internal/agent/runner.go'` | 이전·이후 경로 모두 출력. 기존 Git 트리만 비교했고 파일·Git 객체를 만들지 않음 |
| 대소문자 확인: `git ls-files -- ':(glob)agents.md'`와 `git ls-files -- ':(glob)AGENTS.md'`를 각각 실행 | `core.ignorecase=true`인 이 환경에서도 소문자는 0개, 정확한 이름은 `AGENTS.md` 1개 출력 |
| Git Bash로 같은 전체 경로 명령 실행 시도 | 셸 시작 중 `CreateFileMapping`, `Win32 error 5`로 실패(종료 코드 256). Git 명령까지 실행되지 않았으며 Bash 통과로 기록하지 않음 |
| `wsl --list --quiet` | `Wsl/EnumerateDistros/Service/E_ACCESSDENIED`. Linux 셸 시험 환경을 확보하지 못함 |
| `git diff --check ff5e5ab HEAD` | 종료 코드 0, 공백 오류 없음 |
| `git diff --exit-code 9cac3a8 HEAD -- docs/reviews/2026-10-11-work-owner-escalation-codex.md` | 종료 코드 0, 1차 기록 변경 없음 |
| `git diff --numstat ff5e5ab HEAD`, 변경 파일 줄 수 | 전체 추가 176줄·삭제 39줄. 기준 468줄, AGENTS 86줄, PR 양식 27줄, 1차 기록 73줄. 모두 500줄·1,000줄 기준 미만 |
| `gh pr view work/owner-escalation --json number,url,baseRefName,headRefName,headRefOid,isDraft,body,statusCheckRollup` | 실패: `none of the git remotes configured for this repository point to a known GitHub host.` 인증·원격 설정은 변경하지 않음 |

따라서 **Windows PowerShell의 저장소 뿌리 실행은 통과**, **하위 폴더 실행은 판정 누락 재현**, **Linux/Bash 실행은 미확인**이다. 환경의 셸 접근 거부를 문서 명령의 문법 결함으로 판정하지 않았다.

### 문서 사이에서 추가로 확인한 것

- 소유자 결정과 AI 위임 판단을 구분하는 본문·AGENTS·PR 양식의 핵심 분기는 일치한다. 남은 절차 차이는 R2-3의 결정 시점이다. AGENTS는 기준의 상세 절차를 참조하므로, 소유자가 AI에게 병합 실행을 맡기는 `docs/review-standard.md:183`을 별도의 충돌로 보지 않았다.
- PR 양식의 소유자 결정 칸과 「병합 직전」 `docs/review-standard.md:419`는 병합 전 결정이 필요하다는 점에서 일치한다. R2-2의 사후 집계식은 이 시점을 제대로 반영하지 않는다.
- 검사 정의 파일의 의도적 제외와 변경 종류에 의한 보완은 소유자가 확정할 범위다. `.github/workflows/ci.yml`이 Gradle·npm 검사 명령을 호출하고, 해당 정의가 `build.gradle.kts`·`package.json`에 있는 것은 확인했다. 이 파일들은 예약 패턴의 0개 매칭 사례와 다르다.
- `docs/review-standard.md:52`의 DEC-0088 요약은 바로 뒤 DEC-0096의 제한과 함께 읽었다. 과거 결정 요약을 모든 호출 대상에 대한 새 위임 허용으로 보지 않았다. 2026-10-05의 판단 세 건 사후 인정도 `:468`에 유지되어 있다.

### 부채 후보

- 기술부채 후보: 경로 표와 판정 명령을 함께 검증하는 작은 도구. 뿌리·하위 폴더, 삭제·이름 변경, 예약 경로, Windows·Linux를 확인하면 실행 위치에 따른 누락을 막기 쉽다.
- 인지부채 후보: 검사 정의 파일을 경로 판정에서 제외하는 이유와 소유자가 채택한 범위를 PR에 남길 필요가 있다. 집계에서도 「호출 누락」, 「검토 분류 오류」, 「결정 근거 미확인」을 구분해야 수치의 뜻이 유지된다.
- 부채 채택은 소유자가 정한다. R2-1·R2-2의 현재 결함을 부채로 미뤄 병합할 근거는 아니다.

### 확인하지 않은 것

- 원격 PR 본문·댓글·소유자 결정·검증 결과·초안 여부·원격 HEAD·CI·브랜치 보호. 조회 실패로 확인하지 못했으며 통과로 간주하지 않았다.
- 실제 Linux와 Bash에서의 Git 명령 결과. Git Bash도 Windows 프로그램이므로, 실행됐더라도 Linux 시험을 대신하지는 않는다. macOS와 Windows `cmd.exe`는 시험하지 않았다.
- 비공개 `zm-docs` 원문과 관련 계약·다른 결정 전체. 결정 대조는 사용자가 제공한 DEC-0096 1항과 기준 커밋의 v0.3에 한정했다.
- Go·Kotlin·Web·Electron의 빌드·단위·통합·거부 시험 및 추적 파일 98개 전체의 보안 감사. 이번 변경은 정책 문서와 검토 기록이며, 기존 코드는 경로·검사 정의 확인 범위에서 읽었다.
- 제품 계약·의존성·라이선스·DB·배포 코드 변경과 핵심 모듈 첫 구현은 이번 diff에 없어 해당 검사는 해당 없음이다. v0.3 항목 1·3·5·6·8·11·12의 문서 변경 관련 부분을 검토했으며, 제품 동작 시험을 요구하는 항목 4·7을 통과했다고 주장하지 않는다.
