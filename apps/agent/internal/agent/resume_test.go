package agent

import (
	"context"
	"os"
	"path/filepath"
	"runtime"
	"slices"
	"strings"
	"testing"

	"github.com/hanumoka/zm-baton/apps/agent/internal/api"
)

// The readiness check before the executor (FN-0406) and the worktree a continuing run takes
// (contract v1 "실행마다 별도 작업 트리" 6), against a real Git repository and the fake Claude Code.

func readinessOf(t *testing.T, ended map[string]any) (bool, map[string]map[string]any, []string) {
	t.Helper()
	r, ok := ended["readiness"].(map[string]any)
	if !ok {
		t.Fatalf("the end report has no readiness: %v", ended)
	}
	byName := map[string]map[string]any{}
	var names []string
	for _, c := range r["checks"].([]any) {
		m := c.(map[string]any)
		byName[m["name"].(string)] = m
		names = append(names, m["name"].(string))
	}
	return r["ok"] == true, byName, names
}

func answerOf(evs []api.Event) string {
	out := ""
	for _, e := range evs {
		if e.Kind == "answer" {
			out, _ = e.Payload["text"].(string)
		}
	}
	return out
}

func TestEveryReadinessCheckPassedGoesIntoTheEndReport(t *testing.T) {
	repo := handoffRepo(t)
	f, c := newFakeServer(t)
	h := newTestHandoff(t, c, "edit", repo)
	if got := h.Execute(context.Background(), delegated("run_ready", repo.Key, "main", "Read", "Edit"), repo); got.EndReason != EndSubmitted {
		t.Fatalf("result %+v", got)
	}
	ok, checks, names := readinessOf(t, lastPayload(t, f.runEvents("run_ready")))
	want := []string{"scope", "executor", "repository", "worktree_folder", "disk", "base_branch", "worktree"}
	if !ok || !slices.Equal(names, want) {
		t.Fatalf("readiness ok=%v checks %v", ok, names)
	}
	if checks["executor"]["detail"] != "Claude Code 2.1.296" {
		t.Fatalf("executor check %v", checks["executor"])
	}
}

func TestAFailedReadinessCheckStopsTheRunBeforeTheExecutor(t *testing.T) {
	cases := []struct {
		name, check, detail string
		prepare             func(t *testing.T, repo *Repo)
	}{
		{"old executor", "executor", "Claude Code 2.1.296 이상이 필요하다", func(t *testing.T, repo *Repo) {
			t.Setenv("ZMB_FAKE_CLAUDE_VERSION", "2.1.100")
		}},
		{"another repository in the folder", "repository", "다른 저장소", func(t *testing.T, repo *Repo) {
			// The registered folder now holds another history: another first commit.
			other := filepath.Join(t.TempDir(), "other")
			if err := os.MkdirAll(other, 0o755); err != nil {
				t.Fatal(err)
			}
			gitT(t, other, "init", "--quiet")
			if err := os.WriteFile(filepath.Join(other, "other.txt"), []byte("other\n"), 0o644); err != nil {
				t.Fatal(err)
			}
			gitT(t, other, "add", "other.txt")
			gitT(t, other, "commit", "--quiet", "-m", "another first")
			repo.Root = other
		}},
		{"missing base branch", "base_branch", "기준 브랜치 no-such이(가) 이 저장소에 없다", nil},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			repo := handoffRepo(t)
			f, c := newFakeServer(t)
			h := newTestHandoff(t, c, "edit", repo)
			if tc.prepare != nil {
				tc.prepare(t, &repo)
			}
			branch := ""
			if tc.check == "base_branch" {
				branch = "no-such"
			}
			if got := h.Execute(context.Background(), delegated("run_nr", repo.Key, branch, "Read"), repo); got.EndReason != EndFailed {
				t.Fatalf("result %+v", got)
			}
			evs := f.runEvents("run_nr")
			if got := checkSequence(t, "run_nr", evs); !slices.Equal(got, []string{"error", "lifecycle"}) {
				t.Fatalf("kinds %v", got)
			}
			ended := lastPayload(t, evs)
			ok, checks, _ := readinessOf(t, ended)
			if ok || checks[tc.check]["ok"] != false || !strings.Contains(checks[tc.check]["detail"].(string), tc.detail) {
				t.Fatalf("readiness %v", ended["readiness"])
			}
			if ended["executor_started"] != false {
				t.Fatalf("ended %v", ended)
			}
			if text := evs[0].Payload["text"].(string); !strings.HasPrefix(text, "시작 전 준비 검사에서 멈췄다: ") {
				t.Fatalf("error %q", text)
			}
			if entries, _ := os.ReadDir(repo.Worktrees); len(entries) != 0 {
				t.Fatalf("a worktree was made: %v", entries)
			}
			noLocalPath(t, evs, repo.Root, repo.Worktrees)
		})
	}
}

// firstRun runs one delegated run to the end and returns its session id and workspace report.
func firstRun(t *testing.T, h *Handoff, f *fakeServer, repo Repo, runID string) (string, map[string]any) {
	t.Helper()
	if got := h.Execute(context.Background(), delegated(runID, repo.Key, "main", "Read", "Edit", "Write"), repo); got.EndReason != EndSubmitted {
		t.Fatalf("first run %+v", got)
	}
	started := f.runEvents(runID)[0].Payload
	return started["session_id"].(string), started["workspace"].(map[string]any)
}

func continuing(runID string, repo Repo, from, session string, ws map[string]any, sameDevice bool) api.Claim {
	c := delegated(runID, repo.Key, "main", "Read", "Edit", "Write")
	c.Resume = &api.Resume{RunID: from, ContinueReason: "resume_after_cut", SameDevice: sameDevice, SessionID: session, Workspace: ws}
	return c
}

func TestAContinuingRunTakesOverTheEarlierWorktreeAndSessionOnlyOnceTheyEnded(t *testing.T) {
	if runtime.GOOS != "windows" {
		t.Skip("child processes are counted on Windows only; elsewhere a continuing run always takes a new worktree")
	}
	repo := handoffRepo(t)
	f, c := newFakeServer(t)
	h := newTestHandoff(t, c, "edit", repo)
	first := "run_0199aaaa-0000-7000-8000-00000000000a"
	session, ws := firstRun(t, h, f, repo, first)

	t.Setenv(fakeEnv, "edit-resume")
	next := "run_0199aaaa-0000-7000-8000-00000000000b"
	if got := h.Execute(context.Background(), continuing(next, repo, first, session, ws, true), repo); got.EndReason != EndSubmitted {
		t.Fatalf("continuing run %+v", got)
	}
	evs := f.runEvents(next)
	started := evs[0].Payload
	if started["session_id"] != session {
		t.Fatalf("the earlier session was not continued: %v", started["session_id"])
	}
	got := started["workspace"].(map[string]any)
	if got["origin"] != "reused" || got["branch"] != "zm-baton/"+first || got["base_commit"] != ws["base_commit"] {
		t.Fatalf("workspace %v", got)
	}
	old := filepath.Join(repo.Worktrees, first)
	if _, err := os.Stat(filepath.Join(old, "made-by-resume.txt")); err != nil {
		t.Fatalf("the continuing run did not work in the earlier worktree: %v", err)
	}
	if _, err := os.Stat(filepath.Join(repo.Worktrees, next)); err == nil {
		t.Fatal("a new worktree was made although the earlier one was taken over")
	}
	answer := answerOf(evs)
	for _, part := range []string{"이전 실행(" + first + ")이 쓰던 것", "이미 바뀐 파일 2개: README.md, made-by-run.txt"} {
		if !strings.Contains(answer, part) {
			t.Fatalf("the prompt lacks %q: %q", part, answer)
		}
	}
	_, checks, _ := readinessOf(t, lastPayload(t, evs))
	if !strings.Contains(checks["resume"]["detail"].(string), "이어 쓴다") {
		t.Fatalf("resume check %v", checks["resume"])
	}
	final := evs[len(evs)-2].Payload
	if files := final["files"].([]any); len(files) != 3 {
		t.Fatalf("the change list counts from the original base: %v", final["files"])
	}
	noLocalPath(t, evs, repo.Root, repo.Worktrees, old)
}

func TestAContinuingRunWithoutAConfirmedEndStartsFromTheEarlierBranch(t *testing.T) {
	repo := handoffRepo(t)
	f, c := newFakeServer(t)
	h := newTestHandoff(t, c, "edit", repo)
	first := "run_0199bbbb-0000-7000-8000-00000000000a"
	session, ws := firstRun(t, h, f, repo, first)
	// The connector died mid-run: its record never says the executor exited.
	if err := h.runner.records.save(RunRecord{RunID: first, SessionID: session, PID: 1}); err != nil {
		t.Fatal(err)
	}
	// What the earlier run committed is on its branch; what it left uncommitted is not.
	old := filepath.Join(repo.Worktrees, first)
	gitT(t, old, "add", "made-by-run.txt")
	gitT(t, old, "commit", "--quiet", "-m", "run commit")

	next := "run_0199bbbb-0000-7000-8000-00000000000b"
	if got := h.Execute(context.Background(), continuing(next, repo, first, session, ws, true), repo); got.EndReason != EndSubmitted {
		t.Fatalf("continuing run %+v", got)
	}
	evs := f.runEvents(next)
	started := evs[0].Payload
	if started["session_id"] == session {
		t.Fatal("the earlier session was continued without a confirmed end")
	}
	got := started["workspace"].(map[string]any)
	if got["origin"] != "previous_branch" || got["branch"] != "zm-baton/"+next || got["base_commit"] != ws["base_commit"] {
		t.Fatalf("workspace %v", got)
	}
	fresh := filepath.Join(repo.Worktrees, next)
	if gitT(t, repo.Root, "rev-parse", "zm-baton/"+next) != gitT(t, repo.Root, "rev-parse", "zm-baton/"+first) {
		t.Fatal("the new worktree does not start at the earlier branch's tip")
	}
	if data, _ := os.ReadFile(filepath.Join(old, "README.md")); string(data) != "changed by the run\n" {
		t.Fatal("the earlier worktree was changed")
	}
	answer := answerOf(evs)
	if !strings.Contains(answer, "이어 쓰지 않았다") || !strings.Contains(answer, "커밋하지 않은 변경은 여기에 없다") {
		t.Fatalf("the prompt does not say why: %q", answer)
	}
	noLocalPath(t, evs, repo.Root, repo.Worktrees, old, fresh)
}

func TestAContinuingRunFromAnotherDeviceStartsFromTheBase(t *testing.T) {
	repo := handoffRepo(t)
	f, c := newFakeServer(t)
	h := newTestHandoff(t, c, "edit", repo)
	next := "run_0199cccc-0000-7000-8000-00000000000b"
	claim := continuing(next, repo, "run_0199cccc-0000-7000-8000-00000000000a", "", nil, false)
	if got := h.Execute(context.Background(), claim, repo); got.EndReason != EndSubmitted {
		t.Fatalf("result %+v", got)
	}
	evs := f.runEvents(next)
	if ws := evs[0].Payload["workspace"].(map[string]any); ws["origin"] != "new" || ws["branch"] != "zm-baton/"+next {
		t.Fatalf("workspace %v", ws)
	}
	if !strings.Contains(answerOf(evs), "다른 기기에서 돌았다") {
		t.Fatalf("the prompt does not say so: %q", answerOf(evs))
	}
}

func TestAResumedSessionClaudeCodeNoLongerHasStartsANewOneInTheSameWorktree(t *testing.T) {
	if runtime.GOOS != "windows" {
		t.Skip("a worktree is taken over on Windows only")
	}
	repo := handoffRepo(t)
	f, c := newFakeServer(t)
	h := newTestHandoff(t, c, "edit", repo)
	first := "run_0199dddd-0000-7000-8000-00000000000a"
	session, ws := firstRun(t, h, f, repo, first)
	t.Setenv(fakeEnv, "resume-missing")
	next := "run_0199dddd-0000-7000-8000-00000000000b"
	if got := h.Execute(context.Background(), continuing(next, repo, first, session, ws, true), repo); got.EndReason != EndSubmitted {
		t.Fatalf("result %+v", got)
	}
	var starts []string
	for _, e := range f.runEvents(next) {
		if e.Kind == "lifecycle" && e.Payload["phase"] == "started" {
			starts = append(starts, e.Payload["session_id"].(string))
		}
	}
	if len(starts) != 2 || starts[0] != session || starts[1] == session {
		t.Fatalf("sessions started %v", starts)
	}
	if _, err := os.Stat(filepath.Join(repo.Worktrees, first, "made-by-run.txt")); err != nil {
		t.Fatal(err)
	}
}
