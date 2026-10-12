package main

import (
	"bytes"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"
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

// editingReader stands for a person who answers y while another program changes the
// settings file: the change lands before the answer is read.
type editingReader struct {
	path, content string
	done          bool
}

func (r *editingReader) Read(p []byte) (int, error) {
	if r.done {
		return 0, io.EOF
	}
	r.done = true
	if err := os.WriteFile(r.path, []byte(r.content), 0o600); err != nil {
		return 0, err
	}
	return copy(p, "y\n"), nil
}

func TestAChangeMadeWhileWaitingForTheAnswerIsKept(t *testing.T) {
	project := t.TempDir()
	settings := filepath.Join(project, ".claude", "settings.local.json")
	if err := os.MkdirAll(filepath.Dir(settings), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(settings, []byte(`{"model":"opus"}`), 0o600); err != nil {
		t.Fatal(err)
	}
	changed := `{"model":"opus","permissions":{"deny":["Bash(rm:*)"]}}`
	var out bytes.Buffer
	in := &editingReader{path: settings, content: changed}
	if code := run([]string{"hooks", "install", "--project", project}, in, &out, &out); code != 1 {
		t.Fatalf("install exited %d: %s", code, out.String())
	}
	if data, _ := os.ReadFile(settings); string(data) != changed {
		t.Fatalf("the other program's change was lost:\n%s", data)
	}
	if backups, _ := filepath.Glob(settings + ".zm-baton-backup-*"); len(backups) != 0 {
		t.Errorf("backups %v for a write that did not happen", backups)
	}
	if left, _ := filepath.Glob(filepath.Join(filepath.Dir(settings), ".*zm-baton-*")); len(left) != 0 {
		t.Errorf("temporary files left: %v", left)
	}
}

func TestTwoInstallsInARowKeepBothBackups(t *testing.T) {
	project := t.TempDir()
	settings := filepath.Join(project, ".claude", "settings.local.json")
	if err := os.MkdirAll(filepath.Dir(settings), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(settings, []byte(`{"model":"opus"}`), 0o600); err != nil {
		t.Fatal(err)
	}
	var out bytes.Buffer
	for _, args := range [][]string{{"hooks", "install"}, {"hooks", "uninstall"}} {
		if code := run(append(args, "--project", project, "--yes"), nil, &out, &out); code != 0 {
			t.Fatalf("%v exited %d: %s", args, code, out.String())
		}
	}
	if backups, _ := filepath.Glob(settings + ".zm-baton-backup-*"); len(backups) != 2 {
		t.Errorf("backups %v", backups)
	}
}

// TestHookHelperProcess is not a test: it runs one hook as the real command does.
func TestHookHelperProcess(t *testing.T) {
	if os.Getenv("ZM_BATON_HOOK_HELPER") == "" {
		t.Skip("helper process only")
	}
	os.Exit(run([]string{"hook", "prompt-submit"}, os.Stdin, os.Stdout, os.Stderr))
}

func TestAHookWhoseInputNeverClosesEndsWithinItsLimit(t *testing.T) {
	cmd := exec.Command(os.Args[0], "-test.run=^TestHookHelperProcess$")
	// With -race the runtime sleeps a second before exiting unless told not to.
	cmd.Env = append(os.Environ(), "ZM_BATON_HOOK_HELPER=1", "ZM_BATON_HOME="+t.TempDir(), "GORACE=atexit_sleep_ms=0")
	stdin, err := cmd.StdinPipe()
	if err != nil {
		t.Fatal(err)
	}
	defer stdin.Close() // never written and not closed until the hook is over
	start := time.Now()
	if err := cmd.Start(); err != nil {
		t.Fatal(err)
	}
	done := make(chan error, 1)
	go func() { done <- cmd.Wait() }()
	select {
	case err := <-done:
		if err != nil {
			t.Errorf("hook exited with %v", err)
		}
		if d := time.Since(start); d > 4*time.Second {
			t.Errorf("a 3 second hook took %v", d)
		}
	case <-time.After(10 * time.Second):
		_ = cmd.Process.Kill()
		t.Fatal("the hook did not end")
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
