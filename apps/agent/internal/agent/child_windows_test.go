//go:build windows

package agent

import (
	"syscall"
	"testing"
	"time"
)

// childGone reports whether the process pid has exited, waiting up to ten seconds. A process that
// cannot be opened any more is gone.
func childGone(t *testing.T, pid int) bool {
	t.Helper()
	const synchronize = 0x00100000
	h, err := syscall.OpenProcess(synchronize, false, uint32(pid))
	if err != nil {
		return true
	}
	defer syscall.CloseHandle(h)
	ev, _ := syscall.WaitForSingleObject(h, uint32((10 * time.Second).Milliseconds()))
	return ev == syscall.WAIT_OBJECT_0
}
