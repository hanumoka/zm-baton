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
	RepoKey    string
	Root       string // the repository the person registered
	Path       string // the run's worktree
	Branch     string // zm-baton/<run id>
	BaseCommit string
	// BaseRef is the branch the base commit came from: the delegation's base branch, else the
	// branch checked out in Root when the run began, else "HEAD" (a detached checkout).
	BaseRef string
	// tipRef is BaseRef spelled so that git reads it as that branch, e.g. refs/heads/main.
	tipRef string
}

// Create makes a worktree for runID under worktrees, on a new branch zm-baton/<runID> starting at
// the tip of baseBranch, or at Root's current commit when baseBranch is empty (contract 1).
func Create(ctx context.Context, root, worktrees, repoKey, runIDValue, baseBranch string) (*Workspace, error) {
	if !runID.MatchString(runIDValue) {
		return nil, fmt.Errorf("run id %q cannot name a branch", runIDValue)
	}
	if baseBranch != "" && !IsBranchName(baseBranch) {
		return nil, fmt.Errorf("base branch %q is not a usable branch name", baseBranch)
	}
	w := &Workspace{RepoKey: repoKey, Root: root, Path: filepath.Join(worktrees, runIDValue), Branch: BranchPrefix + runIDValue}
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
	if _, err := os.Stat(w.Path); err == nil {
		return nil, fmt.Errorf("worktree folder %s already exists; it is left as it is", w.Path)
	}
	if err := os.MkdirAll(worktrees, 0o755); err != nil {
		return nil, err
	}
	if _, err := git(ctx, root, false, "worktree", "add", "--quiet", "-b", w.Branch, w.Path, w.BaseCommit); err != nil {
		return nil, err
	}
	return w, nil
}

// Report is the workspace for the run record (contract 8): no path on this device.
func (w *Workspace) Report() map[string]any {
	return map[string]any{"repo_key": w.RepoKey, "base_commit": w.BaseCommit, "branch": w.Branch, "base_ref": w.BaseRef}
}

// Changes is the git_changes payload: the files the run changed since its base commit, committed
// or not, tracked or new; those of them that also have uncommitted changes in Root (base_overlap);
// and when final, those that also changed on the base branch since the base commit
// (base_branch_overlap, contract 4). Other files of Root are never named.
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

var errNoGit = errors.New("git is not available")

// gitRaw runs git without a shell. readOnly commands do not take optional locks, so they never
// block a person working in the same repository.
func gitRaw(ctx context.Context, dir string, readOnly bool, args ...string) (string, error) {
	path, err := exec.LookPath("git")
	if err != nil {
		return "", errNoGit
	}
	cmd := exec.CommandContext(ctx, path, append([]string{"-C", dir}, args...)...)
	cmd.Env = append(os.Environ(), "GIT_TERMINAL_PROMPT=0")
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
