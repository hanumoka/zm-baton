package agent

import (
	"context"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"slices"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/hanumoka/zm-baton/apps/agent/internal/api"
)

// Lease renewal, a person's stop and instructions while a run goes on (FN-0410), against the fake
// server and the fake Claude Code of runner_test.go.

func lastPayload(t *testing.T, evs []api.Event) map[string]any {
	t.Helper()
	if len(evs) == 0 || evs[len(evs)-1].Kind != "lifecycle" {
		t.Fatalf("the run did not end with a lifecycle event: %v", evs)
	}
	return evs[len(evs)-1].Payload
}

func TestAStopFromTheServerEndsTheRunCancelledAndItsChildToo(t *testing.T) {
	f, c := newFakeServer(t)
	r := newTestRunner(t, c, "child", 0)
	pidFile := filepath.Join(r.cfg.Workdir, "child.pid")
	go func() {
		for {
			if _, err := os.Stat(pidFile); err == nil {
				f.set(func(f *fakeServer) { f.stop = true })
				return
			}
			time.Sleep(20 * time.Millisecond)
		}
	}()
	start := time.Now()
	got := r.Execute(context.Background(), api.Claim{RunID: "run_stop", Generation: 3})
	if got.EndReason != EndCancelled {
		t.Fatalf("result %+v", got)
	}
	if d := time.Since(start); d > 30*time.Second {
		t.Fatalf("stopping took %v", d)
	}
	ended := lastPayload(t, f.runEvents("run_stop"))
	if ended["end_reason"] != "cancelled" || ended["executor_started"] != true || ended["result_seen"] != false {
		t.Fatalf("ended %v", ended)
	}
	if f.renewed("run_stop") == 0 {
		t.Fatal("the lease was never renewed")
	}
	data, err := os.ReadFile(pidFile)
	if err != nil {
		t.Fatal(err)
	}
	pid, _ := strconv.Atoi(strings.TrimSpace(string(data)))
	if !childGone(t, pid) {
		t.Fatalf("the executor's child %d still runs after the stop", pid)
	}
}

func TestAServerThatDroppedTheRunStopsTheExecutor(t *testing.T) {
	f, c := newFakeServer(t)
	f.leaseStatus = http.StatusConflict
	r := newTestRunner(t, c, "hang", 0)
	got := r.Execute(context.Background(), api.Claim{RunID: "run_gone", Generation: 3})
	if got.EndReason != EndCancelled {
		t.Fatalf("a run the server no longer holds must stop: %+v", got)
	}
	// Renewing stops once the server said so.
	before := f.renewed("run_gone")
	time.Sleep(100 * time.Millisecond)
	if f.renewed("run_gone") != before {
		t.Fatal("the connector kept renewing a run the server dropped")
	}
}

func TestAnOldServerWithoutLeasesLetsTheRunFinish(t *testing.T) {
	f, c := newFakeServer(t)
	f.leaseStatus = http.StatusNotFound
	r := newTestRunner(t, c, "success", 0)
	if got := r.Execute(context.Background(), api.Claim{RunID: "run_old", Generation: 3}); got.EndReason != EndSubmitted {
		t.Fatalf("result %+v", got)
	}
	if n := f.renewed("run_old"); n != 1 {
		t.Fatalf("renewals against an old server: %d, want 1 then none", n)
	}
}

func TestAStopBeforeTheExecutorStartsEndsCancelledWithoutStartingIt(t *testing.T) {
	f, c := newFakeServer(t)
	f.stop = true
	r := newTestRunner(t, c, "success", 0)
	if got := r.Execute(context.Background(), api.Claim{RunID: "run_early", Generation: 3}); got.EndReason != EndCancelled {
		t.Fatalf("result %+v", got)
	}
	evs := f.runEvents("run_early")
	if got := checkSequence(t, "run_early", evs); !slices.Equal(got, []string{"lifecycle"}) {
		t.Fatalf("kinds %v", got)
	}
	if p := evs[0].Payload; p["end_reason"] != "cancelled" || p["executor_started"] != false {
		t.Fatalf("ended %v", p)
	}
}

func TestAnInstructionReachesTheRunningExecutorOnce(t *testing.T) {
	repo := handoffRepo(t)
	f, c := newFakeServer(t)
	f.inputs = []api.Input{{ID: "inp_1", Text: "README도 고친다"}}
	h := newTestHandoff(t, c, "inputs", repo)
	if got := h.Execute(context.Background(), delegated("run_in", repo.Key, "", "Read", "Edit"), repo); got.EndReason != EndSubmitted {
		t.Fatalf("result %+v", got)
	}
	evs := f.runEvents("run_in")
	var answers []string
	for _, e := range evs {
		if e.Kind == "answer" {
			answers = append(answers, e.Payload["text"].(string))
		}
	}
	// Written once: the executor took it in one turn and got nothing more after its result.
	took := 0
	for _, a := range answers {
		if a == "got: "+inputPrefix+"README도 고친다" {
			took++
		}
		if strings.HasPrefix(a, "extra message after the result") {
			t.Fatalf("the executor got a message after its result: %q", a)
		}
	}
	if took != 1 {
		t.Fatalf("the executor took the instruction %d times: %q", took, answers)
	}
	started := evs[0].Payload
	if caps, _ := started["capabilities"].(map[string]any); caps["live_input"] != true {
		t.Fatalf("started capabilities %v", started["capabilities"])
	}
	ended := lastPayload(t, evs)
	inputs, _ := ended["inputs"].([]any)
	if len(inputs) != 1 || inputs[0].(map[string]any)["id"] != "inp_1" || inputs[0].(map[string]any)["state"] != "delivered" {
		t.Fatalf("ended inputs %v", ended["inputs"])
	}
	// Renewals report the latest state seen since the last one, so whether one carries "sent",
	// "delivered" or nothing depends on timing: the echo can come before the next renewal (the
	// state goes straight to delivered, which the server accepts from pending), and the run can
	// end before any renewal (the end report above settles it). What they carry must be true.
	f.mu.Lock()
	reports := slices.Clone(f.reports)
	f.mu.Unlock()
	for _, r := range reports {
		if r.ID != "inp_1" || (r.State != "sent" && r.State != "delivered") {
			t.Fatalf("a renewal reported %v", reports)
		}
	}
}

func TestInputPipeWritesOnceAndMatchesEchoesInOrder(t *testing.T) {
	pr, pw := io.Pipe()
	got := make(chan string, 8)
	go func() {
		data, _ := io.ReadAll(pr)
		got <- string(data)
	}()
	p := newInputPipe(pw)
	if !p.write("", "the prompt") || !p.write("inp_a", "first") || !p.write("inp_b", "second") {
		t.Fatal("writes to an open input failed")
	}
	if id := p.replayed("the prompt"); id != "" {
		t.Fatalf("the prompt's echo matched %q", id)
	}
	// A changed echo still goes to the oldest message, since messages are taken in order.
	if id := p.replayed("first, reformatted"); id != "inp_a" {
		t.Fatalf("echo matched %q", id)
	}
	if id := p.replayed("second"); id != "inp_b" {
		t.Fatalf("echo matched %q", id)
	}
	if id := p.replayed("nothing waits"); id != "" {
		t.Fatalf("an echo with nothing written matched %q", id)
	}
	p.close()
	p.close()
	if p.write("inp_c", "late") {
		t.Fatal("a write after close succeeded")
	}
	if text := <-got; strings.Count(text, "\n") != 3 || !strings.Contains(text, `"content":"first"`) {
		t.Fatalf("stdin got %q", text)
	}
}

func TestRunRecordsConfirmOnlyAnExitWithNoChildLeft(t *testing.T) {
	recs := runRecords{dir: t.TempDir()}
	if ok, _ := recs.endedConfirmed("run_none", "s"); ok {
		t.Fatal("no record confirmed an end")
	}
	base := RunRecord{RunID: "run_a", SessionID: "sess-1", PID: 42, StartedAt: time.Now()}
	for name, rec := range map[string]RunRecord{
		"still running": base,
		"child left":    func() RunRecord { r := base; r.Exited = true; return r }(),
	} {
		if err := recs.save(rec); err != nil {
			t.Fatal(err)
		}
		if ok, why := recs.endedConfirmed("run_a", "sess-1"); ok || why == "" {
			t.Fatalf("%s confirmed an end", name)
		}
	}
	done := base
	done.Exited, done.ChildrenGone = true, true
	if err := recs.save(done); err != nil {
		t.Fatal(err)
	}
	if ok, _ := recs.endedConfirmed("run_a", "sess-1"); !ok {
		t.Fatal("an exit with no child left was not confirmed")
	}
	if ok, _ := recs.endedConfirmed("run_a", "sess-other"); ok {
		t.Fatal("another session was confirmed")
	}
	if err := recs.save(RunRecord{RunID: "../escape"}); err == nil {
		t.Fatal("a record name that climbs out was accepted")
	}
	if ok, _ := (runRecords{}).endedConfirmed("run_a", "sess-1"); ok {
		t.Fatal("records that are off confirmed an end")
	}
}

func TestReadinessDetailsNeverCarryAPathAndVersionsCompare(t *testing.T) {
	for _, s := range []string{`C:\Users\me\repo가 없다`, "폴더 /home/me/repo", "~/repo", `a\b`} {
		if d := safeDetail(s); d == s || devicePath.MatchString(d) {
			t.Fatalf("%q kept a path: %q", s, d)
		}
	}
	for _, s := range []string{"기준 브랜치 origin/main이(가) 없다", "브랜치 zm-baton/run_1", "도구 Read, Edit"} {
		if d := safeDetail(s); d != s {
			t.Fatalf("%q changed to %q", s, d)
		}
	}
	if len([]rune(safeDetail(strings.Repeat("가", 400)))) != 300 {
		t.Fatal("a long detail was not cut")
	}
	if !versionAtLeast([3]int{2, 1, 296}, MinClaudeVersion) || !versionAtLeast([3]int{2, 2, 0}, MinClaudeVersion) ||
		versionAtLeast([3]int{2, 1, 295}, MinClaudeVersion) || versionAtLeast([3]int{1, 9, 999}, MinClaudeVersion) {
		t.Fatal("version comparison is wrong")
	}
	t.Setenv(fakeEnv, "success")
	t.Setenv("ZMB_FAKE_CLAUDE_VERSION", "2.1.300")
	if v, n, err := claudeVersion(context.Background(), os.Args[0]); err != nil || v != "2.1.300" || n != [3]int{2, 1, 300} {
		t.Fatalf("claudeVersion: %q %v %v", v, n, err)
	}
}
