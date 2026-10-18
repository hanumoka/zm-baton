package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"io"
	"log/slog"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"time"

	"github.com/hanumoka/zm-baton/apps/agent/internal/agent"
	"github.com/hanumoka/zm-baton/apps/agent/internal/api"
	"github.com/hanumoka/zm-baton/apps/agent/internal/session"
)

// tempDirs are the folders a repository and its worktrees must stay out of (contract v1 "실행할
// 때 넘기는 것"). A variable so tests, whose folders are all temporary, can replace it.
var tempDirs = agent.TempDirs

// repoCmd is `zm-baton repo add|list|remove`: the repositories this device's connector may run
// delegated work in. The paths stay in the config folder; the server learns the repository key,
// the folder name and the remote without credentials.
func repoCmd(args []string, stdout io.Writer) error {
	if len(args) == 0 {
		return errors.New("use: zm-baton repo add [DIR] [--worktrees DIR] | repo list | repo remove DIR|KEY")
	}
	switch args[0] {
	case "add":
		return repoAdd(args[1:], stdout)
	case "list":
		return repoList(stdout)
	case "remove":
		return repoRemove(args[1:], stdout)
	}
	return fmt.Errorf("unknown repo command %q", args[0])
}

// device is the config folder and the login of this device.
func device() (string, session.Config, error) {
	dir, err := session.Dir()
	if err != nil {
		return "", session.Config{}, err
	}
	cfg, err := session.LoadConfig(dir)
	if err != nil {
		return dir, cfg, errors.New("this device is not registered yet; run zm-baton login first")
	}
	return dir, cfg, nil
}

func repoAdd(args []string, stdout io.Writer) error {
	fs := flag.NewFlagSet("repo add", flag.ContinueOnError)
	worktrees := fs.String("worktrees", "", "folder that holds each run's worktree (default: <repository folder>-zm-baton-runs beside it)")
	if err := fs.Parse(args); err != nil {
		return err
	}
	if fs.NArg() > 1 {
		return errors.New("use: zm-baton repo add [DIR] [--worktrees DIR]")
	}
	folder := "."
	if fs.NArg() == 1 {
		folder = fs.Arg(0)
	}
	home, cfg, err := device()
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	info, err := session.RepoAt(ctx, folder)
	if err != nil {
		return fmt.Errorf("%s is not inside a Git checkout: %w", folder, err)
	}
	if info.Key == "" {
		return errors.New("the repository has no commit yet; commit once, then add it")
	}
	root, err := agent.CheckWorkdir(info.Root, tempDirs())
	if err != nil {
		return fmt.Errorf("the repository cannot be used: %w", err)
	}
	trees := *worktrees
	if trees == "" {
		trees = filepath.Join(filepath.Dir(root), filepath.Base(root)+"-zm-baton-runs")
	}
	if trees, err = filepath.Abs(trees); err != nil {
		return err
	}
	if inside(trees, root) {
		return fmt.Errorf("the worktree folder %s is inside the repository; pick a folder outside it", trees)
	}
	if err := os.MkdirAll(trees, 0o755); err != nil {
		return err
	}
	if trees, err = agent.CheckWorkdir(trees, tempDirs()); err != nil {
		return fmt.Errorf("the worktree folder cannot be used: %w", err)
	}
	repo := session.LocalRepo{
		Key: info.Key, Name: filepath.Base(root), Root: root, Worktrees: trees, Remote: info.Remote,
		AddedAt: time.Now().UTC(),
	}
	client, err := session.ClientFor(cfg, 10*time.Second)
	if err != nil {
		return err
	}
	if _, err := client.AddRepository(ctx, cfg.DeviceID, repo); err != nil {
		return err
	}
	var replaced string
	err = session.UpdateRepos(ctx, home, func(list []session.LocalRepo) ([]session.LocalRepo, error) {
		out := []session.LocalRepo{}
		for _, r := range list {
			if r.Key == repo.Key {
				if !strings.EqualFold(r.Root, repo.Root) {
					replaced = r.Root
				}
				continue
			}
			out = append(out, r)
		}
		return append(out, repo), nil
	})
	if err != nil {
		return fmt.Errorf("the server has the repository, but this device could not save it: %w", err)
	}
	fmt.Fprintf(stdout, "저장소를 등록했다: %s (%s)\n작업 폴더: %s\n실행마다 작업 트리를 만들 폴더: %s\n", repo.Name, repo.Key, repo.Root, repo.Worktrees)
	if replaced != "" {
		fmt.Fprintf(stdout, "같은 저장소의 다른 폴더(%s) 대신 이 폴더를 쓴다.\n", replaced)
	}
	fmt.Fprintln(stdout, "웹의 작업 상세 「AI에게 맡기기」에서 이 저장소를 고를 수 있다. 맡긴 일은 zm-baton agent가 실행한다.")
	return nil
}

func repoList(stdout io.Writer) error {
	home, err := session.Dir()
	if err != nil {
		return err
	}
	list, err := session.LoadRepos(home)
	if err != nil {
		return err
	}
	if len(list) == 0 {
		fmt.Fprintln(stdout, "등록한 저장소가 없다. zm-baton repo add <폴더>로 더한다.")
		return nil
	}
	for _, r := range list {
		fmt.Fprintf(stdout, "%s (%s)\n  작업 폴더: %s\n  작업 트리 폴더: %s\n", r.Name, r.Key, r.Root, r.Worktrees)
	}
	return nil
}

func repoRemove(args []string, stdout io.Writer) error {
	if len(args) != 1 {
		return errors.New("use: zm-baton repo remove DIR|KEY")
	}
	home, cfg, err := device()
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	key := args[0]
	if !strings.HasPrefix(key, "git:") {
		info, err := session.RepoAt(ctx, key)
		if err != nil {
			return fmt.Errorf("%s is neither a repository key nor inside a Git checkout: %w", key, err)
		}
		key = info.Key
	}
	client, err := session.ClientFor(cfg, 10*time.Second)
	if err != nil {
		return err
	}
	if err := client.RemoveRepository(ctx, cfg.DeviceID, key); err != nil {
		return err
	}
	var gone *session.LocalRepo
	err = session.UpdateRepos(ctx, home, func(list []session.LocalRepo) ([]session.LocalRepo, error) {
		out := []session.LocalRepo{}
		for _, r := range list {
			if r.Key == key {
				gone = &r
				continue
			}
			out = append(out, r)
		}
		return out, nil
	})
	if err != nil {
		return err
	}
	if gone == nil {
		fmt.Fprintf(stdout, "이 기기에 %s는 등록돼 있지 않았다. 서버에서도 뺐다.\n", key)
		return nil
	}
	fmt.Fprintf(stdout, "저장소를 뺐다: %s (%s). 새 일을 맡길 때 더는 보이지 않는다.\n", gone.Name, key)
	fmt.Fprintf(stdout, "이미 만든 작업 트리와 실행 브랜치는 그대로 있다: %s\n", gone.Worktrees)
	return nil
}

// inside reports whether path is root or below it.
func inside(path, root string) bool {
	rel, err := filepath.Rel(root, path)
	if err != nil || filepath.IsAbs(rel) {
		return false
	}
	return rel == "." || (rel != ".." && !strings.HasPrefix(rel, ".."+string(filepath.Separator)))
}

// agentCmd is `zm-baton agent`: runs the work items handed to this device, each in its own
// worktree of a registered repository. Exit codes: 0 done, 1 failed, 2 bad flags, 130 Ctrl+C.
func agentCmd(args []string, stderr io.Writer) int {
	fs := flag.NewFlagSet("agent", flag.ContinueOnError)
	fs.SetOutput(stderr)
	once := fs.Bool("once", false, "claim one run, execute it, then exit (exit code 0 only if it ended submitted)")
	pollInterval := fs.Duration("poll-interval", 2*time.Second, "pause between polls when there is nothing to claim")
	claudeBin := fs.String("claude", "claude", "Claude Code executable name or path")
	serverWorker := fs.Bool("server-worker", false, "shared server: require an explicit API-key account and turn, time and cost limits")
	runTimeout := fs.Duration("run-timeout", 0,
		"stop the executor after this long when that is shorter than the AI account's per-run time limit; 0 uses the account's limit (30m when the server sends none)")
	if err := fs.Parse(args); err != nil {
		return 2
	}
	log := slog.New(slog.NewTextHandler(stderr, nil))
	fail := func(msg string, err error) int {
		log.Error(msg, "err", err)
		return 2
	}
	if fs.NArg() > 0 {
		return fail("unexpected arguments", fmt.Errorf("%q", fs.Args()))
	}
	home, cfg, err := device()
	if err != nil {
		return fail("not registered", err)
	}
	client, err := api.NewClient(cfg.Server)
	if err != nil {
		return fail("bad server in config.json", err)
	}
	// Every poll, claim, report and lease renewal carries this device's token (docs/stage1/auth.md).
	client = client.WithToken(cfg.DeviceToken)
	claudePath, err := agent.ResolveExecutable(*claudeBin)
	if err != nil {
		return fail("bad --claude", err)
	}
	repos := func() (map[string]agent.Repo, error) {
		list, err := session.LoadRepos(home)
		if err != nil {
			return nil, err
		}
		out := make(map[string]agent.Repo, len(list))
		for _, r := range list {
			out[r.Key] = agent.Repo{Key: r.Key, Root: r.Root, Worktrees: r.Worktrees}
		}
		return out, nil
	}
	// Run records (agent/record.go) stay beside repos.json: they say whether an executor this device
	// started ended with no child left, which a continuing run needs before it reuses a worktree. The
	// master's contradiction checks run in an empty folder each under master/ (agent/master.go).
	h, err := agent.NewHandoff(agent.Config{
		Client: client, DeviceID: cfg.DeviceID, ClaudePath: claudePath, PollInterval: *pollInterval,
		RunTimeout: *runTimeout, StateDir: filepath.Join(home, "runs"), ExecutorDir: filepath.Join(home, "executors"),
		MasterDir: filepath.Join(home, "master"), ServerWorker: *serverWorker, Log: log,
	}, repos, tempDirs())
	if err != nil {
		return fail("bad flags", err)
	}
	if list, err := repos(); err != nil || len(list) == 0 {
		log.Warn("no repository is registered on this device yet; add one with zm-baton repo add", "err", err)
	}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt)
	defer stop()
	log.Info("agent started", "server", cfg.Server, "device_id", cfg.DeviceID, "once", *once,
		"poll_interval", pollInterval.String(), "run_timeout", runTimeout.String())
	res, err := h.Run(ctx, *once)
	switch {
	case ctx.Err() != nil:
		log.Info("agent stopped")
		return 130
	case err != nil:
		log.Error("agent failed", "err", err)
		if *serverWorker && errors.Is(err, api.ErrUnauthorized) {
			return 78 // credentials need operator attention; a service must not restart-loop
		}
		return 1
	case *once && res.Delivery != nil:
		return 1
	case *once && res.EndReason != agent.EndSubmitted:
		return 1
	}
	return 0
}
