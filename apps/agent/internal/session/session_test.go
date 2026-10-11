package session

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"runtime"
	"strings"
	"sync"
	"testing"
	"time"
)

// gitRepo makes a repository with one commit and an origin carrying credentials.
func gitRepo(t *testing.T) string {
	t.Helper()
	if _, err := exec.LookPath("git"); err != nil {
		t.Skip("git is not installed")
	}
	dir := t.TempDir()
	run := func(args ...string) {
		t.Helper()
		cmd := exec.Command("git", append([]string{"-C", dir, "-c", "user.name=t", "-c", "user.email=t@example.invalid"}, args...)...)
		if out, err := cmd.CombinedOutput(); err != nil {
			t.Fatalf("git %v: %v\n%s", args, err, out)
		}
	}
	run("init", "-q", "-b", "main")
	if err := os.MkdirAll(filepath.Join(dir, "docs"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "docs", "a.md"), []byte("hello\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	run("add", "docs/a.md")
	run("commit", "-q", "-m", "first")
	run("remote", "add", "origin", "https://user:s3cret@github.com/me/repo.git")
	return dir
}

func TestCleanRemote(t *testing.T) {
	cases := map[string]string{
		"https://user:tok@github.com/me/r.git":                         "https://github.com/me/r.git",
		"https://" + strings.Repeat("SYNTHETIC_", 60) + "@x.invalid/r": "https://x.invalid/r",
		"https://x.invalid/r.git?access_token=SYNTHETIC#frag":          "https://x.invalid/r.git",
		"git@github-personal:me/r.git":                                 "github-personal:me/r.git",
		"SYNTHETIC_USER@github.com:me/r.git":                           "github.com:me/r.git",
		"ssh://git@host:22/me/r.git":                                   "ssh://host:22/me/r.git",
		"/home/me/r.git":                                               "",
		`C:\work\r.git`:                                                "",
		"../r":                                                         "",
		"file:///tmp/r":                                                "",
		"not a remote":                                                 "",
		"https://x.invalid/" + strings.Repeat("p", 600):                "",
	}
	for in, want := range cases {
		if got := CleanRemote(in); got != want {
			t.Errorf("CleanRemote(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestRepoAtReadsKeyPrefixBranchAndCleanRemote(t *testing.T) {
	dir := gitRepo(t)
	r, err := RepoAt(context.Background(), filepath.Join(dir, "docs"))
	if err != nil {
		t.Fatal(err)
	}
	if !regexp.MustCompile(`^git:[0-9a-f]{40,64}$`).MatchString(r.Key) {
		t.Errorf("key %q", r.Key)
	}
	if r.Prefix != "docs" || r.Branch != "main" || r.Remote != "https://github.com/me/repo.git" {
		t.Errorf("prefix %q branch %q remote %q", r.Prefix, r.Branch, r.Remote)
	}
	if rel, ok := r.Rel(filepath.Join(dir, "docs", "a.md")); !ok || rel != "docs/a.md" {
		t.Errorf("Rel inside = %q %v", rel, ok)
	}
	if _, ok := r.Rel(filepath.Join(filepath.Dir(dir), "elsewhere.md")); ok {
		t.Error("a file outside the repository must not be relative")
	}
	if _, ok := r.Rel("docs/a.md"); ok {
		t.Error("a relative input is refused")
	}
	if runtime.GOOS == "windows" {
		if rel, ok := r.Rel(strings.ToUpper(filepath.Join(dir, "docs", "a.md"))); !ok || !strings.EqualFold(rel, "docs/a.md") {
			t.Errorf("Rel must ignore case on Windows: %q %v", rel, ok)
		}
	}
	if _, err := RepoAt(context.Background(), t.TempDir()); err == nil {
		t.Error("a folder outside Git is not a repository")
	}
}

func TestUpdateHandsOutUniqueSequenceNumbersUnderConcurrency(t *testing.T) {
	dir := t.TempDir()
	if err := Update(dir, "claude_code", "s1", time.Second, func(st *State, _ bool) (bool, error) {
		st.SessionID = "ses_x"
		return false, nil
	}); err != nil {
		t.Fatal(err)
	}
	var wg sync.WaitGroup
	seen := make(chan int64, 40)
	for i := 0; i < 40; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			err := Update(dir, "claude_code", "s1", 5*time.Second, func(st *State, _ bool) (bool, error) {
				st.NextSeq++
				seen <- st.NextSeq
				return false, nil
			})
			if err != nil {
				t.Error(err)
			}
		}()
	}
	wg.Wait()
	close(seen)
	got := map[int64]bool{}
	for s := range seen {
		if got[s] {
			t.Fatalf("local_seq %d handed out twice", s)
		}
		got[s] = true
	}
	if len(got) != 40 {
		t.Fatalf("%d numbers, want 40", len(got))
	}
}

func TestAStaleLockIsTakenOver(t *testing.T) {
	dir := t.TempDir()
	path := statePath(dir, "claude_code", "s2") + ".lock"
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte("1"), 0o600); err != nil {
		t.Fatal(err)
	}
	old := time.Now().Add(-time.Minute)
	if err := os.Chtimes(path, old, old); err != nil {
		t.Fatal(err)
	}
	if err := Update(dir, "claude_code", "s2", 100*time.Millisecond, func(*State, bool) (bool, error) { return false, nil }); err != nil {
		t.Fatalf("stale lock blocked: %v", err)
	}
}

const otherSettings = `{
  "model": "opus",
  "hooks": {
    "PreToolUse": [{"matcher": "Bash", "hooks": [{"type": "command", "command": "python a.py && echo ok"}]}],
    "SessionStart": [{"hooks": [{"type": "command", "command": "python -X utf8 scripts/session_memory.py print"}]}]
  },
  "permissions": {"allow": ["Read"]}
}`

func TestInstallHooksKeepsEverythingElse(t *testing.T) {
	// Paths in this OS's form: on Windows the backslashes must come out as forward slashes.
	sep := string(filepath.Separator)
	exe := filepath.Join(sep+"Tools", "zm-baton.exe")
	out, changes, err := InstallHooks([]byte(otherSettings), exe)
	if err != nil {
		t.Fatal(err)
	}
	s := string(out)
	for _, want := range []string{`"python a.py && echo ok"`, `scripts/session_memory.py print`, `"opus"`, `"Read"`} {
		if !strings.Contains(s, want) {
			t.Errorf("lost %s:\n%s", want, s)
		}
	}
	if strings.Index(s, `"model"`) > strings.Index(s, `"hooks"`) || strings.Index(s, `"hooks"`) > strings.Index(s, `"permissions"`) {
		t.Errorf("key order changed:\n%s", s)
	}
	for _, spec := range ClaudeHooks {
		if !strings.Contains(s, `"\"/Tools/zm-baton.exe\" hook `+spec.Arg+`"`) {
			t.Errorf("missing hook %s:\n%s", spec.Arg, s)
		}
	}
	if len(changes) != len(ClaudeHooks) {
		t.Errorf("changes %v", changes)
	}
	again, _, err := InstallHooks(out, exe)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(again, out) {
		t.Errorf("a second install must not change the file:\n%s\n---\n%s", out, again)
	}
	moved, _, err := InstallHooks(out, filepath.Join(sep+"new", "zm-baton.exe"))
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(moved), "/Tools/") || strings.Count(string(moved), "zm-baton.exe") != len(ClaudeHooks) {
		t.Errorf("reinstall from another path must replace, not add:\n%s", moved)
	}
}

func TestUninstallHooksRemovesOnlyZmBaton(t *testing.T) {
	installed, _, err := InstallHooks([]byte(otherSettings), "/opt/zm-baton")
	if err != nil {
		t.Fatal(err)
	}
	out, removed, err := UninstallHooks(installed)
	if err != nil {
		t.Fatal(err)
	}
	if removed != len(ClaudeHooks) {
		t.Errorf("removed %d", removed)
	}
	s := string(out)
	if strings.Contains(s, "zm-baton") {
		t.Errorf("zm-baton left:\n%s", s)
	}
	if !strings.Contains(s, "python a.py && echo ok") || !strings.Contains(s, "session_memory.py") {
		t.Errorf("other hooks lost:\n%s", s)
	}
}

func TestInstallHooksRefusesBrokenSettings(t *testing.T) {
	for _, bad := range []string{`[1,2]`, `{"hooks": 3}`, `{"a":1} trailing`, `{"hooks":{"SessionStart":"x"}}`} {
		if _, _, err := InstallHooks([]byte(bad), "/z"); err == nil {
			t.Errorf("accepted %s", bad)
		}
	}
	out, _, err := InstallHooks(nil, "/z")
	if err != nil || !strings.Contains(string(out), "session-start") {
		t.Errorf("an empty file must become a settings object: %v %s", err, out)
	}
}

// fakeServer records what hooks send.
type fakeServer struct {
	mu       sync.Mutex
	bodies   []string
	events   []Event
	register int
	srv      *httptest.Server
}

func newFakeServer(t *testing.T) *fakeServer {
	f := &fakeServer{}
	f.srv = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		data, _ := io.ReadAll(r.Body)
		f.mu.Lock()
		defer f.mu.Unlock()
		f.bodies = append(f.bodies, string(data))
		switch {
		case r.Method == http.MethodPost && r.URL.Path == "/api/sessions":
			f.register++
			w.WriteHeader(http.StatusCreated)
			_, _ = w.Write([]byte(`{"session_id":"ses_test","token":"zmbs_tok","reused":false,"status_card":"[zm-baton] card"}`))
		case r.Method == http.MethodPost && r.URL.Path == "/api/sessions/ses_test/events":
			if r.Header.Get("Authorization") != "Bearer zmbs_tok" {
				w.WriteHeader(http.StatusUnauthorized)
				return
			}
			var batch struct {
				Events []Event `json:"events"`
			}
			_ = json.Unmarshal(data, &batch)
			f.events = append(f.events, batch.Events...)
			_, _ = w.Write([]byte(`{"stored":1,"duplicates":0,"rejected":0,"reasons":[]}`))
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	t.Cleanup(f.srv.Close)
	return f
}

func (f *fakeServer) kinds() []string {
	f.mu.Lock()
	defer f.mu.Unlock()
	var k []string
	for _, e := range f.events {
		k = append(k, e.Kind)
	}
	return k
}

func setup(t *testing.T, server string) (*Hooks, *bytes.Buffer) {
	dir := t.TempDir()
	if err := SaveConfig(dir, Config{Server: server, DeviceID: "dev_test"}); err != nil {
		t.Fatal(err)
	}
	out := &bytes.Buffer{}
	return &Hooks{Dir: dir, Executor: "claude_code", ExecutorVersion: "2.1.296", Stdout: out}, out
}

func hookInput(t *testing.T, fields map[string]any) io.Reader {
	data, err := json.Marshal(fields)
	if err != nil {
		t.Fatal(err)
	}
	return bytes.NewReader(data)
}

func TestSessionStartRegistersPrintsTheCardAndSendsNoAbsolutePath(t *testing.T) {
	repo := gitRepo(t)
	f := newFakeServer(t)
	h, out := setup(t, f.srv.URL)
	err := h.Run(context.Background(), "session-start", hookInput(t, map[string]any{
		"session_id": "esid-1", "cwd": filepath.Join(repo, "docs"), "hook_event_name": "SessionStart", "source": "startup",
	}))
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(out.String(), "[zm-baton] card") {
		t.Errorf("stdout %q", out.String())
	}
	st, ok, err := LoadState(h.Dir, "claude_code", "esid-1")
	if err != nil || !ok || st.Token != "zmbs_tok" || st.SessionID != "ses_test" || st.RepoKey == "" {
		t.Fatalf("state %+v %v %v", st, ok, err)
	}
	body := f.bodies[0]
	if strings.Contains(body, filepath.ToSlash(repo)) || strings.Contains(body, strings.ReplaceAll(repo, `\`, `\\`)) || strings.Contains(body, "s3cret") {
		t.Errorf("registration leaked a local path or a credential: %s", body)
	}
	if !strings.Contains(body, `"prefix":"docs"`) || !strings.Contains(body, `"branch":"main"`) {
		t.Errorf("registration body %s", body)
	}
}

func TestPostToolUseSendsARelativeFileTouchWithHashAndHead(t *testing.T) {
	repo := gitRepo(t)
	f := newFakeServer(t)
	h, _ := setup(t, f.srv.URL)
	ctx := context.Background()
	if err := h.Run(ctx, "session-start", hookInput(t, map[string]any{"session_id": "esid-2", "cwd": repo})); err != nil {
		t.Fatal(err)
	}
	file := filepath.Join(repo, "docs", "a.md")
	if err := os.WriteFile(file, []byte("changed\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := h.Run(ctx, "post-tool-use", hookInput(t, map[string]any{
		"session_id": "esid-2", "tool_name": "Write", "tool_input": map[string]any{"file_path": file, "content": "changed\n"},
	})); err != nil {
		t.Fatal(err)
	}
	if err := h.Run(ctx, "post-tool-use", hookInput(t, map[string]any{
		"session_id": "esid-2", "tool_name": "Write", "tool_input": map[string]any{"file_path": filepath.Join(t.TempDir(), "x.md")},
	})); err != nil {
		t.Fatal(err)
	}
	if k := f.kinds(); len(k) != 1 || k[0] != "file_touch" {
		t.Fatalf("events %v: a file outside the repository must not be sent", k)
	}
	p := f.events[0].Payload
	if p["path"] != "docs/a.md" || !regexp.MustCompile(`^[0-9a-f]{40,64}$`).MatchString(p["content_hash"].(string)) ||
		!regexp.MustCompile(`^[0-9a-f]{40,64}$`).MatchString(p["head"].(string)) {
		t.Errorf("payload %v", p)
	}
	if _, leaked := p["_root"]; leaked {
		t.Error("the local root left the device")
	}
	if f.events[0].LocalSeq <= 0 || !strings.HasPrefix(f.events[0].EventID, "evt_") {
		t.Errorf("event %+v", f.events[0])
	}
}

func TestAWaitIsFollowedByOneResumeOnTheNextTool(t *testing.T) {
	f := newFakeServer(t)
	h, _ := setup(t, f.srv.URL)
	ctx := context.Background()
	if err := h.Run(ctx, "session-start", hookInput(t, map[string]any{"session_id": "esid-3", "cwd": t.TempDir()})); err != nil {
		t.Fatal(err)
	}
	steps := []struct{ event, tool, kind string }{
		{"notification", "", "permission_prompt"},
		{"post-tool-use", "Bash", ""},
		{"post-tool-use", "Bash", ""},
		{"prompt-submit", "", ""},
	}
	for _, s := range steps {
		in := map[string]any{"session_id": "esid-3", "tool_name": s.tool, "notification_type": s.kind, "message": "needs permission"}
		if err := h.Run(ctx, s.event, hookInput(t, in)); err != nil {
			t.Fatal(err)
		}
	}
	got := strings.Join(f.kinds(), ",")
	if got != "waiting,resumed,resumed" {
		t.Errorf("events %s", got)
	}
	seqs := []int64{}
	for _, e := range f.events {
		seqs = append(seqs, e.LocalSeq)
	}
	for i := 1; i < len(seqs); i++ {
		if seqs[i] <= seqs[i-1] {
			t.Errorf("local_seq must grow: %v", seqs)
		}
	}
}

func TestHooksStayQuietWhenTheServerIsDown(t *testing.T) {
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	addr := l.Addr().String()
	l.Close() // nothing listens here now
	h, out := setup(t, "http://"+addr)
	start := time.Now()
	err = h.Run(context.Background(), "session-start", hookInput(t, map[string]any{"session_id": "esid-4", "cwd": t.TempDir()}))
	if err == nil {
		t.Error("expected the failure to be reported for the log")
	}
	if out.Len() != 0 {
		t.Errorf("printed %q into the session", out.String())
	}
	if time.Since(start) > 5*time.Second {
		t.Errorf("took %v", time.Since(start))
	}
	if _, ok, _ := LoadState(h.Dir, "claude_code", "esid-4"); ok {
		t.Error("no state for a session that never registered")
	}
	// Later hooks of the unregistered session send nothing and create nothing.
	if err := h.Run(context.Background(), "prompt-submit", hookInput(t, map[string]any{"session_id": "esid-4"})); err != nil {
		t.Error(err)
	}
	if _, ok, _ := LoadState(h.Dir, "claude_code", "esid-4"); ok {
		t.Error("a hook created state for an unregistered session")
	}
}

func TestTurnedOffSessionsSendNothingMore(t *testing.T) {
	f := newFakeServer(t)
	h, _ := setup(t, f.srv.URL)
	ctx := context.Background()
	if err := h.Run(ctx, "session-start", hookInput(t, map[string]any{"session_id": "esid-5", "cwd": t.TempDir()})); err != nil {
		t.Fatal(err)
	}
	if _, err := TurnOff(ctx, h.Dir, "claude_code", "esid-5"); err != nil {
		t.Fatal(err)
	}
	registrations := f.register
	if err := h.Run(ctx, "session-start", hookInput(t, map[string]any{"session_id": "esid-5", "cwd": t.TempDir(), "source": "resume"})); err != nil {
		t.Fatal(err)
	}
	if err := h.Run(ctx, "prompt-submit", hookInput(t, map[string]any{"session_id": "esid-5"})); err != nil {
		t.Fatal(err)
	}
	if f.register != registrations || strings.Join(f.kinds(), ",") != "deregistered" {
		t.Errorf("after off: registrations %d->%d, events %v", registrations, f.register, f.kinds())
	}
	st, _, _ := LoadState(h.Dir, "claude_code", "esid-5")
	if st.Token != "" || !st.Off {
		t.Errorf("state after off %+v", st)
	}
	if _, err := SendCurrentWork(ctx, h.Dir, "claude_code", "esid-5", "x", nil, nil); err != ErrNotRegistered {
		t.Errorf("work after off: %v", err)
	}

	h.Off = true // ZM_BATON_OFF
	before := f.register
	if err := h.Run(ctx, "session-start", hookInput(t, map[string]any{"session_id": "esid-6", "cwd": t.TempDir()})); err != nil {
		t.Fatal(err)
	}
	if f.register != before {
		t.Error("ZM_BATON_OFF must keep the session from registering")
	}
}

func TestCurrentWorkAndSessionEnd(t *testing.T) {
	f := newFakeServer(t)
	h, _ := setup(t, f.srv.URL)
	ctx := context.Background()
	if err := h.Run(ctx, "session-start", hookInput(t, map[string]any{"session_id": "esid-7", "cwd": t.TempDir()})); err != nil {
		t.Fatal(err)
	}
	if _, err := SendCurrentWork(ctx, h.Dir, "claude_code", "esid-7", strings.Repeat("가", 301), nil, nil); err == nil {
		t.Error("301 characters must be refused before sending")
	}
	private := false
	if _, err := SendCurrentWork(ctx, h.Dir, "claude_code", "esid-7", "결정 문서 쓰는 중", []string{"DEC-0105"}, &private); err != nil {
		t.Fatal(err)
	}
	if err := h.Run(ctx, "session-end", hookInput(t, map[string]any{"session_id": "esid-7", "reason": "prompt_input_exit"})); err != nil {
		t.Fatal(err)
	}
	if got := strings.Join(f.kinds(), ","); got != "current_work,ended" {
		t.Errorf("events %s", got)
	}
	if f.events[0].Payload["share"] != false {
		t.Errorf("payload %v", f.events[0].Payload)
	}
	if _, ok, _ := LoadState(h.Dir, "claude_code", "esid-7"); ok {
		t.Error("state must be gone after the session ended")
	}
}

func TestBadHookInputIsAnErrorForTheLogOnly(t *testing.T) {
	h, out := setup(t, "http://127.0.0.1:1")
	for _, in := range []string{"not json", `{"session_id":""}`} {
		if err := h.Run(context.Background(), "prompt-submit", strings.NewReader(in)); err == nil {
			t.Errorf("accepted %q", in)
		}
	}
	if err := h.Run(context.Background(), "bogus", strings.NewReader("{}")); err == nil {
		t.Error("unknown event accepted")
	}
	if out.Len() != 0 {
		t.Errorf("printed %q", out.String())
	}
}
