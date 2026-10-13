package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"io"
	"strings"
	"time"

	"github.com/hanumoka/zm-baton/apps/agent/internal/session"
	"github.com/hanumoka/zm-baton/apps/agent/internal/zmdocs"
)

// maxChangeLines is how many changed items the report lists one by one.
const maxChangeLines = 40

// importCmd is `zm-baton import zm-docs`: the trial import of FN-1004. It reads the committed
// files of a zm-docs checkout and sends them; the server keeps copies whose source of truth
// stays the Git files. Without --apply the server applies nothing and only reports.
func importCmd(args []string, stdout io.Writer) error {
	if len(args) == 0 || args[0] != "zm-docs" {
		return errors.New("use: zm-baton import zm-docs [--repo DIR] [--ref REV] [--apply]")
	}
	fs := flag.NewFlagSet("import", flag.ContinueOnError)
	repo := fs.String("repo", ".", "a folder inside the zm-docs checkout")
	ref := fs.String("ref", "HEAD", "the commit to read; files not committed there are not sent")
	apply := fs.Bool("apply", false, "change the server; without it only show what would change")
	if err := fs.Parse(args[1:]); err != nil {
		return err
	}
	dir, err := session.Dir()
	if err != nil {
		return err
	}
	cfg, err := session.LoadConfig(dir)
	if err != nil {
		return errors.New("this device is not registered yet; run zm-baton login first")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	repoInfo, err := session.RepoAt(ctx, *repo)
	if err != nil {
		return fmt.Errorf("%s is not inside a Git checkout: %w", *repo, err)
	}
	if repoInfo.Key == "" {
		return errors.New("the repository has no commit to identify it by")
	}
	snap, err := zmdocs.Read(ctx, repoInfo.Root, *ref)
	if err != nil {
		return err
	}
	client, err := session.NewClient(cfg.Server, 2*time.Minute)
	if err != nil {
		return err
	}
	res, err := client.ImportZmDocs(ctx, repoInfo.Key, snap, *apply)
	if err != nil {
		return err
	}
	printImport(stdout, snap, *ref, res)
	return nil
}

func printImport(w io.Writer, snap *zmdocs.Snapshot, ref string, res session.ImportResult) {
	if res.Applied {
		fmt.Fprintln(w, "zm-docs를 가져왔다.")
	} else {
		fmt.Fprintln(w, "zm-docs 가져오기 미리 보기다. 서버는 아직 바꾸지 않았다.")
	}
	commit := snap.Commit
	if len(commit) > 10 {
		commit = commit[:10]
	}
	fmt.Fprintf(w, "읽은 커밋: %s (%s), 파일 %d개\n", commit, ref, len(snap.Files))
	for _, row := range []struct {
		label string
		count session.ImportCount
	}{{"프로젝트", res.Projects}, {"작업", res.WorkItems}, {"결정 기록", res.Decisions}} {
		fmt.Fprintf(w, "%s: 새로 %d · 바뀜 %d · 그대로 %d · 건너뜀 %d\n",
			row.label, row.count.Created, row.count.Updated, row.count.Unchanged, row.count.Skipped)
	}
	if len(res.Changes) > 0 {
		verb := "바뀌는 것"
		if res.Applied {
			verb = "바뀐 것"
		}
		fmt.Fprintf(w, "%s %d건:\n", verb, len(res.Changes))
		for i, c := range res.Changes {
			if i == maxChangeLines {
				fmt.Fprintf(w, "  그 밖에 %d건\n", len(res.Changes)-maxChangeLines)
				break
			}
			fmt.Fprintf(w, "  %s %s %s%s%s\n", kindLabel(c.Kind), c.Ref, actionLabel(c.Action),
				fieldsNote(c.Fields), noteText(c.Note))
		}
	}
	if len(res.Warnings) > 0 {
		fmt.Fprintf(w, "경고 %d건:\n", len(res.Warnings))
		for _, warning := range res.Warnings {
			fmt.Fprintf(w, "  - %s\n", warning)
		}
	}
	if !res.Applied {
		fmt.Fprintln(w, "적용하려면 같은 명령에 --apply를 붙여 다시 실행한다.")
	}
}

func kindLabel(kind string) string {
	switch kind {
	case "project":
		return "프로젝트"
	case "work_item":
		return "작업"
	case "decision":
		return "결정"
	}
	return kind
}

func actionLabel(action string) string {
	switch action {
	case "created":
		return "새로"
	case "updated":
		return "바뀜"
	case "skipped":
		return "건너뜀"
	}
	return action
}

func fieldsNote(fields []string) string {
	if len(fields) == 0 {
		return ""
	}
	return " (" + strings.Join(fields, ", ") + ")"
}

func noteText(note string) string {
	if note == "" {
		return ""
	}
	return ": " + note
}
