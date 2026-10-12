package session

import (
	"bufio"
	"context"
	"errors"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// Review 1 of work/sessions-cli: locks, time limits, notifications, turning off and resumes.

// TestLockHelperProcess is not a test: it is the other process of the lock tests. It takes the
// device lock, says so and waits to be killed.
func TestLockHelperProcess(t *testing.T) {
	dir := os.Getenv("ZM_BATON_LOCK_HELPER")
	if dir == "" {
		t.Skip("helper process only")
	}
	if _, err := lockFile(context.Background(), filepath.Join(dir, "sessions", ".lock"), time.Second); err != nil {
		os.Exit(3)
	}
	_, _ = os.Stdout.WriteString("locked\n")
	time.Sleep(time.Minute)
	os.Exit(0)
}

func TestALockHeldByALiveProcessTimesOutAndOneLeftByAKilledProcessIsFree(t *testing.T) {
	dir := t.TempDir()
	if err := os.MkdirAll(filepath.Join(dir, "sessions"), 0o700); err != nil {
		t.Fatal(err)
	}
	cmd := exec.Command(os.Args[0], "-test.run=^TestLockHelperProcess$")
	cmd.Env = append(os.Environ(), "ZM_BATON_LOCK_HELPER="+dir)
	out, err := cmd.StdoutPipe()
	if err != nil {
		t.Fatal(err)
	}
	if err := cmd.Start(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = cmd.Process.Kill(); _ = cmd.Wait() })
	if line, _ := bufio.NewReader(out).ReadString('\n'); line != "locked\n" {
		t.Fatalf("helper said %q", line)
	}
	noop := func(*State, bool) (bool, error) { return false, nil }
	start := time.Now()
	if err := Update(context.Background(), dir, "claude_code", "s", 200*time.Millisecond, noop); !errors.Is(err, errLockTimeout) {
		t.Fatalf("a live holder's lock was taken: %v", err)
	}
	if d := time.Since(start); d > time.Second {
		t.Errorf("waited %v for a 200ms limit", d)
	}
	// A killed holder never unlocks; the system releases its lock, and nothing is guessed from age.
	_ = cmd.Process.Kill()
	_ = cmd.Wait()
	if err := Update(context.Background(), dir, "claude_code", "s", 2*time.Second, noop); err != nil {
		t.Fatalf("the killed holder's lock was not released: %v", err)
	}
}

func TestUpdateStopsAtTheContextDeadline(t *testing.T) {
	dir := t.TempDir()
	if err := os.MkdirAll(filepath.Join(dir, "sessions"), 0o700); err != nil {
		t.Fatal(err)
	}
	release, err := lockFile(context.Background(), filepath.Join(dir, "sessions", ".lock"), time.Second)
	if err != nil {
		t.Fatal(err)
	}
	defer release()
	ctx, cancel := context.WithTimeout(context.Background(), 100*time.Millisecond)
	defer cancel()
	start := time.Now()
	err = Update(ctx, dir, "claude_code", "s", 10*time.Second, func(*State, bool) (bool, error) { return false, nil })
	if !errors.Is(err, errLockTimeout) || time.Since(start) > time.Second {
		t.Fatalf("err %v after %v", err, time.Since(start))
	}
}

func TestAHookInputThatNeverEndsStopsAtTheTimeLimit(t *testing.T) {
	h, _ := setup(t, "http://127.0.0.1:1")
	r, w := io.Pipe()
	defer w.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 200*time.Millisecond)
	defer cancel()
	start := time.Now()
	if err := h.Run(ctx, "prompt-submit", r); err == nil {
		t.Error("an unfinished input must be an error for the log")
	}
	if d := time.Since(start); d > time.Second {
		t.Errorf("the hook waited %v for its input", d)
	}
}

func TestNotificationsSendOnlyWaitsAndOnlyAToolName(t *testing.T) {
	f := newFakeServer(t)
	h, _ := setup(t, f.srv.URL)
	ctx := context.Background()
	if err := h.Run(ctx, "session-start", hookInput(t, map[string]any{"session_id": "esid-n", "cwd": t.TempDir()})); err != nil {
		t.Fatal(err)
	}
	notify := func(kind, message string) {
		t.Helper()
		if err := h.Run(ctx, "notification", hookInput(t, map[string]any{"session_id": "esid-n", "notification_type": kind, "message": message})); err != nil {
			t.Fatal(err)
		}
	}
	notify("auth_success", "Signed in")
	notify("agent_completed", "done")
	notify("", "no type")
	notify("idle_prompt", "C:/Users/someone/secret.txt holds SYNTHETIC_TOKEN_123")
	notify("permission_prompt", "Claude needs your permission to use Bash")
	notify("permission_prompt", "Claude needs your permission to use Bash on /home/someone/.ssh/id_rsa")
	if got := strings.Join(f.kinds(), ","); got != "waiting,waiting,waiting" {
		t.Fatalf("events %s: only waits are sent", got)
	}
	for _, body := range f.bodies {
		if strings.Contains(body, "SYNTHETIC_TOKEN") || strings.Contains(body, "someone") {
			t.Errorf("notification text left the device: %s", body)
		}
	}
	if _, ok := f.events[0].Payload["message"]; ok {
		t.Errorf("free text was sent: %v", f.events[0].Payload)
	}
	if f.events[1].Payload["message"] != "Claude needs your permission to use Bash" {
		t.Errorf("the tool name was lost: %v", f.events[1].Payload)
	}
}

func TestSummariesStateTheSessionsSharingEveryTime(t *testing.T) {
	f := newFakeServer(t)
	h, _ := setup(t, f.srv.URL)
	ctx := context.Background()
	if err := h.Run(ctx, "session-start", hookInput(t, map[string]any{"session_id": "esid-s", "cwd": t.TempDir()})); err != nil {
		t.Fatal(err)
	}
	no, yes := false, true
	for _, share := range []*bool{nil, &no, nil, &yes, nil} {
		if _, err := SendCurrentWork(ctx, h.Dir, "claude_code", "esid-s", "작업", nil, share); err != nil {
			t.Fatal(err)
		}
	}
	var got []any
	for _, e := range f.events {
		got = append(got, e.Payload["share"])
	}
	want := []any{true, false, false, true, true}
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("share per summary %v, want %v", got, want)
		}
	}
}

func TestAFailedOffStopsReportingAndCanBeSentAgain(t *testing.T) {
	f := newFakeServer(t)
	h, _ := setup(t, f.srv.URL)
	ctx := context.Background()
	if err := h.Run(ctx, "session-start", hookInput(t, map[string]any{"session_id": "esid-o", "cwd": t.TempDir()})); err != nil {
		t.Fatal(err)
	}
	f.mu.Lock()
	f.failEvents = 1
	f.mu.Unlock()
	if _, err := TurnOff(ctx, h.Dir, "claude_code", "esid-o"); err == nil {
		t.Fatal("a deregistration the server refused must be an error")
	}
	st, _, _ := LoadState(h.Dir, "claude_code", "esid-o")
	if !st.Off || !st.OffPending || st.Token == "" {
		t.Fatalf("after a failed off: %+v", st)
	}
	if err := h.Run(ctx, "prompt-submit", hookInput(t, map[string]any{"session_id": "esid-o"})); err != nil {
		t.Fatal(err)
	}
	if len(f.kinds()) != 0 {
		t.Fatalf("a turned-off session reported %v", f.kinds())
	}
	if outcome, err := TurnOff(ctx, h.Dir, "claude_code", "esid-o"); err != nil || outcome != OffDelivered {
		t.Fatalf("retry: %v %v", outcome, err)
	}
	if outcome, err := TurnOff(ctx, h.Dir, "claude_code", "esid-o"); err != nil || outcome != OffBefore {
		t.Fatalf("third time: %v %v", outcome, err)
	}
	if got := strings.Join(f.kinds(), ","); got != "deregistered" {
		t.Errorf("events %s", got)
	}
	if st, _, _ := LoadState(h.Dir, "claude_code", "esid-o"); st.Token != "" || st.OffPending {
		t.Errorf("the token outlived the delivered off: %+v", st)
	}
}

func TestAnOffThatFinishesWhileARegistrationIsOnItsWayStands(t *testing.T) {
	f := newFakeServer(t)
	h, out := setup(t, f.srv.URL)
	ctx := context.Background()
	if err := h.Run(ctx, "session-start", hookInput(t, map[string]any{"session_id": "esid-r", "cwd": t.TempDir()})); err != nil {
		t.Fatal(err)
	}
	out.Reset()
	f.mu.Lock()
	f.registerSeen = make(chan struct{}, 1)
	f.registerGate = make(chan struct{})
	f.mu.Unlock()
	done := make(chan error, 1)
	go func() {
		done <- h.Run(ctx, "session-start", hookInput(t, map[string]any{"session_id": "esid-r", "cwd": t.TempDir(), "source": "resume"}))
	}()
	<-f.registerSeen
	if outcome, err := TurnOff(ctx, h.Dir, "claude_code", "esid-r"); err != nil || outcome != OffDelivered {
		t.Fatalf("off: %v %v", outcome, err)
	}
	f.mu.Lock()
	close(f.registerGate)
	f.mu.Unlock()
	if err := <-done; err != nil {
		t.Fatal(err)
	}
	st, _, _ := LoadState(h.Dir, "claude_code", "esid-r")
	if !st.Off || st.OffPending || st.Token != "" {
		t.Fatalf("the late registration undid the off: %+v", st)
	}
	if out.Len() != 0 {
		t.Errorf("a turned-off session got a status card: %q", out.String())
	}
	if got := strings.Join(f.kinds(), ","); got != "deregistered,deregistered" {
		t.Errorf("events %s: the late registration must be turned off too", got)
	}
	if err := h.Run(ctx, "prompt-submit", hookInput(t, map[string]any{"session_id": "esid-r"})); err != nil {
		t.Fatal(err)
	}
	if len(f.kinds()) != 2 {
		t.Errorf("reported after off: %v", f.kinds())
	}
}

func TestAReusedRegistrationKeepsTheWaitAndAFailedResumeIsSentAgain(t *testing.T) {
	f := newFakeServer(t)
	h, _ := setup(t, f.srv.URL)
	ctx := context.Background()
	run := func(event string, fields map[string]any) {
		t.Helper()
		fields["session_id"] = "esid-w"
		if err := h.Run(ctx, event, hookInput(t, fields)); err != nil && event != "post-tool-use" {
			t.Fatal(err)
		}
	}
	run("session-start", map[string]any{"cwd": t.TempDir()})
	run("notification", map[string]any{"notification_type": "permission_prompt"})
	run("session-start", map[string]any{"cwd": t.TempDir(), "source": "compact"})
	run("post-tool-use", map[string]any{"tool_name": "Bash"})
	run("notification", map[string]any{"notification_type": "idle_prompt"})
	f.mu.Lock()
	f.failEvents = 1
	f.mu.Unlock()
	run("post-tool-use", map[string]any{"tool_name": "Bash"}) // its resume does not arrive
	run("post-tool-use", map[string]any{"tool_name": "Bash"})
	run("post-tool-use", map[string]any{"tool_name": "Bash"})
	if got := strings.Join(f.kinds(), ","); got != "waiting,resumed,waiting,resumed" {
		t.Errorf("events %s", got)
	}
}

func TestABadServerURLStaysOutOfTheError(t *testing.T) {
	_, err := NewClient("http://user:SYNTHETIC_SECRET@exa%zzmple.invalid", time.Second)
	if err == nil || strings.Contains(err.Error(), "SYNTHETIC") {
		t.Errorf("err %v", err)
	}
}
