## AI 검토 결과

- 적용한 기준: **zm-baton 검토 기준 v0.4**. 기준 커밋 `c0341ff25bfc70b923d98c7d77c26c7b077bfbdd`의 `docs/review-standard.md`를 적용했다.
- 검토자: **Codex / codex-cli 0.162.0 / GPT-6**(실행 환경 안내 기준, 세부 모델 식별자 미확인) / 3차 독립 문서 검토자.
- 검토 세션: `01a132d4-8650-7361-9185-761d93ac7bf9`. `CODEX_SESSION_ID`와 `CODEX_THREAD_ID`에서 같은 값을 확인했다.
- 작성자: Claude Code / 세션 `409394de-ef95-46af-bf81-42c556a8b817`(사용자 제공). 실행기와 세션 번호가 모두 다르며, 작성 대화를 이어받거나 구현에 참여하지 않았다.
- 검토일: 2026-10-13.
- 검토 브랜치·커밋: `work/function-first` / **`fed7839340e163cab6e3b4a03f7375ab46ddc788`**. 기준은 `origin/main`의 `c0341ff25bfc70b923d98c7d77c26c7b077bfbdd`다.
- 검토 범위: **`git diff b0b4583 HEAD`의 `docs/hardening-backlog.md` 변경만** 검토했다. 2차 기록 `docs/reviews/2026-10-12-work-function-first-codex-2.md`를 읽고 그 이후 변경을 확인했으며, 나머지 PR은 다시 검토하지 않았다.
- 판정: **병합 가능**. 2차 N1과 H-03/H-06 상태 표시 문제는 해소됐다. 이번 변경에서 새 사실 오류나 병합 막음·높음 지적은 확인하지 못했다.
- 소유자 호출 조건: 이번 증분에서 추가로 확인된 조건은 없다. 전체 PR의 **조건 1** 해당(`AGENTS.md`, `docs/review-standard.md`, `.github/pull_request_template.md`)은 2차 기록에서 승계한다.
- 소유자 호출 근거: **확인 못 함**. 이번에는 원격 PR과 소유자 결정 댓글을 조회하지 않았다. 이 판정은 해당 병합 요건의 확인을 대신하지 않는다.
- 기록 범위: 요청한 이 검토 기록만 새로 작성했으며 커밋하지 않았다.

### 기존 지적과 변경 내용 확인

위치는 검토 대상 HEAD 기준이다. 검토 항목은 v0.4의 「12. 문서가 사실과 절차를 갖췄는가」다.

| 번호 | 위치 | 검토 항목 | 근거 | 확신 | 병합 막음 |
|---|---|---|---|---|---|
| 2차 N1 | `docs/hardening-backlog.md:30` | 12 · 해소 | 로그 파일 열기가 watchdog 설치보다 먼저이고, 종료 직전 동기 로그 쓰기도 멈출 수 있다고 명시했다. `559df13`의 `apps/agent/cmd/zm-baton/main.go:83`, `:97`, `:99`, `:100`의 실행 순서와 일치한다. 느린 저장소에서만 생긴다는 단정도 제거했다. | 높음 | 아니요 |
| H-03/H-06 상태 메모 | `docs/hardening-backlog.md:13`, `:24`, `:27` | 12 · 해소 | 두 항목을 「고치는 중 … 병합 대기」로 바꾸고, 닫기 규칙도 병합 전·후로 나눴다. #12·#13이 아직 병합되지 않았다는 사용자 제공 상태와 일치하며, 병합 완료를 주장하지 않는다. | 높음 — 문서 일치 확인 | 아니요 |
| H-07 | `docs/hardening-backlog.md:28` | 12 · 적합 | 임시 파일의 세션 토큰 잔류와 갱신 유실 가능성을 추가했다. `559df13`의 `apps/agent/internal/session/state.go`에서 토큰을 포함한 상태를 임시 파일에 쓴 뒤 교체하는 순서와 일치한다. | 높음 | 아니요 |
| H-08 | `docs/hardening-backlog.md:29` | 12 · 적합 | Git 하위 프로세스가 남을 수 있다는 기존 한계를 유지하면서 잔류 시간의 보장을 제거했다. 2차 기록의 확인 범위와 일치한다. | 높음 | 아니요 |

### H-09의 Claude Code timeout 확인

`559df13`의 `apps/agent/internal/session/plugin.go`는 `SessionStart`에 10초, `UserPromptSubmit`·`PostToolUse`·`Notification`에 5초, `SessionEnd`에 2초를 설정한다. 생성되는 command 훅에는 `async: true`가 없다. 공식 문서는 이러한 훅이 timeout에 도달하면 취소하고 출력을 버린다고 명시한다. 따라서 Claude Code가 정상 동작한다는 전제에서 외부 timeout이 훅 대기를 제한한다는 설명에는 근거가 있다. 실제 디스크 정지를 재현한 결과는 아니다. [Claude Code 훅 timeout 문서](https://code.claude.com/docs/en/hooks#timeouts)

다만 **끝 2초는 플러그인의 개별 설정값**이다. 공식 문서에 따르면 `SessionEnd`에는 기본 1.5초의 공통 예산이 있고, 플러그인의 timeout 설정만으로 그 예산이 늘어나지는 않는다. 따라서 기본 설정에서는 더 일찍 취소될 수 있다. H-09는 2초의 실행 시간을 보장한다고 쓰지 않고 지연의 상한으로 제시하므로, 이 차이를 새 오류로 판정하지 않았다. 이 확인을 하위 프로세스 종료나 OS 전체 정지 상황의 보장으로 확대하지 않는다. [Claude Code SessionEnd 문서](https://code.claude.com/docs/en/hooks#sessionend)

### 고도화 후보·부채 후보

새 후보 없음. H-07~H-09의 기존 구현 한계가 해결됐다는 판정은 아니다.

### 확인한 것과 검증 결과

환경: Windows `10.0.26300.0`, PowerShell `7.6.6`, Git `2.53.0.windows.2`, Codex CLI `0.162.0`.

| 명령·확인 | 결과 |
|---|---|
| `git rev-parse HEAD`, `git rev-parse origin/main`, `git branch --show-current` | 요청한 커밋·기준·브랜치와 일치. |
| `git diff --name-status b0b4583 HEAD`, `git diff b0b4583 HEAD -- docs/hardening-backlog.md` | 변경 파일은 지정된 문서 하나이며, 닫기 규칙과 H-03·H-06~H-09 변경을 확인. |
| `git diff --check b0b4583 HEAD` | 종료 0. |
| `git diff --quiet b0b4583 HEAD -- docs/reviews/2026-10-12-work-function-first-codex-2.md` | 종료 0. 2차 기록 불변 확인. |
| `git show c0341ff:docs/review-standard.md`, `git show 559df13:<관련 파일>` | v0.4 기준과 위에 인용한 코드·설정 대조. |
| Claude Code 공식 훅 문서 조회 | 2026-10-13에 timeout 취소 동작과 `SessionEnd` 공통 예산 확인. |

`codex --version`은 버전을 출력했으며 임시 디렉터리 정리·PATH 별칭 생성에 접근 거부 경고가 있었다. 문서·코드 대조에는 영향을 주지 않았다.

### 확인하지 않은 것

- 실제 디스크·로그 I/O 정지, Claude Code timeout 실행, 임시 파일 잔류·갱신 유실, Git 하위 프로세스 잔류를 재현하지 않았다. 제품 시험도 실행하지 않았다.
- 원격 PR 필수 항목·현재 HEAD·CI·소유자 결정 댓글과 #12·#13의 실제 병합 상태는 독립 조회하지 않았다.
- 이번 diff 밖의 정책·구현·하위 PR 전체는 재검토하지 않았다. 이전 검토의 실행 결과를 이번 시험 통과로 기록하지 않았다.
