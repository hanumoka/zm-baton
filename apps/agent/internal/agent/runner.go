// Package agent is the stage-1 local connector: it polls for requested runs,
// claims one, runs Claude Code without a shell and reports its output as events.
// Runner is the compat-test loop (a fixed folder and prompt); Handoff runs delegated work
// items, each in its own Git worktree. Both execute a claimed run through ExecuteJob.
package agent

import (
	"context"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"runtime"
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

// KindGitChanges is the report kind of a run's Git change list (contract v1 "보고 한 건의 모양",
// protocol_version 2). The connector makes it, not the executor, so it is not an activity kind.
const KindGitChanges = "git_changes"

// pipeGrace is how long stdout may stay open after the executor exits
// (for example held by a grandchild) before it is closed.
const pipeGrace = 5 * time.Second

// defaultCloseTimeout bounds how long the last events may take to reach the server.
const defaultCloseTimeout = 30 * time.Second

// defaultChangesInterval is how often a running job's Git change list is sent (contract v1
// "보고 한 건의 모양": every 60 seconds while running, and at the end).
const defaultChangesInterval = 60 * time.Second

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
	// ChangesInterval is how often a job with a Git change list sends it; 0 means 60 seconds.
	ChangesInterval time.Duration
	// RenewInterval is how often a claimed run renews its lease and hears a person's stop request
	// and instructions; 0 means defaultRenewInterval.
	RenewInterval time.Duration
	// StateDir keeps one record per executor this device started (record.go). Empty keeps none,
	// and then no later run reuses an earlier run's worktree and session.
	StateDir string
	Log      *slog.Logger
}

// Job is what one claimed run executes. The compat-test loop builds it from its flags; the
// handoff loop builds it from the delegation and the run's own worktree.
type Job struct {
	Workdir      string
	Prompt       string
	AllowedTools string // comma-separated, for --allowedTools
	// Workspace, when set, goes into the lifecycle events: the run record keeps it (contract
	// "실행마다 별도 작업 트리" 8). It must hold no path on this device.
	Workspace map[string]any
	// Changes, when set, returns the git_changes payload; final is true once the executor ended.
	Changes func(ctx context.Context, final bool) (map[string]any, error)
	// Env is added to the executor's environment.
	Env []string
	// Restricted runs Claude Code with --restricted and --strict-mcp-config, so the person's own
	// settings and MCP servers cannot widen AllowedTools (claude.Options.Restricted).
	Restricted bool
	// HideDir, when set, is a folder on this device whose path is cut out of the executor's
	// activity before it is sent: tools report absolute paths, and the run's worktree is one.
	HideDir string
	// LiveInput sends the prompt and a person's instructions on stdin (claude.Options.StreamInput),
	// so the executor takes an instruction while it runs.
	LiveInput bool
	// ResumeSession, when set, continues that executor session (claude.Options.Resume). If Claude
	// Code no longer has it, the run starts a new session in the same worktree instead.
	ResumeSession string
	// Readiness holds the checks made before the executor starts; it goes into the end report.
	Readiness *Readiness
}

// Prepare makes the job after the claim, for example the run's worktree. An error ends the run
// failed before any executor started; the error text stays in the local log. The Job it returns
// with an error still counts: its Workspace and Readiness are reported if set.
type Prepare func(ctx context.Context, log *slog.Logger) (Job, error)

// Runner executes claimed runs one at a time.
type Runner struct {
	cfg     Config
	log     *slog.Logger
	records runRecords
}

// New checks the settings that every compat-test run needs: the fixed folder and prompt.
func New(cfg Config) (*Runner, error) {
	switch {
	case strings.TrimSpace(cfg.Workdir) == "":
		return nil, errors.New("workdir is required")
	case strings.TrimSpace(cfg.Prompt) == "":
		return nil, errors.New("prompt is required")
	case strings.TrimSpace(cfg.AllowedTools) == "":
		return nil, errors.New("allowed tools must not be empty")
	}
	return newRunner(cfg)
}

// newRunner checks what executing any job needs. The handoff loop has no fixed folder or prompt.
func newRunner(cfg Config) (*Runner, error) {
	switch {
	case cfg.Client == nil:
		return nil, errors.New("client is required")
	case strings.TrimSpace(cfg.DeviceID) == "":
		return nil, errors.New("device id is required")
	case cfg.ClaudePath == "":
		return nil, errors.New("executor path is required")
	}
	if cfg.PollInterval <= 0 {
		cfg.PollInterval = 2 * time.Second
	}
	if cfg.CloseTimeout <= 0 {
		cfg.CloseTimeout = defaultCloseTimeout
	}
	if cfg.ChangesInterval <= 0 {
		cfg.ChangesInterval = defaultChangesInterval
	}
	log := cfg.Log
	if log == nil {
		log = slog.New(slog.DiscardHandler)
	}
	return &Runner{cfg: cfg, log: log, records: runRecords{dir: cfg.StateDir}}, nil
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

// claimNext claims the first requested Claude Code compat-test run. Losing a race is normal.
// A delegated run is never taken here: it needs its own worktree and instruction (Handoff).
func (r *Runner) claimNext(ctx context.Context) (*api.Claim, error) {
	runs, err := r.cfg.Client.PendingRuns(ctx)
	if err != nil {
		return nil, err
	}
	for _, run := range runs {
		if run.Executor != claude.Executor || run.DelegationID != "" {
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

// Execute runs Claude Code for one claimed compat-test run in the fixed folder with the fixed
// prompt. It runs restricted, like a delegated run, so the person's own settings (for example the
// permission mode auto) cannot widen --allowed-tools.
func (r *Runner) Execute(ctx context.Context, c api.Claim) Result {
	return r.ExecuteJob(ctx, c, func(context.Context, *slog.Logger) (Job, error) {
		return Job{Workdir: r.cfg.Workdir, Prompt: r.cfg.Prompt, AllowedTools: r.cfg.AllowedTools, Restricted: true}, nil
	})
}

// ExecuteJob prepares and runs one claimed run and reports everything, ending with a lifecycle
// "ended" event. The lease is renewed from the claim to the end; a stop asked for before the
// executor starts ends the run cancelled without starting it. Delivery failures are returned,
// never folded into success.
func (r *Runner) ExecuteJob(ctx context.Context, c api.Claim, prepare Prepare) Result {
	log := r.log.With("run_id", c.RunID, "generation", c.Generation)
	rep := report.New(r.cfg.Client, c.RunID, c.Generation, r.cfg.FlushInterval, log)
	rep.Start()
	ctl := startControl(ctx, r.cfg.Client, c, r.cfg.DeviceID, r.cfg.RenewInterval, log)

	var reason string
	job, err := prepare(ctx, log)
	switch {
	case err != nil:
		reason = r.failBeforeStart(rep, log, "could not prepare the run", err, job, ctl)
	default:
		ctl.renewNow(ctx) // the freshest word on a stop before anything starts
		if why := ctl.stopReason(); why != "" {
			reason = r.endBeforeStart(rep, log, why, job, ctl)
		} else {
			reason = r.execute(ctx, rep, log, job, ctl)
		}
	}

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

// launched is how one executor process went.
type launched struct {
	started  bool // the process started; when false, failMsg and failErr say why
	failMsg  string
	failErr  error
	mapper   *claude.Mapper
	stopWhy  string // "", "cancelled" or "timeout"
	exitCode int
	waitErr  error
	stderr   string
}

func (r *Runner) execute(ctx context.Context, rep *report.Reporter, log *slog.Logger, job Job, ctl *control) string {
	sessionID, resume := NewUUIDv4(), false
	if job.ResumeSession != "" {
		sessionID, resume = job.ResumeSession, true
	}
	l := r.launch(ctx, rep, log, job, ctl, sessionID, resume)
	if !l.started {
		return r.failBeforeStart(rep, log, l.failMsg, l.failErr, job, ctl)
	}
	if resume && l.stopWhy == "" && l.mapper.ResumeRefused() {
		// The worktree is this run's to use; only the earlier conversation is gone (contract
		// "실행이 끊겼을 때" 2: otherwise a new session). The prompt already carries the whole work item.
		log.Warn("Claude Code no longer has the earlier session; starting a new one in the same worktree")
		if l2 := r.launch(ctx, rep, log, job, ctl, NewUUIDv4(), false); l2.started {
			l = l2
		}
	}

	// A successful result counts even if a stop was requested after it arrived.
	// An error result or a timeout stop is failed. Output that ends with no result
	// at all is lost (contract v1 "실행이 끊겼을 때" 1): nobody knows what was done.
	reason := EndFailed
	switch {
	case l.mapper.Succeeded():
		reason = EndSubmitted
	case l.stopWhy == "cancelled":
		reason = EndCancelled
	case l.stopWhy == "timeout":
		rep.Add(claude.KindError, map[string]any{"source": "agent", "text": "run timeout reached; executor stopped"})
	case !l.mapper.ResultSeen():
		reason = EndLost
		rep.Add(claude.KindError, map[string]any{"source": "agent", "text": "executor exited without a result event"})
	}
	if job.Changes != nil {
		// The last change list, with the base branch comparison, even after Ctrl+C (contract 4).
		changesCtx, cancel := context.WithTimeout(context.WithoutCancel(ctx), 30*time.Second)
		if p, err := job.Changes(changesCtx, true); err != nil {
			log.Warn("could not list the run's Git changes", "err", err)
		} else {
			rep.Add(KindGitChanges, p)
		}
		cancel()
	}
	endedPayload := map[string]any{
		"phase": "ended", "end_reason": reason, "exit_code": l.exitCode,
		"executor_started": true, "result_seen": l.mapper.ResultSeen(),
	}
	r.addEndDetails(endedPayload, job, ctl)
	rep.Add(claude.KindLifecycle, endedPayload)

	if dropped := l.mapper.Dropped(); len(dropped) > 0 {
		log.Info("output lines not sent as events", "by_reason", formatCounts(dropped))
	}
	if reason != EndSubmitted {
		// stderr stays in the local log only; it is never sent to the server.
		log.Warn("executor did not submit", "exit_err", l.waitErr, "stderr_tail", l.stderr)
	}
	return reason
}

// launch starts the executor once, pins it, reports its start and output, and waits for it.
func (r *Runner) launch(ctx context.Context, rep *report.Reporter, log *slog.Logger, job Job, ctl *control, sessionID string, resume bool) launched {
	opts := claude.Options{
		SessionID: sessionID, AllowedTools: job.AllowedTools, Restricted: job.Restricted,
		StreamInput: job.LiveInput, Resume: resume,
	}
	if !job.LiveInput {
		opts.Prompt = job.Prompt
	}
	cmd := exec.Command(r.cfg.ClaudePath, claude.Args(opts)...) // no shell
	cmd.Dir = job.Workdir
	if len(job.Env) > 0 {
		cmd.Env = append(os.Environ(), job.Env...)
	}
	cmd.SysProcAttr = proc.SysProcAttr() // Windows: start suspended until Track pins it
	stderr := &tailBuffer{max: 4096}
	cmd.Stderr = stderr
	cmd.WaitDelay = pipeGrace
	var stdin io.WriteCloser
	if job.LiveInput {
		var err error
		if stdin, err = cmd.StdinPipe(); err != nil {
			return launched{failMsg: "cannot create the input pipe", failErr: err}
		}
	}

	// Our own pipe, so Wait never closes the read end before the last line is read.
	pr, pw, err := os.Pipe()
	if err != nil {
		return launched{failMsg: "cannot create the output pipe", failErr: err}
	}
	defer pr.Close()
	cmd.Stdout = pw
	if err := cmd.Start(); err != nil {
		pw.Close()
		msg := "executor failed to start"
		if errors.Is(err, exec.ErrNotFound) {
			msg = "executor not found"
		}
		return launched{failMsg: msg, failErr: err}
	}
	pw.Close()
	pid := cmd.Process.Pid // recorded right after Start (contract v1 "프로세스 관리" 1)
	// Pin the executor before it runs, so a stop can only ever reach this process.
	pinned, err := proc.Track(cmd.Process)
	if err != nil {
		// Through Go's own handle: still this process, still suspended. If even that
		// fails, do not Wait for a process that will never run.
		if killErr := cmd.Process.Kill(); killErr != nil {
			log.Error("could not end the unpinned executor; it stays suspended", "err", killErr)
		} else {
			_ = cmd.Wait()
		}
		return launched{failMsg: "cannot pin the executor process", failErr: err}
	}
	// Released only after the stop decision below is final.
	defer pinned.Close()
	runID := ctlRunID(ctl)
	record := RunRecord{RunID: runID, SessionID: sessionID, PID: pid, StartedAt: time.Now().UTC()}
	if err := r.records.save(record); err != nil && r.records.dir != "" {
		log.Warn("could not keep the run record; a later run will not reuse this worktree", "err", err)
	}
	log = log.With("pid", pid, "session_id", sessionID)
	log.Info("executor started", "prompt_chars", len([]rune(job.Prompt)), "allowed_tools", job.AllowedTools,
		"resume", resume, "live_input", job.LiveInput)
	startedPayload := map[string]any{
		"phase": "started", "session_id": sessionID, "executor": claude.Executor, "pid": pid,
		"allowed_tools": strings.Split(job.AllowedTools, ","),
		"capabilities":  map[string]any{"live_input": job.LiveInput, "resume": true},
	}
	if job.Workspace != nil {
		startedPayload["workspace"] = job.Workspace
	}
	rep.Add(claude.KindLifecycle, startedPayload)
	var input *inputPipe
	if stdin != nil {
		input = newInputPipe(stdin)
		go func() {
			if !input.write("", job.Prompt) {
				log.Warn("could not give the executor its prompt")
			}
			ctl.attach(input) // instructions that waited go after the prompt
		}()
	}
	stopChanges := r.watchChanges(ctx, rep, log, job)

	mapper := claude.NewMapper()
	hide := pathHider(job.HideDir)
	readDone := make(chan struct{})
	go func() {
		defer close(readDone)
		closing := false
		oversized, err := claude.ReadLines(pr, claude.MaxLine, func(line []byte) {
			for _, a := range mapper.Map(line) {
				rep.Add(a.Kind, hide(a.Payload))
			}
			for _, text := range mapper.TakeReplays() {
				ctl.replayed(text)
			}
			if input != nil && !closing && mapper.ResultSeen() {
				// The run's result arrived: end the input so the executor exits. Never wait on a
				// write from here; the executor might be waiting for this reader.
				closing = true
				input.closing.Store(true)
				go input.close()
			}
		})
		if oversized > 0 {
			log.Warn("skipped oversized output lines", "count", oversized)
		}
		if err != nil && !errors.Is(err, os.ErrClosed) {
			log.Warn("reading executor output stopped", "err", err)
		}
	}()

	stopped := r.watchStop(ctx, pinned, sessionID, ctl.stopped(), log)
	waitErr := cmd.Wait()
	stopWhy := stopped()

	select {
	case <-readDone:
	case <-time.After(pipeGrace):
		log.Warn("executor output still open after exit; closing it")
		pr.Close()
		<-readDone
	}
	stopChanges()
	if input != nil {
		input.close()
	}
	// Did the executor leave a child behind? Only "none" lets a later run reuse this worktree.
	left, remErr := pinned.Remaining()
	if remErr == nil && left > 0 {
		log.Warn("processes the executor started are still running", "count", left)
	}
	now := time.Now().UTC()
	record.Exited, record.ChildrenGone, record.EndedAt = true, remErr == nil && left == 0, &now
	if err := r.records.save(record); err != nil && r.records.dir != "" {
		log.Warn("could not update the run record", "err", err)
	}
	if got := mapper.SessionID(); got != "" && got != sessionID {
		log.Warn("executor reported a different session id", "reported", got)
	}

	exitCode := -1
	if cmd.ProcessState != nil {
		exitCode = cmd.ProcessState.ExitCode()
	}
	return launched{started: true, mapper: mapper, stopWhy: stopWhy, exitCode: exitCode, waitErr: waitErr, stderr: stderr.String()}
}

func ctlRunID(c *control) string {
	if c == nil {
		return ""
	}
	return c.runID
}

// watchStop stops the executor when ctx ends, a person or the server asks for a stop, or the run
// timeout passes. The returned function must be called after Wait; it reports why a stop was
// requested ("" if none), whether or not Kill had to act: on Ctrl+C the console may already have
// ended the executor. Wait may return while a stop is being decided; the pin, closed only after
// that, keeps the PID from being reused. On Windows Kill ends the executor's whole job, so the
// processes it started end with it.
func (r *Runner) watchStop(ctx context.Context, pinned *proc.Run, marker string, asked <-chan struct{}, log *slog.Logger) func() string {
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
		case <-asked:
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

// watchChanges sends the job's Git change list every ChangesInterval while the executor runs.
// The returned function stops it and waits for a send in progress.
func (r *Runner) watchChanges(ctx context.Context, rep *report.Reporter, log *slog.Logger, job Job) func() {
	if job.Changes == nil {
		return func() {}
	}
	stop := make(chan struct{})
	done := make(chan struct{})
	go func() {
		defer close(done)
		t := time.NewTicker(r.cfg.ChangesInterval)
		defer t.Stop()
		for {
			select {
			case <-stop:
				return
			case <-ctx.Done():
				return
			case <-t.C:
				if p, err := job.Changes(ctx, false); err != nil {
					log.Warn("could not list the run's Git changes", "err", err)
				} else {
					rep.Add(KindGitChanges, p)
				}
			}
		}
	}()
	return func() {
		close(stop)
		<-done
	}
}

// addEndDetails puts what the end report carries besides the end itself: the run's worktree, the
// readiness check and what became of a person's instructions.
func (r *Runner) addEndDetails(p map[string]any, job Job, ctl *control) {
	if job.Workspace != nil {
		p["workspace"] = job.Workspace
	}
	if job.Readiness != nil && len(job.Readiness.Checks) > 0 {
		p["readiness"] = job.Readiness.Payload()
	}
	if inputs := ctl.finish(); len(inputs) > 0 {
		p["inputs"] = inputs
	}
}

// failBeforeStart ends a run whose executor never started (contract "상태": nothing changed, so
// no outcome_unknown). The error text stays in the local log; the server gets a short message:
// for a failed readiness check, the check's own detail.
func (r *Runner) failBeforeStart(rep *report.Reporter, log *slog.Logger, msg string, err error, job Job, ctl *control) string {
	log.Error(msg, "err", err)
	if c, ok := failedCheck(err); ok {
		msg = "시작 전 준비 검사에서 멈췄다: " + c.Detail
	}
	rep.Add(claude.KindError, map[string]any{"source": "agent", "text": msg})
	ended := map[string]any{"phase": "ended", "end_reason": EndFailed, "executor_started": false, "result_seen": false}
	r.addEndDetails(ended, job, ctl)
	rep.Add(claude.KindLifecycle, ended)
	return EndFailed
}

// endBeforeStart ends a run a person stopped before its executor started (contract "상태": 띄우기
// 전, 사용자 중단 → cancelled, no outcome_unknown). The worktree, if made, stays.
func (r *Runner) endBeforeStart(rep *report.Reporter, log *slog.Logger, why string, job Job, ctl *control) string {
	log.Warn("stop asked for before the executor started; it does not start", "why", why)
	ended := map[string]any{"phase": "ended", "end_reason": EndCancelled, "executor_started": false, "result_seen": false}
	r.addEndDetails(ended, job, ctl)
	rep.Add(claude.KindLifecycle, ended)
	return EndCancelled
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

// pathHider returns a function that replaces dir, as Windows, slash or Git Bash spells it, in
// every string of a payload: dir followed by a separator becomes nothing (a path from the folder),
// dir alone becomes ".". A longer name that only starts like dir (run_1-old for run_1) is left.
// With dir empty the payload is returned as it is.
func pathHider(dir string) func(map[string]any) map[string]any {
	if dir == "" {
		return func(p map[string]any) map[string]any { return p }
	}
	clean := filepath.Clean(dir)
	forms := []string{clean, filepath.ToSlash(clean)}
	if vol := filepath.VolumeName(clean); len(vol) == 2 && vol[1] == ':' {
		// Git Bash: C:\a\b is /c/a/b.
		forms = append(forms, "/"+strings.ToLower(vol[:1])+filepath.ToSlash(clean[2:]))
	}
	var parts []string
	for _, f := range forms {
		parts = append(parts, regexp.QuoteMeta(f))
	}
	// Windows paths compare without case; elsewhere case matters.
	flags := ""
	if runtime.GOOS == "windows" {
		flags = "(?i)"
	}
	re := regexp.MustCompile(flags + "(?:" + strings.Join(parts, "|") + `)(?:[\\/]|[^\w.\-]|$)`)
	var walk func(v any) any
	walk = func(v any) any {
		switch x := v.(type) {
		case string:
			return re.ReplaceAllStringFunc(x, func(m string) string {
				switch last := m[len(m)-1]; {
				case last == '/' || last == '\\':
					return ""
				case strings.IndexByte(nameChars, last) >= 0:
					return "." // dir ended the string
				default:
					return "." + string(last) // dir, then a quote, a space or the like
				}
			})
		case map[string]any:
			out := make(map[string]any, len(x))
			for k, val := range x {
				out[k] = walk(val)
			}
			return out
		case []any:
			out := make([]any, len(x))
			for i, val := range x {
				out[i] = walk(val)
			}
			return out
		}
		return v
	}
	return func(p map[string]any) map[string]any { return walk(p).(map[string]any) }
}

// nameChars are the bytes a name may go on with after dir; a match never ends on one of them
// unless dir ended the string.
const nameChars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_.-"

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
