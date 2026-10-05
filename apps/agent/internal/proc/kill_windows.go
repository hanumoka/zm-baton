//go:build windows

package proc

import (
	"errors"
	"fmt"
	"os"
	"strings"
	"sync"
	"syscall"
	"unsafe"
)

var (
	kernel32                     = syscall.NewLazyDLL("kernel32.dll")
	ntdll                        = syscall.NewLazyDLL("ntdll.dll")
	procCreateJobObjectW         = kernel32.NewProc("CreateJobObjectW")
	procAssignProcessToJobObject = kernel32.NewProc("AssignProcessToJobObject")
	procTerminateJobObject       = kernel32.NewProc("TerminateJobObject")
	procNtResumeProcess          = ntdll.NewProc("NtResumeProcess")
	procNtQueryInformation       = ntdll.NewProc("NtQueryInformationProcess")
)

const (
	createSuspended                = 0x00000004
	processTerminate               = 0x0001
	processSetQuota                = 0x0100
	processSuspendResume           = 0x0800
	processQueryLimitedInformation = 0x1000
	synchronize                    = 0x00100000

	processCommandLineInformation = 60 // PROCESSINFOCLASS, Windows 8.1 and later
	statusInfoLengthMismatch      = 0xC0000004
	statusBufferTooSmall          = 0xC0000023
	statusBufferOverflow          = 0x80000005
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
	for _, proc := range []*syscall.LazyProc{procCreateJobObjectW, procAssignProcessToJobObject, procTerminateJobObject, procNtResumeProcess, procNtQueryInformation} {
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
// Close, for a bad marker, when the executor has already exited, or when its
// command line cannot be read. Both checks go through the pinned handle, never
// through a PID lookup. If the executor exits between the checks and the stop,
// only processes left in its own job are stopped.
func (r *Run) Kill(marker string) error {
	if err := checkMarker(marker); err != nil {
		return err
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.closed {
		return errClosed
	}
	switch ev, err := syscall.WaitForSingleObject(r.process, 0); ev {
	case syscall.WAIT_TIMEOUT: // still running
	case syscall.WAIT_OBJECT_0:
		return fmt.Errorf("refusing to kill pid %d: %w", r.pid, ErrExited)
	default:
		return fmt.Errorf("refusing to kill pid %d: cannot tell whether it runs: %w", r.pid, err)
	}
	cmdline, err := commandLineOf(r.process)
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

// unicodeString is the UNICODE_STRING header NtQueryInformationProcess writes
// at the start of the buffer; Buffer points into the same buffer.
type unicodeString struct {
	Length        uint16
	MaximumLength uint16
	Buffer        *uint16
}

// commandLineOf reads the command line of the process behind h, so the answer
// belongs to that process object and not to whatever holds its PID now.
func commandLineOf(h syscall.Handle) (string, error) {
	size := uint32(4096)
	for range 5 {
		buf := make([]byte, size)
		var needed uint32
		status, _, _ := procNtQueryInformation.Call(uintptr(h), processCommandLineInformation,
			uintptr(unsafe.Pointer(&buf[0])), uintptr(size), uintptr(unsafe.Pointer(&needed)))
		switch uint32(status) {
		case 0:
			us := (*unicodeString)(unsafe.Pointer(&buf[0]))
			if us.Length == 0 || us.Buffer == nil {
				return "", nil
			}
			return syscall.UTF16ToString(unsafe.Slice(us.Buffer, us.Length/2)), nil
		case statusInfoLengthMismatch, statusBufferTooSmall, statusBufferOverflow:
			size = max(needed, size*2)
		default:
			return "", fmt.Errorf("NtQueryInformationProcess: NTSTATUS 0x%x", uint32(status))
		}
	}
	return "", errors.New("NtQueryInformationProcess: command line keeps growing")
}
