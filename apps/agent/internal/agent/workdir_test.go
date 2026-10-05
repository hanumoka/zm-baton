package agent

import (
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"testing"
)

func TestCheckWorkdirRefusesTempDir(t *testing.T) {
	// t.TempDir lives under os.TempDir, so the real temp list must refuse it.
	dir := t.TempDir()
	if _, err := CheckWorkdir(dir, TempDirs()); err == nil || !strings.Contains(err.Error(), "temp directory") {
		t.Fatalf("workdir under the OS temp dir was accepted (err %v)", err)
	}
	if _, err := CheckWorkdir(os.TempDir(), TempDirs()); err == nil {
		t.Fatal("the temp dir itself was accepted")
	}
}

func TestCheckWorkdirAgainstGivenTempList(t *testing.T) {
	base := t.TempDir()
	tmp := filepath.Join(base, "fake-temp")
	work := filepath.Join(base, "work")
	nested := filepath.Join(tmp, "project")
	for _, d := range []string{tmp, work, nested} {
		if err := os.MkdirAll(d, 0o755); err != nil {
			t.Fatal(err)
		}
	}
	got, err := CheckWorkdir(work, []string{tmp})
	if err != nil {
		t.Fatalf("a folder outside the temp list was refused: %v", err)
	}
	if !filepath.IsAbs(got) {
		t.Fatalf("want an absolute path, got %q", got)
	}
	if _, err := CheckWorkdir(nested, []string{tmp}); err == nil {
		t.Fatal("a folder inside the temp list was accepted")
	}
	// A sibling whose name starts with the temp folder's name is outside it.
	sibling := tmp + "-sibling"
	if err := os.Mkdir(sibling, 0o755); err != nil {
		t.Fatal(err)
	}
	if _, err := CheckWorkdir(sibling, []string{tmp}); err != nil {
		t.Fatalf("prefix-named sibling refused: %v", err)
	}
	// A relative path is resolved before the check.
	t.Chdir(tmp)
	if _, err := CheckWorkdir("project", []string{tmp}); err == nil {
		t.Fatal("relative path into the temp list was accepted")
	}
}

func TestCheckWorkdirRefusesMissingOrFile(t *testing.T) {
	base := t.TempDir()
	file := filepath.Join(base, "file.txt")
	if err := os.WriteFile(file, []byte("x"), 0o644); err != nil {
		t.Fatal(err)
	}
	for _, d := range []string{"", filepath.Join(base, "missing"), file} {
		if _, err := CheckWorkdir(d, nil); err == nil {
			t.Errorf("CheckWorkdir(%q) accepted", d)
		}
	}
}

func TestNewUUIDv4(t *testing.T) {
	re := regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$`)
	seen := map[string]bool{}
	for range 100 {
		u := NewUUIDv4()
		if !re.MatchString(u) {
			t.Fatalf("not a UUIDv4: %q", u)
		}
		if seen[u] {
			t.Fatalf("duplicate %q", u)
		}
		seen[u] = true
	}
}
