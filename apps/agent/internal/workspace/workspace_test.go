package workspace

import (
	"context"
	"encoding/json"
	"os"
	"os/exec"
	"path/filepath"
	"slices"
	"strings"
	"testing"
)

const runA = "run_0199aaaa-0000-7000-8000-000000000001"

// gitT runs git in dir for a test, with a fixed author so commits work on any machine.
func gitT(t *testing.T, dir string, args ...string) string {
	t.Helper()
	cmd := exec.Command("git", append([]string{"-C", dir, "-c", "init.defaultBranch=main"}, args...)...)
	cmd.Env = append(os.Environ(), "GIT_AUTHOR_NAME=t", "GIT_AUTHOR_EMAIL=t@example.com",
		"GIT_COMMITTER_NAME=t", "GIT_COMMITTER_EMAIL=t@example.com")
	out, err := cmd.CombinedOutput()
	if err != nil {
		t.Fatalf("git %v: %v\n%s", args, err, out)
	}
	return strings.TrimSpace(string(out))
}

func write(t *testing.T, path, text string) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte(text), 0o644); err != nil {
		t.Fatal(err)
	}
}

// newRepo makes a repository with three committed files on main.
func newRepo(t *testing.T) string {
	t.Helper()
	if _, err := exec.LookPath("git"); err != nil {
		t.Skip("git is not installed")
	}
	root := filepath.Join(t.TempDir(), "demo")
	if err := os.MkdirAll(root, 0o755); err != nil {
		t.Fatal(err)
	}
	gitT(t, root, "init", "--quiet")
	for _, f := range []string{"README.md", "a.txt", "b.txt"} {
		write(t, filepath.Join(root, f), f+"\n")
	}
	gitT(t, root, "add", ".")
	gitT(t, root, "commit", "--quiet", "-m", "first")
	return root
}

func TestCreateMakesAWorktreeOnTheRunsOwnBranch(t *testing.T) {
	root := newRepo(t)
	head := gitT(t, root, "rev-parse", "HEAD")
	trees := filepath.Join(filepath.Dir(root), "demo-zm-baton-runs")
	w, err := Create(context.Background(), root, trees, "git:abc", runA, "")
	if err != nil {
		t.Fatal(err)
	}
	if w.Path != filepath.Join(trees, runA) || w.Branch != "zm-baton/"+runA || w.BaseCommit != head || w.BaseRef != "main" {
		t.Fatalf("workspace %+v", w)
	}
	if got := gitT(t, w.Path, "rev-parse", "--abbrev-ref", "HEAD"); got != w.Branch {
		t.Fatalf("worktree is on %q", got)
	}
	if _, err := os.Stat(filepath.Join(w.Path, "README.md")); err != nil {
		t.Fatal(err)
	}
	// The person's checkout is untouched: still on main, nothing staged or new.
	if gitT(t, root, "rev-parse", "--abbrev-ref", "HEAD") != "main" || gitT(t, root, "status", "--porcelain") != "" {
		t.Fatal("creating a worktree changed the person's checkout")
	}
	// A second run gets its own folder; the same run id again is refused and the folder kept.
	if _, err := Create(context.Background(), root, trees, "git:abc", runA, ""); err == nil {
		t.Fatal("an existing worktree folder was reused")
	}
	if _, err := os.Stat(filepath.Join(w.Path, "README.md")); err != nil {
		t.Fatal("the existing worktree was touched")
	}
}

func TestReportCarriesNoPathOnTheDevice(t *testing.T) {
	root := newRepo(t)
	w, err := Create(context.Background(), root, filepath.Join(filepath.Dir(root), "runs"), "git:abc", runA, "main")
	if err != nil {
		t.Fatal(err)
	}
	raw, _ := json.Marshal(w.Report())
	for _, p := range []string{root, filepath.ToSlash(root), filepath.Dir(root), w.Path} {
		if strings.Contains(string(raw), p) || strings.Contains(string(raw), strings.ReplaceAll(p, `\`, `\\`)) {
			t.Fatalf("report %s carries the path %s", raw, p)
		}
	}
	if w.Report()["base_ref"] != "main" || w.Report()["repo_key"] != "git:abc" {
		t.Fatalf("report %s", raw)
	}
}

func TestBaseBranchSetsTheBaseCommit(t *testing.T) {
	root := newRepo(t)
	gitT(t, root, "switch", "--quiet", "-c", "feature")
	write(t, filepath.Join(root, "f.txt"), "feature\n")
	gitT(t, root, "add", "f.txt")
	gitT(t, root, "commit", "--quiet", "-m", "feature")
	gitT(t, root, "switch", "--quiet", "main")
	main := gitT(t, root, "rev-parse", "main")
	w, err := Create(context.Background(), root, filepath.Join(filepath.Dir(root), "runs"), "git:abc", runA, "main")
	if err != nil {
		t.Fatal(err)
	}
	if w.BaseCommit != main {
		t.Fatalf("base %s, want main %s", w.BaseCommit, main)
	}
	w2, err := Create(context.Background(), root, filepath.Join(filepath.Dir(root), "runs"), "git:abc", "run_2", "feature")
	if err != nil || w2.BaseCommit != gitT(t, root, "rev-parse", "feature") || w2.BaseRef != "feature" {
		t.Fatalf("feature base: %+v %v", w2, err)
	}
	for _, bad := range []string{"-c", "no-such-branch", "a..b", "x y"} {
		if _, err := Create(context.Background(), root, filepath.Join(filepath.Dir(root), "runs"), "git:abc", "run_3", bad); err == nil {
			t.Errorf("base branch %q accepted", bad)
		}
	}
	for _, bad := range []string{"", "run_../x", "main", "run_a b"} {
		if _, err := Create(context.Background(), root, filepath.Join(filepath.Dir(root), "runs"), "git:abc", bad, ""); err == nil {
			t.Errorf("run id %q accepted", bad)
		}
	}
}

func TestChangesListTheRunsFilesAndOnlyTheirOverlaps(t *testing.T) {
	root := newRepo(t)
	w, err := Create(context.Background(), root, filepath.Join(filepath.Dir(root), "runs"), "git:abc", runA, "")
	if err != nil {
		t.Fatal(err)
	}
	ctx := context.Background()
	empty, err := w.Changes(ctx, false)
	if err != nil {
		t.Fatal(err)
	}
	if raw, _ := json.Marshal(empty); string(raw) != `{"base_overlap":[],"files":[]}` {
		t.Fatalf("no change gives %s", raw)
	}

	// The run edits README.md (uncommitted), adds new.txt (untracked) and commits a change to a.txt.
	write(t, filepath.Join(w.Path, "README.md"), "changed by the run\n")
	write(t, filepath.Join(w.Path, "docs", "new.txt"), "new\n")
	write(t, filepath.Join(w.Path, "a.txt"), "a by the run\n")
	gitT(t, w.Path, "commit", "--quiet", "-am", "run commit")
	// Meanwhile the person edits README.md and b.txt in their own checkout without committing,
	gitT(t, root, "status") // (refresh, as a person's tools would)
	write(t, filepath.Join(root, "README.md"), "person\n")
	write(t, filepath.Join(root, "b.txt"), "person b\n")
	write(t, filepath.Join(root, "person-only.txt"), "secret name\n")

	mid, err := w.Changes(ctx, false)
	if err != nil {
		t.Fatal(err)
	}
	if got := mid["files"].([]string); !slices.Equal(got, []string{"README.md", "a.txt", "docs/new.txt"}) {
		t.Fatalf("files %v", got)
	}
	if got := mid["base_overlap"].([]string); !slices.Equal(got, []string{"README.md"}) {
		t.Fatalf("base_overlap %v", got)
	}
	if _, ok := mid["base_branch_overlap"]; ok {
		t.Fatal("base_branch_overlap is only for the end")
	}

	// Then the person commits a.txt and b.txt on main: only a.txt is also the run's file.
	write(t, filepath.Join(root, "a.txt"), "a by the person\n")
	gitT(t, root, "add", "a.txt", "b.txt")
	gitT(t, root, "commit", "--quiet", "-m", "person commit")
	end, err := w.Changes(ctx, true)
	if err != nil {
		t.Fatal(err)
	}
	if got := end["base_branch_overlap"].([]string); !slices.Equal(got, []string{"a.txt"}) {
		t.Fatalf("base_branch_overlap %v", got)
	}
	raw, _ := json.Marshal(end)
	if strings.Contains(string(raw), "person-only") || strings.Contains(string(raw), "b.txt") {
		t.Fatalf("a file only the person changed was named: %s", raw)
	}
	// The connector never commits for the run: the branch has only the run's own commit.
	if n := gitT(t, w.Path, "rev-list", "--count", w.BaseCommit+".."+w.Branch); n != "1" {
		t.Fatalf("commits on the run branch: %s", n)
	}
}

func TestBranchNameRuleMatchesTheServer(t *testing.T) {
	for _, ok := range []string{"main", "feature/x-1", "release/2026.10"} {
		if !IsBranchName(ok) {
			t.Errorf("%q refused", ok)
		}
	}
	for _, bad := range []string{"", "-main", "a..b", "/a", "a/", "a.", "a.lock", "a//b", "a b", `a\b`, "C:/x", ".hidden", "a/.b"} {
		if IsBranchName(bad) {
			t.Errorf("%q accepted", bad)
		}
	}
}

func TestPlanChangesNothingAndRefusesATakenBranch(t *testing.T) {
	root := newRepo(t)
	trees := filepath.Join(filepath.Dir(root), "runs")
	w, err := Plan(context.Background(), root, trees, "git:abc", runA, "main")
	if err != nil {
		t.Fatal(err)
	}
	if w.Origin != OriginNew || w.Report()["origin"] != "new" {
		t.Fatalf("origin %q", w.Origin)
	}
	if _, err := os.Stat(w.Path); err == nil {
		t.Fatal("Plan made the worktree folder")
	}
	gitT(t, root, "branch", "zm-baton/"+runA)
	if _, err := Plan(context.Background(), root, trees, "git:abc", runA, "main"); err == nil {
		t.Fatal("a run branch that already exists was planned again")
	}
}

func TestAnEarlierRunsWorktreeIsTakenOverOrItsBranchContinued(t *testing.T) {
	ctx := context.Background()
	root := newRepo(t)
	trees := filepath.Join(filepath.Dir(root), "runs")
	first, err := Create(ctx, root, trees, "git:abc", runA, "main")
	if err != nil {
		t.Fatal(err)
	}
	write(t, filepath.Join(first.Path, "a.txt"), "by the first run\n")
	gitT(t, first.Path, "commit", "--quiet", "-am", "first run")
	prev, ok := PreviousFrom(first.Report())
	if !ok || prev.Branch != first.Branch || prev.BaseCommit != first.BaseCommit || prev.BaseRef != "main" {
		t.Fatalf("previous %+v %v", prev, ok)
	}

	again, err := Open(ctx, root, first.Path, "git:abc", prev)
	if err != nil {
		t.Fatal(err)
	}
	if again.Origin != OriginReused || again.Path != first.Path || again.Branch != first.Branch || again.BaseCommit != first.BaseCommit {
		t.Fatalf("taken over %+v", again)
	}
	if _, err := again.Changes(ctx, true); err != nil {
		t.Fatalf("changes of a taken-over worktree: %v", err)
	}
	if err := again.Make(ctx); err == nil {
		t.Fatal("Make on a taken-over worktree")
	}
	// Another run's worktree, or one moved to another branch, is not taken over.
	other := gitT(t, root, "rev-parse", "HEAD")
	gitT(t, first.Path, "switch", "--quiet", "-c", "elsewhere")
	if _, err := Open(ctx, root, first.Path, "git:abc", prev); err == nil {
		t.Fatal("a worktree on another branch was taken over")
	}
	gitT(t, first.Path, "switch", "--quiet", first.Branch)
	if _, err := Open(ctx, newRepo(t), first.Path, "git:abc", prev); err == nil {
		t.Fatal("a worktree of another repository was taken over")
	}

	const runB = "run_0199aaaa-0000-7000-8000-000000000002"
	next, err := PlanFrom(ctx, root, trees, "git:abc", runB, prev)
	if err != nil {
		t.Fatal(err)
	}
	if err := next.Make(ctx); err != nil {
		t.Fatal(err)
	}
	if next.Origin != OriginPreviousBranch || next.Branch != "zm-baton/"+runB || next.BaseCommit != first.BaseCommit || next.BaseRef != "main" {
		t.Fatalf("continued %+v", next)
	}
	if gitT(t, next.Path, "rev-parse", "HEAD") != gitT(t, root, "rev-parse", first.Branch) || other != first.BaseCommit {
		t.Fatal("the new worktree does not start at the earlier branch's tip")
	}
	changes, err := next.Changes(ctx, true)
	if err != nil || !slices.Equal(changes["files"].([]string), []string{"a.txt"}) {
		t.Fatalf("the change list counts from the original base: %v %v", changes, err)
	}
	if _, err := PlanFrom(ctx, root, trees, "git:abc", "run_c", Previous{Branch: "zm-baton/run_gone", BaseCommit: first.BaseCommit, BaseRef: "main"}); err == nil {
		t.Fatal("a missing earlier branch was continued")
	}
	if _, ok := PreviousFrom(map[string]any{"branch": "main", "base_commit": first.BaseCommit}); ok {
		t.Fatal("a branch that is not a run branch was taken as an earlier run's")
	}
}

func TestRepoKeyIsTheSmallestRootCommit(t *testing.T) {
	root := newRepo(t)
	key, err := RepoKey(context.Background(), root)
	if err != nil || key != "git:"+gitT(t, root, "rev-list", "--max-parents=0", "HEAD") {
		t.Fatalf("key %q %v", key, err)
	}
	if !IsRunID(runA) || IsRunID("run_../x") || IsRunID("main") {
		t.Fatal("run id rule")
	}
}

func TestSnapshotPinsTheResultWithoutTouchingTheWorktree(t *testing.T) {
	ctx := context.Background()
	root := newRepo(t)
	write(t, filepath.Join(root, ".gitignore"), "*.log\n")
	gitT(t, root, "add", ".gitignore")
	gitT(t, root, "commit", "--quiet", "-m", "ignore logs")
	w, err := Create(ctx, root, filepath.Join(filepath.Dir(root), "runs"), "git:abc", runA, "")
	if err != nil {
		t.Fatal(err)
	}
	tip := gitT(t, w.Path, "rev-parse", "HEAD")

	// Nothing left uncommitted: the snapshot is the branch tip itself.
	clean, err := w.Snapshot(ctx)
	if err != nil || clean != tip {
		t.Fatalf("clean snapshot %q %v, want the tip %s", clean, err, tip)
	}

	// The run commits one file, edits another without staging, stages a third and adds new files.
	write(t, filepath.Join(w.Path, "a.txt"), "a by the run\n")
	gitT(t, w.Path, "commit", "--quiet", "-am", "run commit")
	tip = gitT(t, w.Path, "rev-parse", "HEAD")
	write(t, filepath.Join(w.Path, "README.md"), "unstaged by the run\n")
	write(t, filepath.Join(w.Path, "b.txt"), "staged by the run\n")
	gitT(t, w.Path, "add", "b.txt")
	write(t, filepath.Join(w.Path, "docs", "new.txt"), "untracked\n")
	write(t, filepath.Join(w.Path, "debug.log"), "ignored\n")
	statusBefore := gitT(t, w.Path, "status", "--porcelain")
	stagedBefore := gitT(t, w.Path, "diff", "--cached", "--name-only")

	end, err := w.Changes(ctx, true)
	if err != nil {
		t.Fatal(err)
	}
	snap, _ := end["snapshot"].(string)
	if len(snap) != 40 || snap == tip {
		t.Fatalf("snapshot %q (tip %s)", snap, tip)
	}
	// The snapshot holds every change since the base, the ignored file excepted, on top of the tip.
	if got := strings.Fields(gitT(t, w.Path, "diff", "--name-only", w.BaseCommit, snap)); !slices.Equal(got, []string{"README.md", "a.txt", "b.txt", "docs/new.txt"}) {
		t.Fatalf("snapshot files %v", got)
	}
	if parent := gitT(t, w.Path, "rev-parse", snap+"^"); parent != tip {
		t.Fatalf("snapshot parent %s, want the tip %s", parent, tip)
	}
	if got := gitT(t, w.Path, "show", snap+":README.md"); got != "unstaged by the run" {
		t.Fatalf("snapshot README %q", got)
	}
	if ref := gitT(t, root, "rev-parse", SnapshotRef+runA); ref != snap {
		t.Fatalf("ref %s, want %s", ref, snap)
	}
	// The worktree's branch, index and files are as the run left them.
	if gitT(t, w.Path, "rev-parse", "HEAD") != tip || gitT(t, w.Path, "rev-parse", "--abbrev-ref", "HEAD") != w.Branch {
		t.Fatal("the snapshot moved the run's branch")
	}
	if gitT(t, w.Path, "status", "--porcelain") != statusBefore || gitT(t, w.Path, "diff", "--cached", "--name-only") != stagedBefore {
		t.Fatal("the snapshot changed the worktree or its index")
	}
	// The person's checkout is untouched too.
	if gitT(t, root, "status", "--porcelain") != "" || gitT(t, root, "rev-parse", "--abbrev-ref", "HEAD") != "main" {
		t.Fatal("the snapshot changed the person's checkout")
	}
	// The same state gives the same snapshot; a running list does not take one.
	again, err := w.Snapshot(ctx)
	if err != nil || gitT(t, w.Path, "rev-parse", again+"^{tree}") != gitT(t, w.Path, "rev-parse", snap+"^{tree}") {
		t.Fatalf("second snapshot %q %v", again, err)
	}
	mid, err := w.Changes(ctx, false)
	if err != nil {
		t.Fatal(err)
	}
	if _, ok := mid["snapshot"]; ok {
		t.Fatal("a running change list carries a snapshot")
	}
}
