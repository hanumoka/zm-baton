package agent

import (
	"bufio"
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"slices"
	"strconv"
	"strings"
	"time"
)

// fakeClaude is this test binary in "fake Claude Code" mode (TestMain): it prints synthetic
// stream-json lines in the shape Claude Code 2.1.296 prints them. Like the real one in
// --input-format stream-json mode, it reads the prompt as the first stdin message, echoes every
// message it takes when --replay-user-messages is set, and after its result waits for stdin to end.
func fakeClaude(mode string, args []string) int {
	if slices.Equal(args, []string{"--version"}) {
		v := os.Getenv("ZMB_FAKE_CLAUDE_VERSION")
		if v == "" {
			v = "2.1.296"
		}
		fmt.Println(v + " (Claude Code)")
		return 0
	}
	flag := func(name string) (string, bool) {
		for i, a := range args {
			if a == name && i+1 < len(args) {
				return args[i+1], true
			}
		}
		return "", false
	}
	has := func(name string) bool { return slices.Contains(args, name) }
	session, _ := flag("--session-id")
	resumed, isResume := flag("--resume")
	if isResume {
		session = resumed
	}
	stream := has("--input-format")
	n := len(args)
	if session == "" || n < 2 || args[0] != "-p" || (!stream && args[n-2] != "--") {
		fmt.Fprintln(os.Stderr, "fake claude: unexpected arguments")
		return 9
	}
	emit := func(v map[string]any) {
		v["session_id"] = session
		line, _ := json.Marshal(v)
		fmt.Println(string(line))
	}
	blocks := func(role string, b ...map[string]any) map[string]any {
		list := make([]any, len(b))
		for i := range b {
			list[i] = b[i]
		}
		return map[string]any{"type": role, "message": map[string]any{"content": list}}
	}
	in := bufio.NewScanner(os.Stdin)
	in.Buffer(make([]byte, 1<<20), 1<<20)
	// take reads the next user message from stdin and echoes it, as Claude Code does.
	take := func() (string, bool) {
		if !in.Scan() {
			return "", false
		}
		var msg struct {
			Type    string `json:"type"`
			Message struct {
				Content string `json:"content"`
			} `json:"message"`
		}
		if json.Unmarshal(in.Bytes(), &msg) != nil || msg.Type != "user" {
			fmt.Fprintln(os.Stderr, "fake claude: stdin line is not a user message")
			os.Exit(10)
		}
		if has("--replay-user-messages") {
			emit(map[string]any{"type": "user", "message": map[string]any{"role": "user", "content": msg.Message.Content}, "isReplay": true})
		}
		return msg.Message.Content, true
	}
	prompt := ""
	if !stream {
		prompt = args[n-1]
	}
	// finish prints the result and, in stream mode, waits for the connector to end the input.
	finish := func(code int) int {
		emit(map[string]any{"type": "result", "subtype": "success", "is_error": false, "result": "done", "num_turns": 2,
			"usage": map[string]any{"output_tokens": 3}})
		if stream {
			deadline := time.AfterFunc(10*time.Second, func() {
				fmt.Fprintln(os.Stderr, "fake claude: stdin was never closed after the result")
				os.Exit(4)
			})
			for in.Scan() {
				// A message after the result would start another turn. None is expected: the
				// connector writes each instruction once and ends the input once the result came.
				// Say so on stdout, where the connector still reads, so a test can see it.
				emit(blocks("assistant", map[string]any{"type": "text", "text": "extra message after the result: " + in.Text()}))
			}
			deadline.Stop()
		}
		return code
	}
	if mode == "resume-missing" && isResume {
		// Claude Code 2.1.296 with --resume of a session it does not have.
		fmt.Fprintln(os.Stderr, "No conversation found with session ID: "+session)
		emit(map[string]any{"type": "result", "subtype": "error_during_execution", "is_error": true, "num_turns": 0,
			"errors": []string{"No conversation found with session ID: " + session}})
		return 1
	}
	emit(map[string]any{"type": "system", "subtype": "init", "cwd": "/fake/should-not-leak"})
	if stream {
		var ok bool
		if prompt, ok = take(); !ok {
			fmt.Fprintln(os.Stderr, "fake claude: no prompt on stdin")
			return 11
		}
	}
	switch mode {
	case "success":
		if !has("--restricted") || !has("--strict-mcp-config") {
			fmt.Fprintln(os.Stderr, "fake claude: the run must be restricted")
			return 5
		}
		emit(blocks("assistant", map[string]any{"type": "thinking", "thinking": "plan"}))
		emit(blocks("assistant", map[string]any{"type": "tool_use", "id": "toolu_1", "name": "Read",
			"input": map[string]any{"file_path": "a.txt"}}))
		emit(blocks("user", map[string]any{"type": "tool_result", "tool_use_id": "toolu_1", "content": "text"}))
		emit(blocks("assistant", map[string]any{"type": "text", "text": "echo: " + prompt}))
		emit(map[string]any{"type": "rate_limit_event", "rate_limit_info": map[string]any{"status": "allowed"}})
		return finish(0)
	case "noresult":
		emit(blocks("assistant", map[string]any{"type": "text", "text": "partial"}))
		return 3
	case "hang":
		time.Sleep(60 * time.Second)
		return 0
	case "child":
		// Starts a child that would outlive it, then hangs: a stop must end both.
		child := exec.Command(os.Args[0])
		child.Env = append(os.Environ(), fakeEnv+"=hang")
		if err := child.Start(); err != nil {
			return 12
		}
		_ = os.WriteFile("child.pid", []byte(strconv.Itoa(child.Process.Pid)), 0o644)
		emit(blocks("assistant", map[string]any{"type": "text", "text": "started a child"}))
		time.Sleep(60 * time.Second)
		return 0
	case "inputs":
		// Works until a person's instruction arrives, then answers it.
		emit(blocks("assistant", map[string]any{"type": "text", "text": "working on: " + prompt}))
		got := make(chan string, 1)
		go func() {
			text, _ := take()
			got <- text
		}()
		select {
		case text := <-got:
			emit(blocks("assistant", map[string]any{"type": "text", "text": "got: " + text}))
		case <-time.After(15 * time.Second):
			emit(blocks("assistant", map[string]any{"type": "text", "text": "no instruction came"}))
		}
		return finish(0)
	case "account", "account-maxturns":
		// A delegated run on an AI account: it says how it would sign in, as Claude Code's init line
		// does, and which limits and configuration folder it got, without ever printing a value.
		if os.Getenv("ZM_BATON_OFF") != "1" || !has("--restricted") {
			fmt.Fprintln(os.Stderr, "fake claude: a delegated run must be restricted with the hooks off")
			return 5
		}
		present := func(name string) string {
			if _, ok := os.LookupEnv(name); ok {
				return "present"
			}
			return "absent"
		}
		source := "none"
		if present("ANTHROPIC_API_KEY") == "present" {
			source = "ANTHROPIC_API_KEY"
		}
		emit(map[string]any{"type": "system", "subtype": "init", "apiKeySource": source})
		config := "none"
		if dir, ok := os.LookupEnv("CLAUDE_CONFIG_DIR"); ok {
			switch under := os.Getenv("ZMB_FAKE_CONFIG_UNDER"); {
			case under != "" && strings.HasPrefix(dir, under):
				config = "own"
			case dir == os.Getenv("ZMB_FAKE_PERSON_CONFIG"):
				config = "kept"
			default:
				config = "other"
			}
		}
		turns, _ := flag("--max-turns")
		budget, _ := flag("--max-budget-usd")
		emit(blocks("assistant", map[string]any{"type": "text", "text": fmt.Sprintf(
			"key=%s auth_token=%s oauth=%s bedrock=%s config=%s max-turns=%s max-budget=%s",
			present("ANTHROPIC_API_KEY"), present("ANTHROPIC_AUTH_TOKEN"), present("CLAUDE_CODE_OAUTH_TOKEN"),
			present("CLAUDE_CODE_USE_BEDROCK"), config, turns, budget)}))
		if mode == "account-maxturns" {
			emit(map[string]any{"type": "result", "subtype": "error_max_turns", "is_error": true, "num_turns": 3,
				"errors": []string{"Reached maximum number of turns (3)"}, "total_cost_usd": 0.2})
			if stream {
				for in.Scan() {
				}
			}
			return 1
		}
		emit(map[string]any{"type": "result", "subtype": "success", "is_error": false, "result": "done", "num_turns": 2,
			"total_cost_usd": 0.0170312, "usage": map[string]any{"output_tokens": 0},
			"modelUsage": map[string]any{"claude-opus-5-5": map[string]any{"inputTokens": 2, "outputTokens": 140, "costUSD": 0.0170312}}})
		if stream {
			for in.Scan() {
			}
		}
		return 0
	case "master":
		// The master's contradiction check: restricted, the hooks off, read-only tools, an empty folder
		// of its own and the instruction on stdin. It answers with the JSON in ZMB_FAKE_RESULT.
		if os.Getenv("ZM_BATON_OFF") != "1" || !has("--restricted") || !has("--strict-mcp-config") || !stream {
			fmt.Fprintln(os.Stderr, "fake claude: a master run must be restricted, with the hooks off and the prompt on stdin")
			return 5
		}
		if tools, _ := flag("--allowedTools"); tools != "Read,Glob,Grep" {
			fmt.Fprintln(os.Stderr, "fake claude: a master run gets read-only tools, not "+tools)
			return 14
		}
		if entries, err := os.ReadDir("."); err != nil || len(entries) > 0 {
			fmt.Fprintln(os.Stderr, "fake claude: a master run starts in an empty folder")
			return 15
		}
		if !strings.Contains(prompt, "S1 시작") {
			fmt.Fprintln(os.Stderr, "fake claude: the prompt does not carry the material")
			return 16
		}
		answer := os.Getenv("ZMB_FAKE_RESULT")
		emit(blocks("assistant", map[string]any{"type": "text", "text": answer}))
		emit(map[string]any{"type": "result", "subtype": "success", "is_error": false, "result": answer, "num_turns": 1,
			"total_cost_usd": 0.01, "usage": map[string]any{"output_tokens": 40}})
		if stream {
			for in.Scan() {
			}
		}
		return 0
	case "edit", "edit-resume", "resume-missing":
		// A delegated run: it must run with zm-baton's own hooks off, restricted, then change files in its folder.
		if os.Getenv("ZM_BATON_OFF") != "1" {
			fmt.Fprintln(os.Stderr, "fake claude: ZM_BATON_OFF is not set")
			return 7
		}
		if !has("--restricted") || !has("--strict-mcp-config") {
			fmt.Fprintln(os.Stderr, "fake claude: a delegated run must be restricted")
			return 5
		}
		if mode == "edit-resume" && !isResume {
			fmt.Fprintln(os.Stderr, "fake claude: expected --resume")
			return 13
		}
		tools, _ := flag("--allowedTools")
		name := "made-by-run.txt"
		if isResume {
			name = "made-by-resume.txt"
		}
		if !isResume {
			if err := os.WriteFile("README.md", []byte("changed by the run\n"), 0o644); err != nil {
				return 6
			}
		}
		if err := os.WriteFile(name, []byte("new\n"), 0o644); err != nil {
			return 6
		}
		// Tools report absolute paths; the connector must cut the worktree's out.
		cwd, _ := os.Getwd()
		made := filepath.Join(cwd, name)
		emit(blocks("assistant", map[string]any{"type": "tool_use", "id": "toolu_w", "name": "Write",
			"input": map[string]any{"file_path": made}}))
		emit(blocks("user", map[string]any{"type": "tool_result", "tool_use_id": "toolu_w",
			"content": "File created successfully at: " + made + " (also " + filepath.ToSlash(made) + ")"}))
		emit(blocks("assistant", map[string]any{"type": "text", "text": "tools: " + tools + "; prompt: " + prompt}))
		return finish(0)
	}
	return 8
}
