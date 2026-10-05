//go:build !windows

package proc

import (
	"errors"
	"fmt"
	"os"
	"strconv"
	"strings"
)

// CommandLine reads /proc/<pid>/cmdline. Systems without /proc return an error,
// so KillRun refuses there instead of guessing.
func CommandLine(pid int) (string, error) {
	data, err := os.ReadFile("/proc/" + strconv.Itoa(pid) + "/cmdline")
	if err != nil {
		return "", err
	}
	return strings.TrimSpace(strings.ReplaceAll(string(data), "\x00", " ")), nil
}

// killTree collects the descendants of pid from /proc first, then kills pid (so it
// cannot start more children) and the collected descendants.
func killTree(pid int) error {
	targets := append([]int{pid}, descendants(pid)...)
	var errs []error
	for i, p := range targets {
		process, err := os.FindProcess(p)
		if err != nil {
			continue
		}
		if err := process.Kill(); err != nil && i == 0 && !errors.Is(err, os.ErrProcessDone) {
			errs = append(errs, fmt.Errorf("kill pid %d: %w", p, err))
		}
	}
	return errors.Join(errs...)
}

// descendants lists every process whose parent chain reaches root.
func descendants(root int) []int {
	entries, err := os.ReadDir("/proc")
	if err != nil {
		return nil
	}
	children := map[int][]int{}
	for _, e := range entries {
		pid, err := strconv.Atoi(e.Name())
		if err != nil {
			continue
		}
		if ppid, ok := parentOf(pid); ok {
			children[ppid] = append(children[ppid], pid)
		}
	}
	var out []int
	queue := []int{root}
	seen := map[int]bool{root: true}
	for len(queue) > 0 {
		p := queue[0]
		queue = queue[1:]
		for _, c := range children[p] {
			if !seen[c] {
				seen[c] = true
				out = append(out, c)
				queue = append(queue, c)
			}
		}
	}
	return out
}

// parentOf reads the parent PID from /proc/<pid>/stat. The command name sits in
// parentheses and may contain spaces, so fields are read after the last ')'.
func parentOf(pid int) (int, bool) {
	data, err := os.ReadFile("/proc/" + strconv.Itoa(pid) + "/stat")
	if err != nil {
		return 0, false
	}
	s := string(data)
	i := strings.LastIndexByte(s, ')')
	if i < 0 {
		return 0, false
	}
	fields := strings.Fields(s[i+1:])
	if len(fields) < 2 {
		return 0, false
	}
	ppid, err := strconv.Atoi(fields[1])
	return ppid, err == nil
}
