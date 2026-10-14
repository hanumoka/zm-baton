package main

import (
	"bytes"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/hanumoka/zm-baton/apps/agent/internal/session"
)

const sampleBundle = `{"format":"zm-baton.export","format_version":1,
 "provenance":{"exported_at":"2026-10-14T00:00:00Z","server":{"version":"0.0.1-SNAPSHOT","commit":"abc1234"},
  "database":{"migration_version":"3"}},
 "collections":{"work_items":{"count":2,"records":[{"id":"wrk_1"},{"id":"wrk_2"}]},"audit_log":{"count":1,"records":[{"id":"aud_1"}]}}}`

func exportServer(t *testing.T, status int, body string) *httptest.Server {
	t.Helper()
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/export" || r.Method != http.MethodGet {
			http.NotFound(w, r)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(status)
		_, _ = w.Write([]byte(body))
	}))
	t.Cleanup(server.Close)
	return server
}

func TestExportWritesTheBundleToANewFileAndPrintsItsProvenance(t *testing.T) {
	server := exportServer(t, http.StatusOK, sampleBundle)
	home := t.TempDir()
	t.Setenv("ZM_BATON_HOME", home)
	if err := session.SaveConfig(home, session.Config{Server: server.URL, DeviceID: "dev_test"}); err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(t.TempDir(), "export.json")
	var out bytes.Buffer
	if code := run([]string{"export", "--out", path}, nil, &out, &out); code != 0 {
		t.Fatalf("export exited %d: %s", code, out.String())
	}
	got, err := os.ReadFile(path)
	if err != nil || string(got) != sampleBundle {
		t.Fatalf("file holds %q (%v)", got, err)
	}
	for _, want := range []string{"내보냈다: " + path, "데이터베이스 마이그레이션 판: 3", "abc1234", "작업 2", "감사 기록 1", "프로젝트 0"} {
		if !strings.Contains(out.String(), want) {
			t.Errorf("output lacks %q:\n%s", want, out.String())
		}
	}
}

func TestExportRefusesAnExistingFileAndLeavesItAlone(t *testing.T) {
	server := exportServer(t, http.StatusOK, sampleBundle)
	path := filepath.Join(t.TempDir(), "keep.json")
	if err := os.WriteFile(path, []byte("a person's file"), 0o644); err != nil {
		t.Fatal(err)
	}
	var out bytes.Buffer
	if code := run([]string{"export", "--server", server.URL, "--out", path}, nil, &out, &out); code == 0 {
		t.Fatalf("export over an existing file succeeded: %s", out.String())
	}
	if got, _ := os.ReadFile(path); string(got) != "a person's file" {
		t.Errorf("the existing file changed to %q", got)
	}
	if !strings.Contains(out.String(), "already exists") {
		t.Errorf("output: %s", out.String())
	}
}

func TestExportRemovesItsFileWhenTheServerRefusesOrSendsNoBundle(t *testing.T) {
	for _, tc := range []struct {
		name   string
		status int
		body   string
		want   string
	}{
		{"refused", http.StatusForbidden, `{"message":"export is a person action"}`, "HTTP 403: export is a person action"},
		{"not a bundle", http.StatusOK, `<html>proxy page</html>`, "not a JSON bundle"},
		{"another format", http.StatusOK, `{"format":"something-else"}`, `format "something-else"`},
	} {
		t.Run(tc.name, func(t *testing.T) {
			server := exportServer(t, tc.status, tc.body)
			path := filepath.Join(t.TempDir(), "export.json")
			var out bytes.Buffer
			if code := run([]string{"export", "--server", server.URL, "--out", path}, nil, &out, &out); code == 0 {
				t.Fatalf("export succeeded: %s", out.String())
			}
			if !strings.Contains(out.String(), tc.want) {
				t.Errorf("output lacks %q: %s", tc.want, out.String())
			}
			if _, err := os.Stat(path); !os.IsNotExist(err) {
				t.Errorf("the partial file was left: %v", err)
			}
		})
	}
}

func TestExportNeedsOut(t *testing.T) {
	var out bytes.Buffer
	if code := run([]string{"export"}, nil, &out, &out); code == 0 || !strings.Contains(out.String(), "--out FILE") {
		t.Errorf("exited %d: %s", code, out.String())
	}
}
