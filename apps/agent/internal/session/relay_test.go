package session

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

// Synthetic credentials, put together at run time so no scanner mistakes this file for a leak.
var (
	fakeGitHubToken = "gh" + "p_" + strings.Repeat("A1b2", 9)
	fakeAWSKey      = "AK" + "IA" + "ABCDEFGHIJKLMNOP"
	fakeAnthropic   = "sk-" + "ant-" + strings.Repeat("x9", 15)
	fakeSession     = "zm" + "bs_" + strings.Repeat("Q", 30)
)

func TestFingerprintIgnoresKeyOrderAndKeepsNumbersAsWritten(t *testing.T) {
	a, err := Fingerprint("Bash", json.RawMessage(`{"command":"ls","timeout":120000,"description":"List"}`))
	if err != nil {
		t.Fatal(err)
	}
	b, _ := Fingerprint("Bash", json.RawMessage(`{ "description":"List", "timeout":120000, "command":"ls" }`))
	if a != b || len(a) != 64 {
		t.Fatalf("same input, different fingerprints: %s %s", a, b)
	}
	for _, other := range []struct {
		tool  string
		input string
	}{
		{"Bash", `{"command":"ls -la","timeout":120000,"description":"List"}`},
		{"Bash", `{"command":"ls","timeout":120000.0,"description":"List"}`},
		{"PowerShell", `{"command":"ls","timeout":120000,"description":"List"}`},
	} {
		if f, _ := Fingerprint(other.tool, json.RawMessage(other.input)); f == a {
			t.Errorf("%s %s has the same fingerprint", other.tool, other.input)
		}
	}
	if _, err := Fingerprint("Bash", json.RawMessage(`{not json`)); err == nil {
		t.Error("an unreadable input has no fingerprint")
	}
}

func TestTheSecretCheckCatchesCredentialsAndLetsOrdinaryWorkThrough(t *testing.T) {
	secrets := []string{
		"git push https://user:" + "hunter22" + "@github.com/me/r.git",
		"export GITHUB_TOKEN=" + fakeGitHubToken,
		"curl -H 'Authorization: Bearer " + strings.Repeat("abc123", 4) + "' https://api.example.invalid",
		"aws configure set aws_access_key_id " + fakeAWSKey,
		"ANTHROPIC_API_KEY=" + fakeAnthropic + " claude -p hi",
		`db.connect(password="` + "s3cr3t-pass" + `")`,
		"mysql --password=" + "tiger123" + " -u root",
		"-----BEGIN OPENSSH " + "PRIVATE KEY-----\nb3BlbnNzaC1rZXkt\n",
		"zm-baton status # " + fakeSession,
	}
	for _, s := range secrets {
		if !HasSecret(s) {
			t.Errorf("missed: %q", s)
		}
	}
	ordinary := []string{
		"npm run build && npm test",
		`git commit -m "fix token refresh when the session expires"`,
		"st.Token = reg.Token",
		"password: string;",
		"export TOKEN=$GITHUB_TOKEN",
		`token: process.env.GITHUB_TOKEN,`,
		"git clone git@github.com:me/repo.git",
		"go test -count=1 ./...",
		"curl -s http://127.0.0.1:18081/actuator/health",
		`docker login -u me --password-stdin < token.txt`,
	}
	for _, s := range ordinary {
		if HasSecret(s) {
			t.Errorf("false alarm: %q", s)
		}
	}
	if !InputHasSecret(json.RawMessage(`{"file_path":"a.env","content":"API_KEY=` + strings.Repeat("k3y", 4) + `"}`)) {
		t.Error("a secret inside a string value of the input is missed")
	}
	if !InputHasSecret(json.RawMessage(`{"url":"https://x.invalid","headers":{"password":"` + "tiger-tiger" + `"}}`)) {
		t.Error("a secret under a secret-named key is missed")
	}
	if !InputHasSecret(json.RawMessage(`{broken`)) {
		t.Error("an input the check cannot read must count as a secret (G34.2)")
	}
	if InputHasSecret(json.RawMessage(`{"command":"ls -la","description":"List files"}`)) {
		t.Error("false alarm on an ordinary input")
	}
}

func TestDecisionOutputIsClaudeCodesHookFormat(t *testing.T) {
	cases := []struct {
		name, kind, answer, want string
		input                    map[string]any
	}{
		{
			"allow", "permission_request", `{"decision":"allow"}`,
			`{"hookSpecificOutput":{"decision":{"behavior":"allow"},"hookEventName":"PermissionRequest"}}`, nil,
		},
		{
			"deny with reason", "permission_request", `{"decision":"deny","reason":"빌드 폴더는 지우지 않는다"}`,
			`{"hookSpecificOutput":{"decision":{"behavior":"deny","message":"Denied from the zm-baton first screen. Reason: 빌드 폴더는 지우지 않는다"},"hookEventName":"PermissionRequest"}}`, nil,
		},
		{
			"deny", "permission_request", `{"decision":"deny"}`,
			`{"hookSpecificOutput":{"decision":{"behavior":"deny","message":"Denied from the zm-baton first screen."},"hookEventName":"PermissionRequest"}}`, nil,
		},
		{
			"question", "session_question", `{"answers":{"Which color?":["Blue"],"Which sizes?":["S","L"]}}`,
			`{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"allow","permissionDecisionReason":"Answered on the zm-baton first screen",` +
				`"updatedInput":{"answers":{"Which color?":"Blue","Which sizes?":"S, L"},"questions":[` +
				`{"header":"Color","multiSelect":false,"options":[{"description":"warm","label":"Red"},{"description":"cool","label":"Blue"}],"question":"Which color?"},` +
				`{"header":"Size","multiSelect":true,"options":[{"label":"S"},{"label":"M"},{"label":"L"}],"question":"Which sizes?"}]}}}`,
			questionInput(),
		},
	}
	for _, c := range cases {
		out, err := DecisionOutput(c.kind, c.input, json.RawMessage(c.answer))
		if err != nil {
			t.Fatalf("%s: %v", c.name, err)
		}
		if got := strings.TrimSpace(string(out)); got != c.want {
			t.Errorf("%s:\n got %s\nwant %s", c.name, got, c.want)
		}
	}
	for _, bad := range []struct{ kind, answer string }{
		{"permission_request", `{"decision":"maybe"}`},
		{"permission_request", `{}`},
		{"session_question", `{"answers":{"Which color?":["Green"],"Which sizes?":["S"]}}`},
		{"session_question", `{"answers":{"Which color?":["Red","Blue"],"Which sizes?":["S"]}}`},
		{"session_question", `{"answers":{"Which color?":["Red"]}}`},
		{"session_question", `{"answers":{"Which color?":["Red"],"Which sizes?":["S"],"Other?":["x"]}}`},
		{"notification", `{}`},
	} {
		if out, err := DecisionOutput(bad.kind, questionInput(), json.RawMessage(bad.answer)); err == nil {
			t.Errorf("%s %s gave %s", bad.kind, bad.answer, out)
		}
	}
}

func questionInput() map[string]any {
	var m map[string]any
	_ = json.Unmarshal([]byte(questionJSON), &m)
	return m
}

const questionJSON = `{"questions":[
 {"question":"Which color?","header":"Color","multiSelect":false,"options":[{"label":"Red","description":"warm"},{"label":"Blue","description":"cool"}]},
 {"question":"Which sizes?","header":"Size","multiSelect":true,"options":[{"label":"S"},{"label":"M"},{"label":"L"}]}]}`

// relayServer is a fake zm-baton server for the relay: registration, events, and a relay whose
// answer the test controls.
type relayServer struct {
	mu      sync.Mutex
	srv     *httptest.Server
	starts  []map[string]any
	takes   int
	started string // the start answer
	// takeFn answers the n-th take (1-based); nil keeps waiting.
	takeFn func(n int, body map[string]any) (int, string)
}

func newRelayServer(t *testing.T) *relayServer {
	r := &relayServer{started: `{"relay":true,"item_id":"inb_x","wait_seconds":60}`}
	r.srv = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		data, _ := io.ReadAll(req.Body)
		var body map[string]any
		_ = json.Unmarshal(data, &body)
		r.mu.Lock()
		defer r.mu.Unlock()
		switch {
		case req.URL.Path == "/api/sessions":
			w.WriteHeader(http.StatusCreated)
			_, _ = w.Write([]byte(`{"session_id":"ses_r","token":"zmbs_r","status_card":"card"}`))
		case req.URL.Path == "/api/sessions/ses_r/relays" && req.Header.Get("Authorization") == "Bearer zmbs_r":
			r.starts = append(r.starts, body)
			_, _ = w.Write([]byte(r.started))
		case strings.HasPrefix(req.URL.Path, "/api/sessions/ses_r/relays/") && strings.HasSuffix(req.URL.Path, "/take"):
			r.takes++
			status, answer := http.StatusOK, `{"outcome":"waiting","state":"open"}`
			if r.takeFn != nil {
				status, answer = r.takeFn(r.takes, body)
			}
			w.WriteHeader(status)
			_, _ = w.Write([]byte(answer))
		case req.URL.Path == "/api/sessions/ses_r/events":
			_, _ = w.Write([]byte(`{"stored":1,"duplicates":0,"rejected":0,"reasons":[]}`))
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	t.Cleanup(r.srv.Close)
	return r
}

func (r *relayServer) lastStart(t *testing.T) map[string]any {
	t.Helper()
	r.mu.Lock()
	defer r.mu.Unlock()
	if len(r.starts) == 0 {
		t.Fatal("no relay was started")
	}
	return r.starts[len(r.starts)-1]
}

// relaySetup registers a session in repo (or a plain folder) and returns hooks whose relay polls fast.
func relaySetup(t *testing.T, server, cwd string) (*Hooks, *bytes.Buffer) {
	t.Helper()
	old := relayPoll
	relayPoll = 10 * time.Millisecond
	t.Cleanup(func() { relayPoll = old })
	h, out := setup(t, server)
	if err := h.Run(context.Background(), "session-start", hookInput(t, map[string]any{"session_id": "esid-relay", "cwd": cwd})); err != nil {
		t.Fatal(err)
	}
	out.Reset()
	return h, out
}

func permissionInput(t *testing.T, cwd string, toolInput map[string]any) io.Reader {
	return hookInput(t, map[string]any{
		"session_id": "esid-relay", "cwd": cwd, "hook_event_name": "PermissionRequest", "permission_mode": "default",
		"tool_name": "Bash", "tool_input": toolInput,
	})
}

func TestAPermissionRequestTheFirstScreenAllowsPrintsAnAllowDecision(t *testing.T) {
	repo := gitRepo(t)
	r := newRelayServer(t)
	r.takeFn = func(n int, body map[string]any) (int, string) {
		if n < 3 {
			return http.StatusOK, `{"outcome":"waiting","state":"open"}`
		}
		return http.StatusOK, `{"outcome":"delivered","state":"delivered","answer":{"decision":"allow"}}`
	}
	h, out := relaySetup(t, r.srv.URL, filepath.Join(repo, "docs"))
	input := map[string]any{"command": "rm -rf build && npm run build", "description": "Rebuild"}
	if err := h.Run(context.Background(), "permission-request", permissionInput(t, filepath.Join(repo, "docs"), input)); err != nil {
		t.Fatal(err)
	}
	want := `{"hookSpecificOutput":{"decision":{"behavior":"allow"},"hookEventName":"PermissionRequest"}}` + "\n"
	if out.String() != want {
		t.Fatalf("stdout %q", out.String())
	}
	start := r.lastStart(t)
	raw, _ := json.Marshal(start)
	if strings.Contains(string(raw), filepath.ToSlash(repo)) || strings.Contains(string(raw), strings.ReplaceAll(repo, `\`, `\\`)) {
		t.Errorf("the request carries the repository's absolute path: %s", raw)
	}
	detail := start["detail"].(map[string]any)
	if detail["command"] != "rm -rf build && npm run build" || detail["description"] != "Rebuild" || detail["tool_name"] != "Bash" {
		t.Errorf("detail %v", detail)
	}
	loc := detail["work_location"].(map[string]any)
	if loc["path"] != "docs" || loc["branch"] != "main" || loc["in_repo"] != true {
		t.Errorf("work location %v", loc)
	}
	fp, _ := Fingerprint("Bash", mustJSON(t, input))
	if start["input_fingerprint"] != fp || start["kind"] != "permission_request" || !strings.HasPrefix(start["relay_id"].(string), "rly_") {
		t.Errorf("start %v", start)
	}
	if st, _, _ := LoadState(h.Dir, "claude_code", "esid-relay"); !st.Waiting {
		t.Error("after a relay the next tool use must report that the session moved on")
	}
}

func mustJSON(t *testing.T, v any) json.RawMessage {
	data, err := json.Marshal(v)
	if err != nil {
		t.Fatal(err)
	}
	return data
}

func TestAFilePathIsSentRelativeToTheRepository(t *testing.T) {
	repo := gitRepo(t)
	r := newRelayServer(t)
	r.takeFn = func(int, map[string]any) (int, string) {
		return http.StatusOK, `{"outcome":"delivered","state":"delivered","answer":{"decision":"deny","reason":"no"}}`
	}
	h, out := relaySetup(t, r.srv.URL, repo)
	in := hookInput(t, map[string]any{
		"session_id": "esid-relay", "cwd": repo, "tool_name": "Write",
		"tool_input": map[string]any{"file_path": filepath.Join(repo, "docs", "new.md"), "content": "hello"},
	})
	if err := h.Run(context.Background(), "permission-request", in); err != nil {
		t.Fatal(err)
	}
	detail := r.lastStart(t)["detail"].(map[string]any)
	if detail["file_path"] != "docs/new.md" || detail["input"].(map[string]any)["file_path"] != "docs/new.md" {
		t.Errorf("detail %v", detail)
	}
	if !strings.Contains(out.String(), `"behavior":"deny"`) || !strings.Contains(out.String(), "Reason: no") {
		t.Errorf("stdout %q", out.String())
	}
}

func TestARelayGivesNoDecisionWhenAnythingIsOff(t *testing.T) {
	cases := []struct {
		name    string
		started string
		takeFn  func(int, map[string]any) (int, string)
	}{
		{"focus mode, not relayed", `{"relay":false,"reason":"focus_mode"}`, nil},
		{"deadline passed", "", func(n int, _ map[string]any) (int, string) {
			return http.StatusOK, `{"outcome":"over","state":"moved_to_terminal"}`
		}},
		{"fingerprint mismatch", "", func(int, map[string]any) (int, string) {
			return http.StatusOK, `{"outcome":"mismatch","state":"answered"}`
		}},
		{"session ended", "", func(int, map[string]any) (int, string) {
			return http.StatusUnauthorized, `{"message":"the session has ended or its token expired; register again"}`
		}},
		{"server error", "", func(int, map[string]any) (int, string) { return http.StatusInternalServerError, `{}` }},
		{"delivered without an answer", "", func(int, map[string]any) (int, string) {
			return http.StatusOK, `{"outcome":"delivered","state":"delivered"}`
		}},
		{"delivered an answer of another kind", "", func(int, map[string]any) (int, string) {
			return http.StatusOK, `{"outcome":"delivered","state":"delivered","answer":{"answers":{"x":["y"]}}}`
		}},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			r := newRelayServer(t)
			if c.started != "" {
				r.started = c.started
			}
			r.takeFn = c.takeFn
			h, out := relaySetup(t, r.srv.URL, t.TempDir())
			_ = h.Run(context.Background(), "permission-request", permissionInput(t, t.TempDir(), map[string]any{"command": "ls"}))
			if out.Len() != 0 {
				t.Errorf("printed %q: Claude Code must ask in its terminal", out.String())
			}
		})
	}
}

func TestARelayStopsWaitingAtItsTimeLimitWithoutADecision(t *testing.T) {
	r := newRelayServer(t) // keeps answering waiting
	h, out := relaySetup(t, r.srv.URL, t.TempDir())
	ctx, cancel := context.WithTimeout(context.Background(), 300*time.Millisecond)
	defer cancel()
	start := time.Now()
	err := h.Run(ctx, "permission-request", permissionInput(t, t.TempDir(), map[string]any{"command": "ls"}))
	if err == nil || out.Len() != 0 {
		t.Errorf("err %v, stdout %q", err, out.String())
	}
	if d := time.Since(start); d > 2*time.Second {
		t.Errorf("waited %v past a 300ms limit", d)
	}
}

func TestARelayWithTheServerDownGivesNoDecisionAtOnce(t *testing.T) {
	r := newRelayServer(t)
	h, out := relaySetup(t, r.srv.URL, t.TempDir())
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	addr := l.Addr().String()
	l.Close()
	if err := Update(context.Background(), h.Dir, "claude_code", "esid-relay", time.Second, func(st *State, _ bool) (bool, error) {
		st.Server = "http://" + addr
		return false, nil
	}); err != nil {
		t.Fatal(err)
	}
	start := time.Now()
	if err := h.Run(context.Background(), "permission-request", permissionInput(t, t.TempDir(), map[string]any{"command": "ls"})); err == nil {
		t.Error("the failure should reach the log")
	}
	if out.Len() != 0 || time.Since(start) > 5*time.Second {
		t.Errorf("stdout %q after %v", out.String(), time.Since(start))
	}
}

func TestASecretInTheInputLeavesTheDeviceAsAWithheldMarkOnly(t *testing.T) {
	r := newRelayServer(t)
	r.started = `{"relay":false,"item_id":"inb_s","reason":"secret_suspected"}`
	h, out := relaySetup(t, r.srv.URL, t.TempDir())
	secret := "export GITHUB_TOKEN=" + fakeGitHubToken
	if err := h.Run(context.Background(), "permission-request", permissionInput(t, t.TempDir(), map[string]any{"command": secret})); err != nil {
		t.Fatal(err)
	}
	start := r.lastStart(t)
	raw, _ := json.Marshal(start)
	if strings.Contains(string(raw), fakeGitHubToken) || start["detail"] != nil || start["withheld"] != "secret_suspected" {
		t.Errorf("start %s", raw)
	}
	if out.Len() != 0 {
		t.Errorf("stdout %q", out.String())
	}
}

func TestATooLongInputGoesToTheTerminal(t *testing.T) {
	r := newRelayServer(t)
	r.started = `{"relay":false,"item_id":"inb_l","reason":"too_large"}`
	h, _ := relaySetup(t, r.srv.URL, t.TempDir())
	if err := h.Run(context.Background(), "permission-request", permissionInput(t, t.TempDir(), map[string]any{"command": strings.Repeat("echo hi; ", 5000)})); err != nil {
		t.Fatal(err)
	}
	if s := r.lastStart(t); s["withheld"] != "too_large" || s["detail"] != nil {
		t.Errorf("start %v", s)
	}
}

func TestAnUnregisteredOrTurnedOffSessionRelaysNothing(t *testing.T) {
	r := newRelayServer(t)
	h, out := setup(t, r.srv.URL)
	if err := h.Run(context.Background(), "permission-request", permissionInput(t, t.TempDir(), map[string]any{"command": "ls"})); err != nil {
		t.Fatal(err)
	}
	h2, _ := relaySetup(t, r.srv.URL, t.TempDir())
	if _, err := TurnOff(context.Background(), h2.Dir, "claude_code", "esid-relay"); err != nil {
		t.Fatal(err)
	}
	if err := h2.Run(context.Background(), "permission-request", permissionInput(t, t.TempDir(), map[string]any{"command": "ls"})); err != nil {
		t.Fatal(err)
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	if len(r.starts) != 0 || out.Len() != 0 {
		t.Errorf("relayed %d", len(r.starts))
	}
}

func TestAQuestionAnsweredOnTheFirstScreenGoesBackInUpdatedInput(t *testing.T) {
	r := newRelayServer(t)
	r.takeFn = func(int, map[string]any) (int, string) {
		return http.StatusOK, `{"outcome":"delivered","state":"delivered","answer":{"answers":{"Which color?":["Blue"],"Which sizes?":["M"]}}}`
	}
	h, out := relaySetup(t, r.srv.URL, t.TempDir())
	var input map[string]any
	_ = json.Unmarshal([]byte(questionJSON), &input)
	in := hookInput(t, map[string]any{"session_id": "esid-relay", "cwd": t.TempDir(), "hook_event_name": "PreToolUse", "tool_name": "AskUserQuestion", "tool_input": input})
	if err := h.Run(context.Background(), "pre-tool-use", in); err != nil {
		t.Fatal(err)
	}
	var got struct {
		HookSpecificOutput struct {
			HookEventName      string         `json:"hookEventName"`
			PermissionDecision string         `json:"permissionDecision"`
			UpdatedInput       map[string]any `json:"updatedInput"`
		} `json:"hookSpecificOutput"`
	}
	if err := json.Unmarshal(out.Bytes(), &got); err != nil {
		t.Fatalf("stdout %q: %v", out.String(), err)
	}
	o := got.HookSpecificOutput
	if o.HookEventName != "PreToolUse" || o.PermissionDecision != "allow" || len(o.UpdatedInput["questions"].([]any)) != 2 {
		t.Errorf("output %+v", o)
	}
	answers := o.UpdatedInput["answers"].(map[string]any)
	if answers["Which color?"] != "Blue" || answers["Which sizes?"] != "M" {
		t.Errorf("answers %v", answers)
	}
	start := r.lastStart(t)
	if start["kind"] != "session_question" || len(start["detail"].(map[string]any)["questions"].([]any)) != 2 {
		t.Errorf("start %v", start)
	}
}

func TestPreToolUseOfAnotherToolDoesNothing(t *testing.T) {
	r := newRelayServer(t)
	h, out := relaySetup(t, r.srv.URL, t.TempDir())
	in := hookInput(t, map[string]any{"session_id": "esid-relay", "tool_name": "Bash", "tool_input": map[string]any{"command": "ls"}})
	if err := h.Run(context.Background(), "pre-tool-use", in); err != nil {
		t.Fatal(err)
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	if len(r.starts) != 0 || out.Len() != 0 {
		t.Errorf("relayed %d, stdout %q", len(r.starts), out.String())
	}
}

// expiringServer issues a new token per registration and treats every earlier one as ended, as the
// real server does once a token lapsed after 12 hours without events.
type expiringServer struct {
	mu       sync.Mutex
	srv      *httptest.Server
	register int
	live     string
	events   []Event
	relays   int
}

func newExpiringServer(t *testing.T) *expiringServer {
	e := &expiringServer{}
	e.srv = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		data, _ := io.ReadAll(r.Body)
		e.mu.Lock()
		defer e.mu.Unlock()
		switch {
		case r.URL.Path == "/api/sessions":
			e.register++
			e.live = "zmbs_" + strings.Repeat("t", e.register)
			w.WriteHeader(http.StatusCreated)
			_, _ = w.Write([]byte(`{"session_id":"ses_e` + strings.Repeat("x", e.register) + `","token":"` + e.live + `","status_card":"card"}`))
		case strings.HasSuffix(r.URL.Path, "/relays"):
			if r.Header.Get("Authorization") != "Bearer "+e.live {
				w.WriteHeader(http.StatusUnauthorized)
				_, _ = w.Write([]byte(`{"message":"the session has ended or its token expired; register again"}`))
				return
			}
			e.relays++
			_, _ = w.Write([]byte(`{"relay":false,"reason":"focus_mode"}`))
		case strings.HasSuffix(r.URL.Path, "/events"):
			if r.Header.Get("Authorization") != "Bearer "+e.live {
				w.WriteHeader(http.StatusUnauthorized)
				_, _ = w.Write([]byte(`{"message":"the session has ended or its token expired; register again"}`))
				return
			}
			var batch struct {
				Events []Event `json:"events"`
			}
			_ = json.Unmarshal(data, &batch)
			e.events = append(e.events, batch.Events...)
			_, _ = w.Write([]byte(`{"stored":1,"duplicates":0,"rejected":0,"reasons":[]}`))
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	t.Cleanup(e.srv.Close)
	return e
}

func (e *expiringServer) expire() {
	e.mu.Lock()
	e.live = "none"
	e.mu.Unlock()
}

func TestASessionWhoseTokenLapsedRegistersAgainOnItsNextHook(t *testing.T) {
	repo := gitRepo(t)
	e := newExpiringServer(t)
	h, _ := setup(t, e.srv.URL)
	ctx := context.Background()
	run := func(event string, fields map[string]any) error {
		fields["session_id"] = "esid-lapse"
		return h.Run(ctx, event, hookInput(t, fields))
	}
	if err := run("session-start", map[string]any{"cwd": repo}); err != nil {
		t.Fatal(err)
	}
	e.expire() // the session sat idle overnight
	file := filepath.Join(repo, "docs", "a.md")
	if err := run("post-tool-use", map[string]any{"cwd": repo, "tool_name": "Edit", "tool_input": map[string]any{"file_path": file}}); err != nil {
		t.Fatalf("the hook should register again and send: %v", err)
	}
	e.mu.Lock()
	registrations, events := e.register, len(e.events)
	e.mu.Unlock()
	if registrations != 2 || events != 1 || e.events[0].Kind != "file_touch" {
		t.Fatalf("registrations %d, events %v", registrations, e.events)
	}
	st, _, _ := LoadState(h.Dir, "claude_code", "esid-lapse")
	if st.SessionID != "ses_exx" || st.RepoKey == "" {
		t.Errorf("state after registering again %+v", st)
	}

	e.expire()
	if _, err := SendCurrentWork(ctx, h.Dir, "claude_code", "esid-lapse", "다시 등록된 뒤의 요약", nil, nil); err != nil {
		t.Fatalf("session work should register again and send: %v", err)
	}
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.register != 3 || e.events[len(e.events)-1].Kind != "current_work" {
		t.Errorf("registrations %d, events %v", e.register, e.events)
	}
}

func TestARelayOfALapsedSessionRegistersAgainOnce(t *testing.T) {
	e := newExpiringServer(t)
	h, out := setup(t, e.srv.URL)
	ctx := context.Background()
	if err := h.Run(ctx, "session-start", hookInput(t, map[string]any{"session_id": "esid-relay", "cwd": t.TempDir()})); err != nil {
		t.Fatal(err)
	}
	out.Reset()
	e.expire()
	if err := h.Run(ctx, "permission-request", permissionInput(t, t.TempDir(), map[string]any{"command": "ls"})); err != nil {
		t.Fatal(err)
	}
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.register != 2 || e.relays != 1 || out.Len() != 0 {
		t.Errorf("registrations %d, relays %d, stdout %q", e.register, e.relays, out.String())
	}
}

func TestTheEndHookDoesNotRegisterAgain(t *testing.T) {
	e := newExpiringServer(t)
	h, _ := setup(t, e.srv.URL)
	ctx := context.Background()
	if err := h.Run(ctx, "session-start", hookInput(t, map[string]any{"session_id": "esid-end", "cwd": t.TempDir()})); err != nil {
		t.Fatal(err)
	}
	e.expire()
	_ = h.Run(ctx, "session-end", hookInput(t, map[string]any{"session_id": "esid-end"}))
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.register != 1 {
		t.Errorf("an ending session registered again: %d registrations", e.register)
	}
}
