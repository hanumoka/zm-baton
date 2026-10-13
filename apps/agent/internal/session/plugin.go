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
	pluginVersion   = "0.1.0"
)

// HookSpec is one Claude Code hook zm-baton adds (contract "직접 연 AI 세션을 서버에 등록한다").
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
		Hooks []hook `json:"hooks"`
	}
	events := map[string][]entry{}
	for _, spec := range ClaudeHooks {
		events[spec.Event] = []entry{{Hooks: []hook{{"command", HookExecutable(exe), HookArgs(spec.Arg), spec.Timeout}}}}
	}
	docs := map[string]any{
		".claude-plugin/marketplace.json": map[string]any{
			"name":  MarketplaceName,
			"owner": map[string]string{"name": "zm-baton"},
			"plugins": []map[string]string{{
				"name": PluginName, "source": "./" + PluginName,
				"description": "Registers this Claude Code session with the zm-baton server on this PC",
			}},
		},
		PluginName + "/.claude-plugin/plugin.json": map[string]any{
			"name": PluginName, "version": pluginVersion,
			"description": "Registers this Claude Code session with the zm-baton server on this PC",
			"author":      map[string]string{"name": "zm-baton"},
		},
		PluginName + "/hooks/hooks.json": map[string]any{
			"description": "zm-baton session hooks: registration, current status card, file changes, waits",
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
