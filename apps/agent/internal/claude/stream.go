// Package claude is the Claude Code adapter for the stage-1 compat test.
// It builds the headless command line and maps stream-json output lines to
// contract v1 activity kinds ("공통 활동 종류").
package claude

import (
	"encoding/json"
	"strconv"
	"strings"
	"unicode/utf8"
)

// Executor is the runs.executor value this adapter handles.
const Executor = "claude_code"

// MaxText is the longest text (in characters) copied into one payload field.
const MaxText = 4000

// Activity kinds (contract v1 "공통 활동 종류") plus lifecycle.
const (
	KindThought      = "thought"
	KindAction       = "action"
	KindActionResult = "action_result"
	KindAnswer       = "answer"
	KindQuestion     = "question"
	KindError        = "error"
	KindUsage        = "usage"
	KindLifecycle    = "lifecycle"
)

// Options are the per-run inputs to the Claude Code command line.
type Options struct {
	SessionID    string // new UUIDv4 per run, or the session a resumed run continues; also the kill marker
	AllowedTools string // comma-separated, passed to --allowedTools
	Prompt       string
	// Restricted adds --restricted and --strict-mcp-config (Claude Code 2.1.296): the user's,
	// project's and local settings files are ignored, so the person's own permission mode and
	// allow rules do not widen what --allowedTools grants; command-running tools (Bash,
	// PowerShell) are removed; file tools stay in the working folder; and no MCP server of the
	// person's own setup is started. Both narrow what the executor can do.
	Restricted bool
	// StreamInput takes the prompt and later messages on stdin as stream-json user messages
	// (--input-format stream-json), so a person can send an instruction while the run goes on
	// (contract "어댑터가 알려야 하는 능력" 실행 중 입력). --replay-user-messages echoes each message
	// back once Claude Code took it into the conversation: that is the delivery confirmation.
	// The prompt is then not an argument; write UserMessage(prompt) first.
	StreamInput bool
	// Resume continues the executor session SessionID with --resume instead of starting it
	// (contract "실행마다 별도 작업 트리" 6). The session id still sits in a flag: it stays the marker.
	Resume bool
	// MaxTurns, when above 0, is passed as --max-turns: Claude Code ends the run with the result
	// subtype error_max_turns when it reaches that many turns (FN-0409 run limit).
	MaxTurns int
	// MaxBudgetUSD, when above 0, is passed as --max-budget-usd: Claude Code ends the run with
	// error_max_budget_usd once its price-list cost estimate reaches it. Measured with Claude Code
	// 2.1.296 on a subscription login: it applies there too. It is never below one cent (0 is refused).
	MaxBudgetUSD float64
}

// MinBudgetUSD is the smallest --max-budget-usd passed; Claude Code refuses 0.
const MinBudgetUSD = 0.01

// Args builds the argument list (without the program name). The session id is the
// marker checked before a kill, so it sits in a flag and never in the prompt
// (contract v1 "프로세스 관리" 2). "--" ends option parsing so the prompt is never
// read as another --allowedTools value or as a flag.
func Args(o Options) []string {
	session := "--session-id"
	if o.Resume {
		session = "--resume"
	}
	args := []string{
		"-p",
		"--output-format", "stream-json",
		"--verbose",
		session, o.SessionID,
		"--permission-prompts", "none",
		"--allowedTools", o.AllowedTools,
	}
	if o.Restricted {
		args = append(args, "--restricted", "--strict-mcp-config")
	}
	if o.MaxTurns > 0 {
		args = append(args, "--max-turns", strconv.Itoa(o.MaxTurns))
	}
	if o.MaxBudgetUSD > 0 {
		args = append(args, "--max-budget-usd", strconv.FormatFloat(max(o.MaxBudgetUSD, MinBudgetUSD), 'f', 2, 64))
	}
	if o.StreamInput {
		return append(args, "--input-format", "stream-json", "--replay-user-messages")
	}
	return append(args, "--", o.Prompt)
}

// UserMessage is one stream-json input line carrying text as a user message (StreamInput).
func UserMessage(text string) []byte {
	line, _ := json.Marshal(map[string]any{"type": "user", "message": map[string]any{"role": "user", "content": text}})
	return append(line, '\n')
}

// Activity is one contract event before it gets a seq.
type Activity struct {
	Kind    string
	Payload map[string]any
}

// Mapper turns stream-json lines into activities and remembers how the run ended.
// It is not safe for concurrent use.
type Mapper struct {
	resultSeen bool
	failed     bool
	sessionID  string
	dropped    map[string]int
	// replays are the user messages Claude Code echoed back after taking them (StreamInput).
	replays []string
	// numTurns is the last result's turn count; assistantSeen whether any assistant line arrived.
	numTurns      float64
	assistantSeen bool
	// apiKeySource is what the init line said the executor signs in with ("none" for a login);
	// initSeen whether an init line arrived. resultSubtype is the last result's subtype.
	apiKeySource  string
	initSeen      bool
	resultSubtype string
}

// NewMapper returns an empty mapper.
func NewMapper() *Mapper {
	return &Mapper{dropped: map[string]int{}}
}

// ResultSeen reports whether the final "result" line arrived.
func (m *Mapper) ResultSeen() bool { return m.resultSeen }

// Succeeded reports whether the result line arrived and was not an error.
func (m *Mapper) Succeeded() bool { return m.resultSeen && !m.failed }

// SessionID is the session id Claude reported, if any line carried one.
func (m *Mapper) SessionID() string { return m.sessionID }

// APIKeySource is the init line's apiKeySource: "none" when Claude Code signs in with a login,
// "ANTHROPIC_API_KEY" when it uses that variable (Claude Code 2.1.296, measured). ok is false
// when no init line arrived.
func (m *Mapper) APIKeySource() (source string, ok bool) { return m.apiKeySource, m.initSeen }

// ResultSubtype is the last result's subtype, e.g. success, error_max_turns or error_max_budget_usd.
func (m *Mapper) ResultSubtype() string { return m.resultSubtype }

// TakeReplays returns the user messages echoed back since the last call, oldest first.
func (m *Mapper) TakeReplays() []string {
	out := m.replays
	m.replays = nil
	return out
}

// ResumeRefused reports whether the run ended with an error result before any turn and without
// any assistant output: what Claude Code 2.1.296 prints when --resume names a session it does not
// have ("No conversation found with session ID").
func (m *Mapper) ResumeRefused() bool {
	return m.resultSeen && m.failed && m.numTurns == 0 && !m.assistantSeen
}

// Dropped counts lines and blocks that produced no event, by reason.
func (m *Mapper) Dropped() map[string]int {
	out := make(map[string]int, len(m.dropped))
	for k, v := range m.dropped {
		out[k] = v
	}
	return out
}

type streamLine struct {
	Type            string          `json:"type"`
	Subtype         string          `json:"subtype"`
	SessionID       string          `json:"session_id"`
	ParentToolUseID *string         `json:"parent_tool_use_id"`
	Message         *streamMessage  `json:"message"`
	IsError         bool            `json:"is_error"`
	IsReplay        bool            `json:"isReplay"`
	Result          string          `json:"result"`
	Errors          json.RawMessage `json:"errors"`
	Usage           map[string]any  `json:"usage"`
	TotalCostUSD    *float64        `json:"total_cost_usd"`
	DurationMS      *float64        `json:"duration_ms"`
	DurationAPIMS   *float64        `json:"duration_api_ms"`
	NumTurns        *float64        `json:"num_turns"`
	RateLimitInfo   map[string]any  `json:"rate_limit_info"`
	APIKeySource    *string         `json:"apiKeySource"`
	TerminalReason  string          `json:"terminal_reason"`
	APIErrorStatus  *float64        `json:"api_error_status"`
	ModelUsage      map[string]any  `json:"modelUsage"`
	Denials         []struct {
		ToolName string `json:"tool_name"`
	} `json:"permission_denials"`
}

type streamMessage struct {
	Content json.RawMessage `json:"content"`
}

type contentBlock struct {
	Type      string          `json:"type"`
	Text      string          `json:"text"`
	Thinking  string          `json:"thinking"`
	ID        string          `json:"id"`
	Name      string          `json:"name"`
	Input     any             `json:"input"`
	ToolUseID string          `json:"tool_use_id"`
	Content   json.RawMessage `json:"content"`
	IsError   bool            `json:"is_error"`
}

// Map converts one output line. Lines that are not mapped (system lines, unknown
// types, broken JSON) produce nothing and are counted in Dropped.
func (m *Mapper) Map(line []byte) []Activity {
	line = []byte(strings.TrimSpace(string(line)))
	if len(line) == 0 {
		return nil
	}
	var sl streamLine
	if err := json.Unmarshal(line, &sl); err != nil || sl.Type == "" {
		m.dropped["invalid_json"]++
		return nil
	}
	if sl.SessionID != "" && m.sessionID == "" {
		m.sessionID = sl.SessionID
	}
	switch sl.Type {
	case "user":
		if sl.IsReplay {
			m.replays = append(m.replays, replayText(sl.Message))
			m.dropped["user:replay"]++
			return nil
		}
		return m.mapMessage(sl)
	case "assistant":
		m.assistantSeen = true
		return m.mapMessage(sl)
	case "system":
		if sl.Subtype == "init" {
			m.initSeen = true
			if sl.APIKeySource != nil {
				m.apiKeySource = truncate(*sl.APIKeySource, 60)
			}
		}
		m.dropped["type:system"]++
		return nil
	case "rate_limit_event":
		p := map[string]any{"source": "rate_limit_event", "rate_limit": scalars(sl.RateLimitInfo)}
		// The five-hour and seven-day windows (billing-signals): utilization and resetsAt per window.
		if windows, ok := sl.RateLimitInfo["unifiedWindows"].(map[string]any); ok {
			w := map[string]any{}
			for name, v := range windows {
				if inner, ok := v.(map[string]any); ok && len(w) < 10 {
					w[truncate(name, 60)] = scalars(inner)
				}
			}
			p["windows"] = w
		}
		return []Activity{{Kind: KindUsage, Payload: p}}
	case "result":
		return m.mapResult(sl)
	default:
		m.dropped["type:"+sl.Type]++
		return nil
	}
}

func (m *Mapper) mapMessage(sl streamLine) []Activity {
	if sl.Message == nil || len(sl.Message.Content) == 0 {
		m.dropped[sl.Type+":no_content"]++
		return nil
	}
	var blocks []contentBlock
	if err := json.Unmarshal(sl.Message.Content, &blocks); err != nil {
		// A plain-string content (for example a replayed prompt) is not an activity.
		m.dropped[sl.Type+":string_content"]++
		return nil
	}
	var out []Activity
	for _, b := range blocks {
		a, ok := m.mapBlock(sl.Type, b)
		if !ok {
			m.dropped[sl.Type+":"+b.Type]++
			continue
		}
		if sl.ParentToolUseID != nil && *sl.ParentToolUseID != "" {
			a.Payload["parent_tool_use_id"] = *sl.ParentToolUseID
		}
		out = append(out, a)
	}
	return out
}

func (m *Mapper) mapBlock(lineType string, b contentBlock) (Activity, bool) {
	switch {
	case lineType == "assistant" && b.Type == "thinking" && b.Thinking != "":
		return Activity{Kind: KindThought, Payload: textPayload(b.Thinking)}, true
	case lineType == "assistant" && b.Type == "text" && b.Text != "":
		return Activity{Kind: KindAnswer, Payload: textPayload(b.Text)}, true
	case lineType == "assistant" && b.Type == "tool_use":
		input, cut := sanitize(b.Input, 0)
		p := map[string]any{"tool_use_id": b.ID, "name": b.Name, "input": input}
		if cut {
			p["truncated"] = true
		}
		return Activity{Kind: KindAction, Payload: p}, true
	case lineType == "user" && b.Type == "tool_result":
		p := textPayload(toolResultText(b.Content))
		p["tool_use_id"] = b.ToolUseID
		p["is_error"] = b.IsError
		return Activity{Kind: KindActionResult, Payload: p}, true
	}
	return Activity{}, false
}

func (m *Mapper) mapResult(sl streamLine) []Activity {
	m.resultSeen = true
	m.failed = sl.IsError || strings.HasPrefix(sl.Subtype, "error")
	m.numTurns = 0
	if sl.NumTurns != nil {
		m.numTurns = *sl.NumTurns
	}
	m.resultSubtype = truncate(sl.Subtype, 60)
	usage := map[string]any{"source": "result", "usage": scalars(sl.Usage), "subtype": m.resultSubtype}
	if sl.TerminalReason != "" {
		usage["terminal_reason"] = truncate(sl.TerminalReason, 60)
	}
	if sl.APIErrorStatus != nil {
		usage["api_error_status"] = *sl.APIErrorStatus
	}
	// Claude Code 2.1.296 puts the run's token totals and cost per model here; the top-level usage
	// can be all zeros (measured), so the server reads these first.
	if len(sl.ModelUsage) > 0 {
		models := map[string]any{}
		for name, v := range sl.ModelUsage {
			if inner, ok := v.(map[string]any); ok && len(models) < 10 {
				models[truncate(name, 100)] = scalars(inner)
			}
		}
		usage["model_usage"] = models
	}
	for key, v := range map[string]*float64{
		"total_cost_usd":  sl.TotalCostUSD,
		"duration_ms":     sl.DurationMS,
		"duration_api_ms": sl.DurationAPIMS,
		"num_turns":       sl.NumTurns,
	} {
		if v != nil {
			usage[key] = *v
		}
	}
	if len(sl.Denials) > 0 {
		names := make([]string, 0, len(sl.Denials))
		for _, d := range sl.Denials {
			names = append(names, truncate(d.ToolName, 200))
		}
		usage["permission_denials"] = names
	}
	out := []Activity{{Kind: KindUsage, Payload: usage}}
	if m.failed {
		msg := sl.Result
		if msg == "" {
			msg = errorsText(sl.Errors)
		}
		p := textPayload(msg)
		p["source"] = "result"
		p["subtype"] = sl.Subtype
		out = append(out, Activity{Kind: KindError, Payload: p})
	}
	return out
}

// replayText reads an echoed user message: a string, or a list whose text blocks are joined.
func replayText(msg *streamMessage) string {
	if msg == nil {
		return ""
	}
	var s string
	if json.Unmarshal(msg.Content, &s) == nil {
		return s
	}
	var blocks []contentBlock
	if json.Unmarshal(msg.Content, &blocks) != nil {
		return ""
	}
	parts := make([]string, 0, len(blocks))
	for _, b := range blocks {
		if b.Type == "text" {
			parts = append(parts, b.Text)
		}
	}
	return strings.Join(parts, "\n")
}

// textPayload holds one text field, truncated to MaxText characters.
func textPayload(s string) map[string]any {
	t := truncate(s, MaxText)
	p := map[string]any{"text": t}
	if t != s {
		p["truncated"] = true
	}
	return p
}

// toolResultText reads tool_result content, which is a string or a list of blocks.
func toolResultText(raw json.RawMessage) string {
	if len(raw) == 0 {
		return ""
	}
	var s string
	if json.Unmarshal(raw, &s) == nil {
		return s
	}
	var blocks []contentBlock
	if json.Unmarshal(raw, &blocks) != nil {
		return ""
	}
	parts := make([]string, 0, len(blocks))
	for _, b := range blocks {
		if b.Type == "text" {
			parts = append(parts, b.Text)
		} else {
			parts = append(parts, "["+b.Type+"]")
		}
	}
	return strings.Join(parts, "\n")
}

// errorsText joins the "errors" field of a failed result, if it is a list of strings.
func errorsText(raw json.RawMessage) string {
	var list []string
	if len(raw) == 0 || json.Unmarshal(raw, &list) != nil {
		return ""
	}
	return strings.Join(list, "\n")
}

// scalars keeps only string, number and bool fields, so nested data never rides along.
func scalars(in map[string]any) map[string]any {
	out := map[string]any{}
	for k, v := range in {
		switch x := v.(type) {
		case string:
			out[k] = truncate(x, 200)
		case float64, bool:
			out[k] = x
		}
	}
	return out
}

// sanitize copies a decoded JSON value, cutting long strings and deep nesting.
func sanitize(v any, depth int) (any, bool) {
	const maxDepth, maxItems = 6, 50
	if depth > maxDepth {
		return "[nested]", true
	}
	switch x := v.(type) {
	case string:
		t := truncate(x, MaxText)
		return t, t != x
	case map[string]any:
		out, cut := make(map[string]any, len(x)), false
		for k, val := range x {
			s, c := sanitize(val, depth+1)
			out[k], cut = s, cut || c
		}
		return out, cut
	case []any:
		n, cut := min(len(x), maxItems), len(x) > maxItems
		out := make([]any, 0, n)
		for _, val := range x[:n] {
			s, c := sanitize(val, depth+1)
			out, cut = append(out, s), cut || c
		}
		return out, cut
	default:
		return x, false
	}
}

// truncate cuts s to at most n characters without splitting a UTF-8 sequence.
func truncate(s string, n int) string {
	if utf8.RuneCountInString(s) <= n {
		return s
	}
	i, count := 0, 0
	for i < len(s) && count < n {
		_, size := utf8.DecodeRuneInString(s[i:])
		i += size
		count++
	}
	return s[:i]
}
