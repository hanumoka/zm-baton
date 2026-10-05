//go:build windows

package proc

import (
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"syscall"
)

var (
	kernel32                     = syscall.NewLazyDLL("kernel32.dll")
	ntdll                        = syscall.NewLazyDLL("ntdll.dll")
	procCreateJobObjectW         = kernel32.NewProc("CreateJobObjectW")
	procAssignProcessToJobObject = kernel32.NewProc("AssignProcessToJobObject")
	procTerminateJobObject       = kernel32.NewProc("TerminateJobObject")
	procNtResumeProcess          = ntdll.NewProc("NtResumeProcess")
)

const (
	createSuspended                = 0x00000004
	processTerminate               = 0x0001
	processSetQuota                = 0x0100
	processSuspendResume           = 0x0800
	processQueryLimitedInformation = 0x1000
	synchronize                    = 0x00100000
)

// SysProcAttr starts the executor suspended, so Track can put it in a job object
// before it runs any code or starts any child process.
func SysProcAttr() *syscall.SysProcAttr {
	return &syscall.SysProcAttr{CreationFlags: createSuspended}
}

// Run pins one executor. Its open process handle keeps the PID from being given
// to another process until Close, and its job object holds the executor and
// every process the executor starts.
type Run struct {
	mu      sync.Mutex
	pid     int
	process syscall.Handle
	job     uintptr
	closed  bool
}

// Track pins p, which must have been started with SysProcAttr and not yet waited
// for. It puts p in a new job object and only then lets it run. If Track fails,
// p is still suspended and the caller must kill it through p.
func Track(p *os.Process) (*Run, error) {
	if p == nil || p.Pid <= 0 || p.Pid == os.Getpid() {
		return nil, errors.New("track: invalid process")
	}
	for _, proc := range []*syscall.LazyProc{procCreateJobObjectW, procAssignProcessToJobObject, procTerminateJobObject, procNtResumeProcess} {
		if err := proc.Find(); err != nil {
			return nil, fmt.Errorf("track: %w", err)
		}
	}
	access := uint32(processTerminate | processSetQuota | processSuspendResume | processQueryLimitedInformation | synchronize)
	h, err := syscall.OpenProcess(access, false, uint32(p.Pid))
	if err != nil {
		return nil, fmt.Errorf("track pid %d: open process: %w", p.Pid, err)
	}
	job, _, callErr := procCreateJobObjectW.Call(0, 0)
	if job == 0 {
		syscall.CloseHandle(h)
		return nil, fmt.Errorf("track pid %d: create job object: %w", p.Pid, callErr)
	}
	r := &Run{pid: p.Pid, process: h, job: job}
	if ok, _, callErr := procAssignProcessToJobObject.Call(job, uintptr(h)); ok == 0 {
		r.Close()
		return nil, fmt.Errorf("track pid %d: assign to job object: %w", p.Pid, callErr)
	}
	if status, _, _ := procNtResumeProcess.Call(uintptr(h)); status != 0 {
		r.Close()
		return nil, fmt.Errorf("track pid %d: resume: NTSTATUS 0x%x", p.Pid, status)
	}
	return r, nil
}

// Kill stops the executor and every process in its job, but only while the
// executor runs and its command line carries marker. It stops nothing after
// Close, for a bad marker, when the command line cannot be read, or once the
// executor has exited: an exited process has no command line to check.
func (r *Run) Kill(marker string) error {
	if err := checkMarker(marker); err != nil {
		return err
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.closed {
		return errClosed
	}
	// The handle is still open, so this PID names the executor and no other process.
	cmdline, err := CommandLine(r.pid)
	if err != nil {
		return fmt.Errorf("refusing to kill pid %d: cannot read its command line: %w", r.pid, err)
	}
	if !strings.Contains(cmdline, marker) {
		return fmt.Errorf("refusing to kill pid %d: %w", r.pid, ErrMarkerMismatch)
	}
	if ok, _, callErr := procTerminateJobObject.Call(r.job, 1); ok == 0 {
		return fmt.Errorf("terminate the job of pid %d: %w", r.pid, callErr)
	}
	return nil
}

// Close releases the pin. Processes still in the job keep running.
func (r *Run) Close() {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.closed {
		return
	}
	r.closed = true
	syscall.CloseHandle(syscall.Handle(r.job))
	syscall.CloseHandle(r.process)
}

// CommandLine reads the command line of pid through CIM. An exited or inaccessible
// process yields an empty string, which never contains a marker.
func CommandLine(pid int) (string, error) {
	// pid is an int, so nothing from outside reaches the script text.
	script := "[Console]::OutputEncoding = [System.Text.Encoding]::UTF8; " +
		fmt.Sprintf("(Get-CimInstance Win32_Process -Filter 'ProcessId=%d').CommandLine", pid)
	powershell := systemTool(filepath.Join("WindowsPowerShell", "v1.0", "powershell.exe"), "powershell")
	out, err := exec.Command(powershell, "-NoProfile", "-NonInteractive", "-Command", script).Output()
	if err != nil {
		return "", fmt.Errorf("powershell: %w", err)
	}
	return strings.TrimSpace(string(out)), nil
}

// systemTool prefers the copy under %SystemRoot%\System32 over a PATH lookup.
func systemTool(rel, fallback string) string {
	if root := os.Getenv("SystemRoot"); root != "" {
		p := filepath.Join(root, "System32", rel)
		if _, err := os.Stat(p); err == nil {
			return p
		}
	}
	return fallback
}
