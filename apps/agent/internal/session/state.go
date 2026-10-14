// Package session connects AI sessions that a person opened directly (Claude Code, and later
// Codex) to the zm-baton server: device login, registration from executor hooks, and the events
// those hooks send (contract v1 "직접 연 AI 세션을 서버에 등록한다", protocol_version 2).
//
// Hooks must never get in the person's way: every hook path is bounded in time, writes nothing
// on failure and leaves the decision to the executor. Errors go to a log file in the config folder.
package session

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"time"
)

// Config is what `zm-baton login` keeps on the device. DeviceToken is a secret of this device's: the
// file is readable by the owner only and the token goes to Server alone. It is empty for a device
// registered before tokens, which a server in local mode still takes (docs/stage1/auth.md).
type Config struct {
	Server      string `json:"server"`
	DeviceID    string `json:"device_id"`
	DeviceToken string `json:"device_token,omitempty"`
}

// Dir is the zm-baton config folder. ZM_BATON_HOME overrides it (tests, separate profiles).
func Dir() (string, error) {
	if d := os.Getenv("ZM_BATON_HOME"); d != "" {
		return d, nil
	}
	base, err := os.UserConfigDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(base, "zm-baton"), nil
}

// LoadConfig reads config.json. A missing file returns an error that matches os.ErrNotExist.
func LoadConfig(dir string) (Config, error) {
	var c Config
	data, err := os.ReadFile(filepath.Join(dir, "config.json"))
	if err != nil {
		return c, err
	}
	if err := json.Unmarshal(data, &c); err != nil {
		return c, fmt.Errorf("config.json: %w", err)
	}
	if c.Server == "" || c.DeviceID == "" {
		return c, errors.New("config.json has no server or device_id; run zm-baton login")
	}
	return c, nil
}

// SaveConfig writes config.json readable by the owner only.
func SaveConfig(dir string, c Config) error {
	data, err := json.MarshalIndent(c, "", "  ")
	if err != nil {
		return err
	}
	return writeFileAtomic(filepath.Join(dir, "config.json"), data)
}

// State is one registered session as this device remembers it. It holds the session token,
// so the file is readable by the owner only and its content goes to the zm-baton server alone.
// RepoRoot is a local absolute path: it stays on the device and is never sent.
type State struct {
	SessionID         string `json:"session_id,omitempty"`
	Token             string `json:"token,omitempty"`
	Server            string `json:"server,omitempty"`
	Executor          string `json:"executor"`
	ExecutorSessionID string `json:"executor_session_id"`
	RepoRoot          string `json:"repo_root,omitempty"`
	RepoKey           string `json:"repo_key,omitempty"`
	// NextSeq is the next local_seq to hand out (contract "세션이 보내는 사건" 3).
	NextSeq int64 `json:"next_seq"`
	// Waiting is set after a wait was reported, so the next tool use sends `resumed`.
	Waiting bool `json:"waiting,omitempty"`
	// Private is the session's sharing choice for 「지금 하는 일」. Every summary states it,
	// so the server never has to guess it from an earlier summary.
	Private bool `json:"private,omitempty"`
	// Off means the person turned registration off for this session; hooks send nothing more.
	Off bool `json:"off,omitempty"`
	// RelayOn is what the server said at the last registration about relays. While it is false
	// the relay hooks end at once without asking the server, and Claude Code asks in its terminal.
	RelayOn bool `json:"relay_on,omitempty"`
	// OffPending means the server has not yet taken the deregistration. SessionID and Token
	// are kept so `zm-baton session off` can send the same event again; once the server has
	// it, the token is dropped.
	OffPending bool   `json:"off_pending,omitempty"`
	OffEventID string `json:"off_event_id,omitempty"`
	OffSeq     int64  `json:"off_seq,omitempty"`
}

var safeName = regexp.MustCompile(`^[A-Za-z0-9_-]{1,100}$`)

func statePath(dir, executor, executorSessionID string) string {
	name := executorSessionID
	if !safeName.MatchString(name) {
		sum := sha256.Sum256([]byte(executorSessionID))
		name = hex.EncodeToString(sum[:16])
	}
	return filepath.Join(dir, "sessions", executor+"-"+name+".json")
}

// LoadState reads the state of one executor session. ok is false when there is none.
func LoadState(dir, executor, executorSessionID string) (State, bool, error) {
	var st State
	data, err := os.ReadFile(statePath(dir, executor, executorSessionID))
	if errors.Is(err, os.ErrNotExist) {
		return State{Executor: executor, ExecutorSessionID: executorSessionID}, false, nil
	}
	if err != nil {
		return st, false, err
	}
	if err := json.Unmarshal(data, &st); err != nil {
		return st, false, fmt.Errorf("session state: %w", err)
	}
	return st, true, nil
}

func saveState(dir string, st State) error {
	data, err := json.MarshalIndent(st, "", "  ")
	if err != nil {
		return err
	}
	return writeFileAtomic(statePath(dir, st.Executor, st.ExecutorSessionID), data)
}

func removeState(dir, executor, executorSessionID string) error {
	err := os.Remove(statePath(dir, executor, executorSessionID))
	if errors.Is(err, os.ErrNotExist) {
		return nil
	}
	return err
}

// errLockTimeout means another hook of the same session held the state too long.
var errLockTimeout = errors.New("session state is locked by another hook")

// Update runs fn on the session's state under the device's state lock and saves what fn
// leaves, unless fn returns remove=true. Hooks of one session can run in parallel (several
// tool calls), so local_seq, the waiting flag and the off marker are only changed here. It
// waits for the lock at most wait, and never past ctx's deadline.
func Update(ctx context.Context, dir, executor, executorSessionID string, wait time.Duration, fn func(st *State, exists bool) (remove bool, err error)) error {
	path := statePath(dir, executor, executorSessionID)
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return err
	}
	release, err := lockFile(ctx, filepath.Join(filepath.Dir(path), ".lock"), wait)
	if err != nil {
		return err
	}
	defer release()
	st, exists, err := LoadState(dir, executor, executorSessionID)
	if err != nil {
		return err
	}
	remove, err := fn(&st, exists)
	if err != nil {
		return err
	}
	if remove {
		return removeState(dir, executor, executorSessionID)
	}
	return saveState(dir, st)
}

// lockFile takes the operating system's exclusive lock on path (LockFileEx on Windows, flock
// elsewhere). The file is never removed: the lock belongs to the open file, so the system
// releases it when the holder closes it or dies, and no one ever has to guess that a lock is
// left over. One file serves all sessions of the device; what it guards takes milliseconds.
func lockFile(ctx context.Context, path string, wait time.Duration) (func(), error) {
	f, err := os.OpenFile(path, os.O_CREATE|os.O_RDWR, 0o600)
	if err != nil {
		return nil, err
	}
	deadline := time.Now().Add(wait)
	if d, ok := ctx.Deadline(); ok && d.Before(deadline) {
		deadline = d
	}
	for {
		ok, err := tryLock(f)
		if err != nil {
			_ = f.Close()
			return nil, err
		}
		if ok {
			return func() {
				unlock(f)
				_ = f.Close()
			}, nil
		}
		if !time.Now().Before(deadline) {
			_ = f.Close()
			return nil, errLockTimeout
		}
		select {
		case <-ctx.Done():
			_ = f.Close()
			return nil, errLockTimeout
		case <-time.After(5 * time.Millisecond):
		}
	}
}

// writeFileAtomic writes through a temporary file and a rename, readable by the owner only.
func writeFileAtomic(path string, data []byte) error {
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return err
	}
	tmp, err := os.CreateTemp(filepath.Dir(path), "."+filepath.Base(path)+".tmp-*")
	if err != nil {
		return err
	}
	name := tmp.Name()
	defer func() { _ = os.Remove(name) }()
	// Windows ignores most mode bits; there the file is protected by living in the user's own folder.
	_ = tmp.Chmod(0o600)
	if _, err := tmp.Write(data); err != nil {
		_ = tmp.Close()
		return err
	}
	if err := tmp.Close(); err != nil {
		return err
	}
	// On Windows a rename over a file that another process is reading fails for a moment.
	for attempt := 0; ; attempt++ {
		err := os.Rename(name, path)
		if err == nil || !errors.Is(err, os.ErrPermission) || attempt == 40 {
			return err
		}
		time.Sleep(5 * time.Millisecond)
	}
}
