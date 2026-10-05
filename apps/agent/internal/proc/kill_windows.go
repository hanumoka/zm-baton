//go:build windows

package proc

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
)

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

// killTree ends pid and its descendants. taskkill /T walks the tree by parent PID.
func killTree(pid int) error {
	taskkill := systemTool("taskkill.exe", "taskkill")
	if err := exec.Command(taskkill, "/PID", strconv.Itoa(pid), "/T", "/F").Run(); err != nil {
		return fmt.Errorf("taskkill pid %d: %w", pid, err)
	}
	return nil
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
