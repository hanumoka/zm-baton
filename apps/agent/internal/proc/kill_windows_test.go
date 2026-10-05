//go:build windows

package proc

import (
	"bufio"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"strconv"
	"strings"
	"syscall"
	"testing"
	"time"
)

// The only processes these tests start or stop are copies of this test binary,
// launched below as helpers with a random marker on their command line.
const helperEnv = "ZMB_PROC_TEST_HELPER"

func TestMain(m *testing.M) {
	switch mode := os.Getenv(helperEnv); mode {
	case "parent", "parent-exits":
		// Start one child (it inherits the job), report its PID, then wait or exit.
		child := exec.Command(os.Args[0], os.Args[1:]...)
		child.Env = append(os.Environ(), helperEnv+"=child")
		if err := child.Start(); err != nil {
			fmt.Fprintln(os.Stderr, err)
			os.Exit(3)
		}
		fmt.Println(child.Process.Pid)
		if mode == "parent-exits" {
			os.Exit(0)
		}
		time.Sleep(60 * time.Second)
		os.Exit(0)
	case "child":
		time.Sleep(60 * time.Second)
		os.Exit(0)
	}
	os.Exit(m.Run())
}

func newMarker(t *testing.T) string {
	t.Helper()
	b := make([]byte, 16)
	rand.Read(b)
	return "zmb-proc-test-" + hex.EncodeToString(b)
}

type helper struct {
	cmd   *exec.Cmd
	run   *Run
	child syscall.Handle // pinned, so its state can be read without a PID lookup
}

// startHelper starts a tracked helper parent carrying marker, and pins the child
// it starts. Cleanup ends both through the job and the pinned handle only.
func startHelper(t *testing.T, mode, marker string) *helper {
	t.Helper()
	cmd := exec.Command(os.Args[0], "zmb-helper", marker)
	cmd.Env = append(os.Environ(), helperEnv+"="+mode)
	cmd.SysProcAttr = SysProcAttr()
	out, err := cmd.StdoutPipe()
	if err != nil {
		t.Fatal(err)
	}
	if err := cmd.Start(); err != nil {
		t.Fatal(err)
	}
	run, err := Track(cmd.Process)
	if err != nil {
		_ = cmd.Process.Kill()
		_ = cmd.Wait()
		t.Fatal(err)
	}
	h := &helper{cmd: cmd, run: run}
	t.Cleanup(func() {
		procTerminateJobObject.Call(run.job, 1)
		if h.child != 0 {
			syscall.TerminateProcess(h.child, 1)
			syscall.CloseHandle(h.child)
		}
		run.Close()
		if cmd.ProcessState == nil {
			_ = cmd.Wait()
		}
	})
	line, err := bufio.NewReader(out).ReadString('\n')
	childPID, convErr := strconv.Atoi(strings.TrimSpace(line))
	if err != nil || convErr != nil {
		t.Fatalf("helper did not report its child: %q %v %v", line, err, convErr)
	}
	// The child sleeps for a minute, so it is still this child when the handle opens.
	h.child, err = syscall.OpenProcess(synchronize|processTerminate|processQueryLimitedInformation, false, uint32(childPID))
	if err != nil {
		t.Fatalf("pin child %d: %v", childPID, err)
	}
	return h
}

// exitedWithin waits on a pinned handle. A failed wait is an error, never an exit.
func exitedWithin(t *testing.T, h syscall.Handle, d time.Duration) bool {
	t.Helper()
	ev, err := syscall.WaitForSingleObject(h, uint32(d.Milliseconds()))
	switch ev {
	case syscall.WAIT_OBJECT_0:
		return true
	case syscall.WAIT_TIMEOUT:
		return false
	}
	t.Fatalf("waiting on a process handle failed: %d %v", ev, err)
	return false
}

func TestKillRefusesWithoutAMatchingMarker(t *testing.T) {
	marker := newMarker(t)
	h := startHelper(t, "parent", marker)
	if got, _ := CommandLine(h.cmd.Process.Pid); !strings.Contains(got, marker) {
		t.Fatalf("helper command line %q does not carry the marker", got)
	}
	cases := map[string]string{
		"empty":      "",
		"blank":      "                    ",
		"too short":  marker[:MinMarkerLen-1],
		"mismatched": newMarker(t),
	}
	for name, m := range cases {
		err := h.run.Kill(m)
		if err == nil {
			t.Errorf("%s marker: Kill returned nil", name)
		} else if name == "mismatched" && !errors.Is(err, ErrMarkerMismatch) {
			t.Errorf("mismatched marker: want ErrMarkerMismatch, got %v", err)
		}
	}
	if exitedWithin(t, h.run.process, 300*time.Millisecond) || exitedWithin(t, h.child, 0) {
		t.Fatal("a refused Kill stopped a helper")
	}
}

// The runner waits for the executor while a stop may be decided, so Wait runs
// before and during Kill here too.
func TestKillStopsTheExecutorAndItsChildrenWhileWaitRuns(t *testing.T) {
	marker := newMarker(t)
	h := startHelper(t, "parent", marker)
	waited := make(chan error, 1)
	go func() { waited <- h.cmd.Wait() }()

	if err := h.run.Kill(marker); err != nil {
		t.Fatalf("Kill with the right marker: %v", err)
	}
	select {
	case err := <-waited:
		if err == nil {
			t.Fatal("helper exited normally; it should have been stopped")
		}
	case <-time.After(15 * time.Second):
		t.Fatal("helper still running after Kill")
	}
	if !exitedWithin(t, h.child, 15*time.Second) {
		t.Fatal("helper child still running after Kill")
	}
}

// Once the executor has exited and Wait has returned, a stop must not act: there
// is no command line left to check, and the PID must never be looked up anew.
func TestKillAfterTheExecutorExitedStopsNothing(t *testing.T) {
	marker := newMarker(t)
	h := startHelper(t, "parent-exits", marker)
	if err := h.cmd.Wait(); err != nil {
		t.Fatalf("helper parent: %v", err)
	}
	if err := h.run.Kill(marker); err == nil {
		t.Fatal("Kill acted on an executor that had already exited")
	}
	if exitedWithin(t, h.child, 300*time.Millisecond) {
		t.Fatal("a refused Kill stopped the orphaned child")
	}
	h.run.Close()
	if err := h.run.Kill(marker); !errors.Is(err, errClosed) {
		t.Fatalf("Kill after Close: %v", err)
	}
}

func TestTrackRefusesInvalidProcesses(t *testing.T) {
	self, _ := os.FindProcess(os.Getpid())
	for name, p := range map[string]*os.Process{"nil": nil, "zero pid": {Pid: 0}, "self": self} {
		if _, err := Track(p); err == nil {
			t.Errorf("%s: Track returned nil", name)
		}
	}
}
