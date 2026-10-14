//go:build !windows

package proc

import (
	"errors"
	"fmt"
	"os"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
)

// Right after Start, /proc/<pid>/cmdline is briefly empty while the kernel finishes
// execve (measured on Linux: empty in most starts, filled within a few ms). Kill
// waits up to emptyCmdlineWait for it instead of refusing a stop that is legitimate.
// A command line that stays empty (an exiting process) is still refused.
const (
	emptyCmdlineWait = 2 * time.Second
	emptyCmdlinePoll = time.Millisecond
)

// SysProcAttr needs nothing on these systems.
func SysProcAttr() *syscall.SysProcAttr { return nil }

// Run pins the executor through the *os.Process the agent started. Go refuses to
// signal a process that Wait has reaped, and on Linux it signals through a pidfd,
// so Kill cannot reach a reused PID. Only the executor itself is stopped: child
// PIDs are not collected, because such a list can go stale before the kill.
type Run struct {
	mu     sync.Mutex
	p      *os.Process
	closed bool
}

// Track pins p, which must not have been waited for yet.
func Track(p *os.Process) (*Run, error) {
	if p == nil || p.Pid <= 0 || p.Pid == os.Getpid() {
		return nil, errors.New("track: invalid process")
	}
	return &Run{p: p}, nil
}

// Kill stops the executor, but only if its command line carries marker. Systems
// without /proc (macOS) cannot read the command line, so Kill refuses there.
func (r *Run) Kill(marker string) error {
	if err := checkMarker(marker); err != nil {
		return err
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.closed {
		return errClosed
	}
	pid := r.p.Pid
	cmdline, err := commandLine(pid)
	for deadline := time.Now().Add(emptyCmdlineWait); err == nil && cmdline == "" && time.Now().Before(deadline); {
		time.Sleep(emptyCmdlinePoll)
		cmdline, err = commandLine(pid)
	}
	if err != nil {
		return fmt.Errorf("refusing to kill pid %d: cannot read its command line: %w", pid, err)
	}
	if !strings.Contains(cmdline, marker) {
		return fmt.Errorf("refusing to kill pid %d: %w", pid, ErrMarkerMismatch)
	}
	if err := r.p.Kill(); err != nil {
		return fmt.Errorf("kill pid %d: %w", pid, err)
	}
	return nil
}

// ErrUnknown means these systems cannot tell whether the executor's child processes ended.
var ErrUnknown = errors.New("child processes are not tracked on this system")

// Remaining cannot count the executor's child processes here: they are not collected (see Run).
// A caller must treat the answer as unknown, never as "they ended".
func (r *Run) Remaining() (int, error) {
	return 0, ErrUnknown
}

// Close releases the pin.
func (r *Run) Close() {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.closed = true
}

// commandLine reads /proc/<pid>/cmdline.
func commandLine(pid int) (string, error) {
	data, err := os.ReadFile("/proc/" + strconv.Itoa(pid) + "/cmdline")
	if err != nil {
		return "", err
	}
	return strings.TrimSpace(strings.ReplaceAll(string(data), "\x00", " ")), nil
}
