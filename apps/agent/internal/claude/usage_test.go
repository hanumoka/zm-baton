package claude

import (
	"slices"
	"testing"
)

// The lines below are synthetic, in the shape Claude Code 2.1.296 printed them in a short run on a
// subscription login with --max-budget-usd 0.01 (2026-10-14): the init line's apiKeySource, the
// result's subtype and terminal_reason, and the run's totals in modelUsage while the top-level usage
// was all zeros.

func TestLimitFlagsGoBeforeThePromptAndABudgetIsNeverBelowACent(t *testing.T) {
	got := Args(Options{SessionID: sid, AllowedTools: "Read", Prompt: "do it", Restricted: true, MaxTurns: 7, MaxBudgetUSD: 2.5})
	want := []string{
		"-p", "--output-format", "stream-json", "--verbose",
		"--session-id", sid,
		"--permission-prompts", "none",
		"--allowedTools", "Read",
		"--restricted", "--strict-mcp-config",
		"--max-turns", "7", "--max-budget-usd", "2.50",
		"--", "do it",
	}
	if !slices.Equal(got, want) {
		t.Fatalf("args\n got %q\nwant %q", got, want)
	}
	tiny := Args(Options{SessionID: sid, AllowedTools: "Read", StreamInput: true, MaxBudgetUSD: 0.001})
	if i := slices.Index(tiny, "--max-budget-usd"); i < 0 || tiny[i+1] != "0.01" {
		t.Fatalf("a budget under a cent must be passed as 0.01: %q", tiny)
	}
	if none := Args(Options{SessionID: sid, AllowedTools: "Read", Prompt: "x"}); slices.Contains(none, "--max-turns") || slices.Contains(none, "--max-budget-usd") {
		t.Fatalf("no limit, no flag: %q", none)
	}
}

func TestTheInitLineSaysHowTheExecutorSignedIn(t *testing.T) {
	m := NewMapper()
	if _, ok := m.APIKeySource(); ok {
		t.Fatal("no init line yet")
	}
	acts := mapAll(t, m, `{"type":"system","subtype":"init","cwd":"C:\\secret\\place","apiKeySource":"none","session_id":"`+sid+`"}`)
	if len(acts) != 0 {
		t.Fatalf("the init line is not an activity: %v", acts)
	}
	if src, ok := m.APIKeySource(); !ok || src != "none" {
		t.Fatalf("apiKeySource %q %v", src, ok)
	}
	m2 := NewMapper()
	mapAll(t, m2, `{"type":"system","subtype":"init","apiKeySource":"ANTHROPIC_API_KEY"}`)
	if src, _ := m2.APIKeySource(); src != "ANTHROPIC_API_KEY" {
		t.Fatalf("apiKeySource %q", src)
	}
}

func TestAResultAtItsBudgetCarriesTheSubtypeAndTheTotalsPerModel(t *testing.T) {
	m := NewMapper()
	acts := mapAll(t, m, `{"type":"result","subtype":"error_max_budget_usd","is_error":true,"terminal_reason":"budget_exhausted",`+
		`"api_error_status":null,"num_turns":1,"total_cost_usd":0.0170312,"duration_ms":2160,"duration_api_ms":0,`+
		`"usage":{"input_tokens":0,"output_tokens":0,"cache_creation_input_tokens":0,"cache_read_input_tokens":0,"cache_creation":{"ephemeral_1h_input_tokens":0}},`+
		`"modelUsage":{"claude-opus-5-5":{"inputTokens":2,"outputTokens":140,"cacheReadInputTokens":1556,"cacheCreationInputTokens":1739,`+
		`"costUSD":0.0170312,"costBasis":"list","provider":"firstParty","nested":{"x":1}}},`+
		`"errors":["Reached maximum budget ($0.01)"]}`)
	if got := kinds(acts); !slices.Equal(got, []string{KindUsage, KindError}) {
		t.Fatalf("kinds %v", got)
	}
	if m.Succeeded() || m.ResultSubtype() != "error_max_budget_usd" {
		t.Fatalf("succeeded %v subtype %q", m.Succeeded(), m.ResultSubtype())
	}
	u := acts[0].Payload
	if u["subtype"] != "error_max_budget_usd" || u["terminal_reason"] != "budget_exhausted" || u["total_cost_usd"] != 0.0170312 {
		t.Fatalf("usage %v", u)
	}
	if _, ok := u["api_error_status"]; ok {
		t.Fatalf("a null api_error_status is left out: %v", u)
	}
	models, _ := u["model_usage"].(map[string]any)
	opus, _ := models["claude-opus-5-5"].(map[string]any)
	if opus["outputTokens"] != 140.0 || opus["cacheReadInputTokens"] != 1556.0 || opus["costBasis"] != "list" {
		t.Fatalf("model usage %v", models)
	}
	if _, nested := opus["nested"]; nested {
		t.Fatal("nested data must not ride along")
	}
	if acts[1].Payload["text"] != "Reached maximum budget ($0.01)" || acts[1].Payload["subtype"] != "error_max_budget_usd" {
		t.Fatalf("error %v", acts[1].Payload)
	}
}

func TestARateLimitEventCarriesItsWindows(t *testing.T) {
	m := NewMapper()
	acts := mapAll(t, m, `{"type":"rate_limit_event","rate_limit_info":{"status":"allowed","overageStatus":"rejected",`+
		`"overageDisabledReason":"org_level_disabled","isUsingOverage":false,"rateLimitType":"five_hour",`+
		`"unifiedWindows":{"five_hour":{"utilization":0.11,"resetsAt":1760000000},"seven_day":{"utilization":0.32,"resetsAt":1760500000}}}}`)
	if len(acts) != 1 || acts[0].Kind != KindUsage {
		t.Fatalf("acts %v", acts)
	}
	p := acts[0].Payload
	info, _ := p["rate_limit"].(map[string]any)
	if info["status"] != "allowed" || info["isUsingOverage"] != false || info["overageDisabledReason"] != "org_level_disabled" {
		t.Fatalf("rate_limit %v", info)
	}
	if _, nested := info["unifiedWindows"]; nested {
		t.Fatal("the windows ride separately, as scalars")
	}
	w, _ := p["windows"].(map[string]any)
	five, _ := w["five_hour"].(map[string]any)
	if five["utilization"] != 0.11 || five["resetsAt"] != 1760000000.0 {
		t.Fatalf("windows %v", w)
	}
}
