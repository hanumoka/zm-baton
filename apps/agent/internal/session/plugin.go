package session

import (
	"bytes"
	"encoding/json"
	"path/filepath"
)

// zm-baton reaches Claude Code as a plugin in a local marketplace folder that zm-baton owns
// (code.claude.com/docs/en/plugins). zm-baton writes only that folder; Claude Code's own
// `claude plugin` commands add it to a settings file. zm-baton never edits Claude Code settings.
const (
	MarketplaceName = "zm-baton-local"
	PluginName      = "zm-baton"
	pluginVersion   = "0.2.0"
)

// HookSpec is one Claude Code hook zm-baton adds (contract "직접 연 AI 세션을 서버에 등록한다").
type HookSpec struct {
	Event   string // Claude Code event name
	Arg     string // `zm-baton hook <Arg>`
	Timeout int    // seconds
	Matcher string // Claude Code matcher; empty matches everything
}

// ClaudeHooks are the hooks for a Claude Code session. Most have no matcher: every source, tool
// and notification type reaches zm-baton, which decides what to send. PreToolUse matches only
// AskUserQuestion, so no other tool waits for a process start. The relay hooks wait for a person
// on the first screen: their timeout is longer than the server's relay deadline (60 seconds, at
// most 70) and the hook's own limit (80 seconds), so the server's deadline always ends the wait.
var ClaudeHooks = []HookSpec{
	{"SessionStart", "session-start", 10, ""},
	{"UserPromptSubmit", "prompt-submit", 5, ""},
	{"PostToolUse", "post-tool-use", 5, ""},
	{"Notification", "notification", 5, ""},
	{"SessionEnd", "session-end", 2, ""},
	{"PermissionRequest", "permission-request", 90, ""},
	{"PreToolUse", "pre-tool-use", 90, "AskUserQuestion"},
}

// HookExecutable is the executable a hook runs. Each hook names it apart from its arguments
// (Claude Code's exec form: `command` plus `args`), so no shell reads the path and characters
// such as spaces, quotes or `$` stay part of it.
func HookExecutable(exe string) string { return filepath.ToSlash(exe) }

// HookArgs are the arguments of one hook.
func HookArgs(arg string) []string { return []string{"hook", arg} }

// PluginFiles returns the marketplace folder's files, by path relative to its root, for the
// executable at exe. The plugin loads in place, so writing them again takes effect at the
// next session without reinstalling.
func PluginFiles(exe string) (map[string][]byte, error) {
	type hook struct {
		Type    string   `json:"type"`
		Command string   `json:"command"`
		Args    []string `json:"args"`
		Timeout int      `json:"timeout"`
	}
	type entry struct {
		Matcher string `json:"matcher,omitempty"`
		Hooks   []hook `json:"hooks"`
	}
	events := map[string][]entry{}
	for _, spec := range ClaudeHooks {
		events[spec.Event] = []entry{{Matcher: spec.Matcher, Hooks: []hook{{"command", HookExecutable(exe), HookArgs(spec.Arg), spec.Timeout}}}}
	}
	docs := map[string]any{
		".claude-plugin/marketplace.json": map[string]any{
			"name":  MarketplaceName,
			"owner": map[string]string{"name": "zm-baton"},
			"plugins": []map[string]string{{
				"name": PluginName, "source": "./" + PluginName,
				"description": "Registers this Claude Code session with the zm-baton server on this PC and relays its permission requests and questions to the first screen",
			}},
		},
		PluginName + "/.claude-plugin/plugin.json": map[string]any{
			"name": PluginName, "version": pluginVersion,
			"description": "Registers this Claude Code session with the zm-baton server on this PC and relays its permission requests and questions to the first screen",
			"author":      map[string]string{"name": "zm-baton"},
		},
		PluginName + "/hooks/hooks.json": map[string]any{
			"description": "zm-baton session hooks: registration, current status card, file changes, waits, first-screen relays",
			"hooks":       events,
		},
	}
	files := map[string][]byte{}
	for path, doc := range docs {
		var buf bytes.Buffer
		enc := json.NewEncoder(&buf)
		enc.SetEscapeHTML(false)
		enc.SetIndent("", "  ")
		if err := enc.Encode(doc); err != nil {
			return nil, err
		}
		files[path] = buf.Bytes()
	}
	return files, nil
}

// WritePlugin writes the marketplace folder under root, which lies in zm-baton's own config folder.
func WritePlugin(root, exe string) error {
	files, err := PluginFiles(exe)
	if err != nil {
		return err
	}
	for rel, data := range files {
		if err := writeFileAtomic(filepath.Join(root, filepath.FromSlash(rel)), data); err != nil {
			return err
		}
	}
	return nil
}
