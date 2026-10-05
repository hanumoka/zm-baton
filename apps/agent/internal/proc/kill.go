// Package proc stops an executor process only after proving it is the one this
// agent started (contract v1 "프로세스 관리").
//
// Processes are never chosen by image name or list order. The caller passes the
// PID it recorded at start and the marker it put on that command line (the run's
// session id). Nothing is killed unless the live command line contains the marker.
package proc

import (
	"errors"
	"fmt"
	"os"
	"strings"
)

// MinMarkerLen rejects markers short enough to match by accident. A UUID is 36.
const MinMarkerLen = 16

// ErrMarkerMismatch means the PID's command line does not carry the marker.
var ErrMarkerMismatch = errors.New("command line does not contain the run marker")

// KillRun kills pid and its child processes, but only if the command line of pid
// contains marker. It refuses, killing nothing, when the marker is empty or too
// short, when pid is not a valid child candidate, when the command line cannot be
// read, or when the marker is not in it.
func KillRun(pid int, marker string) error {
	if strings.TrimSpace(marker) == "" {
		return errors.New("refusing to kill: empty marker")
	}
	if len(marker) < MinMarkerLen {
		return fmt.Errorf("refusing to kill: marker shorter than %d characters", MinMarkerLen)
	}
	if pid <= 0 || pid == os.Getpid() {
		return fmt.Errorf("refusing to kill: invalid pid %d", pid)
	}
	cmdline, err := CommandLine(pid)
	if err != nil {
		return fmt.Errorf("refusing to kill pid %d: cannot read its command line: %w", pid, err)
	}
	if !strings.Contains(cmdline, marker) {
		return fmt.Errorf("refusing to kill pid %d: %w", pid, ErrMarkerMismatch)
	}
	return killTree(pid)
}
