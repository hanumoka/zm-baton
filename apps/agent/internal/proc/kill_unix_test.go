//go:build !windows

package proc

import (
	"errors"
	"os"
	"os/exec"
	"testing"
	"time"
)

// Compiled for Linux and macOS; on the Windows test PC only `go vet` checks it.
func TestKillChecksTheMarkerBeforeStopping(t *testing.T) {
	if _, err := os.Stat("/proc/self/cmdline"); err != nil {
		t.Skip("no /proc: Kill always refuses here")
	}
	marker := "zmb-proc-test-0123456789abcdef"
	// "; :" keeps the shell from replacing itself with sleep, so the marker stays visible.
	cmd := exec.Command("sh", "-c", "sleep 30; :", marker)
	if err := cmd.Start(); err != nil {
		t.Skip("sleep is not available:", err)
	}
	run, err := Track(cmd.Process)
	if err != nil {
		t.Fatal(err)
	}
	defer run.Close()
	waited := make(chan error, 1)
	go func() { waited <- cmd.Wait() }()

	if err := run.Kill("zmb-proc-test-ffffffffffffffff"); !errors.Is(err, ErrMarkerMismatch) {
		t.Fatalf("wrong marker: %v", err)
	}
	if err := run.Kill(marker); err != nil {
		t.Fatalf("right marker: %v", err)
	}
	select {
	case <-waited:
	case <-time.After(10 * time.Second):
		t.Fatal("process still running after Kill")
	}
}

func TestTrackRefusesInvalidProcesses(t *testing.T) {
	if _, err := Track(nil); err == nil {
		t.Fatal("Track(nil) returned nil")
	}
}
