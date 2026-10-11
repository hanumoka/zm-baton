package main

import (
	"bytes"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestHookAlwaysExitsZero(t *testing.T) {
	t.Setenv("ZM_BATON_HOME", t.TempDir())
	var out bytes.Buffer
	for _, args := range [][]string{{"hook"}, {"hook", "bogus"}, {"hook", "session-start"}} {
		if code := run(args, strings.NewReader("not json"), &out, &out); code != 0 {
			t.Errorf("%v exited %d", args, code)
		}
	}
	if out.Len() != 0 {
		t.Errorf("a failing hook printed %q", out.String())
	}
}

func TestUsageAndUnknownCommands(t *testing.T) {
	var out bytes.Buffer
	if code := run(nil, nil, &out, &out); code != 2 {
		t.Errorf("no args exited %d", code)
	}
	if code := run([]string{"frobnicate"}, nil, &out, &out); code != 2 {
		t.Errorf("unknown command exited %d", code)
	}
}

func TestHooksInstallAndUninstallInAProject(t *testing.T) {
	project := t.TempDir()
	settings := filepath.Join(project, ".claude", "settings.local.json")
	if err := os.MkdirAll(filepath.Dir(settings), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(settings, []byte(`{"model":"opus"}`), 0o600); err != nil {
		t.Fatal(err)
	}
	var out bytes.Buffer
	if code := run([]string{"hooks", "install", "--project", project}, strings.NewReader("n\n"), &out, &out); code != 0 {
		t.Fatalf("install exited %d: %s", code, out.String())
	}
	if data, _ := os.ReadFile(settings); strings.Contains(string(data), "hook session-start") {
		t.Fatal("answering n must write nothing")
	}
	out.Reset()
	if code := run([]string{"hooks", "install", "--project", project, "--yes"}, nil, &out, &out); code != 0 {
		t.Fatalf("install exited %d: %s", code, out.String())
	}
	data, _ := os.ReadFile(settings)
	if !strings.Contains(string(data), "hook session-start") || !strings.Contains(string(data), `"opus"`) {
		t.Fatalf("settings after install:\n%s", data)
	}
	backups, _ := filepath.Glob(settings + ".zm-baton-backup-*")
	if len(backups) != 1 {
		t.Errorf("backups %v", backups)
	}
	out.Reset()
	if code := run([]string{"hooks", "uninstall", "--project", project, "--yes"}, nil, &out, &out); code != 0 {
		t.Fatalf("uninstall exited %d: %s", code, out.String())
	}
	data, _ = os.ReadFile(settings)
	if strings.Contains(string(data), "zm-baton") || !strings.Contains(string(data), `"opus"`) {
		t.Fatalf("settings after uninstall:\n%s", data)
	}
}

func TestSessionCommandsNeedARegisteredSession(t *testing.T) {
	t.Setenv("ZM_BATON_HOME", t.TempDir())
	t.Setenv("CLAUDE_CODE_SESSION_ID", "")
	var out bytes.Buffer
	if code := run([]string{"session", "work", "x"}, nil, &out, &out); code != 1 {
		t.Errorf("without a session id exited %d", code)
	}
	out.Reset()
	if code := run([]string{"session", "work", "--session", "nope", "x"}, nil, &out, &out); code != 1 || !strings.Contains(out.String(), "not registered") {
		t.Errorf("unregistered session: %d %s", code, out.String())
	}
}
