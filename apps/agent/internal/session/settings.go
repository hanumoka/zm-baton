package session

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"path/filepath"
	"regexp"
	"strings"
)

// HookSpec is one Claude Code hook zm-baton adds (contract "`zm-baton hooks install`").
type HookSpec struct {
	Event   string // Claude Code event name
	Arg     string // `zm-baton hook <Arg>`
	Timeout int    // seconds
}

// ClaudeHooks are the hooks for a Claude Code session. No matcher: every source, tool and
// notification type reaches zm-baton, which decides what to send.
var ClaudeHooks = []HookSpec{
	{"SessionStart", "session-start", 10},
	{"UserPromptSubmit", "prompt-submit", 5},
	{"PostToolUse", "post-tool-use", 5},
	{"Notification", "notification", 5},
	{"SessionEnd", "session-end", 2},
}

// HookCommand is the command line put into settings. Forward slashes work in every shell
// Claude Code uses for hooks, and the quotes keep a path with spaces in one piece.
func HookCommand(exe, arg string) string {
	return `"` + filepath.ToSlash(exe) + `" hook ` + arg
}

// ourCommand matches `"<…>/zm-baton<…>" hook <event>`: an executable whose name starts with
// zm-baton, called with one of zm-baton's hook events and nothing after it.
var ourCommand = regexp.MustCompile(`(^|[/\\"])zm-baton[^"/\\ ]*"? hook (session-start|prompt-submit|post-tool-use|notification|session-end)$`)

// IsOurCommand reports whether a hook command is one zm-baton installed.
func IsOurCommand(cmd string) bool { return ourCommand.MatchString(strings.TrimSpace(cmd)) }

// member is one key of a JSON object, its value kept byte for byte.
type member struct {
	key   string
	value json.RawMessage
}

// readObject reads a JSON object keeping key order. Empty input is an empty object.
func readObject(data []byte) ([]member, error) {
	if len(bytes.TrimSpace(data)) == 0 {
		return nil, nil
	}
	dec := json.NewDecoder(bytes.NewReader(data))
	dec.UseNumber()
	tok, err := dec.Token()
	if err != nil {
		return nil, err
	}
	if d, ok := tok.(json.Delim); !ok || d != '{' {
		return nil, errors.New("not a JSON object")
	}
	var members []member
	for dec.More() {
		tok, err := dec.Token()
		if err != nil {
			return nil, err
		}
		key, ok := tok.(string)
		if !ok {
			return nil, errors.New("object key is not a string")
		}
		var raw json.RawMessage
		if err := dec.Decode(&raw); err != nil {
			return nil, err
		}
		members = append(members, member{key, raw})
	}
	if _, err := dec.Token(); err != nil { // the closing brace
		return nil, err
	}
	if _, err := dec.Token(); err != io.EOF {
		return nil, errors.New("extra data after the JSON object")
	}
	return members, nil
}

// writeObject writes members in order, indented with two spaces like Claude Code's own files.
func writeObject(members []member) ([]byte, error) {
	var compact bytes.Buffer
	compact.WriteByte('{')
	for i, m := range members {
		if i > 0 {
			compact.WriteByte(',')
		}
		key, _ := marshal(m.key)
		compact.Write(key)
		compact.WriteByte(':')
		compact.Write(m.value)
	}
	compact.WriteByte('}')
	var out bytes.Buffer
	if err := json.Indent(&out, compact.Bytes(), "", "  "); err != nil {
		return nil, err
	}
	out.WriteByte('\n')
	return out.Bytes(), nil
}

// marshal is json.Marshal without HTML escaping, so `&&` in another tool's hook command stays
// `&&` instead of becoming `&&` when zm-baton rewrites the file.
func marshal(v any) ([]byte, error) {
	var buf bytes.Buffer
	enc := json.NewEncoder(&buf)
	enc.SetEscapeHTML(false)
	if err := enc.Encode(v); err != nil {
		return nil, err
	}
	return bytes.TrimRight(buf.Bytes(), "\n"), nil
}

func find(members []member, key string) int {
	for i, m := range members {
		if m.key == key {
			return i
		}
	}
	return -1
}

type hookEntry struct {
	Hooks []struct {
		Command string `json:"command"`
	} `json:"hooks"`
}

// onlyOurs reports whether a matcher entry holds exactly one hook and that hook is zm-baton's.
// zm-baton only ever writes such entries, so it never edits an entry another tool owns.
func onlyOurs(raw json.RawMessage) bool {
	var e hookEntry
	if err := json.Unmarshal(raw, &e); err != nil {
		return false
	}
	return len(e.Hooks) == 1 && IsOurCommand(e.Hooks[0].Command)
}

// InstallHooks returns settings with zm-baton's hooks for exe, replacing older zm-baton entries
// and keeping everything else as it was. changes says what moved, one line per event.
func InstallHooks(settings []byte, exe string) ([]byte, []string, error) {
	top, err := readObject(settings)
	if err != nil {
		return nil, nil, fmt.Errorf("settings file: %w", err)
	}
	hi := find(top, "hooks")
	var events []member
	if hi >= 0 {
		if events, err = readObject(top[hi].value); err != nil {
			return nil, nil, fmt.Errorf("settings hooks: %w", err)
		}
	}
	var changes []string
	for _, spec := range ClaudeHooks {
		entry, _ := marshal(map[string]any{
			"hooks": []map[string]any{{"type": "command", "command": HookCommand(exe, spec.Arg), "timeout": spec.Timeout}},
		})
		ei := find(events, spec.Event)
		var entries []json.RawMessage
		if ei >= 0 {
			if err := json.Unmarshal(events[ei].value, &entries); err != nil {
				return nil, nil, fmt.Errorf("settings hooks.%s is not a list: %w", spec.Event, err)
			}
		}
		kept := entries[:0:0]
		replaced := 0
		for _, e := range entries {
			if onlyOurs(e) {
				replaced++
				continue
			}
			kept = append(kept, e)
		}
		kept = append(kept, entry)
		value, err := marshal(kept)
		if err != nil {
			return nil, nil, err
		}
		if ei >= 0 {
			events[ei].value = value
		} else {
			events = append(events, member{spec.Event, value})
		}
		if replaced > 0 {
			changes = append(changes, fmt.Sprintf("%s: zm-baton 항목 %d개를 새 항목으로 바꾼다", spec.Event, replaced))
		} else {
			changes = append(changes, fmt.Sprintf("%s: zm-baton 항목을 더한다 (다른 항목 %d개는 그대로)", spec.Event, len(entries)))
		}
	}
	hooksValue, err := writeObject(events)
	if err != nil {
		return nil, nil, err
	}
	if hi >= 0 {
		top[hi].value = hooksValue
	} else {
		top = append(top, member{"hooks", hooksValue})
	}
	out, err := writeObject(top)
	return out, changes, err
}

// UninstallHooks removes zm-baton's entries only. An event list left empty is removed.
func UninstallHooks(settings []byte) ([]byte, int, error) {
	top, err := readObject(settings)
	if err != nil {
		return nil, 0, fmt.Errorf("settings file: %w", err)
	}
	hi := find(top, "hooks")
	if hi < 0 {
		out, err := writeObject(top)
		return out, 0, err
	}
	events, err := readObject(top[hi].value)
	if err != nil {
		return nil, 0, fmt.Errorf("settings hooks: %w", err)
	}
	removed := 0
	kept := events[:0:0]
	for _, ev := range events {
		var entries []json.RawMessage
		if err := json.Unmarshal(ev.value, &entries); err != nil {
			kept = append(kept, ev) // not a list: not ours to touch
			continue
		}
		left := entries[:0:0]
		for _, e := range entries {
			if onlyOurs(e) {
				removed++
				continue
			}
			left = append(left, e)
		}
		if len(left) == 0 && len(entries) > 0 {
			continue
		}
		value, err := marshal(left)
		if err != nil {
			return nil, 0, err
		}
		kept = append(kept, member{ev.key, value})
	}
	hooksValue, err := writeObject(kept)
	if err != nil {
		return nil, 0, err
	}
	top[hi].value = hooksValue
	out, err := writeObject(top)
	return out, removed, err
}
