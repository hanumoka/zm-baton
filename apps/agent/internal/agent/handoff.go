package agent

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"slices"
	"strings"
	"time"

	"github.com/hanumoka/zm-baton/apps/agent/internal/api"
	"github.com/hanumoka/zm-baton/apps/agent/internal/claude"
	"github.com/hanumoka/zm-baton/apps/agent/internal/workspace"
)

// Repo is a repository this device runs delegated work in (`zm-baton repo add`). Root and
// Worktrees are local absolute paths and never leave the device.
type Repo struct {
	Key       string
	Root      string
	Worktrees string
}

// KnownTools are the Claude Code tools a delegation may allow: the server's list. A claim that
// asks for anything else is refused before an executor starts, whatever the server said.
var KnownTools = []string{"Read", "Glob", "Grep", "Edit", "Write"}

// executorEnv keeps the zm-baton hooks of the person's own Claude Code setup from registering a
// run zm-baton started as a session the person opened (contract "직접 연 AI 세션"): the run
// reports through its own events already.
var executorEnv = []string{"ZM_BATON_OFF=1"}

// Handoff is `zm-baton agent`: it claims the delegated runs meant for this device and for a
// repository registered here, gives each its own worktree and branch, and runs Claude Code there
// with the instruction the server built (contract v1 "위임", "실행마다 별도 작업 트리"). Claude
// Code runs restricted, so only the delegation's tools apply, and the worktree's path is cut out
// of what its tools report.
type Handoff struct {
	runner   *Runner
	repos    func() (map[string]Repo, error)
	tempDirs []string
	log      *slog.Logger
	// skipped remembers runs already logged as not for this device's repositories.
	skipped map[string]bool
}

// NewHandoff checks the settings. repos is read again before every poll, so a repository added
// while the connector runs is picked up. tempDirs are the folders a worktree must stay out of.
func NewHandoff(cfg Config, repos func() (map[string]Repo, error), tempDirs []string) (*Handoff, error) {
	if repos == nil {
		return nil, errors.New("repositories are required")
	}
	r, err := newRunner(cfg)
	if err != nil {
		return nil, err
	}
	return &Handoff{runner: r, repos: repos, tempDirs: tempDirs, log: r.log, skipped: map[string]bool{}}, nil
}

// Run polls and executes delegated runs until ctx ends. With once it returns the result of the
// first run it claimed.
func (h *Handoff) Run(ctx context.Context, once bool) (Result, error) {
	for {
		claim, repo, err := h.claimNext(ctx)
		if errors.Is(err, api.ErrUnauthorized) {
			return Result{}, err
		}
		if err != nil && ctx.Err() == nil {
			h.log.Warn("poll or claim failed", "err", err)
		}
		if claim != nil {
			res := h.Execute(ctx, *claim, repo)
			if once {
				return res, nil
			}
			continue
		}
		select {
		case <-ctx.Done():
			return Result{}, ctx.Err()
		case <-time.After(h.runner.cfg.PollInterval):
		}
	}
}

// claimNext claims the first delegated Claude Code run for this device whose repository is
// registered here. Runs for other repositories are left for whoever has them.
func (h *Handoff) claimNext(ctx context.Context) (*api.Claim, Repo, error) {
	repos, err := h.repos()
	if err != nil {
		return nil, Repo{}, fmt.Errorf("read this device's repositories: %w", err)
	}
	cfg := h.runner.cfg
	runs, err := cfg.Client.PendingRunsFor(ctx, cfg.DeviceID)
	if err != nil {
		return nil, Repo{}, err
	}
	for _, run := range runs {
		if run.Executor != claude.Executor || run.DelegationID == "" || run.DeviceID != cfg.DeviceID {
			continue
		}
		repo, ok := repos[run.RepoKey]
		if run.Purpose == api.PurposeMaster {
			// The master's run needs no repository (master.go).
			repo, ok = Repo{}, true
		}
		if !ok {
			if !h.skipped[run.ID] {
				h.skipped[run.ID] = true
				h.log.Warn("a run waits for a repository not registered on this device; add it with zm-baton repo add",
					"run_id", run.ID, "repo_key", run.RepoKey)
			}
			continue
		}
		c, err := cfg.Client.Claim(ctx, run.ID, cfg.DeviceID)
		if errors.Is(err, api.ErrConflict) {
			h.log.Info("run already claimed elsewhere", "run_id", run.ID)
			continue
		}
		if err != nil {
			return nil, Repo{}, err
		}
		h.log.Info("claimed run", "run_id", c.RunID, "work_item_id", c.WorkItemID, "generation", c.Generation, "repo_key", run.RepoKey)
		return &c, repo, nil
	}
	return nil, Repo{}, nil
}

// Execute runs one claimed delegated run in a worktree of repo. Before the executor starts, the
// readiness check (readiness.go) looks at the delegation, the executor, the repository, the disk and
// the worktree; any failure ends the run before the executor. A continuing run reuses the earlier
// run's worktree and session only as contract v1 "실행마다 별도 작업 트리" 6 allows (workspaceFor).
func (h *Handoff) Execute(ctx context.Context, c api.Claim, repo Repo) Result {
	if c.Purpose == api.PurposeMaster {
		return h.executeMaster(ctx, c)
	}
	return h.runner.ExecuteJob(ctx, c, func(ctx context.Context, log *slog.Logger) (Job, error) {
		ready := &Readiness{}
		job := Job{Readiness: ready}
		d := c.Delegation
		if d == nil || d.Executor != claude.Executor || d.RepoKey != repo.Key {
			return job, ready.Fail("scope", "청구 응답에 이 저장소를 Claude Code에 맡긴 위임이 없다", nil)
		}
		if strings.TrimSpace(c.Instruction) == "" {
			return job, ready.Fail("scope", "청구 응답에 실행 지시가 없다", nil)
		}
		tools, err := checkTools(d.AllowedTools)
		if err != nil {
			return job, ready.Fail("scope", "위임의 도구를 실행기에 넘길 수 없다: "+err.Error(), err)
		}
		ready.Pass("scope", "도구 "+strings.Join(tools, ", ")+". 명령 실행 도구는 넘기지 않는다")
		if err := h.prepareExecutor(ctx, c, &job, log); err != nil {
			return job, err
		}

		// Neither the repository nor its worktrees may sit under a temp folder (contract "실행할 때 넘기는 것").
		if _, err := CheckWorkdir(repo.Root, h.tempDirs); err != nil {
			return job, ready.Fail("repository", "등록한 저장소 폴더가 없거나 임시 폴더 안에 있다", err)
		}
		key, err := workspace.RepoKey(ctx, repo.Root)
		if err != nil {
			return job, ready.Fail("repository", "등록한 폴더에서 저장소의 첫 커밋을 읽지 못했다", err)
		}
		if key != repo.Key {
			return job, ready.Fail("repository", "등록한 폴더에 지금은 다른 저장소가 있다. zm-baton repo add로 다시 등록한다", nil)
		}
		ready.Pass("repository", "등록한 저장소와 같다")

		if err := os.MkdirAll(repo.Worktrees, 0o755); err != nil {
			return job, ready.Fail("worktree_folder", "작업 트리 폴더를 만들 수 없다", err)
		}
		if _, err := CheckWorkdir(repo.Worktrees, h.tempDirs); err != nil {
			return job, ready.Fail("worktree_folder", "작업 트리 폴더가 임시 폴더 안에 있다", err)
		}
		ready.Pass("worktree_folder", "작업 트리 폴더를 쓸 수 있다")
		switch free, err := freeBytes(repo.Worktrees); {
		case err != nil:
			ready.Pass("disk", "남은 공간을 읽지 못해 확인하지 않았다")
			log.Warn("could not read the free disk space", "err", err)
		case free < MinFreeBytes:
			return job, ready.Fail("disk", fmt.Sprintf("작업 트리 폴더의 디스크에 남은 공간이 %d MB로, %d MB보다 적다", free>>20, MinFreeBytes>>20), nil)
		default:
			ready.Pass("disk", fmt.Sprintf("남은 공간 %d MB", free>>20))
		}

		ws, session, note, err := h.workspaceFor(ctx, c, repo, ready, log)
		if err != nil {
			return job, err // a failed check
		}
		job.Workspace = ws.Report()
		log.Info("worktree ready; it stays after the run for you to review", "path", ws.Path, "branch", ws.Branch,
			"base_commit", ws.BaseCommit, "base_ref", ws.BaseRef, "origin", ws.Origin, "resume_session", session != "")
		prompt := c.Instruction
		if appendix := h.appendix(ctx, c, ws, note, log); appendix != "" {
			prompt += "\n\n" + appendix
		}
		job.Workdir = ws.Path
		job.Prompt = prompt
		job.AllowedTools = strings.Join(tools, ",")
		job.Changes = ws.Changes
		job.Env = executorEnv
		job.Restricted = true
		job.HideDir = ws.Path
		job.LiveInput = true
		job.ResumeSession = session
		return job, nil
	})
}

// prepareExecutor checks the executor's version and arranges its sign-in and limits, for a delegated
// run and the master's run alike. A failed check is returned as the error ExecuteJob reports.
func (h *Handoff) prepareExecutor(ctx context.Context, c api.Claim, job *Job, log *slog.Logger) error {
	ready := job.Readiness
	version, v, err := claudeVersion(ctx, h.runner.cfg.ClaudePath)
	if err != nil {
		return ready.Fail("executor", "Claude Code의 판을 읽지 못했다", err)
	}
	if !versionAtLeast(v, MinClaudeVersion) {
		return ready.Fail("executor", fmt.Sprintf("Claude Code %s 이상이 필요하다. 이 PC는 %s다", versionString(MinClaudeVersion), version), nil)
	}
	ready.Pass("executor", "Claude Code "+version)

	// The account's way of signing in, and only that one (auth.go). A run handed over before
	// accounts, or by an older server, has none and keeps the environment as it is.
	if acct := c.Account; acct != nil {
		sign, present, err := signInFor(*acct, os.Environ(), h.runner.cfg.ExecutorDir)
		if err != nil {
			return ready.Fail("account", err.Error(), nil)
		}
		if err := ensureConfigDir(sign.ConfigDir); err != nil {
			return ready.Fail("account", "이 계정 전용 설정 폴더를 만들 수 없다", err)
		}
		ready.Pass("account", sign.Detail(*acct, present))
		job.SignIn = &sign
		log.Info("executor sign-in", "account_id", acct.ID, "method", sign.Method, "removed_present", present,
			"own_config_folder", sign.ConfigDir != "")
	}
	job.Timeout, job.TimeoutIsLimit = runTimeout(h.runner.cfg.RunTimeout, c.Limits)
	if c.Limits != nil {
		job.MaxTurns, job.MaxBudgetUSD = c.Limits.MaxTurns, c.Limits.MaxBudgetUSD
	}
	return nil
}

// workspaceFor picks the run's worktree (contract v1 "실행마다 별도 작업 트리" 1, 6):
//
//  1. A continuing run on the device that ran the earlier one takes over that worktree and its
//     executor session, but only when this device saw that executor and its child processes end.
//  2. Otherwise, on that device, a new worktree on the run's own branch starts at the tip of the
//     earlier run's branch (6.1). The earlier worktree stays as it is (6.4).
//  3. A first run, a run on another device (6.2), or one whose earlier branch is gone starts from
//     the delegation's base.
//
// It returns the worktree, the session to continue ("" for a new one) and a note for the executor.
func (h *Handoff) workspaceFor(ctx context.Context, c api.Claim, repo Repo, ready *Readiness, log *slog.Logger) (*workspace.Workspace, string, string, error) {
	r := c.Resume
	note := ""
	sameAccount := r == nil || r.SameAccount == nil || *r.SameAccount
	if r != nil && r.SameDevice && sameAccount && workspace.IsRunID(r.RunID) {
		if prev, ok := workspace.PreviousFrom(r.Workspace); ok {
			confirmed, why := h.runner.records.endedConfirmed(r.RunID, r.SessionID)
			if confirmed {
				ws, err := workspace.Open(ctx, repo.Root, repo.Worktrees, repo.Key, c.RunID, prev)
				if err == nil {
					ready.Pass("resume", why+". 이전 실행의 작업 트리와 세션을 이어 쓴다")
					return ws, r.SessionID, fmt.Sprintf("이 작업 트리는 이전 실행(%s)이 쓰던 것이고, 같은 세션을 이어 간다.", r.RunID), nil
				}
				log.Warn("the earlier run's worktree cannot be taken over", "err", err)
				why = "이전 실행의 작업 트리를 이어 쓸 수 없다"
			}
			ws, err := workspace.PlanFrom(ctx, repo.Root, repo.Worktrees, repo.Key, c.RunID, prev)
			if err == nil {
				if err := ws.Make(ctx); err != nil {
					return nil, "", "", ready.Fail("worktree", "이전 실행의 브랜치에서 새 작업 트리를 만들지 못했다", err)
				}
				ready.Pass("resume", why+". 이전 실행의 브랜치 끝에서 새 작업 트리와 새 세션으로 시작한다")
				return ws, "", fmt.Sprintf("이전 실행(%s)의 작업 트리를 이어 쓰지 않았다. 이유: %s. 그 브랜치 끝에서 새 작업 트리를 만들었으므로 이전 실행이 커밋하지 않은 변경은 여기에 없다.", r.RunID, why), nil
			}
			log.Warn("no worktree from the earlier run's branch", "err", err)
		}
		note = "이전 실행의 브랜치를 이 기기에서 찾지 못해 기준에서 새로 시작했다. 이전 실행의 결과는 여기에 없다."
	} else if r != nil && !sameAccount {
		note = "이전 실행은 다른 AI 계정으로 돌았다. 기준에서 새로 시작했으므로 이전 실행의 결과는 여기에 없다."
	} else if r != nil {
		note = "이전 실행은 다른 기기에서 돌았다. 기준에서 새로 시작했으므로 이전 실행의 결과는 여기에 없다."
	}
	d := c.Delegation
	ws, err := workspace.Plan(ctx, repo.Root, repo.Worktrees, repo.Key, c.RunID, d.BaseBranch)
	if err != nil {
		if d.BaseBranch != "" && strings.Contains(err.Error(), "base branch") {
			return nil, "", "", ready.Fail("base_branch", "기준 브랜치 "+d.BaseBranch+"이(가) 이 저장소에 없다", err)
		}
		return nil, "", "", ready.Fail("worktree", "이 실행의 작업 트리를 만들 수 없다(같은 이름의 폴더나 브랜치가 이미 있거나 실행 번호가 맞지 않는다)", err)
	}
	ready.Pass("base_branch", "기준 "+ws.BaseRef+" "+ws.BaseCommit[:10])
	if err := ws.Make(ctx); err != nil {
		return nil, "", "", ready.Fail("worktree", "git worktree add가 실패했다", err)
	}
	ready.Pass("worktree", "새 작업 트리와 브랜치 "+ws.Branch)
	if r != nil {
		ready.Pass("resume", "이전 실행의 작업 트리와 세션을 쓰지 않는다")
	}
	return ws, "", note, nil
}

// appendix is what the connector adds to the server's instruction for a continuing run: how the
// worktree came to be and which files it already holds changed (contract "실행이 끊겼을 때" 3: the
// new run checks the files first). A first run gets nothing.
func (h *Handoff) appendix(ctx context.Context, c api.Claim, ws *workspace.Workspace, note string, log *slog.Logger) string {
	if c.Resume == nil {
		return ""
	}
	var b strings.Builder
	b.WriteString("zm-baton 연결 프로그램이 덧붙인다:\n")
	if note != "" {
		b.WriteString(note + "\n")
	}
	p, err := ws.Changes(ctx, false)
	if err != nil {
		log.Warn("could not list the worktree's changes for the instruction", "err", err)
		b.WriteString("이 작업 트리의 바뀐 파일 목록을 만들지 못했다. 직접 확인한다.")
		return b.String()
	}
	files, _ := p["files"].([]string)
	if len(files) == 0 {
		b.WriteString("이 작업 트리에는 아직 기준 커밋 대비 바뀐 파일이 없다.")
		return b.String()
	}
	shown := files
	if len(shown) > 50 {
		shown = shown[:50]
	}
	fmt.Fprintf(&b, "이 작업 트리에서 기준 커밋 대비 이미 바뀐 파일 %d개: %s", len(files), strings.Join(shown, ", "))
	if len(files) > len(shown) {
		b.WriteString(" 외")
	}
	return b.String()
}

// defaultRunTimeout bounds a delegated run when neither the account nor --run-timeout sets a time.
const defaultRunTimeout = 30 * time.Minute

// runTimeout is how long a delegated run may take: the account's per-run time limit, or --run-timeout
// when that is set and shorter. limit says the account's limit is what applies. Without either the
// run gets defaultRunTimeout.
func runTimeout(flag time.Duration, limits *api.Limits) (d time.Duration, limit bool) {
	if limits != nil && limits.MaxMinutes > 0 {
		account := time.Duration(limits.MaxMinutes) * time.Minute
		if flag > 0 && flag < account {
			return flag, false
		}
		return account, true
	}
	if flag > 0 {
		return flag, false
	}
	return defaultRunTimeout, false
}

// checkTools keeps the delegation's tools only when every one is known; an empty list is refused.
func checkTools(tools []string) ([]string, error) {
	if len(tools) == 0 {
		return nil, errors.New("the delegation allows no tools")
	}
	for _, t := range tools {
		if !slices.Contains(KnownTools, t) {
			return nil, fmt.Errorf("the delegation allows %q, which this connector does not pass to the executor", t)
		}
	}
	return tools, nil
}
