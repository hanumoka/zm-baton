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

// Execute runs one claimed delegated run in a new worktree of repo.
func (h *Handoff) Execute(ctx context.Context, c api.Claim, repo Repo) Result {
	return h.runner.ExecuteJob(ctx, c, func(ctx context.Context, log *slog.Logger) (Job, error) {
		d := c.Delegation
		if d == nil || d.Executor != claude.Executor || d.RepoKey != repo.Key {
			return Job{}, errors.New("the claim carries no Claude Code delegation for this repository")
		}
		if strings.TrimSpace(c.Instruction) == "" {
			return Job{}, errors.New("the claim carries no instruction")
		}
		tools, err := checkTools(d.AllowedTools)
		if err != nil {
			return Job{}, err
		}
		// Neither the repository nor its worktrees may sit under a temp folder (contract "실행할 때 넘기는 것").
		if _, err := CheckWorkdir(repo.Root, h.tempDirs); err != nil {
			return Job{}, err
		}
		if err := os.MkdirAll(repo.Worktrees, 0o755); err != nil {
			return Job{}, err
		}
		if _, err := CheckWorkdir(repo.Worktrees, h.tempDirs); err != nil {
			return Job{}, err
		}
		ws, err := workspace.Create(ctx, repo.Root, repo.Worktrees, repo.Key, c.RunID, d.BaseBranch)
		if err != nil {
			return Job{}, err
		}
		log.Info("worktree ready; it stays after the run for you to review", "path", ws.Path, "branch", ws.Branch,
			"base_commit", ws.BaseCommit, "base_ref", ws.BaseRef)
		return Job{
			Workdir:      ws.Path,
			Prompt:       c.Instruction,
			AllowedTools: strings.Join(tools, ","),
			Workspace:    ws.Report(),
			Changes:      ws.Changes,
			Env:          executorEnv,
			Restricted:   true,
			HideDir:      ws.Path,
		}, nil
	})
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
