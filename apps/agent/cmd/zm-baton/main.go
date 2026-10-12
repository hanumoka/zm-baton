// Command zm-baton connects AI sessions a person opens directly (Claude Code for now) to the
// zm-baton server (contract v1 "직접 연 AI 세션을 서버에 등록한다", protocol_version 2).
//
// Executor hooks call `zm-baton hook <event>`; a person runs login, hooks install, session
// work and session off. Hooks always exit 0 and never print errors, so a stopped server never
// gets in the way of the session; errors go to hook.log in the config folder.
package main

import (
	"bufio"
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
  zm-baton hooks install [--project DIR] [--yes] [--dry-run]
                                               add zm-baton hooks to Claude Code settings
  zm-baton hooks uninstall [--project DIR] [--yes]
  zm-baton session work [--ref REF]... [--private|--share] "<what, where>"
                                               tell zm-baton what this session works on;
                                               --private keeps it and later ones unshared
  zm-baton session off                         stop reporting this session
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
		return report(stderr, hooks(args[1:], stdin, stdout))
	case "session":
		return report(stderr, sessionCmd(args[1:], stdout))
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

func hooks(args []string, stdin io.Reader, stdout io.Writer) error {
	if len(args) == 0 || (args[0] != "install" && args[0] != "uninstall") {
		return errors.New("use: zm-baton hooks install|uninstall [--project DIR] [--yes] [--dry-run]")
	}
	fs := flag.NewFlagSet("hooks", flag.ContinueOnError)
	project := fs.String("project", "", "install into <DIR>/.claude/settings.local.json instead of the user settings")
	yes := fs.Bool("yes", false, "do not ask before writing")
	dryRun := fs.Bool("dry-run", false, "print the new settings and write nothing")
	if err := fs.Parse(args[1:]); err != nil {
		return err
	}
	target, err := settingsPath(*project)
	if err != nil {
		return err
	}
	old, err := os.ReadFile(target)
	if err != nil && !errors.Is(err, os.ErrNotExist) {
		return err
	}
	existed := err == nil

	var next []byte
	var lines []string
	if args[0] == "install" {
		exe, err := executable()
		if err != nil {
			return err
		}
		next, lines, err = session.InstallHooks(old, exe)
		if err != nil {
			return err
		}
		lines = append(lines, "명령: "+session.HookCommand(exe, "<event>"))
	} else {
		var removed int
		next, removed, err = session.UninstallHooks(old)
		if err != nil {
			return err
		}
		lines = []string{fmt.Sprintf("zm-baton 항목 %d개를 지운다. 다른 항목은 그대로 둔다", removed)}
	}
	if string(next) == string(old) {
		fmt.Fprintf(stdout, "바뀌는 것이 없다: %s\n", target)
		return nil
	}
	fmt.Fprintf(stdout, "대상 파일: %s\n", target)
	for _, l := range lines {
		fmt.Fprintln(stdout, "  "+l)
	}
	if *dryRun {
		fmt.Fprintln(stdout, "\n--- 쓸 내용(쓰지 않음) ---")
		_, _ = stdout.Write(next)
		return nil
	}
	if !*yes {
		fmt.Fprint(stdout, "계속할까요? [y/N] ")
		answer, _ := bufio.NewReader(stdin).ReadString('\n')
		if a := strings.ToLower(strings.TrimSpace(answer)); a != "y" && a != "yes" {
			fmt.Fprintln(stdout, "그만뒀다. 아무것도 쓰지 않았다.")
			return nil
		}
	}
	if err := writeSettings(target, old, existed, next, stdout); err != nil {
		return err
	}
	fmt.Fprintln(stdout, "썼다. 새로 여는 세션부터 적용된다.")
	return nil
}

// errSettingsChanged means the settings file changed after it was read: someone else's change
// would be lost, so nothing is written.
var errSettingsChanged = errors.New("the settings file changed while zm-baton was waiting; nothing was written. Run the command again")

// writeSettings replaces target with next only if target still holds old, the content the
// person approved a change of. It keeps old as a new backup first and writes through a fresh
// temporary file, so neither an earlier backup nor another run's file is overwritten.
func writeSettings(target string, old []byte, existed bool, next []byte, stdout io.Writer) error {
	unchanged := func() error {
		now, err := os.ReadFile(target)
		switch {
		case errors.Is(err, os.ErrNotExist) && !existed:
			return nil
		case err != nil && !errors.Is(err, os.ErrNotExist):
			return err
		case err != nil || !existed || string(now) != string(old):
			return errSettingsChanged
		}
		return nil
	}
	if err := unchanged(); err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(target), 0o700); err != nil {
		return err
	}
	if existed {
		backup, err := os.OpenFile(target+".zm-baton-backup-"+time.Now().Format("20060102-150405.000000000"), os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0o600)
		if err != nil {
			return fmt.Errorf("backup: %w", err)
		}
		_, werr := backup.Write(old)
		if cerr := backup.Close(); werr == nil {
			werr = cerr
		}
		if werr != nil {
			return fmt.Errorf("backup: %w", werr)
		}
		fmt.Fprintf(stdout, "이전 파일을 남겼다: %s\n", backup.Name())
	}
	tmp, err := os.CreateTemp(filepath.Dir(target), "."+filepath.Base(target)+".zm-baton-*")
	if err != nil {
		return err
	}
	defer func() { _ = os.Remove(tmp.Name()) }()
	_, werr := tmp.Write(next)
	if cerr := tmp.Close(); werr == nil {
		werr = cerr
	}
	if werr != nil {
		return werr
	}
	// Checked again at the last moment; what remains is the instant between this read and the rename.
	if err := unchanged(); err != nil {
		return err
	}
	return os.Rename(tmp.Name(), target)
}

func settingsPath(project string) (string, error) {
	if project != "" {
		abs, err := filepath.Abs(project)
		if err != nil {
			return "", err
		}
		return filepath.Join(abs, ".claude", "settings.local.json"), nil
	}
	home, err := os.UserHomeDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(home, ".claude", "settings.json"), nil
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
