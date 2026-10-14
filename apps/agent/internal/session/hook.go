package session

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"time"

	"github.com/hanumoka/zm-baton/apps/agent/internal/report"
)

// HookInput is the part of a Claude Code hook's stdin that zm-baton reads
// (code.claude.com/docs/en/hooks, "Common input fields" and the per-event inputs).
type HookInput struct {
	SessionID        string          `json:"session_id"`
	Cwd              string          `json:"cwd"`
	HookEventName    string          `json:"hook_event_name"`
	Source           string          `json:"source"`
	Reason           string          `json:"reason"`
	ToolName         string          `json:"tool_name"`
	ToolInput        json.RawMessage `json:"tool_input"`
	NotificationType string          `json:"notification_type"`
	Message          string          `json:"message"`
	PermissionMode   string          `json:"permission_mode"`
}

// HookEvents are the arguments of `zm-baton hook <event>`.
var HookEvents = []string{"session-start", "prompt-submit", "post-tool-use", "notification", "session-end", "permission-request", "pre-tool-use"}

// fileTools change files; their paths become file_touch events. NotebookEdit names its file notebook_path.
var fileTools = map[string]bool{"Edit": true, "Write": true, "MultiEdit": true, "NotebookEdit": true}

// Hooks handles executor hook calls for one device.
type Hooks struct {
	Dir      string    // zm-baton config folder
	Executor string    // "claude_code"
	Stdout   io.Writer // only session-start writes here: the status card for the model
	Log      *slog.Logger
	// Off is true when ZM_BATON_OFF asks this session not to register (contract: "시작 전에 끄면").
	Off bool
}

// Timeouts per hook. SessionEnd has a 1.5 second budget in Claude Code.
var hookTimeouts = map[string]time.Duration{
	"session-start": 4 * time.Second,
	"prompt-submit": 3 * time.Second,
	"post-tool-use": 3 * time.Second,
	"notification":  3 * time.Second,
	"session-end":   1 * time.Second,
	// A relay waits for a person on the first screen (contract relay 1): longer than the server's deadline.
	"permission-request": relayLimit,
	"pre-tool-use":       relayLimit,
}

// HookTimeout is the time limit of one hook event; ok is false for an unknown event.
func HookTimeout(event string) (time.Duration, bool) {
	t, ok := hookTimeouts[event]
	return t, ok
}

// waitKinds are the Claude Code notifications that mean the session waits for a person
// (code.claude.com/docs/en/hooks, Notification matchers). Others, such as auth_success or
// agent_completed, are not waits and send nothing.
var waitKinds = map[string]bool{
	"permission_prompt": true, "idle_prompt": true, "elicitation_dialog": true,
	"elicitation_url_dialog": true, "agent_needs_input": true,
}

// permissionMessage is the one notification text that leaves the device, because it names
// only a tool. Any other text may hold paths, file content or secrets and stays here.
var permissionMessage = regexp.MustCompile(`^Claude needs your permission to use [A-Za-z0-9_.:-]{1,100}$`)

// Run handles one hook call. It never blocks the executor for longer than the hook's timeout
// and the caller exits 0 whatever it returns; the error is only for the log.
func (h *Hooks) Run(ctx context.Context, event string, stdin io.Reader) error {
	timeout, ok := hookTimeouts[event]
	if !ok {
		return fmt.Errorf("unknown hook event %q", event)
	}
	ctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	if h.Off {
		return nil
	}
	data, err := readAll(ctx, io.LimitReader(stdin, 4<<20))
	if err != nil {
		return fmt.Errorf("read hook input: %w", err)
	}
	var in HookInput
	if err := json.Unmarshal(data, &in); err != nil {
		return fmt.Errorf("hook input is not JSON: %w", err)
	}
	if strings.TrimSpace(in.SessionID) == "" {
		return errors.New("hook input has no session_id")
	}
	switch event {
	case "session-start":
		return h.sessionStart(ctx, in)
	case "prompt-submit":
		return h.send(ctx, in, func(st *State) []Event {
			st.Waiting = false
			return []Event{{Kind: "resumed", Payload: map[string]any{}}}
		})
	case "post-tool-use":
		return h.postToolUse(ctx, in)
	case "notification":
		if !waitKinds[in.NotificationType] {
			return nil
		}
		payload := map[string]any{"wait_kind": in.NotificationType}
		if msg := strings.TrimSpace(in.Message); permissionMessage.MatchString(msg) {
			payload["message"] = msg
		}
		return h.send(ctx, in, func(st *State) []Event {
			st.Waiting = true
			return []Event{{Kind: "waiting", Payload: payload}}
		})
	case "session-end":
		return h.sessionEnd(ctx, in)
	case "permission-request":
		return h.relay(ctx, in, "permission_request")
	case "pre-tool-use":
		// The plugin matches AskUserQuestion only; any other tool is none of the relay's business.
		if in.ToolName != "AskUserQuestion" {
			return nil
		}
		return h.relay(ctx, in, "session_question")
	}
	return nil
}

func (h *Hooks) sessionStart(ctx context.Context, in HookInput) error {
	reg, registered, err := h.register(ctx, in)
	if err != nil || !registered {
		return err
	}
	// Plain stdout of a SessionStart hook is added to the model's context.
	_, err = io.WriteString(h.Stdout, reg.StatusCard+"\n")
	return err
}

// register registers the executor session (or renews a live registration) and keeps the token.
// registered is false when the session is turned off, including an off that finished while the
// registration was on its way; that registration is turned off at once.
func (h *Hooks) register(ctx context.Context, in HookInput) (reg Registered, registered bool, err error) {
	cfg, err := LoadConfig(h.Dir)
	if err != nil {
		return reg, false, fmt.Errorf("not logged in: %w", err)
	}
	st, _, err := LoadState(h.Dir, h.Executor, in.SessionID)
	if err != nil {
		return reg, false, err
	}
	if st.Off {
		return reg, false, nil
	}
	var repoInfo *RepositoryInfo
	var repo *Repo
	dir := in.Cwd
	if dir == "" {
		dir = st.RepoRoot
	}
	if dir != "" {
		if r, err := RepoAt(ctx, dir); err == nil {
			repo = r
			repoInfo = &RepositoryInfo{RepoKey: r.Key, Remote: r.Remote, Prefix: r.Prefix, Branch: r.Branch}
		}
	}
	client, err := NewClient(cfg.Server, 3*time.Second)
	if err != nil {
		return reg, false, err
	}
	// No executor_version: neither the hook input nor its environment says which Claude Code
	// runs this session, and an inherited CLAUDE_CODE_VERSION names a parent's, not this one.
	reg, err = client.Register(ctx, RegisterRequest{
		DeviceID:          cfg.DeviceID,
		Executor:          h.Executor,
		ExecutorSessionID: in.SessionID,
		Repository:        repoInfo,
	})
	if err != nil {
		return reg, false, err
	}
	turnedOff := false
	err = Update(ctx, h.Dir, h.Executor, in.SessionID, 2*time.Second, func(st *State, exists bool) (bool, error) {
		if st.Off {
			// `zm-baton session off` finished while this registration was on its way. The off
			// stands, and the registration the server just made is the one to turn off now.
			turnedOff = true
			st.NextSeq = max(st.NextSeq, time.Now().UnixMilli()) + 1
			st.SessionID, st.Token, st.Server = reg.SessionID, reg.Token, cfg.Server
			st.OffPending, st.OffEventID, st.OffSeq = true, report.NewEventID(time.Now()), st.NextSeq
			return false, nil
		}
		if st.SessionID != reg.SessionID || st.NextSeq == 0 {
			// local_seq only has to grow within one registration. Starting from the clock keeps it
			// growing even when this file was lost and the server reused the registration.
			st.NextSeq = time.Now().UnixMilli()
			// A new registration starts with no waits: the server closed the old one's. A reused
			// one (a resume, a compaction) keeps them until a resume is sent.
			st.Waiting = false
		}
		st.SessionID, st.Token, st.Server = reg.SessionID, reg.Token, cfg.Server
		st.RepoRoot, st.RepoKey = "", ""
		if repo != nil {
			st.RepoRoot, st.RepoKey = repo.Root, repo.Key
		}
		return false, nil
	})
	if err != nil {
		return reg, false, err
	}
	if turnedOff {
		_, err := deliverOff(ctx, h.Dir, h.Executor, in.SessionID)
		return reg, false, err
	}
	return reg, true, nil
}

func (h *Hooks) postToolUse(ctx context.Context, in HookInput) error {
	var touch map[string]any
	if fileTools[in.ToolName] {
		var ti struct {
			FilePath     string `json:"file_path"`
			NotebookPath string `json:"notebook_path"`
		}
		_ = json.Unmarshal(in.ToolInput, &ti)
		path := ti.FilePath
		if path == "" {
			path = ti.NotebookPath
		}
		if path != "" {
			touch = map[string]any{"path": path, "tool": in.ToolName}
		}
	}
	return h.send(ctx, in, func(st *State) []Event {
		var events []Event
		if st.Waiting {
			// The tool ran, so whatever the session waited for is over (contract "resumed").
			st.Waiting = false
			events = append(events, Event{Kind: "resumed", Payload: map[string]any{}})
		}
		if touch != nil && st.RepoRoot != "" && st.RepoKey != "" {
			repo := &Repo{Root: st.RepoRoot, Key: st.RepoKey}
			if rel, ok := repo.Rel(touch["path"].(string)); ok {
				events = append(events, Event{Kind: "file_touch", Payload: map[string]any{
					"repo_key": st.RepoKey, "path": rel, "tool": touch["tool"],
					// Filled in after the lock is released; see fillTouch.
					"_root": st.RepoRoot,
				}})
			}
		}
		return events
	})
}

func (h *Hooks) sessionEnd(ctx context.Context, in HookInput) error {
	err := h.sendEvents(ctx, in, false, func(st *State) []Event {
		return []Event{{Kind: "ended", Payload: map[string]any{}}}
	})
	// The state goes even when the server could not be told: its token is useless after the end.
	// A turned-off session keeps its marker, and an undelivered off what a retry needs.
	if rmErr := Update(ctx, h.Dir, h.Executor, in.SessionID, 500*time.Millisecond, func(st *State, exists bool) (bool, error) {
		return !st.Off, nil
	}); rmErr != nil && err == nil {
		err = rmErr
	}
	return err
}

// send picks events under the state lock (local_seq and the waiting flag change only there),
// then sends them without holding the lock. Nothing is sent for an unregistered or turned-off session.
//
// A session the server no longer takes as live (HTTP 401) is registered again and the events go
// once more: the executor session is evidently still running, since its hook fires. The token
// lapses after 12 hours without events (a session left open overnight), and before this only the
// start hook registered, so such a session stayed unregistered until its next start or compaction.
func (h *Hooks) send(ctx context.Context, in HookInput, pick func(st *State) []Event) error {
	return h.sendEvents(ctx, in, true, pick)
}

func (h *Hooks) sendEvents(ctx context.Context, in HookInput, registerAgain bool, pick func(st *State) []Event) error {
	var events []Event
	var session State
	err := Update(ctx, h.Dir, h.Executor, in.SessionID, 2*time.Second, func(st *State, exists bool) (bool, error) {
		if !exists {
			return true, nil // nothing to save for a session this device never registered
		}
		if st.Off || st.SessionID == "" {
			return false, nil
		}
		events = pick(st)
		for i := range events {
			st.NextSeq++
			events[i].LocalSeq = st.NextSeq
			events[i].EventID = report.NewEventID(time.Now())
		}
		session = *st
		return false, nil
	})
	if err != nil || len(events) == 0 {
		return err
	}
	for i := range events {
		fillTouch(ctx, &events[i])
	}
	client, err := NewClient(session.Server, 3*time.Second)
	if err != nil {
		return err
	}
	res, err := client.Send(ctx, session.SessionID, session.Token, events)
	var status *StatusError
	if registerAgain && errors.As(err, &status) && status.Status == http.StatusUnauthorized {
		if _, registered, rerr := h.register(ctx, in); rerr == nil && registered {
			return h.sendEvents(ctx, in, false, pick)
		}
	}
	if err != nil {
		h.keepWaiting(ctx, in.SessionID, session.SessionID, events)
		return err
	}
	if res.Rejected > 0 {
		return fmt.Errorf("server rejected %d event(s): %s", res.Rejected, strings.Join(res.Reasons, "; "))
	}
	return nil
}

// keepWaiting puts the waiting flag back after a `resumed` failed to reach the server, so the
// next tool use or prompt sends it again instead of leaving the wait open on the first screen.
func (h *Hooks) keepWaiting(ctx context.Context, executorSessionID, sessionID string, events []Event) {
	for _, e := range events {
		if e.Kind != "resumed" {
			continue
		}
		_ = Update(ctx, h.Dir, h.Executor, executorSessionID, 500*time.Millisecond, func(st *State, exists bool) (bool, error) {
			if exists && !st.Off && st.SessionID == sessionID {
				st.Waiting = true
			}
			return !exists, nil
		})
		return
	}
}

// readAll reads the hook input but gives up at ctx's deadline: a stdin that never closes must
// not hold the session. The reading goroutine ends with the process.
func readAll(ctx context.Context, r io.Reader) ([]byte, error) {
	type result struct {
		data []byte
		err  error
	}
	done := make(chan result, 1)
	go func() {
		data, err := io.ReadAll(r)
		done <- result{data, err}
	}()
	select {
	case res := <-done:
		return res.data, res.err
	case <-ctx.Done():
		return nil, ctx.Err()
	}
}

// fillTouch adds the file's content hash and HEAD to a file_touch event, outside the state
// lock because both run git. The local root is removed before the event leaves the device.
func fillTouch(ctx context.Context, e *Event) {
	if e.Kind != "file_touch" {
		return
	}
	root, _ := e.Payload["_root"].(string)
	delete(e.Payload, "_root")
	rel, _ := e.Payload["path"].(string)
	if root == "" || rel == "" {
		return
	}
	if _, err := os.Stat(filepath.Join(root, filepath.FromSlash(rel))); err == nil {
		if h := HashObject(ctx, root, rel); h != "" {
			e.Payload["content_hash"] = h
		}
	}
	if head := Head(ctx, root); head != "" {
		e.Payload["head"] = head
	}
}
