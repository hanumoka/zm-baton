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
	"testing"
	"time"
)

// The only processes these tests start or kill are copies of this test binary,
// launched below as helpers with a random marker on their command line.
const helperEnv = "ZMB_PROC_TEST_HELPER"

func TestMain(m *testing.M) {
	switch os.Getenv(helperEnv) {
	case "parent":
		// Start one child with the same marker, report its PID, then wait to be killed.
		child := exec.Command(os.Args[0], os.Args[1:]...)
		child.Env = append(os.Environ(), helperEnv+"=child")
		if err := child.Start(); err != nil {
			fmt.Fprintln(os.Stderr, err)
			os.Exit(3)
		}
		fmt.Println(child.Process.Pid)
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

// startHelper starts a helper parent (which starts a helper child) carrying marker
// on its command line. It returns the parent command and the child PID.
func startHelper(t *testing.T, marker string) (*exec.Cmd, int) {
	t.Helper()
	cmd := exec.Command(os.Args[0], "zmb-helper", marker)
	cmd.Env = append(os.Environ(), helperEnv+"=parent")
	out, err := cmd.StdoutPipe()
	if err != nil {
		t.Fatal(err)
	}
	if err := cmd.Start(); err != nil {
		t.Fatal(err)
	}
	line, err := bufio.NewReader(out).ReadString('\n')
	childPID, convErr := strconv.Atoi(strings.TrimSpace(line))
	t.Cleanup(func() {
		// Best effort, only on processes this test started: the parent through its
		// own handle, the child through KillRun with the same marker.
		if cmd.ProcessState == nil {
			_ = cmd.Process.Kill()
			_ = cmd.Wait()
		}
		if convErr == nil && strings.Contains(mustCommandLine(childPID), marker) {
			_ = KillRun(childPID, marker)
		}
	})
	if err != nil || convErr != nil {
		t.Fatalf("helper did not report its child: %q %v %v", line, err, convErr)
	}
	return cmd, childPID
}

func mustCommandLine(pid int) string {
	s, _ := CommandLine(pid)
	return s
}

func waitGone(t *testing.T, pid int, marker string) {
	t.Helper()
	deadline := time.Now().Add(15 * time.Second)
	for strings.Contains(mustCommandLine(pid), marker) {
		if time.Now().After(deadline) {
			t.Fatalf("pid %d with the marker is still running", pid)
		}
		time.Sleep(200 * time.Millisecond)
	}
}

func TestKillRunRefusesWithoutAMatchingMarker(t *testing.T) {
	marker := newMarker(t)
	cmd, childPID := startHelper(t, marker)
	pid := cmd.Process.Pid
	if got := mustCommandLine(pid); !strings.Contains(got, marker) {
		t.Fatalf("helper command line %q does not carry the marker", got)
	}

	cases := map[string]string{
		"empty":      "",
		"blank":      "                    ",
		"too short":  marker[:MinMarkerLen-1],
		"mismatched": newMarker(t),
	}
	for name, m := range cases {
		if err := KillRun(pid, m); err == nil {
			t.Errorf("%s marker: KillRun returned nil", name)
		} else if name == "mismatched" && !errors.Is(err, ErrMarkerMismatch) {
			t.Errorf("mismatched marker: want ErrMarkerMismatch, got %v", err)
		}
	}
	// The child carries the marker too, but asking about the parent with a
	// wrong marker must not have touched either of them.
	time.Sleep(300 * time.Millisecond)
	if !strings.Contains(mustCommandLine(pid), marker) {
		t.Fatal("helper parent died after a refused KillRun")
	}
	if !strings.Contains(mustCommandLine(childPID), marker) {
		t.Fatal("helper child died after a refused KillRun")
	}
}

func TestKillRunRefusesInvalidPIDs(t *testing.T) {
	marker := newMarker(t)
	for _, pid := range []int{0, -1, os.Getpid()} {
		if err := KillRun(pid, marker); err == nil {
			t.Errorf("KillRun(%d) returned nil", pid)
		}
	}
}

func TestKillRunKillsMarkedProcessAndChildren(t *testing.T) {
	marker := newMarker(t)
	cmd, childPID := startHelper(t, marker)
	pid := cmd.Process.Pid
	// The child prints nothing; wait until its command line is visible.
	deadline := time.Now().Add(10 * time.Second)
	for !strings.Contains(mustCommandLine(childPID), marker) {
		if time.Now().After(deadline) {
			t.Fatal("helper child never showed up")
		}
		time.Sleep(100 * time.Millisecond)
	}

	if err := KillRun(pid, marker); err != nil {
		t.Fatalf("KillRun on the marked helper: %v", err)
	}
	waited := make(chan error, 1)
	go func() { waited <- cmd.Wait() }()
	select {
	case err := <-waited:
		if err == nil {
			t.Fatal("helper exited normally; it should have been killed")
		}
	case <-time.After(15 * time.Second):
		t.Fatal("helper still running after KillRun")
	}
	waitGone(t, childPID, marker)
}
