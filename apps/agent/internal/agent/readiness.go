package agent

import (
	"context"
	"errors"
	"fmt"
	"os/exec"
	"regexp"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"
)

// The check before an executor starts (FN-0406, G38.3): the delegation's scope is complete, the
// executor is there and new enough, the repository is the one registered, a worktree can be made
// cleanly from the base, and the disk has room. When a check fails the executor never starts; the
// run ends failed and the server marks the work item readiness_failed. Every check, passed or not,
// goes into the end report so the person sees what was looked at.

// MinClaudeVersion is the oldest Claude Code a delegated run starts: the version whose --restricted,
// --strict-mcp-config, --input-format stream-json and --replay-user-messages were tried (2.1.296).
var MinClaudeVersion = [3]int{2, 1, 296}

// MinFreeBytes is the least free space the worktree folder's disk must have for a new worktree.
const MinFreeBytes = 512 << 20

// Check is one item of the readiness check. Detail is written for a person and never holds a path
// on this device.
type Check struct {
	Name   string
	OK     bool
	Detail string
}

// Readiness collects the checks of one run in the order they ran.
type Readiness struct {
	Checks []Check
}

// readinessError is a failed check; its Error keeps the local cause for the log only.
type readinessError struct {
	check Check
	cause error
}

func (e *readinessError) Error() string {
	if e.cause == nil {
		return e.check.Name + ": " + e.check.Detail
	}
	return e.check.Name + ": " + e.check.Detail + ": " + e.cause.Error()
}

func (e *readinessError) Unwrap() error { return e.cause }

// Pass records a check that passed.
func (r *Readiness) Pass(name, detail string) {
	r.Checks = append(r.Checks, Check{Name: name, OK: true, Detail: safeDetail(detail)})
}

// Fail records a check that failed and returns the error that ends the run before its executor.
func (r *Readiness) Fail(name, detail string, cause error) error {
	c := Check{Name: name, Detail: safeDetail(detail)}
	r.Checks = append(r.Checks, c)
	return &readinessError{check: c, cause: cause}
}

// OK reports whether every check passed.
func (r *Readiness) OK() bool {
	for _, c := range r.Checks {
		if !c.OK {
			return false
		}
	}
	return true
}

// Payload is the end report's readiness: {"ok", "checks": [{"name", "ok", "detail"}]}.
func (r *Readiness) Payload() map[string]any {
	checks := make([]any, 0, len(r.Checks))
	for _, c := range r.Checks {
		checks = append(checks, map[string]any{"name": c.Name, "ok": c.OK, "detail": c.Detail})
	}
	return map[string]any{"ok": r.OK(), "checks": checks}
}

// failedCheck returns the failed check behind err, if err came from Readiness.Fail.
func failedCheck(err error) (Check, bool) {
	var re *readinessError
	if errors.As(err, &re) {
		return re.check, true
	}
	return Check{}, false
}

// devicePath is what an absolute path on a device looks like: a drive, a backslash, or a word that
// starts with / or ~. The server refuses a readiness detail that matches; so does safeDetail.
var devicePath = regexp.MustCompile(`[A-Za-z]:[\\/]|\\|(^|\s)[/~]`)

// safeDetail keeps a detail short and free of paths on this device, whatever went into it.
func safeDetail(s string) string {
	s = strings.TrimSpace(s)
	if devicePath.MatchString(s) {
		return "자세한 이유는 이 PC의 연결 프로그램 창에 있다"
	}
	if utf8.RuneCountInString(s) > 300 {
		s = string([]rune(s)[:300])
	}
	return s
}

var versionPattern = regexp.MustCompile(`(\d+)\.(\d+)\.(\d+)`)

// claudeVersion asks the executor for its version (`claude --version`, no shell) and reads the
// first x.y.z in the answer.
func claudeVersion(ctx context.Context, path string) (string, [3]int, error) {
	ctx, cancel := context.WithTimeout(ctx, 20*time.Second)
	defer cancel()
	out, err := exec.CommandContext(ctx, path, "--version").Output()
	if err != nil {
		return "", [3]int{}, err
	}
	m := versionPattern.FindStringSubmatch(string(out))
	if m == nil {
		return "", [3]int{}, fmt.Errorf("no version in %q", strings.TrimSpace(string(out)))
	}
	var v [3]int
	for i := range v {
		v[i], _ = strconv.Atoi(m[i+1]) // the pattern holds only digits
	}
	return m[0], v, nil
}

func versionAtLeast(v, min [3]int) bool {
	for i := range v {
		if v[i] != min[i] {
			return v[i] > min[i]
		}
	}
	return true
}

func versionString(v [3]int) string { return fmt.Sprintf("%d.%d.%d", v[0], v[1], v[2]) }
