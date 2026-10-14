// Package workspace gives each delegated run its own Git worktree and branch, and lists what the
// run changed (contract v1 "실행마다 별도 작업 트리", "보고 한 건의 모양" git_changes).
//
// The connector never commits in a run's worktree and never removes it: what the executor left,
// committed or not, stays on the run's branch and in its worktree for a person to review and to
// merge or drop with Git (DEC-0098). Absolute paths stay on this device; reports carry only the
// repository key, commits, branch names and paths from the repository root.
package workspace

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"runtime"
	"slices"
	"strings"
)

// MaxFiles is the most paths one change list carries; the server refuses more.
const MaxFiles = 2000

// BranchPrefix starts every run branch: zm-baton/<run id>.
const BranchPrefix = "zm-baton/"

var (
	runID  = regexp.MustCompile(`^run_[0-9A-Za-z-]{1,64}$`)
	commit = regexp.MustCompile(`^[0-9a-f]{40}([0-9a-f]{24})?$`)
	branch = regexp.MustCompile(`^[A-Za-z0-9._/-]{1,200}$`)
)

// IsBranchName is the server's rule for a branch named in a delegation: safe on a command line
// and in a path. It never starts with `-`, so git cannot read it as an option.
func IsBranchName(s string) bool {
	return branch.MatchString(s) && !strings.HasPrefix(s, "-") && !strings.HasPrefix(s, "/") &&
		!strings.HasPrefix(s, ".") && !strings.HasSuffix(s, "/") && !strings.HasSuffix(s, ".") &&
		!strings.HasSuffix(s, ".lock") && !strings.Contains(s, "..") && !strings.Contains(s, "//") &&
		!strings.Contains(s, "/.")
}

// Workspace is one run's worktree. Root and Path are local absolute paths and are never sent.
type Workspace struct {
	RunID      string // the current run; a reused worktree's branch may name an earlier run
	RepoKey    string
	Root       string // the repository the person registered
	Path       string // the run's worktree
	Branch     string // zm-baton/<run id>
	BaseCommit string
	// BaseRef is the branch the base commit came from: the delegation's base branch, else the
	// branch checked out in Root when the run began, else "HEAD" (a detached checkout).
	BaseRef string
	// Origin says how the worktree came to be: OriginNew, OriginReused or OriginPreviousBranch.
	Origin string
	// tipRef is BaseRef spelled so that git reads it as that branch, e.g. refs/heads/main.
	tipRef string
	// startPoint is the commit the new worktree checks out: the base commit, or the tip of an
	// earlier run's branch.
	startPoint string
}

// How a run's worktree came to be (reported as workspace.origin).
const (
	OriginNew            = "new"             // its own new worktree at the base commit
	OriginReused         = "reused"          // the earlier run's worktree, continued (contract 6)
	OriginPreviousBranch = "previous_branch" // its own new worktree at the tip of the earlier run's branch (contract 6.1)
)

// Previous is what an earlier run reported about its worktree: its branch, base commit and base ref.
type Previous struct {
	Branch     string
	BaseCommit string
	BaseRef    string
}

// PreviousFrom reads an earlier run's workspace as the server sent it; ok is false when it lacks a
// usable branch or base commit.
func PreviousFrom(ws map[string]any) (Previous, bool) {
	p := Previous{}
	p.Branch, _ = ws["branch"].(string)
	p.BaseCommit, _ = ws["base_commit"].(string)
	p.BaseRef, _ = ws["base_ref"].(string)
	if p.BaseRef == "" {
		p.BaseRef = "HEAD"
	}
	ok := IsBranchName(p.Branch) && strings.HasPrefix(p.Branch, BranchPrefix) && commit.MatchString(p.BaseCommit) &&
		(p.BaseRef == "HEAD" || IsBranchName(p.BaseRef))
	return p, ok
}

// Create makes a worktree for runID under worktrees, on a new branch zm-baton/<runID> starting at
// the tip of baseBranch, or at Root's current commit when baseBranch is empty (contract 1).
func Create(ctx context.Context, root, worktrees, repoKey, runIDValue, baseBranch string) (*Workspace, error) {
	w, err := Plan(ctx, root, worktrees, repoKey, runIDValue, baseBranch)
	if err != nil {
		return nil, err
	}
	return w, w.Make(ctx)
}

// Plan works out a new worktree for runID without changing anything: the base commit (the tip of
// baseBranch, or Root's current commit), and that neither its folder nor its branch exists yet.
func Plan(ctx context.Context, root, worktrees, repoKey, runIDValue, baseBranch string) (*Workspace, error) {
	if !runID.MatchString(runIDValue) {
		return nil, fmt.Errorf("run id %q cannot name a branch", runIDValue)
	}
	if baseBranch != "" && !IsBranchName(baseBranch) {
		return nil, fmt.Errorf("base branch %q is not a usable branch name", baseBranch)
	}
	w := &Workspace{RunID: runIDValue, RepoKey: repoKey, Root: root, Path: filepath.Join(worktrees, runIDValue), Branch: BranchPrefix + runIDValue, Origin: OriginNew}
	if baseBranch != "" {
		// A local branch first; a name such as origin/main is taken as it is.
		w.tipRef = "refs/heads/" + baseBranch
		sha, err := revParse(ctx, root, w.tipRef)
		if err != nil {
			w.tipRef = baseBranch
			if sha, err = revParse(ctx, root, baseBranch); err != nil {
				return nil, fmt.Errorf("base branch %s is not in the repository: %w", baseBranch, err)
			}
		}
		w.BaseCommit, w.BaseRef = sha, baseBranch
	} else {
		sha, err := revParse(ctx, root, "HEAD")
		if err != nil {
			return nil, fmt.Errorf("the repository has no current commit: %w", err)
		}
		w.BaseCommit, w.BaseRef, w.tipRef = sha, "HEAD", "HEAD"
		if full, err := git(ctx, root, true, "symbolic-ref", "--quiet", "HEAD"); err == nil {
			if name, ok := strings.CutPrefix(full, "refs/heads/"); ok && IsBranchName(name) {
				w.BaseRef, w.tipRef = name, full
			}
		}
	}
	w.startPoint = w.BaseCommit
	return w, w.checkFree(ctx)
}

// PlanFrom works out a new worktree for runID on its own branch that starts at the tip of an earlier
// run's branch and keeps that run's base commit and base ref, so the change list still counts from
// the original base (contract 6.1). What the earlier run left uncommitted is not on its branch.
func PlanFrom(ctx context.Context, root, worktrees, repoKey, runIDValue string, prev Previous) (*Workspace, error) {
	if !runID.MatchString(runIDValue) {
		return nil, fmt.Errorf("run id %q cannot name a branch", runIDValue)
	}
	tip, err := revParse(ctx, root, "refs/heads/"+prev.Branch)
	if err != nil {
		return nil, fmt.Errorf("the earlier run's branch %s is not in the repository: %w", prev.Branch, err)
	}
	w := &Workspace{
		RunID: runIDValue, RepoKey: repoKey, Root: root, Path: filepath.Join(worktrees, runIDValue), Branch: BranchPrefix + runIDValue,
		BaseCommit: prev.BaseCommit, BaseRef: prev.BaseRef, Origin: OriginPreviousBranch, startPoint: tip,
		tipRef: tipRefFor(ctx, root, prev.BaseRef),
	}
	return w, w.checkFree(ctx)
}

// Open takes over the worktree named by the earlier run's branch (contract 6). Several runs may
// have reused it, so the immediately preceding run's ID need not name its folder. The current
// run gets its own snapshot reference; the worktree, branch and uncommitted files stay unchanged.
func Open(ctx context.Context, root, worktrees, repoKey, runIDValue string, prev Previous) (*Workspace, error) {
	worktreeID, ok := strings.CutPrefix(prev.Branch, BranchPrefix)
	if !ok || !IsRunID(worktreeID) || !IsRunID(runIDValue) {
		return nil, errors.New("the run or earlier branch does not name a run worktree")
	}
	path := filepath.Join(worktrees, worktreeID)
	if _, err := os.Stat(path); err != nil {
		return nil, fmt.Errorf("the earlier run's worktree is gone: %w", err)
	}
	head, err := git(ctx, path, true, "symbolic-ref", "--quiet", "HEAD")
	if err != nil || head != "refs/heads/"+prev.Branch {
		return nil, fmt.Errorf("the earlier run's worktree is not on its branch %s any more", prev.Branch)
	}
	common, err := git(ctx, path, true, "rev-parse", "--path-format=absolute", "--git-common-dir")
	if err != nil {
		return nil, err
	}
	rootCommon, err := git(ctx, root, true, "rev-parse", "--path-format=absolute", "--git-common-dir")
	if err != nil {
		return nil, err
	}
	if !sameDir(common, rootCommon) {
		return nil, errors.New("the earlier run's worktree belongs to another repository")
	}
	if _, err := revParse(ctx, root, prev.BaseCommit); err != nil {
		return nil, fmt.Errorf("the earlier run's base commit is gone: %w", err)
	}
	return &Workspace{
		RunID: runIDValue, RepoKey: repoKey, Root: root, Path: path, Branch: prev.Branch, BaseCommit: prev.BaseCommit, BaseRef: prev.BaseRef,
		Origin: OriginReused, tipRef: tipRefFor(ctx, root, prev.BaseRef), startPoint: prev.BaseCommit,
	}, nil
}

// Make creates the planned worktree and its branch at the start point.
func (w *Workspace) Make(ctx context.Context) error {
	if w.Origin == OriginReused {
		return errors.New("a reused worktree already exists")
	}
	if err := os.MkdirAll(filepath.Dir(w.Path), 0o755); err != nil {
		return err
	}
	_, err := git(ctx, w.Root, false, "worktree", "add", "--quiet", "-b", w.Branch, w.Path, w.startPoint)
	return err
}

// checkFree refuses a planned worktree whose folder or branch already exists; both are left as they are.
func (w *Workspace) checkFree(ctx context.Context) error {
	if _, err := os.Stat(w.Path); err == nil {
		return fmt.Errorf("worktree folder %s already exists; it is left as it is", w.Path)
	}
	if _, err := revParse(ctx, w.Root, "refs/heads/"+w.Branch); err == nil {
		return fmt.Errorf("branch %s already exists; it is left as it is", w.Branch)
	}
	return nil
}

// tipRefFor spells a base ref so that git reads it as that branch: refs/heads/<name> when the
// local branch exists, else the name as it is, and HEAD for a detached base.
func tipRefFor(ctx context.Context, root, baseRef string) string {
	if baseRef == "" || baseRef == "HEAD" {
		return "HEAD"
	}
	if _, err := revParse(ctx, root, "refs/heads/"+baseRef); err == nil {
		return "refs/heads/" + baseRef
	}
	return baseRef
}

// RepoKey is the repository's key as `zm-baton repo add` computes it: "git:" and its smallest root
// commit. A folder that now holds another repository has another key.
func RepoKey(ctx context.Context, root string) (string, error) {
	out, err := git(ctx, root, true, "rev-list", "--max-parents=0", "HEAD")
	if err != nil {
		return "", err
	}
	roots := strings.Fields(out)
	if len(roots) == 0 {
		return "", errors.New("the repository has no commit")
	}
	slices.Sort(roots)
	return "git:" + roots[0], nil
}

func sameDir(a, b string) bool {
	ca, cb := filepath.Clean(a), filepath.Clean(b)
	if runtime.GOOS == "windows" {
		return strings.EqualFold(ca, cb)
	}
	return ca == cb
}

// Report is the workspace for the run record (contract 8): no path on this device.
func (w *Workspace) Report() map[string]any {
	r := map[string]any{"repo_key": w.RepoKey, "base_commit": w.BaseCommit, "branch": w.Branch, "base_ref": w.BaseRef}
	if w.Origin != "" {
		r["origin"] = w.Origin
	}
	return r
}

// Changes is the git_changes payload: the files the run changed since its base commit, committed
// or not, tracked or new; those of them that also have uncommitted changes in Root (base_overlap);
// and when final, those that also changed on the base branch since the base commit
// (base_branch_overlap, contract 4) and the snapshot commit that pins the result for review
// (snapshot, contract "검토 대상을 고정한다"). Other files of Root are never named. A snapshot
// that cannot be made is left out; the server then marks the result as not pinned.
func (w *Workspace) Changes(ctx context.Context, final bool) (map[string]any, error) {
	diff, err := names(ctx, w.Path, false, "diff", "--name-only", "-z", "--no-renames", w.BaseCommit)
	if err != nil {
		return nil, err
	}
	untracked, err := names(ctx, w.Path, false, "ls-files", "--others", "--exclude-standard", "-z")
	if err != nil {
		return nil, err
	}
	files := union(diff, untracked)
	// Root belongs to the person, who may be working in it: read without taking its index lock.
	rootDiff, err := names(ctx, w.Root, true, "diff", "--name-only", "-z", "--no-renames", "HEAD")
	if err != nil {
		return nil, err
	}
	rootNew, err := names(ctx, w.Root, true, "ls-files", "--others", "--exclude-standard", "-z")
	if err != nil {
		return nil, err
	}
	p := map[string]any{"base_overlap": intersect(files, union(rootDiff, rootNew))}
	if final {
		tip, err := revParse(ctx, w.Root, w.tipRef)
		if err != nil {
			return nil, fmt.Errorf("base branch %s: %w", w.BaseRef, err)
		}
		moved, err := names(ctx, w.Root, true, "diff", "--name-only", "-z", "--no-renames", w.BaseCommit, tip)
		if err != nil {
			return nil, err
		}
		p["base_branch_overlap"] = intersect(files, moved)
		if sha, err := w.Snapshot(ctx); err == nil {
			p["snapshot"] = sha
		}
	}
	if len(files) > MaxFiles {
		files = files[:MaxFiles]
		p["truncated"] = true
		for _, k := range []string{"base_overlap", "base_branch_overlap"} {
			if list, ok := p[k].([]string); ok {
				p[k] = intersect(files, list)
			}
		}
	}
	p["files"] = files
	return p, nil
}

// SnapshotRef is where a run's snapshot commit is kept: refs/zm-baton/snapshots/<run id>.
const SnapshotRef = "refs/zm-baton/snapshots/"

// Snapshot pins what the run left in its worktree as one commit (contract v1 "검토 대상을
// 고정한다" 1, 2): committed, staged and unstaged changes and new files (not ignored ones). It
// builds the commit in a temporary index, so the worktree's files, its index and its branch stay
// as they are, and keeps it only under refs/zm-baton/snapshots/<run id>, never on a branch. When
// nothing is left uncommitted the snapshot is the branch tip itself. It returns the commit.
func (w *Workspace) Snapshot(ctx context.Context) (string, error) {
	runIDValue := w.RunID
	if !runID.MatchString(runIDValue) {
		return "", fmt.Errorf("run id %q cannot name a snapshot", runIDValue)
	}
	head, err := revParse(ctx, w.Path, "HEAD")
	if err != nil {
		return "", err
	}
	tmp, err := os.CreateTemp("", "zm-baton-snapshot-*.index")
	if err != nil {
		return "", err
	}
	index := tmp.Name()
	tmp.Close()
	_ = os.Remove(index) // git makes it; an empty file is not an index
	defer os.Remove(index)
	env := []string{"GIT_INDEX_FILE=" + index}
	if _, err := gitEnv(ctx, w.Path, env, "read-tree", "HEAD"); err != nil {
		return "", err
	}
	if _, err := gitEnv(ctx, w.Path, env, "add", "--all", "--", "."); err != nil {
		return "", err
	}
	tree, err := gitEnv(ctx, w.Path, env, "write-tree")
	if err != nil {
		return "", err
	}
	headTree, err := git(ctx, w.Path, true, "rev-parse", "--verify", "--quiet", "--end-of-options", head+"^{tree}")
	if err != nil {
		return "", err
	}
	sha := head
	if tree != headTree {
		// A commit object only: no branch moves. Its author is zm-baton, not the person or the executor.
		id := []string{"GIT_AUTHOR_NAME=zm-baton", "GIT_AUTHOR_EMAIL=zm-baton@localhost",
			"GIT_COMMITTER_NAME=zm-baton", "GIT_COMMITTER_EMAIL=zm-baton@localhost"}
		if sha, err = gitEnv(ctx, w.Path, id, "commit-tree", tree, "-p", head, "-m", "zm-baton snapshot of "+runIDValue); err != nil {
			return "", err
		}
	}
	if !commit.MatchString(sha) {
		return "", fmt.Errorf("git commit-tree gave %q", sha)
	}
	if _, err := gitEnv(ctx, w.Path, nil, "update-ref", SnapshotRef+runIDValue, sha); err != nil {
		return "", err
	}
	return sha, nil
}

func revParse(ctx context.Context, dir, rev string) (string, error) {
	sha, err := git(ctx, dir, true, "rev-parse", "--verify", "--quiet", "--end-of-options", rev+"^{commit}")
	if err != nil {
		return "", err
	}
	if !commit.MatchString(sha) {
		return "", fmt.Errorf("git rev-parse gave %q", sha)
	}
	return sha, nil
}

// names runs a git command that prints NUL-separated paths from the repository root.
func names(ctx context.Context, dir string, readOnly bool, args ...string) ([]string, error) {
	out, err := gitRaw(ctx, dir, readOnly, args...)
	if err != nil {
		return nil, err
	}
	var list []string
	for _, n := range strings.Split(out, "\x00") {
		if n != "" {
			list = append(list, n)
		}
	}
	return list, nil
}

func git(ctx context.Context, dir string, readOnly bool, args ...string) (string, error) {
	out, err := gitRaw(ctx, dir, readOnly, args...)
	return strings.TrimSpace(out), err
}

// gitEnv runs a writing git command with extra environment, e.g. a temporary index.
func gitEnv(ctx context.Context, dir string, env []string, args ...string) (string, error) {
	out, err := gitRun(ctx, dir, false, env, args...)
	return strings.TrimSpace(out), err
}

var errNoGit = errors.New("git is not available")

// gitRaw runs git without a shell. readOnly commands do not take optional locks, so they never
// block a person working in the same repository.
func gitRaw(ctx context.Context, dir string, readOnly bool, args ...string) (string, error) {
	return gitRun(ctx, dir, readOnly, nil, args...)
}

func gitRun(ctx context.Context, dir string, readOnly bool, env []string, args ...string) (string, error) {
	path, err := exec.LookPath("git")
	if err != nil {
		return "", errNoGit
	}
	cmd := exec.CommandContext(ctx, path, append([]string{"-C", dir}, args...)...)
	cmd.Env = append(append(os.Environ(), "GIT_TERMINAL_PROMPT=0"), env...)
	if readOnly {
		cmd.Env = append(cmd.Env, "GIT_OPTIONAL_LOCKS=0")
	}
	var out, errOut bytes.Buffer
	cmd.Stdout, cmd.Stderr = &out, &errOut
	if err := cmd.Run(); err != nil {
		msg := strings.TrimSpace(errOut.String())
		if msg == "" {
			msg = err.Error()
		}
		return "", fmt.Errorf("git %s: %s", args[0], msg)
	}
	return out.String(), nil
}

// union is sorted and never nil, so an empty list goes out as [] and not as null.
func union(a, b []string) []string {
	out := append([]string{}, a...)
	out = append(out, b...)
	slices.Sort(out)
	return slices.Compact(out)
}

func intersect(files, other []string) []string {
	set := make(map[string]bool, len(other))
	for _, o := range other {
		set[o] = true
	}
	out := []string{}
	for _, f := range files {
		if set[f] {
			out = append(out, f)
		}
	}
	return out
}

// IsRunID reports whether s is a run id that can name a branch and a folder: run_ and safe characters.
func IsRunID(s string) bool { return runID.MatchString(s) }
