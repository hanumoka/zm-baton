// Package agent is the stage-1 local connector: it polls for requested runs,
// claims one, runs Claude Code without a shell and reports its output as events.
package agent

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/hanumoka/zm-baton/apps/agent/internal/api"
	"github.com/hanumoka/zm-baton/apps/agent/internal/claude"
	"github.com/hanumoka/zm-baton/apps/agent/internal/proc"
	"github.com/hanumoka/zm-baton/apps/agent/internal/report"
)

// End reasons sent in the lifecycle "ended" event (contract v1 "실행 시도 > 상태").
const (
	EndSubmitted = "submitted"
	EndFailed    = "failed"
	EndCancelled = "cancelled"
	EndLost      = "lost"
)

// pipeGrace is how long stdout may stay open after the executor exits
// (for example held by a grandchild) before it is closed.
const pipeGrace = 5 * time.Second

// defaultCloseTimeout bounds how long the last events may take to reach the server.
const defaultCloseTimeout = 30 * time.Second

// Result is how one run ended on this device, and whether the server got its events.
type Result struct {
	EndReason string
	// Delivery is nil when the server stored, or already had, every event of the run.
	// Otherwise the server may not know how the run ended, whatever EndReason says.
	Delivery error
}

// Config holds the runner settings. Workdir must already have passed CheckWorkdir.
type Config struct {
	Client        *api.Client
	DeviceID      string
	Workdir       string
	Prompt        string
	AllowedTools  string
	ClaudePath    string        // resolved executable, see ResolveExecutable
	PollInterval  time.Duration // pause between polls when nothing was claimed
	RunTimeout    time.Duration // 0 means no limit
	FlushInterval time.Duration // 0 means report.DefaultInterval
	CloseTimeout  time.Duration // 0 means defaultCloseTimeout
	Log           *slog.Logger
}

// Runner executes claimed runs one at a time.
type Runner struct {
	cfg Config
	log *slog.Logger
}

// New checks the settings that every run needs.
func New(cfg Config) (*Runner, error) {
	switch {
	case cfg.Client == nil:
		return nil, errors.New("client is required")
	case strings.TrimSpace(cfg.DeviceID) == "":
		return nil, errors.New("device id is required")
	case strings.TrimSpace(cfg.Workdir) == "":
		return nil, errors.New("workdir is required")
	case strings.TrimSpace(cfg.Prompt) == "":
		return nil, errors.New("prompt is required")
	case strings.TrimSpace(cfg.AllowedTools) == "":
		return nil, errors.New("allowed tools must not be empty")
	case cfg.ClaudePath == "":
		return nil, errors.New("executor path is required")
	}
	if cfg.PollInterval <= 0 {
		cfg.PollInterval = 2 * time.Second
	}
	if cfg.CloseTimeout <= 0 {
		cfg.CloseTimeout = defaultCloseTimeout
	}
	log := cfg.Log
	if log == nil {
		log = slog.New(slog.DiscardHandler)
	}
	return &Runner{cfg: cfg, log: log}, nil
}

// ResolveExecutable finds the executor on PATH. Batch files are refused because
// Windows runs them through cmd.exe, and the contract says no shell.
func ResolveExecutable(name string) (string, error) {
	path, err := exec.LookPath(name)
	if err != nil {
		return "", err
	}
	switch strings.ToLower(filepath.Ext(path)) {
	case ".cmd", ".bat":
		return "", fmt.Errorf("%s is a batch file; point --claude at the native executable", path)
	}
	return path, nil
}

// Run polls and executes runs until ctx ends. With once it returns the result
// of the first run it claimed.
func (r *Runner) Run(ctx context.Context, once bool) (Result, error) {
	for {
		claim, err := r.claimNext(ctx)
		if err != nil && ctx.Err() == nil {
			r.log.Warn("poll or claim failed", "err", err)
		}
		if claim != nil {
			res := r.Execute(ctx, *claim)
			if once {
				return res, nil
			}
			continue
		}
		select {
		case <-ctx.Done():
			return Result{}, ctx.Err()
		case <-time.After(r.cfg.PollInterval):
		}
	}
}

// claimNext claims the first requested Claude Code run. Losing a race is normal.
func (r *Runner) claimNext(ctx context.Context) (*api.Claim, error) {
	runs, err := r.cfg.Client.PendingRuns(ctx)
	if err != nil {
		return nil, err
	}
	for _, run := range runs {
		if run.Executor != claude.Executor {
			continue
		}
		c, err := r.cfg.Client.Claim(ctx, run.ID, r.cfg.DeviceID)
		if errors.Is(err, api.ErrConflict) {
			r.log.Info("run already claimed elsewhere", "run_id", run.ID)
			continue
		}
		if err != nil {
			return nil, err
		}
		r.log.Info("claimed run", "run_id", c.RunID, "work_item_id", c.WorkItemID, "generation", c.Generation)
		return &c, nil
	}
	return nil, nil
}

// Execute runs Claude Code for one claimed run and reports everything, ending with
// a lifecycle "ended" event. Delivery failures are returned, never folded into success.
func (r *Runner) Execute(ctx context.Context, c api.Claim) Result {
	log := r.log.With("run_id", c.RunID, "generation", c.Generation)
	rep := report.New(r.cfg.Client, c.RunID, c.Generation, r.cfg.FlushInterval, log)
	rep.Start()

	reason := r.execute(ctx, rep, log)

	// Deliver the tail even if ctx was cancelled; give up after a bounded wait.
	closeCtx, cancel := context.WithTimeout(context.WithoutCancel(ctx), r.cfg.CloseTimeout)
	defer cancel()
	delivery := rep.Close(closeCtx)
	if delivery != nil {
		log.Error("the server did not get every event", "err", delivery)
	}
	totals, dropped := rep.Totals()
	log.Info("run finished", "end_reason", reason, "events", rep.Count(), "stored", totals.Stored,
		"duplicates", totals.Duplicates, "late", totals.Late, "rejected", totals.Rejected, "dropped", dropped)
	return Result{EndReason: reason, Delivery: delivery}
}

func (r *Runner) execute(ctx context.Context, rep *report.Reporter, log *slog.Logger) string {
	sessionID := NewUUIDv4()
	args := claude.Args(claude.Options{SessionID: sessionID, AllowedTools: r.cfg.AllowedTools, Prompt: r.cfg.Prompt})
	cmd := exec.Command(r.cfg.ClaudePath, args...) // no shell
	cmd.Dir = r.cfg.Workdir
	cmd.SysProcAttr = proc.SysProcAttr() // Windows: start suspended until Track pins it
	stderr := &tailBuffer{max: 4096}
	cmd.Stderr = stderr
	cmd.WaitDelay = pipeGrace

	// Our own pipe, so Wait never closes the read end before the last line is read.
	pr, pw, err := os.Pipe()
	if err != nil {
		return r.failBeforeStart(rep, log, "cannot create the output pipe", err)
	}
	defer pr.Close()
	cmd.Stdout = pw
	if err := cmd.Start(); err != nil {
		pw.Close()
		msg := "executor failed to start"
		if errors.Is(err, exec.ErrNotFound) {
			msg = "executor not found"
		}
		return r.failBeforeStart(rep, log, msg, err)
	}
	pw.Close()
	pid := cmd.Process.Pid // recorded right after Start (contract v1 "프로세스 관리" 1)
	// Pin the executor before it runs, so a stop can only ever reach this process.
	pinned, err := proc.Track(cmd.Process)
	if err != nil {
		_ = cmd.Process.Kill() // through Go's own handle: still this process, still suspended
		_ = cmd.Wait()
		return r.failBeforeStart(rep, log, "cannot pin the executor process", err)
	}
	// Released only after the stop decision below is final.
	defer pinned.Close()
	log = log.With("pid", pid, "session_id", sessionID)
	log.Info("executor started", "prompt_chars", len([]rune(r.cfg.Prompt)))
	rep.Add(claude.KindLifecycle, map[string]any{
		"phase": "started", "session_id": sessionID, "executor": claude.Executor, "pid": pid,
	})

	mapper := claude.NewMapper()
	readDone := make(chan struct{})
	go func() {
		defer close(readDone)
		oversized, err := claude.ReadLines(pr, claude.MaxLine, func(line []byte) {
			for _, a := range mapper.Map(line) {
				rep.Add(a.Kind, a.Payload)
			}
		})
		if oversized > 0 {
			log.Warn("skipped oversized output lines", "count", oversized)
		}
		if err != nil && !errors.Is(err, os.ErrClosed) {
			log.Warn("reading executor output stopped", "err", err)
		}
	}()

	stopped := r.watchStop(ctx, pinned, sessionID, log)
	waitErr := cmd.Wait()
	stopWhy := stopped()

	select {
	case <-readDone:
	case <-time.After(pipeGrace):
		log.Warn("executor output still open after exit; closing it")
		pr.Close()
		<-readDone
	}

	exitCode := -1
	if cmd.ProcessState != nil {
		exitCode = cmd.ProcessState.ExitCode()
	}
	// A successful result counts even if a stop was requested after it arrived.
	// An error result or a timeout stop is failed. Output that ends with no result
	// at all is lost (contract v1 "실행이 끊겼을 때" 1): nobody knows what was done.
	reason := EndFailed
	switch {
	case mapper.Succeeded():
		reason = EndSubmitted
	case stopWhy == "cancelled":
		reason = EndCancelled
	case stopWhy == "timeout":
		rep.Add(claude.KindError, map[string]any{"source": "agent", "text": "run timeout reached; executor stopped"})
	case !mapper.ResultSeen():
		reason = EndLost
		rep.Add(claude.KindError, map[string]any{"source": "agent", "text": "executor exited without a result event"})
	}
	rep.Add(claude.KindLifecycle, map[string]any{
		"phase": "ended", "end_reason": reason, "exit_code": exitCode, "result_seen": mapper.ResultSeen(),
	})

	if got := mapper.SessionID(); got != "" && got != sessionID {
		log.Warn("executor reported a different session id", "reported", got)
	}
	if dropped := mapper.Dropped(); len(dropped) > 0 {
		log.Info("output lines not sent as events", "by_reason", formatCounts(dropped))
	}
	if reason != EndSubmitted {
		// stderr stays in the local log only; it is never sent to the server.
		log.Warn("executor did not submit", "exit_err", waitErr, "stderr_tail", stderr.String())
	}
	return reason
}

// watchStop stops the executor when ctx ends or the run timeout passes. The
// returned function must be called after Wait; it reports why a stop was
// requested ("" if none), whether or not Kill had to act: on Ctrl+C the console
// may already have ended the executor. Wait may return while a stop is being
// decided; the pin, closed only after that, keeps the PID from being reused.
func (r *Runner) watchStop(ctx context.Context, pinned *proc.Run, marker string, log *slog.Logger) func() string {
	exited := make(chan struct{})
	done := make(chan struct{})
	var why string
	go func() {
		defer close(done)
		var timeout <-chan time.Time
		if r.cfg.RunTimeout > 0 {
			t := time.NewTimer(r.cfg.RunTimeout)
			defer t.Stop()
			timeout = t.C
		}
		reason := ""
		select {
		case <-exited:
			return
		case <-ctx.Done():
			reason = "cancelled"
		case <-timeout:
			reason = "timeout"
		}
		why = reason
		if err := pinned.Kill(marker); err != nil {
			log.Error("executor not stopped", "why", reason, "err", err)
			return
		}
		log.Warn("executor stopped", "why", reason)
	}()
	return func() string {
		close(exited)
		<-done
		return why
	}
}

func (r *Runner) failBeforeStart(rep *report.Reporter, log *slog.Logger, msg string, err error) string {
	log.Error(msg, "err", err)
	rep.Add(claude.KindError, map[string]any{"source": "agent", "text": msg})
	rep.Add(claude.KindLifecycle, map[string]any{"phase": "ended", "end_reason": EndFailed, "result_seen": false})
	return EndFailed
}

func formatCounts(m map[string]int) string {
	keys := make([]string, 0, len(m))
	for k := range m {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	parts := make([]string, 0, len(keys))
	for _, k := range keys {
		parts = append(parts, fmt.Sprintf("%s=%d", k, m[k]))
	}
	return strings.Join(parts, " ")
}

// tailBuffer keeps the last max bytes written to it.
type tailBuffer struct {
	mu  sync.Mutex
	max int
	buf []byte
}

func (t *tailBuffer) Write(p []byte) (int, error) {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.buf = append(t.buf, p...)
	if over := len(t.buf) - t.max; over > 0 {
		t.buf = append(t.buf[:0], t.buf[over:]...)
	}
	return len(p), nil
}

func (t *tailBuffer) String() string {
	t.mu.Lock()
	defer t.mu.Unlock()
	return strings.TrimSpace(string(t.buf))
}
