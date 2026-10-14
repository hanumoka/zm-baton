package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"io"
	"os"
	"time"

	"github.com/hanumoka/zm-baton/apps/agent/internal/session"
)

// exportTimeout bounds one export; the bundle holds every record of the server.
const exportTimeout = 5 * time.Minute

// exportLabels names the collections a person is shown, in this order. The bundle has more.
var exportLabels = []struct{ key, label string }{
	{"projects", "프로젝트"}, {"work_items", "작업"}, {"decisions", "결정"}, {"sessions", "세션"},
	{"inbox_items", "첫 화면 항목"}, {"notices", "충돌 알림"}, {"audit_log", "감사 기록"},
}

// exportCmd is `zm-baton export --out FILE` (FN-1404): it writes the server's export bundle to a
// new file. In order:
//  1. It creates FILE and refuses if it already exists, so no file a person keeps is overwritten.
//  2. It copies the server's answer into FILE as it arrives.
//  3. If anything fails, it removes FILE, which it created itself in step 1.
//  4. It reads the bundle's provenance and counts back from FILE and prints them.
func exportCmd(args []string, stdout io.Writer) error {
	fs := flag.NewFlagSet("export", flag.ContinueOnError)
	out := fs.String("out", "", "the file to write; it must not exist yet")
	server := fs.String("server", "", "zm-baton server URL (default: the one zm-baton login saved, else http://127.0.0.1:18081)")
	if err := fs.Parse(args); err != nil {
		return err
	}
	if *out == "" || fs.NArg() > 0 {
		return errors.New("use: zm-baton export --out FILE")
	}
	url := *server
	if url == "" {
		url = "http://127.0.0.1:18081"
		if dir, err := session.Dir(); err == nil {
			if cfg, err := session.LoadConfig(dir); err == nil {
				url = cfg.Server
			}
		}
	}
	client, err := session.NewClient(url, exportTimeout)
	if err != nil {
		return err
	}
	f, err := os.OpenFile(*out, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o600)
	if err != nil {
		if errors.Is(err, os.ErrExist) {
			return fmt.Errorf("%s already exists; choose a new file name", *out)
		}
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), exportTimeout)
	defer cancel()
	n, err := client.Export(ctx, f)
	if closeErr := f.Close(); err == nil {
		err = closeErr
	}
	if err == nil {
		err = printExport(stdout, *out, n)
	}
	if err != nil {
		// The file is ours (step 1) and holds a partial or unreadable bundle.
		if removeErr := os.Remove(*out); removeErr != nil {
			return fmt.Errorf("%w (and %s could not be removed: %v)", err, *out, removeErr)
		}
		return err
	}
	return nil
}

func printExport(w io.Writer, path string, size int64) error {
	f, err := os.Open(path)
	if err != nil {
		return err
	}
	defer f.Close()
	s, err := session.ReadExportSummary(f)
	if err != nil {
		return err
	}
	p := s.Provenance
	fmt.Fprintf(w, "내보냈다: %s (%d바이트, 형식 %s v%d)\n", path, size, s.Format, s.FormatVersion)
	fmt.Fprintf(w, "내보낸 시각: %s · 서버 판: %s (%s) · 데이터베이스 마이그레이션 판: %s\n",
		p.ExportedAt, p.Server.Version, p.Server.Commit, p.Database.MigrationVersion)
	for i, l := range exportLabels {
		if i > 0 {
			fmt.Fprint(w, " · ")
		}
		fmt.Fprintf(w, "%s %d", l.label, s.Collections[l.key].Count)
	}
	fmt.Fprintln(w)
	fmt.Fprintln(w, "세션 토큰 해시 같은 비밀값은 들어 있지 않다. 그 밖의 기록은 모두 들어 있으니 이 파일을 공개된 곳에 두지 않는다.")
	return nil
}
