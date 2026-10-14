package claude

import (
	"encoding/json"
	"slices"
	"strings"
	"testing"
	"unicode/utf8"
)

// All sample lines below are synthetic: written for these tests in the shape of
// Claude Code stream-json output, not copied from a real run.
const sid = "11111111-2222-4333-8444-555555555555"

func mapAll(t *testing.T, m *Mapper, lines ...string) []Activity {
	t.Helper()
	var out []Activity
	for _, l := range lines {
		out = append(out, m.Map([]byte(l))...)
	}
	return out
}

func kinds(acts []Activity) []string {
	out := make([]string, len(acts))
	for i, a := range acts {
		out[i] = a.Kind
	}
	return out
}

func TestArgsKeepMarkerOutOfPromptAndEndOptions(t *testing.T) {
	got := Args(Options{SessionID: sid, AllowedTools: "Read,Grep", Prompt: "--not-a-flag summarize"})
	want := []string{
		"-p", "--output-format", "stream-json", "--verbose",
		"--session-id", sid,
		"--permission-prompts", "none",
		"--allowedTools", "Read,Grep",
		"--", "--not-a-flag summarize",
	}
	if !slices.Equal(got, want) {
		t.Fatalf("args\n got %q\nwant %q", got, want)
	}
}

func TestRestrictedArgsComeBeforeThePrompt(t *testing.T) {
	got := Args(Options{SessionID: sid, AllowedTools: "Read,Edit", Prompt: "do it", Restricted: true})
	want := []string{
		"-p", "--output-format", "stream-json", "--verbose",
		"--session-id", sid,
		"--permission-prompts", "none",
		"--allowedTools", "Read,Edit",
		"--restricted", "--strict-mcp-config",
		"--", "do it",
	}
	if !slices.Equal(got, want) {
		t.Fatalf("args\n got %q\nwant %q", got, want)
	}
}

func TestStreamInputTakesThePromptOnStdinAndResumeKeepsTheMarker(t *testing.T) {
	got := Args(Options{SessionID: sid, AllowedTools: "Read", Prompt: "never an argument", Restricted: true, StreamInput: true, Resume: true})
	want := []string{
		"-p", "--output-format", "stream-json", "--verbose",
		"--resume", sid,
		"--permission-prompts", "none",
		"--allowedTools", "Read",
		"--restricted", "--strict-mcp-config",
		"--input-format", "stream-json", "--replay-user-messages",
	}
	if !slices.Equal(got, want) {
		t.Fatalf("args\n got %q\nwant %q", got, want)
	}
	line := UserMessage("한 줄\n두 줄 \"따옴표\"")
	if line[len(line)-1] != '\n' || strings.Count(string(line), "\n") != 1 {
		t.Fatalf("one message must be one line: %q", line)
	}
	var msg struct {
		Type    string `json:"type"`
		Message struct {
			Role    string `json:"role"`
			Content string `json:"content"`
		} `json:"message"`
	}
	if err := json.Unmarshal(line, &msg); err != nil || msg.Type != "user" || msg.Message.Role != "user" || msg.Message.Content != "한 줄\n두 줄 \"따옴표\"" {
		t.Fatalf("message %+v %v", msg, err)
	}
}

func TestEchoedUserMessagesAreConfirmationsNotActivity(t *testing.T) {
	m := NewMapper()
	acts := mapAll(t, m,
		`{"type":"user","message":{"role":"user","content":"the prompt"},"session_id":"`+sid+`","isReplay":true}`,
		`{"type":"assistant","message":{"content":[{"type":"text","text":"ok"}]},"session_id":"`+sid+`"}`,
		`{"type":"user","message":{"role":"user","content":[{"type":"text","text":"사람이 실행 중에 보낸 지시: 멈춰"}]},"isReplay":true}`,
	)
	if got := kinds(acts); !slices.Equal(got, []string{KindAnswer}) {
		t.Fatalf("kinds %v", got)
	}
	if got := m.TakeReplays(); !slices.Equal(got, []string{"the prompt", "사람이 실행 중에 보낸 지시: 멈춰"}) {
		t.Fatalf("replays %q", got)
	}
	if got := m.TakeReplays(); len(got) != 0 {
		t.Fatalf("replays are taken once: %q", got)
	}
	if m.Dropped()["user:replay"] != 2 {
		t.Fatalf("dropped %v", m.Dropped())
	}
}

func TestAResumeOfAMissingSessionIsToldApartFromAFailedRun(t *testing.T) {
	missing := NewMapper()
	mapAll(t, missing, `{"type":"result","subtype":"error_during_execution","is_error":true,"num_turns":0,"errors":["No conversation found with session ID: x"]}`)
	if !missing.ResumeRefused() || missing.Succeeded() {
		t.Fatal("a resume Claude Code refused was not recognised")
	}
	worked := NewMapper()
	mapAll(t, worked,
		`{"type":"assistant","message":{"content":[{"type":"text","text":"tried"}]}}`,
		`{"type":"result","subtype":"error_max_turns","is_error":true,"num_turns":0}`)
	if worked.ResumeRefused() {
		t.Fatal("a run with output counted as a refused resume")
	}
	ok := NewMapper()
	mapAll(t, ok, `{"type":"result","subtype":"success","is_error":false,"num_turns":0}`)
	if ok.ResumeRefused() {
		t.Fatal("a successful result counted as a refused resume")
	}
}

func TestMapSuccessfulRun(t *testing.T) {
	m := NewMapper()
	acts := mapAll(t, m,
		`{"type":"system","subtype":"init","session_id":"`+sid+`","cwd":"/work/demo","tools":["Read"]}`,
		`{"type":"assistant","message":{"role":"assistant","content":[{"type":"thinking","thinking":"Read the file first.","signature":"xyz"}]},"parent_tool_use_id":null,"session_id":"`+sid+`"}`,
		`{"type":"assistant","message":{"content":[{"type":"tool_use","id":"toolu_01","name":"Read","input":{"file_path":"notes.txt"}}]},"session_id":"`+sid+`"}`,
		`{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_01","content":"hello","is_error":false}]},"session_id":"`+sid+`"}`,
		`{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"toolu_02","content":[{"type":"text","text":"line one"},{"type":"image","source":{}}],"is_error":true}]}}`,
		`{"type":"assistant","message":{"content":[{"type":"text","text":"The file says hello."}]},"session_id":"`+sid+`"}`,
		`{"type":"rate_limit_event","rate_limit_info":{"status":"allowed","rateLimitType":"five_hour","utilization":0.25,"nested":{"x":1}},"session_id":"`+sid+`"}`,
		`{"type":"result","subtype":"success","is_error":false,"duration_ms":1234,"num_turns":2,"result":"The file says hello.","total_cost_usd":0.01,"usage":{"input_tokens":10,"output_tokens":5,"server_tool_use":{"web_search_requests":0}},"permission_denials":[{"tool_name":"Bash","tool_use_id":"toolu_09","tool_input":{"command":"echo secret-input"}}],"session_id":"`+sid+`"}`,
	)

	want := []string{KindThought, KindAction, KindActionResult, KindActionResult, KindAnswer, KindUsage, KindUsage}
	if got := kinds(acts); !slices.Equal(got, want) {
		t.Fatalf("kinds\n got %v\nwant %v", got, want)
	}
	if !m.ResultSeen() || !m.Succeeded() {
		t.Fatalf("result seen=%v succeeded=%v, want both true", m.ResultSeen(), m.Succeeded())
	}
	if m.SessionID() != sid {
		t.Fatalf("session id %q", m.SessionID())
	}
	if d := m.Dropped(); d["type:system"] != 1 || len(d) != 1 {
		t.Fatalf("dropped %v, want only the system line", d)
	}

	if acts[0].Payload["text"] != "Read the file first." {
		t.Errorf("thought payload %v", acts[0].Payload)
	}
	if p := acts[1].Payload; p["tool_use_id"] != "toolu_01" || p["name"] != "Read" ||
		p["input"].(map[string]any)["file_path"] != "notes.txt" {
		t.Errorf("action payload %v", p)
	}
	if p := acts[2].Payload; p["tool_use_id"] != "toolu_01" || p["text"] != "hello" || p["is_error"] != false {
		t.Errorf("action_result payload %v", p)
	}
	if p := acts[3].Payload; p["text"] != "line one\n[image]" || p["is_error"] != true {
		t.Errorf("block-list action_result payload %v", p)
	}
	if acts[4].Payload["text"] != "The file says hello." {
		t.Errorf("answer payload %v", acts[4].Payload)
	}
	rl := acts[5].Payload
	if rl["source"] != "rate_limit_event" {
		t.Errorf("rate limit payload %v", rl)
	}
	info := rl["rate_limit"].(map[string]any)
	if info["status"] != "allowed" || info["utilization"] != 0.25 || info["nested"] != nil {
		t.Errorf("rate limit info should keep scalars only: %v", info)
	}
	res := acts[6].Payload
	if res["source"] != "result" || res["duration_ms"] != 1234.0 || res["num_turns"] != 2.0 {
		t.Errorf("result usage payload %v", res)
	}
	if u := res["usage"].(map[string]any); u["input_tokens"] != 10.0 || u["server_tool_use"] != nil {
		t.Errorf("result usage should keep scalars only: %v", u)
	}
	if d := res["permission_denials"].([]string); !slices.Equal(d, []string{"Bash"}) {
		t.Errorf("permission denials %v", d)
	}
	raw, _ := json.Marshal(acts)
	if strings.Contains(string(raw), "secret-input") || strings.Contains(string(raw), "/work/demo") {
		t.Errorf("payloads leak denied tool input or the init line: %s", raw)
	}
}

func TestMapFailedResults(t *testing.T) {
	cases := []struct {
		name, line, wantText, wantSubtype string
	}{
		{"is_error", `{"type":"result","subtype":"success","is_error":true,"result":"API Error: 500"}`, "API Error: 500", "success"},
		{"error subtype", `{"type":"result","subtype":"error_during_execution","is_error":false,"errors":["boom","bang"]}`, "boom\nbang", "error_during_execution"},
		{"max turns", `{"type":"result","subtype":"error_max_turns","is_error":true}`, "", "error_max_turns"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			m := NewMapper()
			acts := m.Map([]byte(tc.line))
			if got := kinds(acts); !slices.Equal(got, []string{KindUsage, KindError}) {
				t.Fatalf("kinds %v", got)
			}
			if !m.ResultSeen() || m.Succeeded() {
				t.Fatalf("result seen=%v succeeded=%v, want seen and failed", m.ResultSeen(), m.Succeeded())
			}
			p := acts[1].Payload
			if p["text"] != tc.wantText || p["subtype"] != tc.wantSubtype || p["source"] != "result" {
				t.Fatalf("error payload %v", p)
			}
		})
	}
}

func TestMapDropsUnknownLines(t *testing.T) {
	m := NewMapper()
	acts := mapAll(t, m,
		"",
		"   ",
		"not json at all",
		`{"no_type":1}`,
		`{"type":"stream_event","event":{"type":"content_block_delta"}}`,
		`{"type":"assistant","message":{"content":[{"type":"redacted_thinking","data":"x"},{"type":"thinking","thinking":""}]}}`,
		`{"type":"user","message":{"role":"user","content":"a replayed prompt"}}`,
		`{"type":"user","message":{"content":[{"type":"text","text":"user text is not an activity"}]}}`,
	)
	if len(acts) != 0 {
		t.Fatalf("want no activities, got %v", kinds(acts))
	}
	want := map[string]int{
		"invalid_json": 2, "type:stream_event": 1, "assistant:redacted_thinking": 1,
		"assistant:thinking": 1, "user:string_content": 1, "user:text": 1,
	}
	got := m.Dropped()
	if len(got) != len(want) {
		t.Fatalf("dropped %v, want %v", got, want)
	}
	for k, v := range want {
		if got[k] != v {
			t.Fatalf("dropped %v, want %v", got, want)
		}
	}
	if m.ResultSeen() {
		t.Fatal("no result line was given")
	}
}

func TestMapTruncatesLongTextAndMarksSubagents(t *testing.T) {
	long := strings.Repeat("가", MaxText+500)
	line, _ := json.Marshal(map[string]any{
		"type":               "assistant",
		"parent_tool_use_id": "toolu_parent",
		"message": map[string]any{"content": []any{
			map[string]any{"type": "text", "text": long},
			map[string]any{"type": "tool_use", "id": "toolu_w", "name": "Write",
				"input": map[string]any{"file_path": "a.txt", "content": long}},
		}},
	})
	acts := NewMapper().Map(line)
	if len(acts) != 2 {
		t.Fatalf("want 2 activities, got %d", len(acts))
	}
	text := acts[0].Payload["text"].(string)
	if utf8.RuneCountInString(text) != MaxText || !utf8.ValidString(text) || acts[0].Payload["truncated"] != true {
		t.Fatalf("answer not cut to %d valid characters (got %d)", MaxText, utf8.RuneCountInString(text))
	}
	input := acts[1].Payload["input"].(map[string]any)
	if utf8.RuneCountInString(input["content"].(string)) != MaxText || acts[1].Payload["truncated"] != true {
		t.Fatal("tool input string not cut")
	}
	for _, a := range acts {
		if a.Payload["parent_tool_use_id"] != "toolu_parent" {
			t.Fatalf("subagent marker missing: %v", a.Payload)
		}
	}
}

func TestReadLines(t *testing.T) {
	input := "first\r\n" + strings.Repeat("x", 100) + "\nsecond\n\nlast-without-newline"
	var got []string
	oversized, err := ReadLines(strings.NewReader(input), 50, func(line []byte) {
		got = append(got, string(line))
	})
	if err != nil {
		t.Fatal(err)
	}
	want := []string{"first", "second", "last-without-newline"}
	if !slices.Equal(got, want) || oversized != 1 {
		t.Fatalf("lines %q oversized %d, want %q and 1", got, oversized, want)
	}
}
