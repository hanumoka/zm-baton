package agent

import (
	"context"
	"log/slog"
	"os"
	"path/filepath"
	"testing"

	"github.com/hanumoka/zm-baton/apps/agent/internal/workspace"
)

// Use real Git worktrees with confirmed local termination records. No executor is launched:
// this checks the selector on both OSes, independently of process-termination support.
func TestConsecutiveResumesKeepUncommittedWork(t *testing.T) {
	repo := handoffRepo(t)
	_, client := newFakeServer(t)
	h := newTestHandoff(t, client, "edit", repo)
	ctx := context.Background()
	first, second, third := "run_first", "run_second", "run_third"
	ws, err := workspace.Create(ctx, repo.Root, repo.Worktrees, repo.Key, first, "main")
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(ws.Path, "first.txt"), []byte("first draft"), 0o600); err != nil {
		t.Fatal(err)
	}
	for _, id := range []string{first, second} {
		if err := h.runner.records.save(RunRecord{RunID: id, SessionID: "resume-session", Exited: true, ChildrenGone: true}); err != nil {
			t.Fatal(err)
		}
	}
	resumed, session, _, err := h.workspaceFor(ctx, continuing(second, repo, first, "resume-session", ws.Report(), true), repo, &Readiness{}, slog.Default())
	if err != nil {
		t.Fatal(err)
	}
	if resumed.Path != ws.Path || session != "resume-session" {
		t.Fatalf("first resume: %+v %q", resumed, session)
	}
	if err := os.WriteFile(filepath.Join(resumed.Path, "second.txt"), []byte("second draft"), 0o600); err != nil {
		t.Fatal(err)
	}
	again, session, _, err := h.workspaceFor(ctx, continuing(third, repo, second, "resume-session", resumed.Report(), true), repo, &Readiness{}, slog.Default())
	if err != nil {
		t.Fatal(err)
	}
	if again.Path != ws.Path || again.Branch != ws.Branch || again.RunID != third || session != "resume-session" {
		t.Fatalf("second resume lost the shared worktree or current run identity: %+v %q", again, session)
	}
	for name, want := range map[string]string{"first.txt": "first draft", "second.txt": "second draft"} {
		got, err := os.ReadFile(filepath.Join(again.Path, name))
		if err != nil || string(got) != want {
			t.Fatalf("%s: %q %v", name, got, err)
		}
	}
}

func TestResumedRunsKeepSeparateSnapshots(t *testing.T) {
	repo := handoffRepo(t)
	_, client := newFakeServer(t)
	h := newTestHandoff(t, client, "edit", repo)
	ctx := context.Background()
	first, second := "run_snapshotfirst", "run_snapshotsecond"
	ws, err := workspace.Create(ctx, repo.Root, repo.Worktrees, repo.Key, first, "main")
	if err != nil {
		t.Fatal(err)
	}
	p := filepath.Join(ws.Path, "result.txt")
	if err := os.WriteFile(p, []byte("first result"), 0o600); err != nil {
		t.Fatal(err)
	}
	original, err := ws.Snapshot(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if err := h.runner.records.save(RunRecord{RunID: first, SessionID: "resume-session", Exited: true, ChildrenGone: true}); err != nil {
		t.Fatal(err)
	}
	resumed, _, _, err := h.workspaceFor(ctx, continuing(second, repo, first, "resume-session", ws.Report(), true), repo, &Readiness{}, slog.Default())
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(p, []byte("second result"), 0o600); err != nil {
		t.Fatal(err)
	}
	changes, err := resumed.Changes(ctx, true)
	if err != nil {
		t.Fatal(err)
	}
	latest, ok := changes["snapshot"].(string)
	if !ok || latest == original {
		t.Fatalf("no new snapshot in final report: %v", changes)
	}
	for id, want := range map[string]string{first: original, second: latest} {
		if got := gitT(t, repo.Root, "rev-parse", workspace.SnapshotRef+id); got != want {
			t.Fatalf("%s points to %s, want %s", id, got, want)
		}
	}
	if got := gitT(t, repo.Root, "show", original+":result.txt"); got != "first result" {
		t.Fatalf("first result: %q", got)
	}
	if got := gitT(t, repo.Root, "show", latest+":result.txt"); got != "second result" {
		t.Fatalf("second result: %q", got)
	}
	if got := gitT(t, resumed.Path, "branch", "--show-current"); got != ws.Branch {
		t.Fatalf("branch moved: %q", got)
	}
}
