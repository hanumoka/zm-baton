// Package proc stops an executor process only after proving it is the one this
// agent started (contract v1 "프로세스 관리").
//
// Processes are never chosen by image name, list order or a bare PID. Track pins
// the process right after it starts, and Run.Kill stops it only through that pin
// and only while its command line still carries the run's marker (the session id).
// A PID that was checked and then reused by another process can never be reached.
package proc

import (
	"errors"
	"fmt"
	"strings"
)

// MinMarkerLen rejects markers short enough to match by accident. A UUID is 36.
const MinMarkerLen = 16

// ErrMarkerMismatch means the executor's command line does not carry the marker.
var ErrMarkerMismatch = errors.New("command line does not contain the run marker")

// errClosed means Kill was called after Close released the pin.
var errClosed = errors.New("refusing to kill: the executor is no longer tracked")

func checkMarker(marker string) error {
	if strings.TrimSpace(marker) == "" {
		return errors.New("refusing to kill: empty marker")
	}
	if len(marker) < MinMarkerLen {
		return fmt.Errorf("refusing to kill: marker shorter than %d characters", MinMarkerLen)
	}
	return nil
}
