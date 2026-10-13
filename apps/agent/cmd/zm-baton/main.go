// Command zm-baton connects AI sessions a person opens directly (Claude Code for now) to the
// zm-baton server (contract v1 "직접 연 AI 세션을 서버에 등록한다", protocol_version 2), and
// runs the work items a person hands to an AI on this device (`zm-baton agent`, contract "위임").
//
// Executor hooks call `zm-baton hook <event>`; a person runs login, hooks install, session
// work and session off. Hooks always exit 0 and never print errors, so a stopped server never
// gets in the way of the session; errors go to hook.log in the config folder.
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"time"

	"github.com/hanumoka/zm-baton/apps/agent/internal/session"
)

const usage = `zm-baton — connects AI sessions you open yourself to the zm-baton server

  zm-baton login [--server URL] [--name NAME]   register this device (once)
  zm-baton hooks install [--project DIR]      make the Claude Code plugin and show how to turn it on
  zm-baton hooks uninstall [--project DIR]    show how to turn it off
  zm-baton session work [--ref REF]... [--private|--share] "<what, where>"
                                               tell zm-baton what this session works on;
                                               --private keeps it and later ones unshared
  zm-baton session off                         stop reporting this session
  zm-baton import zm-docs [--repo DIR] [--ref REV] [--apply]
                                               copy zm-docs work items and decisions to the
                                               server; shows what would change unless --apply
  zm-baton repo add [DIR] [--worktrees DIR]    let this device run handed-over work in a repository
  zm-baton repo list                           show the repositories registered on this device
  zm-baton repo remove DIR|KEY                 stop running handed-over work in a repository
  zm-baton agent [--once] [--claude PATH] [--run-timeout D]
                                               run the work handed to this device, each run in
                                               its own worktree and branch; Ctrl+C stops
  zm-baton status                              show this device's connection
  zm-baton hook <event>                        called by executor hooks; not for people

Set ZM_BATON_OFF=1 before starting a session to keep it from registering.
`

func main() {
	os.Exit(run(os.Args[1:], os.Stdin, os.Stdout, os.Stderr))
}

func run(args []string, stdin io.Reader, stdout, stderr io.Writer) int {
	if len(args) == 0 {
		fmt.Fprint(stderr, usage)
		return 2
	}
	switch args[0] {
	case "hook":
		return runHook(args[1:], stdin, stdout)
	case "login":
		return report(stderr, login(args[1:], stdout))
	case "hooks":
		return report(stderr, hooks(args[1:], stdout))
	case "session":
		return report(stderr, sessionCmd(args[1:], stdout))
	case "import":
		return report(stderr, importCmd(args[1:], stdout))
	case "repo":
		return report(stderr, repoCmd(args[1:], stdout))
	case "agent":
		return agentCmd(args[1:], stderr)
	case "status":
		return report(stderr, status(stdout))
	case "help", "-h", "--help":
		fmt.Fprint(stdout, usage)
		return 0
	default:
		fmt.Fprintf(stderr, "unknown command %q\n\n%s", args[0], usage)
		return 2
	}
}

func report(stderr io.Writer, err error) int {
	if err == nil {
		return 0
	}
	fmt.Fprintln(stderr, "zm-baton:", err)
	return 1
}

// runHook always returns 0: a hook that fails must not show an error in the session.
func runHook(args []string, stdin io.Reader, stdout io.Writer) (code int) {
	dir, err := session.Dir()
	if err != nil {
		return 0
	}
	log, closeLog := hookLog(dir)
	defer closeLog()
	defer func() {
		if r := recover(); r != nil {
			log.Error("hook panicked", "panic", fmt.Sprint(r))
			code = 0
		}
	}()
	if len(args) != 1 {
		log.Error("hook needs one event name", "args", strings.Join(args, " "))
		return 0
	}
	// Every wait inside a hook stops at its time limit. This is the last line: whatever still
	// blocks a little after it (a stuck disk, a git child) does not hold the session.
	if limit, ok := session.HookTimeout(args[0]); ok {
		watchdog := time.AfterFunc(limit+250*time.Millisecond, func() {
			log.Warn("hook passed its time limit; exiting", "event", args[0])
			os.Exit(0)
		})
		defer watchdog.Stop()
	}
	h := &session.Hooks{
		Dir:      dir,
		Executor: "claude_code",
		Stdout:   stdout,
		Off:      os.Getenv("ZM_BATON_OFF") != "",
	}
	if err := h.Run(context.Background(), args[0], stdin); err != nil {
		log.Warn("hook failed", "event", args[0], "err", err.Error())
	}
	return 0
}

// hookLog appends to hook.log, starting over when it passes 1 MiB. Tokens are never logged.
func hookLog(dir string) (*slog.Logger, func()) {
	path := filepath.Join(dir, "hook.log")
	if info, err := os.Stat(path); err == nil && info.Size() > 1<<20 {
		_ = os.Rename(path, path+".1")
	}
	_ = os.MkdirAll(dir, 0o700)
	f, err := os.OpenFile(path, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o600)
	if err != nil {
		return slog.New(slog.NewTextHandler(io.Discard, nil)), func() {}
	}
	return slog.New(slog.NewTextHandler(f, nil)), func() { _ = f.Close() }
}

func login(args []string, stdout io.Writer) error {
	fs := flag.NewFlagSet("login", flag.ContinueOnError)
	server := fs.String("server", "http://127.0.0.1:18081", "zm-baton server URL")
	host, _ := os.Hostname()
	name := fs.String("name", host, "a name for this device")
	force := fs.Bool("force", false, "register a new device even when this one is registered")
	if err := fs.Parse(args); err != nil {
		return err
	}
	dir, err := session.Dir()
	if err != nil {
		return err
	}
	if cfg, err := session.LoadConfig(dir); err == nil && !*force {
		fmt.Fprintf(stdout, "이미 등록된 기기다: %s (서버 %s). 새로 등록하려면 --force\n", cfg.DeviceID, cfg.Server)
		return nil
	}
	client, err := session.NewClient(*server, 10*time.Second)
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	deviceID, err := client.RegisterDevice(ctx, *name, runtime.GOOS)
	if err != nil {
		return err
	}
	if err := session.SaveConfig(dir, session.Config{Server: *server, DeviceID: deviceID}); err != nil {
		return err
	}
	fmt.Fprintf(stdout, "기기를 등록했다: %s\n설정 폴더: %s\n", deviceID, dir)
	return nil
}

func hooks(args []string, stdout io.Writer) error {
	if len(args) == 0 || (args[0] != "install" && args[0] != "uninstall") {
		return errors.New("use: zm-baton hooks install|uninstall [--project DIR]")
	}
	fs := flag.NewFlagSet("hooks", flag.ContinueOnError)
	project := fs.String("project", "", "turn the hooks on for this repository only (Claude Code scope local)")
	if err := fs.Parse(args[1:]); err != nil {
		return err
	}
	dir, err := session.Dir()
	if err != nil {
		return err
	}
	root := filepath.Join(dir, "claude-plugin")
	scope, cd := "user", ""
	if *project != "" {
		abs, err := filepath.Abs(*project)
		if err != nil {
			return err
		}
		scope, cd = "local", abs
	}
	plugin := session.PluginName + "@" + session.MarketplaceName
	if args[0] == "uninstall" {
		fmt.Fprintln(stdout, "zm-baton은 Claude Code 설정을 고치지 않는다. 훅을 빼려면 PowerShell에서 아래를 실행한다:")
		if cd != "" {
			fmt.Fprintf(stdout, "  cd %s\n", psQuote(cd))
		}
		fmt.Fprintf(stdout, "  claude plugin uninstall %s --scope %s\n", plugin, scope)
		fmt.Fprintf(stdout, "모든 곳에서 뺐으면 마켓플레이스도 지운다:\n  claude plugin marketplace remove %s\n", session.MarketplaceName)
		fmt.Fprintf(stdout, "그 뒤에는 이 폴더를 지워도 된다: %s\n", root)
		return nil
	}
	exe, err := executable()
	if err != nil {
		return err
	}
	if err := session.WritePlugin(root, exe); err != nil {
		return err
	}
	fmt.Fprintf(stdout, "Claude Code 플러그인을 만들었다: %s\n", root)
	fmt.Fprintf(stdout, "훅이 부르는 실행 파일: %s\n", session.HookExecutable(exe))
	fmt.Fprintln(stdout, "zm-baton은 Claude Code 설정을 고치지 않는다. PowerShell에서 아래를 실행하면 Claude Code가 설정에 넣는다:")
	fmt.Fprintf(stdout, "  claude plugin marketplace add %s      # 처음 한 번\n", psQuote(root))
	if cd != "" {
		fmt.Fprintf(stdout, "  cd %s\n", psQuote(cd))
	}
	fmt.Fprintf(stdout, "  claude plugin install %s --scope %s\n", plugin, scope)
	fmt.Fprintln(stdout, "새로 여는 세션부터 적용된다. 실행 파일을 옮기면 이 명령만 다시 실행한다.")
	return nil
}

// psSingleQuotes are the characters PowerShell reads as a single quote: the ASCII one and four
// typographic ones (PowerShell language specification, "single-quote-character").
var psSingleQuotes = []string{"'", "‘", "’", "‚", "‛"}

// psQuote quotes a path for PowerShell as a single-quoted string, which PowerShell never
// expands: `$`, backticks and double quotes stay literal. Each single-quote character is
// doubled with itself, which PowerShell reads back as that one character.
func psQuote(s string) string {
	for _, q := range psSingleQuotes {
		s = strings.ReplaceAll(s, q, q+q)
	}
	return "'" + s + "'"
}

func executable() (string, error) {
	exe, err := os.Executable()
	if err != nil {
		return "", err
	}
	if resolved, err := filepath.EvalSymlinks(exe); err == nil {
		exe = resolved
	}
	return exe, nil
}

type refList []string

func (r *refList) String() string     { return strings.Join(*r, ",") }
func (r *refList) Set(v string) error { *r = append(*r, v); return nil }

func sessionCmd(args []string, stdout io.Writer) error {
	if len(args) == 0 {
		return errors.New("use: zm-baton session work|off")
	}
	fs := flag.NewFlagSet("session", flag.ContinueOnError)
	id := fs.String("session", os.Getenv("CLAUDE_CODE_SESSION_ID"), "the executor session id (default: CLAUDE_CODE_SESSION_ID)")
	var refs refList
	fs.Var(&refs, "ref", "a related work item or document; repeatable")
	private := fs.Bool("private", false, "from now on keep this session's summaries off other sessions' status cards")
	shared := fs.Bool("share", false, "from now on show this session's summaries again (the default)")
	if err := fs.Parse(args[1:]); err != nil {
		return err
	}
	if *id == "" {
		return errors.New("no session: run inside a Claude Code session or pass --session")
	}
	if *private && *shared {
		return errors.New("use --private or --share, not both")
	}
	dir, err := session.Dir()
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	switch args[0] {
	case "work":
		summary := strings.Join(fs.Args(), " ")
		var share *bool
		if *private || *shared {
			share = shared
		}
		res, err := session.SendCurrentWork(ctx, dir, "claude_code", *id, summary, refs, share)
		if err != nil {
			return err
		}
		if res.Rejected > 0 {
			return fmt.Errorf("server rejected it: %s", strings.Join(res.Reasons, "; "))
		}
		fmt.Fprintln(stdout, "올렸다.")
	case "off":
		outcome, err := session.TurnOff(ctx, dir, "claude_code", *id)
		if err != nil {
			if errors.Is(err, session.ErrNotRegistered) {
				return err
			}
			return fmt.Errorf("이 세션의 보고는 멈췄지만 서버에 알리지 못했다(%v). 서버가 응답할 때 zm-baton session off를 다시 실행하면 서버에도 알린다", err)
		}
		switch outcome {
		case session.OffDelivered:
			fmt.Fprintln(stdout, "이 세션의 등록을 껐다. 이제 이 세션은 충돌 판정에서 빠진다.")
		case session.OffAlreadyOver:
			fmt.Fprintln(stdout, "이 세션의 보고를 멈췄다. 서버는 이 등록을 이미 끝난 것으로 본다.")
		case session.OffBefore:
			fmt.Fprintln(stdout, "이 세션은 이미 꺼져 있다.")
		}
	default:
		return fmt.Errorf("unknown session command %q", args[0])
	}
	return nil
}

func status(stdout io.Writer) error {
	dir, err := session.Dir()
	if err != nil {
		return err
	}
	cfg, err := session.LoadConfig(dir)
	if err != nil {
		fmt.Fprintf(stdout, "이 기기는 아직 등록하지 않았다(zm-baton login). 설정 폴더: %s\n", dir)
		return nil
	}
	fmt.Fprintf(stdout, "기기: %s\n서버: %s\n설정 폴더: %s\n", cfg.DeviceID, cfg.Server, dir)
	client, err := session.NewClient(cfg.Server, 3*time.Second)
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	if err := client.Health(ctx); err != nil {
		fmt.Fprintf(stdout, "서버 응답: 없음 (%v). 훅은 아무것도 하지 않고 끝난다.\n", err)
	} else {
		fmt.Fprintln(stdout, "서버 응답: 정상")
	}
	return nil
}
