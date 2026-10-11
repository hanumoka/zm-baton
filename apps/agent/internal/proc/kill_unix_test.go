//go:build !windows

package proc

import (
	"errors"
	"os"
	"os/exec"
	"strconv"
	"syscall"
	"testing"
	"time"
)

// Compiled for Linux and macOS; on the Windows test PC only `go vet` checks it.

// TestHelperSleep is not a test: startHelper runs this test binary again with
// ZMB_PROC_HELPER=1, and it just sleeps. The process under test then has no
// children, so Kill leaves nothing running behind it.
func TestHelperSleep(t *testing.T) {
	if os.Getenv("ZMB_PROC_HELPER") != "1" {
		return
	}
	time.Sleep(30 * time.Second)
	os.Exit(0)
}

// startHelper starts a child-free process whose command line carries marker.
func startHelper(t *testing.T, marker string) *exec.Cmd {
	t.Helper()
	cmd := exec.Command(os.Args[0], "-test.run=^TestHelperSleep$", "--", marker)
	cmd.Env = append(os.Environ(), "ZMB_PROC_HELPER=1")
	if err := cmd.Start(); err != nil {
		t.Fatalf("start the helper process: %v", err)
	}
	return cmd
}

// waitKilled reaps cmd and fails unless it ended by SIGKILL within 10 s.
func waitKilled(t *testing.T, cmd *exec.Cmd, label string) {
	t.Helper()
	done := make(chan error, 1)
	go func() { done <- cmd.Wait() }()
	select {
	case <-done:
	case <-time.After(10 * time.Second):
		_ = cmd.Process.Kill()
		<-done
		t.Fatalf("%s: process still running 10s after Kill", label)
	}
	ws, ok := cmd.ProcessState.Sys().(syscall.WaitStatus)
	if !ok || !ws.Signaled() || ws.Signal() != syscall.SIGKILL {
		t.Fatalf("%s: process did not end by SIGKILL: %v", label, cmd.ProcessState)
	}
}

func skipWithoutProc(t *testing.T) {
	t.Helper()
	if _, err := os.Stat("/proc/self/cmdline"); err != nil {
		t.Skip("no /proc: Kill always refuses here")
	}
}

func TestKillChecksTheMarkerBeforeStopping(t *testing.T) {
	skipWithoutProc(t)
	marker := "zmb-proc-test-0123456789abcdef"
	cmd := startHelper(t, marker)
	run, err := Track(cmd.Process)
	if err != nil {
		_ = cmd.Process.Kill()
		_ = cmd.Wait()
		t.Fatal(err)
	}
	defer run.Close()

	if err := run.Kill("zmb-proc-test-ffffffffffffffff"); !errors.Is(err, ErrMarkerMismatch) {
		_ = cmd.Process.Kill()
		_ = cmd.Wait()
		t.Fatalf("wrong marker: %v", err)
	}
	if err := run.Kill(marker); err != nil {
		_ = cmd.Process.Kill()
		_ = cmd.Wait()
		t.Fatalf("right marker: %v", err)
	}
	waitKilled(t, cmd, "right marker")
}

// Kill right after Start used to be refused when it raced execve, because the new
// process's command line is briefly empty. Each start here is killed at once.
func TestKillRightAfterStartStopsTheProcess(t *testing.T) {
	skipWithoutProc(t)
	marker := "zmb-proc-race-0123456789abcdef"
	for i := 0; i < 200; i++ {
		cmd := startHelper(t, marker)
		run, err := Track(cmd.Process)
		if err != nil {
			_ = cmd.Process.Kill()
			_ = cmd.Wait()
			t.Fatalf("start %d: %v", i, err)
		}
		killErr := run.Kill(marker)
		run.Close()
		if killErr != nil {
			_ = cmd.Process.Kill()
			_ = cmd.Wait()
			t.Fatalf("start %d: Kill right after Start: %v", i, killErr)
		}
		waitKilled(t, cmd, "start "+strconv.Itoa(i))
	}
}

func TestTrackRefusesInvalidProcesses(t *testing.T) {
	if _, err := Track(nil); err == nil {
		t.Fatal("Track(nil) returned nil")
	}
}
