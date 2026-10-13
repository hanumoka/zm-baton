// Package zmdocs reads the files of a zm-docs checkout that the server's trial import takes:
// registry/projects.json, registry/tasks.json and the decision records. It reads them from a
// commit, not from the working folder, so what another tool is still editing never goes in and
// the server can record which commit each copy came from.
package zmdocs

import (
	"bufio"
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"regexp"
	"strconv"
	"strings"
)

// The paths the import takes, from the repository root.
const (
	ProjectsPath  = "registry/projects.json"
	TasksPath     = "registry/tasks.json"
	DecisionsPath = "docs/governance/decisions"
)

var decisionFile = regexp.MustCompile(`^docs/governance/decisions/DEC-[0-9]{4,}-[^/]+\.md$`)

// File is one file as the server's import API takes it.
type File struct {
	Path    string `json:"path"`
	Content string `json:"content"`
}

// Snapshot is what one import sends: the files of one commit of one repository.
type Snapshot struct {
	Commit string
	Files  []File
}

// Read reads the import's files from ref (for example HEAD) of the repository that contains dir.
func Read(ctx context.Context, dir, ref string) (*Snapshot, error) {
	if ref == "" {
		ref = "HEAD"
	}
	if strings.HasPrefix(ref, "-") {
		return nil, fmt.Errorf("ref %q is not a revision", ref)
	}
	commit, err := git(ctx, dir, nil, "rev-parse", "--verify", "--end-of-options", ref+"^{commit}")
	if err != nil {
		return nil, err
	}
	commit = strings.TrimSpace(commit)
	listing, err := git(ctx, dir, nil, "ls-tree", "-r", "-z", "--full-tree", "--name-only", commit, "--", ProjectsPath, TasksPath, DecisionsPath)
	if err != nil {
		return nil, err
	}
	var paths []string
	for _, p := range strings.Split(listing, "\x00") {
		if p == ProjectsPath || p == TasksPath || decisionFile.MatchString(p) {
			paths = append(paths, p)
		}
	}
	if len(paths) == 0 {
		return nil, fmt.Errorf("commit %s has no %s, %s or decision records: is this a zm-docs checkout?", short(commit), TasksPath, ProjectsPath)
	}
	contents, err := catFiles(ctx, dir, commit, paths)
	if err != nil {
		return nil, err
	}
	snap := &Snapshot{Commit: commit}
	for i, p := range paths {
		snap.Files = append(snap.Files, File{Path: p, Content: contents[i]})
	}
	return snap, nil
}

// catFiles reads every path of commit with one `git cat-file --batch`.
func catFiles(ctx context.Context, dir, commit string, paths []string) ([]string, error) {
	var in bytes.Buffer
	for _, p := range paths {
		in.WriteString(commit + ":" + p + "\n")
	}
	raw, err := git(ctx, dir, &in, "cat-file", "--batch")
	if err != nil {
		return nil, err
	}
	r := bufio.NewReader(strings.NewReader(raw))
	out := make([]string, 0, len(paths))
	for _, p := range paths {
		header, err := r.ReadString('\n')
		if err != nil {
			return nil, fmt.Errorf("read %s: %w", p, err)
		}
		fields := strings.Fields(header)
		if len(fields) != 3 || fields[1] != "blob" {
			return nil, fmt.Errorf("read %s: unexpected answer %q", p, strings.TrimSpace(header))
		}
		size, err := strconv.Atoi(fields[2])
		if err != nil {
			return nil, fmt.Errorf("read %s: %w", p, err)
		}
		body := make([]byte, size+1) // the content and the newline after it
		if _, err := io.ReadFull(r, body); err != nil {
			return nil, fmt.Errorf("read %s: %w", p, err)
		}
		out = append(out, string(body[:size]))
	}
	return out, nil
}

var errNoGit = errors.New("git is not available")

// git runs one read-only git command in dir. GIT_OPTIONAL_LOCKS=0 keeps it from taking the
// index lock another tool working in the same folder might need.
func git(ctx context.Context, dir string, stdin io.Reader, args ...string) (string, error) {
	path, err := exec.LookPath("git")
	if err != nil {
		return "", errNoGit
	}
	cmd := exec.CommandContext(ctx, path, append([]string{"-C", dir}, args...)...)
	cmd.Env = append(os.Environ(), "GIT_OPTIONAL_LOCKS=0", "GIT_TERMINAL_PROMPT=0")
	cmd.Stdin = stdin
	var out, errOut bytes.Buffer
	cmd.Stdout = &out
	cmd.Stderr = &errOut
	if err := cmd.Run(); err != nil {
		return "", errors.New("git " + args[0] + ": " + strings.TrimSpace(errOut.String()))
	}
	return out.String(), nil
}

func short(commit string) string {
	if len(commit) > 10 {
		return commit[:10]
	}
	return commit
}
