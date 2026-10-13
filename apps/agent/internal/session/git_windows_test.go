//go:build windows

package session

import (
	"context"
	"path/filepath"
	"syscall"
	"testing"
)

// shortPath returns the Windows short (8.3) spelling of an existing path.
func shortPath(t *testing.T, long string) string {
	t.Helper()
	in, err := syscall.UTF16PtrFromString(long)
	if err != nil {
		t.Fatal(err)
	}
	buf := make([]uint16, 1024)
	n, err := syscall.GetShortPathName(in, &buf[0], uint32(len(buf)))
	if err != nil || n == 0 {
		t.Skipf("no short name for %s: %v", long, err)
	}
	return syscall.UTF16ToString(buf[:n])
}

func TestRelAcceptsTheShortSpellingOfAFileInTheRepository(t *testing.T) {
	dir := gitRepo(t)
	r, err := RepoAt(context.Background(), dir)
	if err != nil {
		t.Fatal(err)
	}
	long := filepath.Join(dir, "docs", "a.md")
	short := shortPath(t, long)
	if short == long {
		t.Skip("this volume has no short names")
	}
	if rel, ok := r.Rel(short); !ok || rel != "docs/a.md" {
		t.Errorf("Rel(%s) = %q %v", short, rel, ok)
	}
	// A file deleted after the tool ran still resolves through its existing parent.
	gone := filepath.Join(filepath.Dir(short), "deleted-later.md")
	if rel, ok := r.Rel(gone); !ok || rel != "docs/deleted-later.md" {
		t.Errorf("Rel(%s) = %q %v", gone, rel, ok)
	}
}
