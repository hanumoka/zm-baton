package main

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"testing"

	"github.com/hanumoka/zm-baton/apps/agent/internal/session"
)

// repoServer records what `zm-baton repo` sends.
type repoServer struct {
	mu     sync.Mutex
	bodies []string
	paths  []string
}

func newRepoServer(t *testing.T) (*repoServer, string) {
	t.Helper()
	s := &repoServer{}
	mux := http.NewServeMux()
	mux.HandleFunc("POST /api/devices/{id}/repositories", func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		s.mu.Lock()
		s.bodies = append(s.bodies, string(body))
		s.paths = append(s.paths, r.URL.String())
		s.mu.Unlock()
		io.WriteString(w, `{"device_id":"dev_cli","repo_key":"git:x","name":"demo"}`)
	})
	mux.HandleFunc("DELETE /api/devices/{id}/repositories", func(w http.ResponseWriter, r *http.Request) {
		s.mu.Lock()
		s.paths = append(s.paths, r.URL.String())
		s.mu.Unlock()
		io.WriteString(w, `{"removed":true}`)
	})
	srv := httptest.NewServer(mux)
	t.Cleanup(srv.Close)
	return s, srv.URL
}

// cliRepo makes a repository with one commit under the test's temp folder.
func cliRepo(t *testing.T) string {
	t.Helper()
	if _, err := exec.LookPath("git"); err != nil {
		t.Skip("git is not installed")
	}
	root := filepath.Join(t.TempDir(), "demo")
	if err := os.MkdirAll(root, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(root, "README.md"), []byte("demo\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	for _, args := range [][]string{{"init", "--quiet"}, {"add", "README.md"}, {"commit", "--quiet", "-m", "first"}} {
		cmd := exec.Command("git", append([]string{"-C", root, "-c", "init.defaultBranch=main"}, args...)...)
		cmd.Env = append(os.Environ(), "GIT_AUTHOR_NAME=t", "GIT_AUTHOR_EMAIL=t@example.com",
			"GIT_COMMITTER_NAME=t", "GIT_COMMITTER_EMAIL=t@example.com")
		if out, err := cmd.CombinedOutput(); err != nil {
			t.Fatalf("git %v: %v\n%s", args, err, out)
		}
	}
	return root
}

// loggedIn points the config folder at a temp folder logged in to server, and lets temp folders
// count as usable (every test folder is one).
func loggedIn(t *testing.T, server string) string {
	t.Helper()
	home := t.TempDir()
	t.Setenv("ZM_BATON_HOME", home)
	if err := session.SaveConfig(home, session.Config{Server: server, DeviceID: "dev_cli"}); err != nil {
		t.Fatal(err)
	}
	old := tempDirs
	tempDirs = func() []string { return nil }
	t.Cleanup(func() { tempDirs = old })
	return home
}

func TestRepoAddListRemoveKeepPathsOnTheDevice(t *testing.T) {
	srv, url := newRepoServer(t)
	home := loggedIn(t, url)
	root := cliRepo(t)
	var out bytes.Buffer
	if code := run([]string{"repo", "add", root}, nil, &out, &out); code != 0 {
		t.Fatalf("repo add exited %d: %s", code, out.String())
	}
	if len(srv.bodies) != 1 || !strings.Contains(srv.paths[0], "/api/devices/dev_cli/repositories") {
		t.Fatalf("server got %v %v", srv.paths, srv.bodies)
	}
	var sent map[string]string
	if err := json.Unmarshal([]byte(srv.bodies[0]), &sent); err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(sent["repo_key"], "git:") || sent["name"] != "demo" {
		t.Fatalf("sent %v", sent)
	}
	for _, p := range []string{root, filepath.ToSlash(root), filepath.Dir(root)} {
		if strings.Contains(srv.bodies[0], p) || strings.Contains(srv.bodies[0], strings.ReplaceAll(p, `\`, `\\`)) {
			t.Fatalf("the server got the local path %s: %s", p, srv.bodies[0])
		}
	}
	repos, err := session.LoadRepos(home)
	if err != nil || len(repos) != 1 {
		t.Fatalf("repos %v %v", repos, err)
	}
	wantTrees := filepath.Join(filepath.Dir(repos[0].Root), "demo-zm-baton-runs")
	if repos[0].Key != sent["repo_key"] || !strings.EqualFold(repos[0].Worktrees, wantTrees) {
		t.Fatalf("saved %+v, want worktrees %s", repos[0], wantTrees)
	}
	if info, err := os.Stat(repos[0].Worktrees); err != nil || !info.IsDir() {
		t.Fatalf("worktree folder: %v", err)
	}

	out.Reset()
	if code := run([]string{"repo", "list"}, nil, &out, &out); code != 0 || !strings.Contains(out.String(), "demo ("+sent["repo_key"]+")") {
		t.Fatalf("repo list exited %d: %s", code, out.String())
	}
	out.Reset()
	if code := run([]string{"repo", "remove", root}, nil, &out, &out); code != 0 {
		t.Fatalf("repo remove exited %d: %s", code, out.String())
	}
	if last := srv.paths[len(srv.paths)-1]; !strings.Contains(last, "repo_key=git%3A") {
		t.Fatalf("remove sent %s", last)
	}
	if repos, _ := session.LoadRepos(home); len(repos) != 0 {
		t.Fatalf("still saved: %v", repos)
	}
	if _, err := os.Stat(wantTrees); err != nil {
		t.Fatal("removing a repository must leave its worktree folder")
	}
}

func TestRepoAddRefusesFoldersItCannotUse(t *testing.T) {
	srv, url := newRepoServer(t)
	loggedIn(t, url)
	root := cliRepo(t)
	var out bytes.Buffer
	if code := run([]string{"repo", "add", root, "--worktrees", filepath.Join(root, "runs")}, nil, &out, &out); code == 0 {
		t.Fatal("a worktree folder inside the repository was accepted")
	}
	if code := run([]string{"repo", "add", t.TempDir()}, nil, &out, &out); code == 0 {
		t.Fatal("a folder outside any Git checkout was accepted")
	}
	tempDirs = func() []string { return []string{filepath.Dir(root)} }
	if code := run([]string{"repo", "add", root}, nil, &out, &out); code == 0 {
		t.Fatal("a repository under a temp folder was accepted")
	}
	if len(srv.bodies) != 0 {
		t.Fatalf("refused repositories reached the server: %v", srv.bodies)
	}
}

func TestRepoAndAgentNeedALogin(t *testing.T) {
	t.Setenv("ZM_BATON_HOME", t.TempDir())
	var out bytes.Buffer
	if code := run([]string{"repo", "add", "."}, nil, &out, &out); code != 1 || !strings.Contains(out.String(), "zm-baton login") {
		t.Fatalf("repo add without login exited %d: %s", code, out.String())
	}
	out.Reset()
	if code := run([]string{"agent", "--once"}, nil, &out, &out); code != 2 || !strings.Contains(out.String(), "zm-baton login") {
		t.Fatalf("agent without login exited %d: %s", code, out.String())
	}
	if code := run([]string{"agent", "--no-such-flag"}, nil, &out, &out); code != 2 {
		t.Fatalf("a bad flag exited %d", code)
	}
}
