package session

import (
	"bytes"
	"context"
	"errors"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"runtime"
	"sort"
	"strings"
)

// Repo describes the Git repository a session works in. Root is a local absolute path and is
// never sent; the server gets Key, Prefix, Branch and Remote (contract: "기기의 절대 경로는
// 보내지 않는다").
type Repo struct {
	Root   string
	Prefix string // the working folder relative to Root, "/" separated, "" at the root
	Branch string
	Remote string // origin without credentials, "" when there is none or it is a local path
	// Key identifies the repository across clones and worktrees: "git:<root commit>".
	Key string
}

// RepoAt reads the repository that contains dir. It returns an error when dir is not inside
// a Git working tree; then the session registers without a repository and gets no conflict checks.
func RepoAt(ctx context.Context, dir string) (*Repo, error) {
	root, err := git(ctx, dir, "rev-parse", "--show-toplevel")
	if err != nil {
		return nil, err
	}
	r := &Repo{Root: filepath.Clean(filepath.FromSlash(root))}
	r.Prefix, _ = git(ctx, dir, "rev-parse", "--show-prefix")
	r.Prefix = strings.TrimSuffix(r.Prefix, "/")
	if branch, err := git(ctx, dir, "rev-parse", "--abbrev-ref", "HEAD"); err == nil {
		r.Branch = branch
	}
	if remote, err := git(ctx, dir, "config", "--get", "remote.origin.url"); err == nil {
		r.Remote = CleanRemote(remote)
	}
	if roots, err := git(ctx, dir, "rev-list", "--max-parents=0", "HEAD"); err == nil && roots != "" {
		list := strings.Fields(roots)
		sort.Strings(list) // several roots (merged histories): the smallest is stable
		r.Key = "git:" + list[0]
	}
	return r, nil
}

// Rel returns path relative to the repository root with "/" separators. ok is false when the
// path is outside the repository or cannot be made relative.
func (r *Repo) Rel(path string) (string, bool) {
	if r == nil || r.Root == "" || path == "" {
		return "", false
	}
	p := filepath.Clean(filepath.FromSlash(path))
	if !filepath.IsAbs(p) {
		return "", false
	}
	root := r.Root
	if runtime.GOOS == "windows" {
		// Drive letters and folder names compare case-insensitively on Windows.
		if !strings.HasPrefix(strings.ToLower(p)+`\`, strings.ToLower(root)+`\`) && !strings.EqualFold(p, root) {
			return "", false
		}
		p = root + p[len(root):]
	}
	rel, err := filepath.Rel(root, p)
	if err != nil || rel == "." || rel == ".." || strings.HasPrefix(rel, ".."+string(filepath.Separator)) {
		return "", false
	}
	return filepath.ToSlash(rel), true
}

// HashObject is `git hash-object` of one file without storing an object. Empty when it fails.
func HashObject(ctx context.Context, root, rel string) string {
	h, err := git(ctx, root, "hash-object", "--", filepath.FromSlash(rel))
	if err != nil {
		return ""
	}
	return h
}

// Head is the commit HEAD points to, or "" when there is none.
func Head(ctx context.Context, root string) string {
	h, err := git(ctx, root, "rev-parse", "HEAD")
	if err != nil {
		return ""
	}
	return h
}

var (
	urlScheme   = regexp.MustCompile(`^[A-Za-z][A-Za-z0-9+.-]*://`)
	scpRemote   = regexp.MustCompile(`^(?:[^@/\s]+@)?([A-Za-z0-9._-]+):([^\s?#]+)$`)
	driveLetter = regexp.MustCompile(`^[A-Za-z]:`)
)

// CleanRemote keeps only the host and path of a remote, the same way the server does. The
// whole value is parsed first and rebuilt from parts that cannot hold a secret: user info,
// query and fragment are dropped, and so is the user of an scp-style `user@host:path`. Local
// paths and anything unparsable give "", so neither a token nor a path on this PC leaves it.
func CleanRemote(remote string) string {
	remote = strings.TrimSpace(remote)
	if remote == "" || len(remote) > 2000 || strings.HasPrefix(remote, "/") || strings.HasPrefix(remote, `\`) ||
		strings.HasPrefix(remote, ".") || strings.HasPrefix(remote, "~") || driveLetter.MatchString(remote) ||
		strings.HasPrefix(strings.ToLower(remote), "file:") {
		return ""
	}
	var out string
	if urlScheme.MatchString(remote) {
		u, err := url.Parse(remote)
		if err != nil || u.Host == "" {
			return ""
		}
		out = strings.ToLower(u.Scheme) + "://" + u.Host + u.EscapedPath()
	} else {
		m := scpRemote.FindStringSubmatch(remote)
		if m == nil {
			return ""
		}
		out = m[1] + ":" + m[2]
	}
	if len(out) > 500 {
		return ""
	}
	return out
}

var errNoGit = errors.New("git is not available")

// git runs one read-only git command. GIT_OPTIONAL_LOCKS=0 keeps it from taking the index lock
// that another tool working in the same folder might need.
func git(ctx context.Context, dir string, args ...string) (string, error) {
	path, err := exec.LookPath("git")
	if err != nil {
		return "", errNoGit
	}
	cmd := exec.CommandContext(ctx, path, append([]string{"-C", dir}, args...)...)
	cmd.Env = append(os.Environ(), "GIT_OPTIONAL_LOCKS=0", "GIT_TERMINAL_PROMPT=0")
	var out, errOut bytes.Buffer
	cmd.Stdout = &out
	cmd.Stderr = &errOut
	if err := cmd.Run(); err != nil {
		return "", errors.New("git " + args[0] + ": " + strings.TrimSpace(errOut.String()))
	}
	return strings.TrimSpace(out.String()), nil
}
