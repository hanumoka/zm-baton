package claude

import (
	"strings"
	"testing"
)

// The final answer of a successful result is kept for a job that sends it back (the master's run);
// an error result keeps none, and a long one is cut to MaxResult characters.
func TestResultTextKeepsOnlyASuccessfulAnswer(t *testing.T) {
	m := NewMapper()
	m.Map([]byte(`{"type":"result","subtype":"success","is_error":false,"result":"{\"candidates\":[]}","num_turns":1}`))
	if text, cut := m.ResultText(); text != `{"candidates":[]}` || cut {
		t.Fatalf("got %q %v", text, cut)
	}

	failed := NewMapper()
	failed.Map([]byte(`{"type":"result","subtype":"error_max_turns","is_error":true,"result":"partial answer","num_turns":5}`))
	if text, _ := failed.ResultText(); text != "" {
		t.Fatalf("an error result keeps no answer, got %q", text)
	}

	long := NewMapper()
	answer := strings.Repeat("가", MaxResult+10)
	long.Map([]byte(`{"type":"result","subtype":"success","is_error":false,"result":"` + answer + `"}`))
	text, cut := long.ResultText()
	if !cut || len([]rune(text)) != MaxResult {
		t.Fatalf("cut %v, %d characters", cut, len([]rune(text)))
	}
}
