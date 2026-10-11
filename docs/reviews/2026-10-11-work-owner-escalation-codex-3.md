## AI 검토 결과

- 적용한 기준: **zm-baton 검토 기준 v0.3**, 기준 커밋 `ff5e5ab7e1390e600a0f1423e4188c58e004103d`의 `docs/review-standard.md`. v0.4는 검토 대상이다. 결정 대조에는 사용자가 제공한 DEC-0096 1항을 사용했다.
- 검토자: Codex / CLI `0.162.0` / GPT-6(세션 안내 기준, 세부 모델 식별자 미확인) / 독립 검토자, 3차.
- 검토 세션: `01a129e2-bfdc-71d1-bec5-6754c7104bfa`. `CODEX_SESSION_ID`와 `CODEX_THREAD_ID`에서 확인했다.
- 작성 세션: `409394de-ef95-46af-bf81-42c556a8b817`(사용자 제공). 작성 실행기는 앞선 기록의 Claude Code / Opus를 따랐다. 실행기와 세션 번호가 모두 다르며, 작성 대화 대신 검토 지시와 저장소 자료를 받았다.
- 검토한 브랜치·커밋: `work/owner-escalation` / `3928286a5aa89923f986504d08651d2fdc34315e`.
- 비교 기준: `origin/main` / `ff5e5ab7e1390e600a0f1423e4188c58e004103d`. 공통 조상도 같다. 이번 수정은 `git diff c5e3237 HEAD`로 분리했다.
- 판정: **병합 막음**. R2-1~R2-4의 직접 원인은 해결됐으나, 새 집계 절차의 **R3-1·R3-2는 심각도 높음**으로 병합을 막는다.
- 소유자 호출 조건: **해당 — 조건 1**. `AGENTS.md`, `docs/review-standard.md`, `.github/pull_request_template.md`가 정책 경로다. 제공된 DEC-0096에 근거한 판단이며, v0.4를 이 PR의 적용 기준으로 삼은 것은 아니다.
- 소유자 호출 근거: **확인 못 함**. 원격 PR 조회가 실패하여 실제 호출·결정 댓글·시각을 확인하지 못했다. 호출 누락 0건으로 간주하지 않는다.
- 기록: `docs/reviews/2026-10-11-work-owner-escalation-codex-3.md`만 작성했다. 커밋하지 않았다. 1·2차 기록은 각각 `9cac3a8`, `c5e3237` 이후 변경되지 않았다.

위치는 검토 대상 HEAD 기준이며, 검토 항목 번호는 v0.3의 열두 가지 항목을 따른다.

### 2차 지적별 처리 상태

| 번호 | 상태 | 위치와 확인 결과 |
|---|---|---|
| R2-1 | **해결** | `docs/review-standard.md:169`, `:170`: `:(top,glob)`로 매칭 기준을 저장소 뿌리에 고정했다. 조건 1·2를 각각 루트와 `apps/server`에서 실행하여 동일한 변경 파일을 확인했다. `git ls-files`도 동일한 파일을 찾는다. 출력 경로 표기의 차이는 아래에 구분했다. |
| R2-2 | **해결** | `docs/review-standard.md:185`, `:189`, `:191`, `:192`, `:193`, `:194`: 호출 누락과 검토 분류 오류를 분리하고, 병합보다 앞선 결정 댓글을 확인하며, 미확인 상태와 건별 시각을 기록한다. 잘못된 검토 분류만으로 누락을 더하거나 병합 뒤 새 댓글로 누락을 지우던 직접 원인은 해결됐다. 새 재판정 방식의 별도 결함은 R3-1·R3-2다. |
| R2-3 | **해결** | `docs/review-standard.md:78`, `:181`, `:183`, `:391`, `:424`; `AGENTS.md:42`; `.github/pull_request_template.md:24`: 검토 요청 때는 「물음, 결정 대기」가 허용되고, 소유자의 병합 결정 댓글은 병합 전에 요구한다. 검토 이전에 결정을 받아야 하는 순서 모순이 없어졌다. |
| R2-4 | **해결** | `docs/review-standard.md:162`, `:163`, `:170`: 「되돌릴 수 없음」 행은 조건 2, 나머지는 조건 1로 나누었다. 기존 UI 병합을 실행했을 때 desktop 보안 경로는 조건 1, `application.properties`는 조건 2로 구분됐다. |

### 이번 수정으로 생긴 지적

| 번호 | 위치 | 검토 항목 | 근거 | 확신 | 병합 막음 |
|---|---|---|---|---|---|
| R3-1 | `docs/review-standard.md:188`, `:192`; 대조 `:167`, `:168` | 1. 수락 조건 / 6. 보고의 사실성 / 12. 문서 절차 | **높음 — squash 결과만으로 병합 전 호출 대상을 재구성할 수 없다.** 새 규칙은 조건 1·2를 병합 부모와 결과의 차이만으로 다시 정하고 기존 검토 판정은 사용하지 않는다. 그러나 병합 전 호출 기준은 PR head와 공통 조상의 차이다. PR의 보호 경로 변경과 같은 변경이 다른 PR을 통해 먼저 main에 들어오면, 기존 PR은 공통 조상 비교에서는 여전히 호출 대상이지만 squash가 main에 더하는 차이에서는 그 경로가 사라진다. 일반 파일 변경도 포함된 PR이라면 그대로 병합될 수 있다. 결정 없이 병합해도 새 집계는 비해당으로 보아 누락을 놓치고, 올바른 기존 검토를 「분류 오류」로 기록할 수 있다. 병합 당시 PR head·비교 기준과 그때 적용한 호출 판정을 보존하여 재검증하고, 부모 비교는 실제 반영 변경을 확인하는 보조 자료로 써야 한다. | 높음 | **예** |
| R3-2 | `docs/review-standard.md:188`; 대조 `:174`, `:177`, `:198`, `:213` | 1. 수락 조건 / 6. 보고의 사실성 / 12. 문서 절차 | **높음 — 경로 표 밖에서 변경 종류 때문에 호출해야 하는 PR이 집계에서 빠진다.** 본문은 표 밖의 권한·보안·시크릿·DB·배포·정책 변경도 호출하도록 했지만, 새 재판정은 조건 1·2에 경로 명령만 쓰고 3~5에는 판정 불가·이견·면제만 찾는다. 예를 들어 `apps/web/package.json`의 검사 범위를 줄이는 변경은 `:177`에 따라 정책 문서로 표시해 호출해야 한다. 이 파일은 표에 매칭되지 않는다. 검토자가 호출 대상으로 올바르게 적었어도, 이견·면제·판정 불가 없이 결정 댓글 없이 병합하면 새 절차는 비해당으로 재분류한다. 이전 절차의 「검토 결과가 해당」 분기에서는 잡히던 사례다. PR의 변경 종류와 실제 diff도 다시 확인하여 `:174`의 보완 규칙을 집계에 포함해야 한다. | 높음 | **예** |

두 지적은 새 절차가 잘못된 건수를 낼 수 있다는 검토 결과이며, 기존 PR에서 실제 호출 누락이 있었다는 주장이 아니다.

### 경로 명령 검증

환경: Windows `10.0.26300.0`, PowerShell `7.6.6`, Git `2.53.0.windows.2`, Codex CLI `0.162.0`, GitHub CLI `2.93.0`.

표의 패턴을 조건별로 추출했다. 조건 1은 18개 항목이며 `NOTICE` 중복을 빼면 17개, 조건 2는 3개다. 아래와 같이 문서의 생략 부호를 실제 패턴으로 채워 실행했다.

```powershell
$rows = (Get-Content docs/review-standard.md)[151..162]
$spec1 = @()
$spec2 = @()
foreach ($row in $rows) {
    $cells = $row -split '\|'
    $specs = @([regex]::Matches($cells[2], '`([^`]+)`') |
        ForEach-Object { ':(top,glob)' + $_.Groups[1].Value })
    if ($cells[1].Trim() -eq '되돌릴 수 없음') {
        $spec2 += $specs
    } else {
        $spec1 += $specs
    }
}
$base = git merge-base origin/main HEAD
# $spec1과 $spec2를 각각 $specs에 넣어 아래 네 명령을 실행했다.
git diff --no-renames --name-only $base HEAD -- @specs
git -C apps/server diff --no-renames --name-only $base HEAD -- @specs
git ls-files -- @specs
git -C apps/server ls-files -- @specs
```

| 비교 | 루트 | `apps/server` | 결과 |
|---|---|---|---|
| 조건 1 diff | 정책 파일 3개 | 같은 3개 | 출력까지 같음, 종료 코드 0 |
| 조건 2 diff | 0개 | 0개 | 출력까지 같음, 종료 코드 0 |
| 조건 1 ls-files | 19개 | 같은 파일 19개 | 매칭 같음, 종료 코드 0 |
| 조건 2 ls-files | 4개 | 같은 파일 4개 | 매칭 같음, 종료 코드 0 |

조건 1의 diff는 `.github/pull_request_template.md`, `AGENTS.md`, `docs/review-standard.md`다. 조건 2의 추적 파일은 `application.properties`, `V1__stage1_core.sql`, `deploy/compose/.env.example`, `deploy/compose/compose.yaml`이다.

**ls-files의 원문 출력은 실행 위치에 따라 다르다.** 예를 들어 루트의 `AGENTS.md`는 하위 폴더에서 `../../AGENTS.md`로 나온다. `top`은 매칭 기준을 고정하며 출력 표기까지 고정하지는 않는다. 양쪽 명령에 `--full-name`을 추가한 재시험에서는 조건별 19개·4개의 문자열 목록까지 정확히 같았다. 호출 누락은 없으므로 이를 병합 막음으로 보지 않는다.

R3-2의 경로 근거도 확인했다. `apps/web/package.json:11`에 `test` 명령이 있고, 이 파일과 `apps/server/build.gradle.kts` 모두 표 전체의 `git ls-files` 결과에 없다. 검사 축소 사례는 문서의 규칙을 적용한 반례이며 실제 파일은 바꾸지 않았다.

### squash 병합 비교

다음 두 병합은 부모가 하나이며, 로컬에 남은 원격 추적 브랜치의 head 트리와 병합 결과 트리가 각각 동일했다. 비교에는 `git diff --no-renames --name-only <병합 커밋>^ <병합 커밋> -- @specs`를 사용했다. PR 쪽은 `git merge-base <병합 커밋>^ <PR head>`를 기준으로 같은 diff를 실행했다.

| 병합 커밋 / PR head | 부모 = PR 공통 조상 | 전체 변경 파일 수 | 조건 1 | 조건 2 |
|---|---|---|---|---|
| `1049f71` / `origin/work/ci` (`3aa48bb`) | `68d2bed` | 양쪽 4개 | `.github/workflows/ci.yml` | 없음 |
| `68d2bed` / `origin/work/compat-ui` (`1f51b07`) | `1eb77e9` | 양쪽 30개 | `apps/desktop/src/main.ts` | `apps/server/src/main/resources/application.properties` |

조건별 명령을 루트·하위 폴더 모두에서 실행했으며 결과가 같고 모두 종료 코드 0이었다. **squash 커밋을 부모와 비교하는 것은 main에 실제 반영한 변경을 확인하는 올바른 방법이다.** 두 실제 사례에서는 PR 기준 비교와도 차이가 없었다. squash라는 이유만으로 마지막 개별 커밋의 변경만 나온다는 문제는 없다.

그러나 이것이 병합 전 호출 판정과 항상 같다는 뜻은 아니다. R3-1의 비교 차이는 기존 객체만으로도 확인할 수 있다.

```text
git merge-base 1049f71 origin/work/ci
=> 68d2beda9ec28451f01572ff158c26db7a9393dc

git diff --no-renames --name-only 68d2bed origin/work/ci -- ':(top,glob).github/**'
=> .github/workflows/ci.yml

git diff --no-renames --name-only 1049f71 origin/work/ci -- ':(top,glob).github/**'
=> 출력 없음
```

`1049f71`과 `origin/work/ci`의 트리 해시도 모두 `f4d7aa008a82ec9f5bcfc50023633e86cf344581`이다. 즉 보호 경로 변경이 이미 main에 들어왔어도 원래 PR 계보의 공통 조상 비교에는 남는다. 이 상태의 작업 브랜치에 일반 문서 변경이 더 있는 경우, 새 squash의 부모 비교에는 일반 문서만 남지만 병합 전 호출 기준에는 보호 경로가 포함된다. 마지막 경우는 이 트리 관계에 근거한 반례이며, 별도 브랜치·커밋·시험 PR을 만들거나 실제 누락을 재현한 것은 아니다.

### 결정 시점과 문서 일관성

| 확인 대상 | 현재 요구와 판단 |
|---|---|
| PR 준비 요건 8번, `docs/review-standard.md:78` | 검토 전에는 호출 사실과 결정 대기로 충분하다. 병합 전 댓글을 별도로 요구한다. |
| 「소유자를 부를 때」, `:181`, `:182`, `:183` | 발견하면 즉시 묻고 독립 검토를 유지하며, 결정을 댓글로 남긴 뒤 병합한다. 말로 받은 결정도 받은 때와 함께 옮긴다. |
| 「병합 직전」 4번, `:424` | 소유자의 **병합 결정** 댓글을 요구한다. 검토 시작 전에 요구하지 않는다. |
| AI 검토 결과 7번, `:391` | 검토 당시 호출 칸·결정 댓글이 있는지 기록한다. 결정 댓글 부재 자체를 검토 불가로 만들지 않는다. |
| `AGENTS.md:38`, `:42` | 준비 요건을 따르되, 8단계 이전에 조건을 알면 그때 묻도록 보완했다. 결정 댓글은 병합 전에 요구한다. |
| `.github/pull_request_template.md:24`, 기준 내 양식 `:106` | 조건·파일·물은 때를 쓰고, 결정은 병합 전 댓글로 남기도록 일치한다. |

이 여섯 곳 사이에 결정 시점의 불가능한 선후관계는 남지 않았다. `docs/review-standard.md:27`의 「PR에 있다」는 `:424`의 댓글 요구보다 일반적인 표현이며, 댓글도 PR의 기록이므로 모순으로 보지 않는다. `AGENTS.md:43`의 비해당 PR 병합 절차도, 소유자가 AI에게 실행을 맡길 수 있다는 기준 `:183`을 금지하지 않는다. 소유자 호출 대상과 계약·의존성의 위임 판단 구분도 일치한다.

남은 문제는 R3-1·R3-2처럼 **실제 호출 조건과 사후 집계의 재판정 범위가 다른 것**이다. 또한 과거 PR 비교는 명령 검증일 뿐, v0.4를 과거에 소급해 호출 누락으로 판정한 것이 아니다. 새 기준의 비소급 조항 `:446`과 2026-10-05 판단 세 건의 사후 인정 `:473`을 유지해서 읽었다.

### 기타 검증 기록

| 명령·확인 | 결과 |
|---|---|
| `git status --short`, `git rev-parse HEAD`, `git rev-parse origin/main` | 시작 시 변경 없음. 지정 HEAD·base 확인 |
| `git diff c5e3237 HEAD` | 정책 문서 3개, 추가 20줄·삭제 15줄. 이번 수정 전체 확인 |
| `git show ff5e5ab:docs/review-standard.md` | 적용 기준 v0.3 확인 |
| `git diff --check ff5e5ab HEAD` | 종료 코드 0 |
| `git diff --exit-code 9cac3a8 HEAD -- docs/reviews/2026-10-11-work-owner-escalation-codex.md` | 종료 코드 0, 1차 기록 변경 없음 |
| `git diff --exit-code c5e3237 HEAD -- docs/reviews/2026-10-11-work-owner-escalation-codex-2.md` | 종료 코드 0, 2차 기록 변경 없음 |
| 변경 정책 파일 줄 수 | AGENTS 86줄, PR 양식 27줄, 기준 473줄. 500줄·1,000줄 미만 |
| `gh pr view work/owner-escalation --json number,url,baseRefName,headRefName,headRefOid,isDraft,body,comments,mergedAt,mergeCommit,statusCheckRollup` | 실패: `none of the git remotes configured for this repository point to a known GitHub host.` 인증·원격 설정은 변경하지 않음 |

`codex --version`은 버전을 반환했으나 임시 디렉터리 정리와 PATH 별칭 생성의 접근 거부 경고도 냈다. 이를 해결했거나 경고 없이 실행됐다고 기록하지 않는다.

### 부채 후보

- 기술부채 후보: 경로 판정과 호출 집계의 작은 회귀 검사. 루트·하위 폴더, 조건 1·2 분리, 표 밖 변경 종류, main에 먼저 들어온 동일 변경, 결정 대기·병합 전·병합 후 댓글 사례를 포함할 수 있다.
- 인지부채 후보: 집계 기록에 적용 기준 버전, 병합 당시 PR head·비교 기준, 소유자 결정 댓글의 식별자를 남겨 재판정 근거를 보존한다. 검토 양식 `docs/review-standard.md:408`도 「호출함·결정 대기」와 「결정 있음」을 구분하면 검토 당시 상태를 더 정확히 남길 수 있다.
- 부채 채택은 소유자가 정한다. R3-1·R3-2를 부채로 미뤄 병합할 근거는 아니다.

### 확인하지 않은 것

- 원격 PR 본문·댓글·결정 시각·현재 원격 HEAD·초안 여부·CI·브랜치 보호·작성자 검증 보고. 조회 실패로 확인하지 못했으며 통과로 간주하지 않았다.
- 실제 PR에서의 호출 누락 건수와 기존 두 병합의 승인 경위. 로컬 Git 객체 비교를 실제 승인 감사로 보지 않는다. 반례용 새 커밋·브랜치·병합은 만들지 않았다.
- Linux·macOS·Bash·cmd.exe의 실행 결과. 이번 명령 검증은 Windows PowerShell에서 수행했다.
- 비공개 `zm-docs` 원문과 관련 계약·결정 전체. 사용자가 제공한 DEC-0096 1항과 저장소의 v0.3을 대조했다.
- Go·Kotlin·Web·Electron의 빌드·제품 동작·단위·통합·거부 시험 및 전체 코드 보안 감사. 이번 수정은 정책 문서이며, 제품 실행 결과를 통과했다고 주장하지 않는다.
- 제품 계약·의존성·라이선스·DB·배포 코드 변경과 핵심 모듈 첫 구현은 이번 수정에 없어 해당 검사는 해당 없음이다. 문서 절차와 보고의 정확성에 관련된 v0.3 항목을 검토했다.

