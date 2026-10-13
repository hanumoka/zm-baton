package session

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestPluginFilesCarryTheFiveHooksForTheExecutable(t *testing.T) {
	exe := filepath.Join(t.TempDir(), "Program Files", "zm-baton.exe")
	files, err := PluginFiles(exe)
	if err != nil {
		t.Fatal(err)
	}
	var market struct {
		Name    string `json:"name"`
		Owner   struct{ Name string }
		Plugins []struct{ Name, Source string }
	}
	if err := json.Unmarshal(files[".claude-plugin/marketplace.json"], &market); err != nil {
		t.Fatal(err)
	}
	if market.Name != MarketplaceName || market.Owner.Name == "" || len(market.Plugins) != 1 ||
		market.Plugins[0].Name != PluginName || market.Plugins[0].Source != "./"+PluginName {
		t.Errorf("marketplace %+v", market)
	}
	var plugin struct{ Name, Version string }
	if err := json.Unmarshal(files[PluginName+"/.claude-plugin/plugin.json"], &plugin); err != nil || plugin.Name != PluginName || plugin.Version == "" {
		t.Errorf("plugin.json %+v %v", plugin, err)
	}
	var hooks struct {
		Hooks map[string][]struct {
			Hooks []struct {
				Type    string
				Command string
				Args    []string
				Timeout int
			}
		}
	}
	if err := json.Unmarshal(files[PluginName+"/hooks/hooks.json"], &hooks); err != nil {
		t.Fatal(err)
	}
	if len(hooks.Hooks) != len(ClaudeHooks) {
		t.Fatalf("%d events, want %d", len(hooks.Hooks), len(ClaudeHooks))
	}
	for _, spec := range ClaudeHooks {
		entries := hooks.Hooks[spec.Event]
		if len(entries) != 1 || len(entries[0].Hooks) != 1 {
			t.Fatalf("%s: %+v", spec.Event, entries)
		}
		h := entries[0].Hooks[0]
		// Exec form: the executable alone in command, the rest in args, so no shell reads the path.
		if h.Type != "command" || h.Command != filepath.ToSlash(exe) || len(h.Args) != 2 ||
			h.Args[0] != "hook" || h.Args[1] != spec.Arg || h.Timeout != spec.Timeout {
			t.Errorf("%s: %+v", spec.Event, h)
		}
		if strings.Contains(h.Command, `\`) {
			t.Errorf("%s: backslash in %q", spec.Event, h.Command)
		}
	}
}

func TestWritePluginRewritesInPlace(t *testing.T) {
	root := filepath.Join(t.TempDir(), "claude-plugin")
	if err := WritePlugin(root, "C:/old/zm-baton.exe"); err != nil {
		t.Fatal(err)
	}
	if err := WritePlugin(root, "C:/new/zm-baton.exe"); err != nil {
		t.Fatal(err)
	}
	data, err := os.ReadFile(filepath.Join(root, PluginName, "hooks", "hooks.json"))
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(data), "C:/old/") || !strings.Contains(string(data), "C:/new/zm-baton.exe") {
		t.Errorf("hooks.json after moving the executable:\n%s", data)
	}
}
