package main

import (
	"bytes"
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

func TestHooksInstallWritesOnlyItsOwnPluginAndShowsClaudeCommands(t *testing.T) {
	home := t.TempDir()
	t.Setenv("ZM_BATON_HOME", home)
	project := t.TempDir()
	var out bytes.Buffer
	if code := run([]string{"hooks", "install", "--project", project}, nil, &out, &out); code != 0 {
		t.Fatalf("install exited %d: %s", code, out.String())
	}
	root := filepath.Join(home, "claude-plugin")
	for _, rel := range []string{".claude-plugin/marketplace.json", "zm-baton/.claude-plugin/plugin.json", "zm-baton/hooks/hooks.json"} {
		if _, err := os.Stat(filepath.Join(root, filepath.FromSlash(rel))); err != nil {
			t.Errorf("plugin file %s: %v", rel, err)
		}
	}
	if _, err := os.Stat(filepath.Join(project, ".claude")); !os.IsNotExist(err) {
		t.Errorf("zm-baton touched the project's Claude Code settings: %v", err)
	}
	for _, want := range []string{"claude plugin marketplace add " + psQuote(root), "cd " + psQuote(project), "claude plugin install zm-baton@zm-baton-local --scope local"} {
		if !strings.Contains(out.String(), want) {
			t.Errorf("output lacks %q:\n%s", want, out.String())
		}
	}
	out.Reset()
	if code := run([]string{"hooks", "install"}, nil, &out, &out); code != 0 || !strings.Contains(out.String(), "--scope user") {
		t.Errorf("user install exited %d:\n%s", code, out.String())
	}
	out.Reset()
	if code := run([]string{"hooks", "uninstall", "--project", project}, nil, &out, &out); code != 0 ||
		!strings.Contains(out.String(), "claude plugin uninstall zm-baton@zm-baton-local --scope local") {
		t.Errorf("uninstall exited %d:\n%s", code, out.String())
	}
	if _, err := os.Stat(root); err != nil {
		t.Errorf("uninstall must leave the plugin for Claude Code to remove first: %v", err)
	}
}

func TestPrintedPathsReachPowerShellUnchanged(t *testing.T) {
	if got := psQuote(`C:\work\it's`); got != `'C:\work\it''s'` {
		t.Fatalf("psQuote = %s", got)
	}
	pwsh, err := exec.LookPath("pwsh")
	if err != nil {
		t.Skip("pwsh is not installed")
	}
	// Each PowerShell single-quote character tries to close the string early and run a command.
	cases := []string{`C:\work\it's $(Remove-Item x) ` + "`" + `n "quoted"`}
	for _, q := range psSingleQuotes {
		cases = append(cases, `C:\work\a`+q+`; Write-Output INJECTED; `+q+`b`)
	}
	for _, odd := range cases {
		cmd := "[Console]::OutputEncoding = [Text.Encoding]::UTF8; Write-Output " + psQuote(odd)
		out, err := exec.Command(pwsh, "-NoProfile", "-NonInteractive", "-Command", cmd).Output()
		if err != nil {
			t.Fatalf("%q: %v", odd, err)
		}
		if got := strings.TrimRight(string(out), "\r\n"); got != odd {
			t.Errorf("PowerShell read %q, want %q", got, odd)
		}
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
