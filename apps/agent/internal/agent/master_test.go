package agent

import (
	"context"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"testing"
	"time"

	"github.com/hanumoka/zm-baton/apps/agent/internal/api"
)

const masterAnswer = `{"candidates":[{"left":"S1","left_quote":"마스터는 API 키로 돈다.","right":"C1","right_quote":"마스터는 구독으로 돈다.","reason":"인증 방식이 다르다"}]}`

func masterClaim(id string, tools ...string) api.Claim {
	return api.Claim{
		RunID: id, WorkItemID: "wrk_m", Generation: 3, LeaseSeconds: 45, Purpose: api.PurposeMaster,
		Delegation:  &api.Delegation{ID: "dlg_" + id, Executor: "claude_code", AllowedTools: tools},
		Instruction: "----- S1 시작 [ab12] 문서 「안내」 판 2 -----\n마스터는 API 키로 돈다.\n----- S1 끝 [ab12] -----",
		Limits:      &api.Limits{MaxTurns: 5, MaxMinutes: 10, MaxBudgetUSD: 2},
	}
}

func newMasterHandoff(t *testing.T, c *api.Client, masterDir string) *Handoff {
	t.Helper()
	t.Setenv(fakeEnv, "master")
	t.Setenv("ZMB_FAKE_RESULT", masterAnswer)
	h, err := NewHandoff(Config{
		Client: c, DeviceID: "dev_test", ClaudePath: os.Args[0], PollInterval: 20 * time.Millisecond,
		FlushInterval: 20 * time.Millisecond, RenewInterval: 20 * time.Millisecond,
		StateDir: filepath.Join(t.TempDir(), "runs"), MasterDir: masterDir,
	}, func() (map[string]Repo, error) { return map[string]Repo{}, nil }, nil)
	if err != nil {
		t.Fatal(err)
	}
	return h
}

func ended(t *testing.T, evs []api.Event) map[string]any {
	t.Helper()
	last := evs[len(evs)-1]
	if last.Kind != "lifecycle" || last.Payload["phase"] != "ended" {
		t.Fatalf("the last event is not the end report: %+v", last)
	}
	return last.Payload
}

func TestMasterRunAnswersInTheEndReportWithoutARepository(t *testing.T) {
	f, c := newFakeServer(t)
	// No repository is registered on this device: the master's run needs none.
	f.pending = []api.PendingRun{
		{ID: "run_other", Executor: "claude_code", DelegationID: "dlg_o", DeviceID: "dev_test", RepoKey: "git:not-here"},
		{ID: "run_m", Executor: "claude_code", DelegationID: "dlg_run_m", DeviceID: "dev_test", Purpose: api.PurposeMaster},
	}
	f.answers["run_m"] = masterClaim("run_m", "Read", "Glob", "Grep")
	masterDir := filepath.Join(t.TempDir(), "master")
	h := newMasterHandoff(t, c, masterDir)

	got, err := h.Run(context.Background(), true)
	if err != nil || got.EndReason != EndSubmitted || got.Delivery != nil {
		t.Fatalf("run: %+v %v", got, err)
	}
	if !slices.Equal(f.claims, []string{"run_m"}) {
		t.Fatalf("claimed %v; only the master run may be claimed", f.claims)
	}
	evs := f.runEvents("run_m")
	kinds := checkSequence(t, "run_m", evs)
	if slices.Contains(kinds, KindGitChanges) {
		t.Fatalf("a master run has no Git change list: %v", kinds)
	}
	end := ended(t, evs)
	result, _ := end["result"].(map[string]any)
	if result["text"] != masterAnswer || result["truncated"] != false {
		t.Fatalf("the end report carries %v, want the answer", end["result"])
	}
	if _, ok := end["workspace"]; ok {
		t.Fatal("a master run has no worktree to report")
	}
	if ok, _ := end["readiness"].(map[string]any)["ok"].(bool); !ok {
		t.Fatalf("readiness %v", end["readiness"])
	}
	if _, err := os.Stat(filepath.Join(masterDir, "run_m")); !os.IsNotExist(err) {
		t.Fatalf("the empty master folder should be gone: %v", err)
	}
	noLocalPath(t, evs, masterDir)
}

func TestMasterRunRefusesAnythingButReadOnlyTools(t *testing.T) {
	for _, tools := range [][]string{{"Read", "Edit"}, {"Write"}, {}} {
		f, c := newFakeServer(t)
		f.pending = []api.PendingRun{{ID: "run_w", Executor: "claude_code", DelegationID: "dlg_run_w", DeviceID: "dev_test", Purpose: api.PurposeMaster}}
		f.answers["run_w"] = masterClaim("run_w", tools...)
		h := newMasterHandoff(t, c, filepath.Join(t.TempDir(), "master"))
		got, err := h.Run(context.Background(), true)
		if err != nil || got.EndReason != EndFailed {
			t.Fatalf("tools %v: %+v %v", tools, got, err)
		}
		evs := f.runEvents("run_w")
		for _, ev := range evs {
			if ev.Kind == "lifecycle" && ev.Payload["phase"] == "started" {
				t.Fatalf("tools %v: the executor must not start", tools)
			}
		}
		end := ended(t, evs)
		if end["executor_started"] != false || end["result"] != nil {
			t.Fatalf("tools %v: end %v", tools, end)
		}
		checks, _ := end["readiness"].(map[string]any)["checks"].([]any)
		first, _ := checks[0].(map[string]any)
		if first["name"] != "scope" || first["ok"] != false || !strings.Contains(first["detail"].(string), "읽기 도구") {
			t.Fatalf("tools %v: checks %v", tools, checks)
		}
	}
}

func TestMasterRunNeedsItsFolderSetting(t *testing.T) {
	f, c := newFakeServer(t)
	f.pending = []api.PendingRun{{ID: "run_n", Executor: "claude_code", DelegationID: "dlg_run_n", DeviceID: "dev_test", Purpose: api.PurposeMaster}}
	f.answers["run_n"] = masterClaim("run_n", "Read", "Glob", "Grep")
	h := newMasterHandoff(t, c, "")
	got, _ := h.Run(context.Background(), true)
	if got.EndReason != EndFailed {
		t.Fatalf("got %+v", got)
	}
	if !strings.Contains(stringsOf(ended(t, f.runEvents("run_n"))["readiness"]), "master_folder") {
		t.Fatal("the failed check should be the master folder")
	}
}

func stringsOf(v any) string {
	m, _ := v.(map[string]any)
	var b strings.Builder
	for _, c := range m["checks"].([]any) {
		b.WriteString(c.(map[string]any)["name"].(string) + " ")
	}
	return b.String()
}
