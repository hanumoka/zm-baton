package main

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"

	"github.com/hanumoka/zm-baton/apps/agent/internal/session"
)

// zmDocsRepo makes a tiny committed zm-docs checkout.
func zmDocsRepo(t *testing.T) string {
	t.Helper()
	if _, err := exec.LookPath("git"); err != nil {
		t.Skip("git is not installed")
	}
	dir := t.TempDir()
	git := func(args ...string) {
		t.Helper()
		cmd := exec.Command("git", append([]string{"-C", dir, "-c", "user.name=t", "-c", "user.email=t@example.invalid"}, args...)...)
		if out, err := cmd.CombinedOutput(); err != nil {
			t.Fatalf("git %v: %v\n%s", args, err, out)
		}
	}
	git("init", "-q", "-b", "main")
	if err := os.MkdirAll(filepath.Join(dir, "registry"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "registry", "tasks.json"), []byte(`{"tasks":[]}`), 0o644); err != nil {
		t.Fatal(err)
	}
	git("add", "-A")
	git("commit", "-q", "-m", "first")
	return dir
}

func TestImportZmDocsSendsTheCommitAndPrintsTheReport(t *testing.T) {
	var got map[string]any
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/imports/zm-docs" || r.Method != http.MethodPost {
			http.NotFound(w, r)
			return
		}
		_ = json.NewDecoder(r.Body).Decode(&got)
		apply, _ := got["apply"].(bool)
		_ = json.NewEncoder(w).Encode(map[string]any{
			"applied":    apply,
			"projects":   map[string]int{"created": 1},
			"work_items": map[string]int{"created": 2, "updated": 1},
			"decisions":  map[string]int{"unchanged": 3},
			"changes": []map[string]any{
				{"kind": "work_item", "ref": "TSK-001", "action": "updated", "fields": []string{"stage", "next_action"}},
				{"kind": "work_item", "ref": "TSK-002", "action": "skipped", "note": "정본이 zm-baton으로 넘어온 작업"},
			},
			"warnings": []string{"DEC-0009: 프로젝트 PRJ-999 이(가) 없다"},
		})
	}))
	defer server.Close()
	home := t.TempDir()
	t.Setenv("ZM_BATON_HOME", home)
	if err := session.SaveConfig(home, session.Config{Server: server.URL, DeviceID: "dev_test"}); err != nil {
		t.Fatal(err)
	}
	repo := zmDocsRepo(t)

	var out bytes.Buffer
	if code := run([]string{"import", "zm-docs", "--repo", repo}, nil, &out, &out); code != 0 {
		t.Fatalf("import exited %d: %s", code, out.String())
	}
	if got["apply"] != false || !strings.HasPrefix(got["repo_key"].(string), "git:") || len(got["commit"].(string)) != 40 {
		t.Errorf("request %v", got)
	}
	files := got["files"].([]any)
	if len(files) != 1 || files[0].(map[string]any)["path"] != "registry/tasks.json" {
		t.Errorf("files %v", files)
	}
	for _, want := range []string{
		"미리 보기", "작업: 새로 2 · 바뀜 1 · 그대로 0 · 건너뜀 0", "결정 기록: 새로 0 · 바뀜 0 · 그대로 3",
		"작업 TSK-001 바뀜 (stage, next_action)", "작업 TSK-002 건너뜀: 정본이 zm-baton으로 넘어온 작업",
		"경고 1건:", "--apply를 붙여",
	} {
		if !strings.Contains(out.String(), want) {
			t.Errorf("output lacks %q:\n%s", want, out.String())
		}
	}

	out.Reset()
	if code := run([]string{"import", "zm-docs", "--repo", repo, "--apply"}, nil, &out, &out); code != 0 {
		t.Fatalf("apply exited %d: %s", code, out.String())
	}
	if got["apply"] != true || !strings.Contains(out.String(), "zm-docs를 가져왔다.") || strings.Contains(out.String(), "--apply를 붙여") {
		t.Errorf("apply: request %v\n%s", got["apply"], out.String())
	}
}

func TestImportShowsTheServersReasonWhenRefused(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusBadRequest)
		_, _ = w.Write([]byte(`{"status":400,"message":"repo_key is missing\nor not a key"}`))
	}))
	defer server.Close()
	home := t.TempDir()
	t.Setenv("ZM_BATON_HOME", home)
	if err := session.SaveConfig(home, session.Config{Server: server.URL, DeviceID: "dev_test"}); err != nil {
		t.Fatal(err)
	}
	var out bytes.Buffer
	if code := run([]string{"import", "zm-docs", "--repo", zmDocsRepo(t)}, nil, &out, &out); code != 1 {
		t.Fatalf("exited %d", code)
	}
	if !strings.Contains(out.String(), "HTTP 400: repo_key is missing or not a key") {
		t.Errorf("output %q", out.String())
	}
}

func TestImportNeedsLoginAndTheZmDocsArgument(t *testing.T) {
	t.Setenv("ZM_BATON_HOME", t.TempDir())
	var out bytes.Buffer
	if code := run([]string{"import"}, nil, &out, &out); code != 1 {
		t.Errorf("no target exited %d", code)
	}
	out.Reset()
	if code := run([]string{"import", "zm-docs"}, nil, &out, &out); code != 1 || !strings.Contains(out.String(), "zm-baton login") {
		t.Errorf("without login exited %d: %s", code, out.String())
	}
}
