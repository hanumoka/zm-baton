// Command zm-baton-agent is the stage-1 local connector for the install/compat
// test (docs/stage1/compat-test.md, "로컬 연결 프로그램").
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"log/slog"
	"os"
	"os/signal"
	"time"

	"github.com/hanumoka/zm-baton/apps/agent/internal/agent"
	"github.com/hanumoka/zm-baton/apps/agent/internal/api"
)

func main() {
	os.Exit(run())
}

func run() int {
	server := flag.String("server", "http://localhost:18081", "zm-baton server base URL")
	deviceID := flag.String("device-id", "", "device id sent with each claim, e.g. dev_compat_a (required)")
	workdir := flag.String("workdir", "", "executor working directory; must exist and must not be under the OS temp dir (required)")
	prompt := flag.String("prompt", "", "prompt passed to Claude Code (required)")
	once := flag.Bool("once", false, "claim one run, execute it, then exit (exit code 0 only if it ended submitted)")
	pollInterval := flag.Duration("poll-interval", 2*time.Second, "pause between polls when there is nothing to claim")
	allowedTools := flag.String("allowed-tools", "Read", "comma-separated list passed to Claude Code --allowedTools")
	claudeBin := flag.String("claude", "claude", "Claude Code executable name or path")
	runTimeout := flag.Duration("run-timeout", 15*time.Minute, "stop the executor after this long; 0 means no limit")
	flag.Parse()

	log := slog.New(slog.NewTextHandler(os.Stderr, nil))
	fail := func(msg string, err error) int {
		log.Error(msg, "err", err)
		return 2
	}
	if flag.NArg() > 0 {
		return fail("unexpected arguments", fmt.Errorf("%q", flag.Args()))
	}
	dir, err := agent.CheckWorkdir(*workdir, agent.TempDirs())
	if err != nil {
		return fail("bad --workdir", err)
	}
	client, err := api.NewClient(*server)
	if err != nil {
		return fail("bad --server", err)
	}
	claudePath, err := agent.ResolveExecutable(*claudeBin)
	if err != nil {
		return fail("bad --claude", err)
	}
	runner, err := agent.New(agent.Config{
		Client:       client,
		DeviceID:     *deviceID,
		Workdir:      dir,
		Prompt:       *prompt,
		AllowedTools: *allowedTools,
		ClaudePath:   claudePath,
		PollInterval: *pollInterval,
		RunTimeout:   *runTimeout,
		Log:          log,
	})
	if err != nil {
		return fail("bad flags", err)
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt)
	defer stop()
	log.Info("agent started", "server", *server, "device_id", *deviceID, "once", *once,
		"poll_interval", pollInterval.String(), "allowed_tools", *allowedTools)
	reason, err := runner.Run(ctx, *once)
	switch {
	case errors.Is(err, context.Canceled):
		log.Info("agent stopped")
		return 130
	case err != nil:
		log.Error("agent failed", "err", err)
		return 1
	case *once && reason != agent.EndSubmitted:
		return 1
	}
	return 0
}
