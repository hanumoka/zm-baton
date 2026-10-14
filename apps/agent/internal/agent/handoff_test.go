package agent

import (
	"context"
	"encoding/json"
	"os"
	"os/exec"
	"path/filepath"
	"slices"
	"strings"
	"testing"
	"time"

	"github.com/hanumoka/zm-baton/apps/agent/internal/api"
)

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

// handoffRepo is a repository with README.md committed on main, and a folder for its worktrees.
func handoffRepo(t *testing.T) Repo {
	t.Helper()
	if _, err := exec.LookPath("git"); err != nil {
		t.Skip("git is not installed")
	}
	base := t.TempDir()
	root := filepath.Join(base, "demo")
	if err := os.MkdirAll(root, 0o755); err != nil {
		t.Fatal(err)
	}
	gitT(t, root, "init", "--quiet")
	if err := os.WriteFile(filepath.Join(root, "README.md"), []byte("demo\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	gitT(t, root, "add", "README.md")
	gitT(t, root, "commit", "--quiet", "-m", "first")
	key := "git:" + gitT(t, root, "rev-list", "--max-parents=0", "HEAD")
	return Repo{Key: key, Root: root, Worktrees: filepath.Join(base, "demo-zm-baton-runs")}
}

func newTestHandoff(t *testing.T, c *api.Client, mode string, repos ...Repo) *Handoff {
	t.Helper()
	t.Setenv(fakeEnv, mode)
	byKey := map[string]Repo{}
	for _, r := range repos {
		byKey[r.Key] = r
	}
	// No temp folders to stay out of: the test repositories live under the test's temp folder.
	h, err := NewHandoff(Config{
		Client: c, DeviceID: "dev_test", ClaudePath: os.Args[0], PollInterval: 20 * time.Millisecond,
		FlushInterval: 20 * time.Millisecond, ChangesInterval: 20 * time.Millisecond, RenewInterval: 20 * time.Millisecond,
		StateDir: filepath.Join(t.TempDir(), "runs"),
	}, func() (map[string]Repo, error) { return byKey, nil }, nil)
	if err != nil {
		t.Fatal(err)
	}
	return h
}

func delegated(id string, repoKey, baseBranch string, tools ...string) api.Claim {
	return api.Claim{
		RunID: id, WorkItemID: "wrk_h", Generation: 3, LeaseSeconds: 45,
		Delegation:  &api.Delegation{ID: "dlg_" + id, Executor: "claude_code", RepoKey: repoKey, BaseBranch: baseBranch, AllowedTools: tools},
		Instruction: "make the file",
	}
}

// noLocalPath fails when any sent event names a path on this device.
func noLocalPath(t *testing.T, evs []api.Event, paths ...string) {
	t.Helper()
	raw, _ := json.Marshal(evs)
	for _, p := range paths {
		for _, form := range []string{p, filepath.ToSlash(p), strings.ReplaceAll(p, `\`, `\\`)} {
			if strings.Contains(string(raw), form) {
				t.Fatalf("an event carries the local path %s", form)
			}
		}
	}
}

func TestHandoffRunsADelegatedRunInItsOwnWorktree(t *testing.T) {
	repo := handoffRepo(t)
	base := gitT(t, repo.Root, "rev-parse", "HEAD")
	f, c := newFakeServer(t)
	f.pending = []api.PendingRun{
		{ID: "run_codex", Executor: "codex", DelegationID: "dlg_c", DeviceID: "dev_test", RepoKey: repo.Key},
		{ID: "run_elsewhere", Executor: "claude_code", DelegationID: "dlg_e", DeviceID: "dev_test", RepoKey: "git:not-here"},
		{ID: "run_compat", Executor: "claude_code"},
		{ID: "run_other_device", Executor: "claude_code", DelegationID: "dlg_o", DeviceID: "dev_other", RepoKey: repo.Key},
		{ID: "run_mine", Executor: "claude_code", DelegationID: "dlg_run_mine", DeviceID: "dev_test", RepoKey: repo.Key},
	}
	f.answers["run_mine"] = delegated("run_mine", repo.Key, "main", "Read", "Edit", "Write")
	h := newTestHandoff(t, c, "edit", repo)

	got, err := h.Run(context.Background(), true)
	if err != nil || got.EndReason != EndSubmitted || got.Delivery != nil {
		t.Fatalf("run: %+v %v", got, err)
	}
	if !slices.Equal(f.claims, []string{"run_mine"}) {
		t.Fatalf("claimed %v; only the run for a repository registered here may be claimed", f.claims)
	}
	if !slices.Contains(f.queries, "state=requested&device_id=dev_test") {
		t.Fatalf("polls %v did not ask for this device's runs", f.queries)
	}

	evs := f.runEvents("run_mine")
	kinds := checkSequence(t, "run_mine", evs)
	if kinds[0] != "lifecycle" || kinds[len(kinds)-1] != "lifecycle" || kinds[len(kinds)-2] != KindGitChanges {
		t.Fatalf("kinds %v", kinds)
	}
	started := evs[0].Payload
	ws, _ := started["workspace"].(map[string]any)
	if ws["repo_key"] != repo.Key || ws["branch"] != "zm-baton/run_mine" || ws["base_commit"] != base || ws["base_ref"] != "main" {
		t.Fatalf("started workspace %v", started)
	}
	if tools, _ := started["allowed_tools"].([]any); len(tools) != 3 {
		t.Fatalf("started allowed_tools %v", started["allowed_tools"])
	}
	var answer string
	for _, e := range evs {
		if e.Kind == "answer" {
			answer, _ = e.Payload["text"].(string)
		}
	}
	if answer != "tools: Read,Edit,Write; prompt: make the file" {
		t.Fatalf("the executor got %q", answer)
	}
	for _, e := range evs {
		if e.Kind == "action" {
			if in, _ := e.Payload["input"].(map[string]any); in["file_path"] != "made-by-run.txt" {
				t.Fatalf("the worktree path was not cut out of the tool input: %v", e.Payload)
			}
		}
		if e.Kind == "action_result" && e.Payload["text"] != "File created successfully at: made-by-run.txt (also made-by-run.txt)" {
			t.Fatalf("the worktree path was not cut out of the tool result: %v", e.Payload)
		}
	}
	final := evs[len(evs)-2].Payload
	files, _ := json.Marshal(final["files"])
	overlap, _ := json.Marshal(final["base_branch_overlap"])
	if string(files) != `["README.md","made-by-run.txt"]` || string(overlap) != `[]` {
		t.Fatalf("final changes %v", final)
	}
	ended := evs[len(evs)-1].Payload
	if ended["end_reason"] != "submitted" || ended["executor_started"] != true || ended["workspace"] == nil {
		t.Fatalf("ended %v", ended)
	}
	worktree := filepath.Join(repo.Worktrees, "run_mine")
	noLocalPath(t, evs, repo.Root, repo.Worktrees, worktree, filepath.Dir(repo.Root))

	// The worktree and its uncommitted change stay for the person; nothing was committed or cleaned up.
	if _, err := os.Stat(filepath.Join(worktree, "made-by-run.txt")); err != nil {
		t.Fatalf("the run's file is gone: %v", err)
	}
	if tip := gitT(t, repo.Root, "rev-parse", "zm-baton/run_mine"); tip != base {
		t.Fatalf("the connector committed on the run branch: %s", tip)
	}
	if status := gitT(t, repo.Root, "status", "--porcelain"); status != "" || gitT(t, repo.Root, "rev-parse", "--abbrev-ref", "HEAD") != "main" {
		t.Fatalf("the person's checkout changed: %q", status)
	}
}

func TestHandoffRefusesAClaimBeforeAnyExecutorStarts(t *testing.T) {
	repo := handoffRepo(t)
	cases := map[string]api.Claim{
		"unknown tool":      delegated("run_tool", repo.Key, "", "Read", "Bash"),
		"no tools":          delegated("run_none", repo.Key, ""),
		"missing branch":    delegated("run_branch", repo.Key, "no-such-branch", "Read"),
		"option as branch":  delegated("run_opt", repo.Key, "--upload-pack=x", "Read"),
		"other repository":  delegated("run_repo", "git:other", "", "Read"),
		"no delegation":     {RunID: "run_bare", Generation: 3, Instruction: "x"},
		"no instruction":    func() api.Claim { c := delegated("run_empty", repo.Key, "", "Read"); c.Instruction = " "; return c }(),
		"run id not a name": delegated("run_../../x", repo.Key, "", "Read"),
	}
	for name, claim := range cases {
		t.Run(name, func(t *testing.T) {
			f, c := newFakeServer(t)
			h := newTestHandoff(t, c, "edit", repo)
			if got := h.Execute(context.Background(), claim, repo); got.EndReason != EndFailed {
				t.Fatalf("result %+v", got)
			}
			evs := f.runEvents(claim.RunID)
			if got := checkSequence(t, claim.RunID, evs); !slices.Equal(got, []string{"error", "lifecycle"}) {
				t.Fatalf("kinds %v", got)
			}
			if p := evs[1].Payload; p["executor_started"] != false {
				t.Fatalf("ended %v", p)
			}
			noLocalPath(t, evs, repo.Root, repo.Worktrees)
		})
	}
	// No worktree was made for any of them.
	if entries, _ := os.ReadDir(repo.Worktrees); len(entries) != 0 {
		t.Fatalf("worktrees left behind: %v", entries)
	}
}

func TestPathHiderCutsOnlyThatFolderOut(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "runs", "run_1")
	hide := pathHider(dir)
	in := map[string]any{
		"a":    filepath.Join(dir, "docs", "x.md"),
		"b":    "see " + filepath.ToSlash(dir) + "/y.md and " + dir,
		"list": []any{filepath.Join(dir, "z.txt"), 3.0, true},
		"next": map[string]any{"c": dir + "-other/w.txt"},
		"keep": filepath.Join(filepath.Dir(dir), "run_10", "v.txt"),
	}
	got := hide(in)
	want := map[string]any{
		"a":    filepath.Join("docs", "x.md"),
		"b":    "see y.md and .",
		"list": []any{"z.txt", 3.0, true},
		"next": map[string]any{"c": dir + "-other/w.txt"},
		"keep": filepath.Join(filepath.Dir(dir), "run_10", "v.txt"),
	}
	a, _ := json.Marshal(got)
	b, _ := json.Marshal(want)
	if string(a) != string(b) {
		t.Fatalf("hidden\n got %s\nwant %s", a, b)
	}
	if vol := filepath.VolumeName(dir); len(vol) == 2 {
		bash := "/" + strings.ToLower(vol[:1]) + filepath.ToSlash(dir[2:]) + "/q.txt"
		if got := hide(map[string]any{"s": bash})["s"]; got != "q.txt" {
			t.Fatalf("Git Bash path %s gave %v", bash, got)
		}
	}
	if p := pathHider("")(in); p["a"] != in["a"] {
		t.Fatal("an empty folder must change nothing")
	}
}

func TestHandoffRefusesARepositoryUnderATempFolder(t *testing.T) {
	repo := handoffRepo(t)
	_, c := newFakeServer(t)
	t.Setenv(fakeEnv, "edit")
	h, err := NewHandoff(Config{Client: c, DeviceID: "dev_test", ClaudePath: os.Args[0], FlushInterval: 20 * time.Millisecond},
		func() (map[string]Repo, error) { return nil, nil }, []string{filepath.Dir(repo.Root)})
	if err != nil {
		t.Fatal(err)
	}
	if got := h.Execute(context.Background(), delegated("run_tmp", repo.Key, "", "Read"), repo); got.EndReason != EndFailed {
		t.Fatalf("a repository under a temp folder ran: %+v", got)
	}
}
