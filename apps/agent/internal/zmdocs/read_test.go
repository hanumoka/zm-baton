package zmdocs

import (
	"context"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
)

// repo makes a Git repository with the given files committed and returns its folder.
func repo(t *testing.T, files map[string]string) (dir string, git func(args ...string) string) {
	t.Helper()
	if _, err := exec.LookPath("git"); err != nil {
		t.Skip("git is not installed")
	}
	dir = t.TempDir()
	git = func(args ...string) string {
		t.Helper()
		cmd := exec.Command("git", append([]string{"-C", dir, "-c", "user.name=t", "-c", "user.email=t@example.invalid"}, args...)...)
		out, err := cmd.CombinedOutput()
		if err != nil {
			t.Fatalf("git %v: %v\n%s", args, err, out)
		}
		return strings.TrimSpace(string(out))
	}
	git("init", "-q", "-b", "main")
	write(t, dir, files)
	git("add", "-A")
	git("commit", "-q", "-m", "first")
	return dir, git
}

func write(t *testing.T, dir string, files map[string]string) {
	t.Helper()
	for rel, content := range files {
		path := filepath.Join(dir, filepath.FromSlash(rel))
		if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(path, []byte(content), 0o644); err != nil {
			t.Fatal(err)
		}
	}
}

func TestReadTakesOnlyTheImportFilesOfTheCommitNotTheWorkingFolder(t *testing.T) {
	dir, git := repo(t, map[string]string{
		"registry/tasks.json":                         `{"tasks":[]}`,
		"registry/projects.json":                      `{"projects":[]}`,
		"registry/people.json":                        `{}`,
		"docs/governance/decisions/DEC-0001-first.md": "---\nid: DEC-0001\n---\n한글 본문\n",
		"docs/governance/decisions/README.md":         "# 인덱스",
		"docs/other.md":                               "x",
	})
	head := git("rev-parse", "HEAD")
	// Uncommitted work in the folder must not go in.
	write(t, dir, map[string]string{
		"registry/tasks.json":                         `{"tasks":["not committed"]}`,
		"docs/governance/decisions/DEC-0002-draft.md": "---\nid: DEC-0002\n---\n",
	})

	snap, err := Read(context.Background(), filepath.Join(dir, "docs"), "HEAD")
	if err != nil {
		t.Fatal(err)
	}
	if snap.Commit != head {
		t.Errorf("commit %s, want %s", snap.Commit, head)
	}
	got := map[string]string{}
	for _, f := range snap.Files {
		got[f.Path] = f.Content
	}
	want := map[string]string{
		"registry/tasks.json":                         `{"tasks":[]}`,
		"registry/projects.json":                      `{"projects":[]}`,
		"docs/governance/decisions/DEC-0001-first.md": "---\nid: DEC-0001\n---\n한글 본문\n",
	}
	if len(got) != len(want) {
		t.Fatalf("files %v, want %v", keys(got), keys(want))
	}
	for path, content := range want {
		if got[path] != content {
			t.Errorf("%s = %q, want %q", path, got[path], content)
		}
	}
}

func TestReadAnOlderRef(t *testing.T) {
	dir, git := repo(t, map[string]string{"registry/tasks.json": `{"v":1}`})
	first := git("rev-parse", "HEAD")
	write(t, dir, map[string]string{"registry/tasks.json": `{"v":2}`})
	git("commit", "-q", "-am", "second")
	snap, err := Read(context.Background(), dir, first)
	if err != nil {
		t.Fatal(err)
	}
	if snap.Commit != first || snap.Files[0].Content != `{"v":1}` {
		t.Errorf("read %s %q", snap.Commit, snap.Files[0].Content)
	}
}

func TestReadRefusesOptionsAndForeignRepositories(t *testing.T) {
	dir, _ := repo(t, map[string]string{"README.md": "not zm-docs"})
	if _, err := Read(context.Background(), dir, "HEAD"); err == nil || !strings.Contains(err.Error(), "zm-docs") {
		t.Errorf("a repository without zm-docs files: %v", err)
	}
	if _, err := Read(context.Background(), dir, "--output=x"); err == nil {
		t.Error("a ref that looks like an option was accepted")
	}
	if _, err := Read(context.Background(), dir, "no-such-branch"); err == nil {
		t.Error("an unknown ref was accepted")
	}
}

func keys(m map[string]string) []string {
	var out []string
	for k := range m {
		out = append(out, k)
	}
	return out
}
