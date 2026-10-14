package agent

import (
	"context"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"slices"
	"strings"

	"github.com/hanumoka/zm-baton/apps/agent/internal/api"
	"github.com/hanumoka/zm-baton/apps/agent/internal/claude"
	"github.com/hanumoka/zm-baton/apps/agent/internal/workspace"
)

// MasterTools are the only tools a master run may get: the server sends read-only tools, and the
// run has nothing to read (contract v1 "문서 모순 후보" 5, the owner's own subscription on the owner's
// own PC). A claim that asks for anything else is refused before the executor starts.
var MasterTools = []string{"Read", "Glob", "Grep"}

// executeMaster runs the master's contradiction check (contract v1 "문서 모순 후보" 5-3): the server
// made it the owner's work item because its API path is off or failed, and this device runs it with
// the owner's own Claude Code login. Everything the master compares is in the instruction, so the run
// needs no repository and no worktree:
//
//  1. Check the delegation (master purpose, read-only tools only), the instruction, the executor and
//     the account's sign-in, like a delegated run.
//  2. Make an empty folder of its own under MasterDir and run Claude Code there, restricted, with
//     --strict-mcp-config, the instruction on stdin and the hooks of the person's setup off.
//  3. Send the final answer back in the end report (`result`); the server checks every quoted
//     sentence before it raises anything.
//  4. Remove the folder when it is still empty.
func (h *Handoff) executeMaster(ctx context.Context, c api.Claim) Result {
	dir := ""
	res := h.runner.ExecuteJob(ctx, c, func(ctx context.Context, log *slog.Logger) (Job, error) {
		ready := &Readiness{}
		job := Job{Readiness: ready}
		d := c.Delegation
		if d == nil || d.Executor != claude.Executor {
			return job, ready.Fail("scope", "청구 응답에 마스터 실행을 Claude Code에 맡긴 위임이 없다", nil)
		}
		if len(d.AllowedTools) == 0 || slices.ContainsFunc(d.AllowedTools, func(t string) bool { return !slices.Contains(MasterTools, t) }) {
			return job, ready.Fail("scope", "마스터 실행에는 읽기 도구(Read, Glob, Grep)만 넘긴다. 위임이 다른 도구를 달라고 했다", nil)
		}
		if strings.TrimSpace(c.Instruction) == "" {
			return job, ready.Fail("scope", "청구 응답에 실행 지시가 없다", nil)
		}
		ready.Pass("scope", "마스터 실행. 도구 "+strings.Join(d.AllowedTools, ", ")+", 작업 트리 없음")
		if err := h.prepareExecutor(ctx, c, &job, log); err != nil {
			return job, err
		}

		root := h.runner.cfg.MasterDir
		if root == "" || !workspace.IsRunID(c.RunID) {
			return job, ready.Fail("master_folder", "마스터 실행 폴더를 정하지 못했다", nil)
		}
		dir = filepath.Join(root, c.RunID)
		if err := os.MkdirAll(dir, 0o755); err != nil {
			return job, ready.Fail("master_folder", "마스터 실행 폴더를 만들 수 없다", err)
		}
		if _, err := CheckWorkdir(dir, h.tempDirs); err != nil {
			return job, ready.Fail("master_folder", "마스터 실행 폴더가 임시 폴더 안에 있다", err)
		}
		if entries, err := os.ReadDir(dir); err != nil || len(entries) > 0 {
			return job, ready.Fail("master_folder", fmt.Sprintf("마스터 실행 폴더가 비어 있지 않다(%d개)", len(entries)), err)
		}
		ready.Pass("master_folder", "빈 폴더에서 돈다")
		log.Info("master run ready", "folder", dir)

		job.Workdir = dir
		job.Prompt = c.Instruction
		job.AllowedTools = strings.Join(d.AllowedTools, ",")
		job.Env = executorEnv
		job.Restricted = true
		job.HideDir = dir
		// The instruction holds every text compared and can be longer than a command line allows.
		job.LiveInput = true
		job.KeepResult = true
		return job, nil
	})
	if dir != "" {
		// Only an empty folder goes: whatever the executor left stays for a person to look at.
		if err := os.Remove(dir); err != nil && !os.IsNotExist(err) {
			h.log.Warn("the master run's folder is not empty; it stays", "folder", dir)
		}
	}
	return res
}
