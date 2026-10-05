package agent

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/hanumoka/zm-baton/apps/agent/internal/api"
	"github.com/hanumoka/zm-baton/apps/agent/internal/proc"
	"github.com/hanumoka/zm-baton/apps/agent/internal/report"
)

// The runner tests never start the real Claude Code. The executor is this test
// binary in "fake Claude" mode, printing synthetic stream-json lines.
const fakeEnv = "ZMB_AGENT_TEST_FAKE_CLAUDE"

func TestMain(m *testing.M) {
	if mode := os.Getenv(fakeEnv); mode != "" {
		os.Exit(fakeClaude(mode, os.Args[1:]))
	}
	os.Exit(m.Run())
}

func fakeClaude(mode string, args []string) int {
	session := ""
	for i, a := range args {
		if a == "--session-id" && i+1 < len(args) {
			session = args[i+1]
		}
	}
	n := len(args)
	if session == "" || n < 2 || args[0] != "-p" || args[n-2] != "--" {
		fmt.Fprintln(os.Stderr, "fake claude: unexpected arguments")
		return 9
	}
	emit := func(v map[string]any) {
		v["session_id"] = session
		line, _ := json.Marshal(v)
		fmt.Println(string(line))
	}
	blocks := func(role string, b ...map[string]any) map[string]any {
		list := make([]any, len(b))
		for i := range b {
			list[i] = b[i]
		}
		return map[string]any{"type": role, "message": map[string]any{"content": list}}
	}
	emit(map[string]any{"type": "system", "subtype": "init", "cwd": "/fake/should-not-leak"})
	switch mode {
	case "success":
		emit(blocks("assistant", map[string]any{"type": "thinking", "thinking": "plan"}))
		emit(blocks("assistant", map[string]any{"type": "tool_use", "id": "toolu_1", "name": "Read",
			"input": map[string]any{"file_path": "a.txt"}}))
		emit(blocks("user", map[string]any{"type": "tool_result", "tool_use_id": "toolu_1", "content": "text"}))
		emit(blocks("assistant", map[string]any{"type": "text", "text": "echo: " + args[n-1]}))
		emit(map[string]any{"type": "rate_limit_event", "rate_limit_info": map[string]any{"status": "allowed"}})
		emit(map[string]any{"type": "result", "subtype": "success", "is_error": false, "result": "done",
			"usage": map[string]any{"output_tokens": 3}})
		return 0
	case "noresult":
		emit(blocks("assistant", map[string]any{"type": "text", "text": "partial"}))
		return 3
	case "hang":
		time.Sleep(60 * time.Second)
		return 0
	}
	return 8
}

// fakeServer mimics the three server endpoints the agent uses.
type fakeServer struct {
	mu      sync.Mutex
	pending []api.PendingRun
	taken   map[string]bool
	claims  []string
	events  map[string][]api.Event
}

func newFakeServer(t *testing.T) (*fakeServer, *api.Client) {
	t.Helper()
	f := &fakeServer{taken: map[string]bool{}, events: map[string][]api.Event{}}
	mux := http.NewServeMux()
	mux.HandleFunc("GET /api/runs", func(w http.ResponseWriter, r *http.Request) {
		f.mu.Lock()
		defer f.mu.Unlock()
		json.NewEncoder(w).Encode(f.pending)
	})
	mux.HandleFunc("POST /api/runs/{id}/claim", func(w http.ResponseWriter, r *http.Request) {
		id := r.PathValue("id")
		f.mu.Lock()
		defer f.mu.Unlock()
		f.claims = append(f.claims, id)
		if f.taken[id] {
			w.WriteHeader(http.StatusConflict)
			return
		}
		f.taken[id] = true
		json.NewEncoder(w).Encode(api.Claim{RunID: id, WorkItemID: "wrk_test", Generation: 3, LeaseSeconds: 45})
	})
	mux.HandleFunc("POST /api/runs/{id}/events", func(w http.ResponseWriter, r *http.Request) {
		var b api.EventBatch
		if err := json.NewDecoder(r.Body).Decode(&b); err != nil || b.ProtocolVersion != 1 {
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		id := r.PathValue("id")
		var res api.EventResult
		f.mu.Lock()
		for _, ev := range b.Events {
			if slices.ContainsFunc(f.events[id], func(e api.Event) bool { return e.EventID == ev.EventID }) {
				res.Duplicates++
				continue
			}
			f.events[id] = append(f.events[id], ev)
			res.Stored++
		}
		f.mu.Unlock()
		json.NewEncoder(w).Encode(res)
	})
	srv := httptest.NewServer(mux)
	t.Cleanup(srv.Close)
	c, err := api.NewClient(srv.URL)
	if err != nil {
		t.Fatal(err)
	}
	return f, c
}

func (f *fakeServer) runEvents(id string) []api.Event {
	f.mu.Lock()
	defer f.mu.Unlock()
	return slices.Clone(f.events[id])
}

func newTestRunner(t *testing.T, c *api.Client, mode string, timeout time.Duration) *Runner {
	t.Helper()
	t.Setenv(fakeEnv, mode) // inherited by the executor the runner starts
	r, err := New(Config{
		Client: c, DeviceID: "dev_test", Workdir: t.TempDir(), Prompt: "say hi",
		AllowedTools: "Read", ClaudePath: os.Args[0], PollInterval: 20 * time.Millisecond,
		RunTimeout: timeout, FlushInterval: 20 * time.Millisecond,
	})
	if err != nil {
		t.Fatal(err)
	}
	return r
}

// checkSequence verifies numbering and framing, and returns the kinds in order.
func checkSequence(t *testing.T, runID string, evs []api.Event) []string {
	t.Helper()
	out := make([]string, len(evs))
	for i, ev := range evs {
		if ev.Seq != int64(i+1) || !report.IsEventID(ev.EventID) || ev.Generation != 3 {
			t.Fatalf("event %d badly numbered: %+v", i, ev)
		}
		out[i] = ev.Kind
	}
	raw, _ := json.Marshal(evs)
	if strings.Contains(string(raw), "should-not-leak") {
		t.Fatal("the system init line reached the server")
	}
	return out
}

func TestExecuteReportsASuccessfulRun(t *testing.T) {
	f, c := newFakeServer(t)
	r := newTestRunner(t, c, "success", 0)
	if got := r.Execute(context.Background(), api.Claim{RunID: "run_ok", Generation: 3}); got != EndSubmitted {
		t.Fatalf("end reason %q", got)
	}
	evs := f.runEvents("run_ok")
	want := []string{"lifecycle", "thought", "action", "action_result", "answer", "usage", "usage", "lifecycle"}
	if got := checkSequence(t, "run_ok", evs); !slices.Equal(got, want) {
		t.Fatalf("kinds\n got %v\nwant %v", got, want)
	}
	started, ended := evs[0].Payload, evs[len(evs)-1].Payload
	if started["phase"] != "started" || started["executor"] != "claude_code" || started["pid"] == nil {
		t.Fatalf("started payload %v", started)
	}
	if sid, _ := started["session_id"].(string); len(sid) != 36 {
		t.Fatalf("session id %v", started["session_id"])
	}
	if ended["phase"] != "ended" || ended["end_reason"] != "submitted" || ended["exit_code"] != 0.0 {
		t.Fatalf("ended payload %v", ended)
	}
	if evs[4].Payload["text"] != "echo: say hi" {
		t.Fatalf("prompt did not reach the executor as the last argument: %v", evs[4].Payload)
	}
}

func TestExecuteWithoutResultEndsLost(t *testing.T) {
	f, c := newFakeServer(t)
	r := newTestRunner(t, c, "noresult", 0)
	if got := r.Execute(context.Background(), api.Claim{RunID: "run_nr", Generation: 3}); got != EndLost {
		t.Fatalf("end reason %q", got)
	}
	evs := f.runEvents("run_nr")
	if got := checkSequence(t, "run_nr", evs); !slices.Equal(got, []string{"lifecycle", "answer", "error", "lifecycle"}) {
		t.Fatalf("kinds %v", got)
	}
	if p := evs[3].Payload; p["end_reason"] != "lost" || p["exit_code"] != 3.0 || p["result_seen"] != false {
		t.Fatalf("ended payload %v", p)
	}
}

func TestExecuteMissingExecutorEndsFailed(t *testing.T) {
	f, c := newFakeServer(t)
	r := newTestRunner(t, c, "success", 0)
	r.cfg.ClaudePath = filepath.Join(t.TempDir(), "missing-executor")
	if got := r.Execute(context.Background(), api.Claim{RunID: "run_ms", Generation: 3}); got != EndFailed {
		t.Fatalf("end reason %q", got)
	}
	if got := checkSequence(t, "run_ms", f.runEvents("run_ms")); !slices.Equal(got, []string{"error", "lifecycle"}) {
		t.Fatalf("kinds %v", got)
	}
}

// Both stop paths go through KillRun against the fake executor, whose command
// line carries the run's session id.
func TestExecuteStopsAHungExecutor(t *testing.T) {
	cases := []struct {
		name, want string
		timeout    time.Duration
		cancel     bool
	}{
		{"run timeout", EndFailed, time.Second, false},
		{"context cancelled", EndCancelled, 0, true},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			f, c := newFakeServer(t)
			r := newTestRunner(t, c, "hang", tc.timeout)
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			if tc.cancel {
				go func() {
					for len(f.runEvents("run_h")) == 0 {
						time.Sleep(20 * time.Millisecond)
					}
					cancel()
				}()
			}
			start := time.Now()
			if got := r.Execute(ctx, api.Claim{RunID: "run_h", Generation: 3}); got != tc.want {
				t.Fatalf("end reason %q, want %q", got, tc.want)
			}
			if d := time.Since(start); d > 30*time.Second {
				t.Fatalf("stopping took %v", d)
			}
			evs := f.runEvents("run_h")
			kinds := checkSequence(t, "run_h", evs)
			if kinds[0] != "lifecycle" || kinds[len(kinds)-1] != "lifecycle" {
				t.Fatalf("kinds %v", kinds)
			}
			if !tc.cancel && !slices.Contains(kinds, "error") {
				t.Fatalf("timeout should add an error event: %v", kinds)
			}
			pid := int(evs[0].Payload["pid"].(float64))
			sid := evs[0].Payload["session_id"].(string)
			if cl, _ := proc.CommandLine(pid); strings.Contains(cl, sid) {
				t.Fatal("the executor is still running")
			}
		})
	}
}

func TestRunOnceSkipsOtherExecutorsAndLostClaims(t *testing.T) {
	f, c := newFakeServer(t)
	f.pending = []api.PendingRun{
		{ID: "run_codex", WorkItemID: "wrk_1", Executor: "codex"},
		{ID: "run_lost", WorkItemID: "wrk_2", Executor: "claude_code"},
		{ID: "run_mine", WorkItemID: "wrk_3", Executor: "claude_code"},
	}
	f.taken["run_lost"] = true
	r := newTestRunner(t, c, "success", 0)
	got, err := r.Run(context.Background(), true)
	if err != nil || got != EndSubmitted {
		t.Fatalf("Run once: %q %v", got, err)
	}
	if !slices.Equal(f.claims, []string{"run_lost", "run_mine"}) {
		t.Fatalf("claims %v", f.claims)
	}
	if len(f.runEvents("run_mine")) == 0 || len(f.runEvents("run_lost")) != 0 {
		t.Fatal("events went to the wrong run")
	}
}

func TestNewRequiresSettings(t *testing.T) {
	_, c := newFakeServer(t)
	base := Config{Client: c, DeviceID: "dev", Workdir: "w", Prompt: "p", AllowedTools: "Read", ClaudePath: "claude"}
	for name, mutate := range map[string]func(*Config){
		"client":  func(c *Config) { c.Client = nil },
		"device":  func(c *Config) { c.DeviceID = " " },
		"workdir": func(c *Config) { c.Workdir = "" },
		"prompt":  func(c *Config) { c.Prompt = "" },
		"tools":   func(c *Config) { c.AllowedTools = "" },
		"claude":  func(c *Config) { c.ClaudePath = "" },
	} {
		cfg := base
		mutate(&cfg)
		if _, err := New(cfg); err == nil {
			t.Errorf("missing %s accepted", name)
		}
	}
}
