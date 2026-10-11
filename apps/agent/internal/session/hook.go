package session

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"os"
	"path/filepath"
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
}

// HookEvents are the arguments of `zm-baton hook <event>`.
var HookEvents = []string{"session-start", "prompt-submit", "post-tool-use", "notification", "session-end"}

// fileTools change files; their paths become file_touch events. NotebookEdit names its file notebook_path.
var fileTools = map[string]bool{"Edit": true, "Write": true, "MultiEdit": true, "NotebookEdit": true}

// Hooks handles executor hook calls for one device.
type Hooks struct {
	Dir             string // zm-baton config folder
	Executor        string // "claude_code"
	ExecutorVersion string
	Stdout          io.Writer // only session-start writes here: the status card for the model
	Log             *slog.Logger
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
}

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
	data, err := io.ReadAll(io.LimitReader(stdin, 4<<20))
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
		return h.send(ctx, in, func(st *State) []Event {
			st.Waiting = true
			kind := in.NotificationType
			if kind == "" {
				kind = "unknown"
			}
			return []Event{{Kind: "waiting", Payload: map[string]any{"wait_kind": kind, "message": truncate(in.Message, 300)}}}
		})
	case "session-end":
		return h.sessionEnd(ctx, in)
	}
	return nil
}

func (h *Hooks) sessionStart(ctx context.Context, in HookInput) error {
	cfg, err := LoadConfig(h.Dir)
	if err != nil {
		return fmt.Errorf("not logged in: %w", err)
	}
	st, _, err := LoadState(h.Dir, h.Executor, in.SessionID)
	if err != nil {
		return err
	}
	if st.Off {
		return nil
	}
	var repoInfo *RepositoryInfo
	var repo *Repo
	if in.Cwd != "" {
		if r, err := RepoAt(ctx, in.Cwd); err == nil {
			repo = r
			repoInfo = &RepositoryInfo{RepoKey: r.Key, Remote: r.Remote, Prefix: r.Prefix, Branch: r.Branch}
		}
	}
	client, err := NewClient(cfg.Server, 3*time.Second)
	if err != nil {
		return err
	}
	reg, err := client.Register(ctx, RegisterRequest{
		DeviceID:          cfg.DeviceID,
		Executor:          h.Executor,
		ExecutorVersion:   h.ExecutorVersion,
		ExecutorSessionID: in.SessionID,
		Repository:        repoInfo,
	})
	if err != nil {
		return err
	}
	err = Update(h.Dir, h.Executor, in.SessionID, 2*time.Second, func(st *State, exists bool) (bool, error) {
		if st.SessionID != reg.SessionID || st.NextSeq == 0 {
			// local_seq only has to grow within one registration. Starting from the clock keeps it
			// growing even when this file was lost and the server reused the registration.
			st.NextSeq = time.Now().UnixMilli()
		}
		st.SessionID, st.Token, st.Server = reg.SessionID, reg.Token, cfg.Server
		st.RepoRoot, st.RepoKey = "", ""
		if repo != nil {
			st.RepoRoot, st.RepoKey = repo.Root, repo.Key
		}
		st.Waiting, st.Off = false, false
		return false, nil
	})
	if err != nil {
		return err
	}
	// Plain stdout of a SessionStart hook is added to the model's context.
	_, err = io.WriteString(h.Stdout, reg.StatusCard+"\n")
	return err
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
	err := h.send(ctx, in, func(st *State) []Event {
		return []Event{{Kind: "ended", Payload: map[string]any{}}}
	})
	// The state goes even when the server could not be told: its token is useless after the end.
	if rmErr := Update(h.Dir, h.Executor, in.SessionID, 500*time.Millisecond, func(st *State, exists bool) (bool, error) {
		return !st.Off, nil
	}); rmErr != nil && err == nil {
		err = rmErr
	}
	return err
}

// send picks events under the state lock (local_seq and the waiting flag change only there),
// then sends them without holding the lock. Nothing is sent for an unregistered or turned-off session.
func (h *Hooks) send(ctx context.Context, in HookInput, pick func(st *State) []Event) error {
	var events []Event
	var session State
	err := Update(h.Dir, h.Executor, in.SessionID, 2*time.Second, func(st *State, exists bool) (bool, error) {
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
	if err != nil {
		return err
	}
	if res.Rejected > 0 {
		return fmt.Errorf("server rejected %d event(s): %s", res.Rejected, strings.Join(res.Reasons, "; "))
	}
	return nil
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

func truncate(s string, n int) string {
	r := []rune(strings.TrimSpace(s))
	if len(r) <= n {
		return string(r)
	}
	return string(r[:n])
}
