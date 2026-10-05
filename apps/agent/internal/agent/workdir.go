package agent

import (
	"crypto/rand"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

// TempDirs lists the temp directories a workdir must stay out of: the OS temp dir
// and the TMP, TEMP and TMPDIR variables. Some executors grant write access to
// these by default (contract v1 "실행할 때 넘기는 것").
func TempDirs() []string {
	var out []string
	for _, d := range []string{os.TempDir(), os.Getenv("TMP"), os.Getenv("TEMP"), os.Getenv("TMPDIR")} {
		if d != "" && !containsFold(out, d) {
			out = append(out, d)
		}
	}
	return out
}

// CheckWorkdir returns the absolute, resolved workdir. It refuses a path that is
// missing, not a directory, or equal to or inside any of tempDirs.
func CheckWorkdir(dir string, tempDirs []string) (string, error) {
	if strings.TrimSpace(dir) == "" {
		return "", errors.New("workdir is required")
	}
	resolved, err := resolve(dir)
	if err != nil {
		return "", fmt.Errorf("workdir: %w", err)
	}
	info, err := os.Stat(resolved)
	if err != nil {
		return "", fmt.Errorf("workdir: %w", err)
	}
	if !info.IsDir() {
		return "", fmt.Errorf("workdir is not a directory: %s", resolved)
	}
	for _, t := range tempDirs {
		root, err := resolve(t)
		if err != nil {
			continue
		}
		if within(resolved, root) {
			return "", fmt.Errorf("workdir %s is inside the temp directory %s; use a folder outside it", resolved, root)
		}
	}
	return resolved, nil
}

// resolve makes p absolute and follows links. On Windows this also expands 8.3
// short names, so C:\Users\LONGNA~1\... and C:\Users\LongName\... compare equal.
func resolve(p string) (string, error) {
	abs, err := filepath.Abs(p)
	if err != nil {
		return "", err
	}
	real, err := filepath.EvalSymlinks(abs)
	if err != nil {
		return "", err
	}
	return filepath.Clean(real), nil
}

// within reports whether path is root or below it. filepath.Rel compares
// case-insensitively on Windows.
func within(path, root string) bool {
	rel, err := filepath.Rel(root, path)
	if err != nil || filepath.IsAbs(rel) {
		return false
	}
	return rel != ".." && !strings.HasPrefix(rel, ".."+string(filepath.Separator))
}

func containsFold(list []string, s string) bool {
	for _, x := range list {
		if strings.EqualFold(filepath.Clean(x), filepath.Clean(s)) {
			return true
		}
	}
	return false
}

// NewUUIDv4 returns a random RFC 9562 version 4 UUID, used as the Claude session id.
func NewUUIDv4() string {
	var b [16]byte
	_, _ = rand.Read(b[:]) // crypto/rand.Read never fails since Go 1.24
	b[6] = b[6]&0x0f | 0x40
	b[8] = b[8]&0x3f | 0x80
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:16])
}
