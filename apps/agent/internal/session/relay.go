package session

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/hanumoka/zm-baton/apps/agent/internal/report"
)

// Relaying a Claude Code permission request (PermissionRequest hook) or multiple-choice question
// (PreToolUse on AskUserQuestion) to the first screen (contract v1 "권한 요청과 선택형 질문을 첫
// 화면으로 중계한다"). The hook sends what a person judges, waits for the person's answer and hands
// it to Claude Code. It gives a decision only when it took a person's answer from the server;
// any failure, refusal, mismatch or timeout ends the hook without output, and Claude Code asks in
// its terminal as it would without zm-baton. Nothing here allows or answers on its own.

// relayLimit is how long a relay hook may take: longer than the server's longest deadline (70
// seconds, RelayService.MAX_WAIT_SECONDS), shorter than the plugin's hook timeout (90 seconds).
const relayLimit = 80 * time.Second

// relayPoll is how often the hook asks whether a person answered.
var relayPoll = 500 * time.Millisecond

// detailMax is the longest detail a relay sends. A longer one goes to the terminal instead: the
// first screen must show the whole input, not a cut one.
const detailMax = 32 << 10

// RelayRequest is the body of POST /api/sessions/{id}/relays.
type RelayRequest struct {
	ProtocolVersion  int            `json:"protocol_version"`
	RelayID          string         `json:"relay_id"`
	Kind             string         `json:"kind"`
	InputFingerprint string         `json:"input_fingerprint"`
	LocalSeq         int64          `json:"local_seq"`
	Detail           map[string]any `json:"detail,omitempty"`
	Withheld         string         `json:"withheld,omitempty"`
}

// RelayStarted is the server's answer: whether to wait, and why not.
type RelayStarted struct {
	Relay       bool   `json:"relay"`
	ItemID      string `json:"item_id"`
	Reason      string `json:"reason"`
	WaitSeconds int    `json:"wait_seconds"`
}

// RelayTaken is one look for the answer: delivered (with the answer), waiting, over or mismatch.
type RelayTaken struct {
	Outcome string          `json:"outcome"`
	State   string          `json:"state"`
	Answer  json.RawMessage `json:"answer"`
}

// StartRelay asks the server to relay one request.
func (c *Client) StartRelay(ctx context.Context, sessionID, token string, req RelayRequest) (RelayStarted, error) {
	req.ProtocolVersion = ProtocolVersion
	var out RelayStarted
	err := c.do(ctx, http.MethodPost, "/api/sessions/"+url.PathEscape(sessionID)+"/relays", token, req, http.StatusOK, &out, "start relay")
	return out, err
}

// TakeRelay takes a person's answer if there is one.
func (c *Client) TakeRelay(ctx context.Context, sessionID, token, relayID, fingerprint string) (RelayTaken, error) {
	var out RelayTaken
	body := map[string]any{"protocol_version": ProtocolVersion, "input_fingerprint": fingerprint}
	path := "/api/sessions/" + url.PathEscape(sessionID) + "/relays/" + url.PathEscape(relayID) + "/take"
	err := c.do(ctx, http.MethodPost, path, token, body, http.StatusOK, &out, "take relay")
	return out, err
}

// Fingerprint is the SHA-256 of the tool name and the whole tool input, serialised with sorted keys
// and numbers as written (contract relay 2.2). The hook sends it with the request and again when
// it takes the answer, so an answer given to one input is never handed to another.
func Fingerprint(toolName string, toolInput json.RawMessage) (string, error) {
	input, err := decodeJSON(toolInput)
	if err != nil {
		return "", err
	}
	data, err := marshal(map[string]any{"tool_name": toolName, "tool_input": input})
	if err != nil {
		return "", err
	}
	sum := sha256.Sum256(data)
	return hex.EncodeToString(sum[:]), nil
}

func decodeJSON(raw json.RawMessage) (any, error) {
	if len(bytes.TrimSpace(raw)) == 0 {
		return nil, nil
	}
	var v any
	dec := json.NewDecoder(bytes.NewReader(raw))
	dec.UseNumber()
	if err := dec.Decode(&v); err != nil {
		return nil, fmt.Errorf("tool_input is not JSON: %w", err)
	}
	return v, nil
}

// marshal writes JSON with sorted map keys and without HTML escaping, and no trailing newline.
func marshal(v any) ([]byte, error) {
	var buf bytes.Buffer
	enc := json.NewEncoder(&buf)
	enc.SetEscapeHTML(false)
	if err := enc.Encode(v); err != nil {
		return nil, err
	}
	return bytes.TrimRight(buf.Bytes(), "\n"), nil
}

func newRelayID() string { return "rly_" + strings.TrimPrefix(report.NewEventID(time.Now()), "evt_") }

// relay handles a PermissionRequest (kind permission_request) or an AskUserQuestion PreToolUse
// (kind session_question). See the file comment for what it may and may not do.
func (h *Hooks) relay(ctx context.Context, in HookInput, kind string) error {
	if strings.TrimSpace(in.ToolName) == "" {
		return errors.New("relay: hook input has no tool_name")
	}
	st, ok, err := LoadState(h.Dir, h.Executor, in.SessionID)
	if err != nil || !ok || st.Off || st.SessionID == "" {
		return err // not registered here: Claude Code asks as usual
	}
	fingerprint, err := Fingerprint(in.ToolName, in.ToolInput)
	if err != nil {
		return err
	}
	decoded, _ := decodeJSON(in.ToolInput)
	input, _ := decoded.(map[string]any)
	if input == nil {
		return errors.New("relay: tool_input is not an object")
	}
	req := RelayRequest{RelayID: newRelayID(), Kind: kind, InputFingerprint: fingerprint}
	if InputHasSecret(in.ToolInput) {
		// Contract relay 3: nothing of the input leaves the device; the first screen only says so.
		req.Withheld = "secret_suspected"
	} else {
		req.Detail = relayDetail(ctx, in, kind, input, st)
		if data, err := marshal(req.Detail); err != nil || len(data) > detailMax {
			req.Detail, req.Withheld = nil, "too_large"
		}
	}
	client, started, st, err := h.startRelay(ctx, in, req, true)
	if err != nil || !started.Relay {
		return err
	}
	answer, err := waitAnswer(ctx, client, st, req.RelayID, fingerprint)
	if err != nil || answer == nil {
		return err
	}
	out, err := DecisionOutput(kind, input, answer)
	if err != nil {
		return err
	}
	_, err = h.Stdout.Write(out)
	return err
}

// startRelay sends the request. local_seq orders it against the session's resumes, and the
// waiting flag makes the next tool use or prompt report that the session moved on, which closes
// the relay's item. A registration the server no longer takes as live is renewed once, as the
// other hooks do (see send), and the request goes again under the new registration.
func (h *Hooks) startRelay(ctx context.Context, in HookInput, req RelayRequest, registerAgain bool) (*Client, RelayStarted, State, error) {
	var st State
	err := Update(ctx, h.Dir, h.Executor, in.SessionID, 2*time.Second, func(s *State, exists bool) (bool, error) {
		if !exists || s.Off || s.SessionID == "" {
			return !exists, nil
		}
		s.NextSeq++
		req.LocalSeq = s.NextSeq
		s.Waiting = true
		st = *s
		return false, nil
	})
	if err != nil || req.LocalSeq == 0 {
		return nil, RelayStarted{}, st, err
	}
	client, err := NewClient(st.Server, 3*time.Second)
	if err != nil {
		return nil, RelayStarted{}, st, err
	}
	started, err := client.StartRelay(ctx, st.SessionID, st.Token, req)
	var status *StatusError
	if registerAgain && errors.As(err, &status) && status.Status == http.StatusUnauthorized {
		if _, registered, rerr := h.register(ctx, in); rerr == nil && registered {
			req.LocalSeq = 0
			return h.startRelay(ctx, in, req, false)
		}
	}
	return client, started, st, err
}

// waitAnswer asks until a person's answer is taken (returned), the server says the terminal
// decides (nil), or the hook's time runs out (nil and the reason).
func waitAnswer(ctx context.Context, client *Client, st State, relayID, fingerprint string) (json.RawMessage, error) {
	for {
		taken, err := client.TakeRelay(ctx, st.SessionID, st.Token, relayID, fingerprint)
		if err != nil {
			return nil, err
		}
		switch taken.Outcome {
		case "delivered":
			if len(taken.Answer) == 0 {
				return nil, errors.New("relay: the server delivered no answer")
			}
			return taken.Answer, nil
		case "waiting":
		default:
			return nil, nil
		}
		select {
		case <-ctx.Done():
			return nil, fmt.Errorf("relay: no answer in time: %w", ctx.Err())
		case <-time.After(relayPoll):
		}
	}
}

// DecisionOutput is what the hook prints for a person's answer, in Claude Code's hook output
// format (code.claude.com/docs/en/hooks): PermissionRequest takes decision.behavior allow or deny
// with a message; PreToolUse on AskUserQuestion takes permissionDecision allow with the questions
// echoed and the answers added in updatedInput. An answer that does not fit what was asked is an
// error, and then nothing is printed.
func DecisionOutput(kind string, input map[string]any, answer json.RawMessage) ([]byte, error) {
	var out map[string]any
	switch kind {
	case "permission_request":
		var a struct {
			Decision string `json:"decision"`
			Reason   string `json:"reason"`
		}
		if err := json.Unmarshal(answer, &a); err != nil {
			return nil, err
		}
		var decision map[string]any
		switch a.Decision {
		case "allow":
			decision = map[string]any{"behavior": "allow"}
		case "deny":
			message := "Denied from the zm-baton first screen."
			if reason := strings.TrimSpace(a.Reason); reason != "" {
				message += " Reason: " + reason
			}
			decision = map[string]any{"behavior": "deny", "message": message}
		default:
			return nil, fmt.Errorf("relay: unknown decision %q", a.Decision)
		}
		out = map[string]any{"hookSpecificOutput": map[string]any{"hookEventName": "PermissionRequest", "decision": decision}}
	case "session_question":
		var a struct {
			Answers map[string][]string `json:"answers"`
		}
		if err := json.Unmarshal(answer, &a); err != nil {
			return nil, err
		}
		answers, err := questionAnswers(input, a.Answers)
		if err != nil {
			return nil, err
		}
		updated := make(map[string]any, len(input)+1)
		for k, v := range input {
			updated[k] = v
		}
		updated["answers"] = answers
		out = map[string]any{"hookSpecificOutput": map[string]any{
			"hookEventName":            "PreToolUse",
			"permissionDecision":       "allow",
			"permissionDecisionReason": "Answered on the zm-baton first screen",
			"updatedInput":             updated,
		}}
	default:
		return nil, fmt.Errorf("relay: unknown kind %q", kind)
	}
	data, err := marshal(out)
	if err != nil {
		return nil, err
	}
	return append(data, '\n'), nil
}

// questionAnswers checks the chosen labels against the questions that were asked and joins each
// question's labels with commas, as AskUserQuestion's answers field takes them.
func questionAnswers(input map[string]any, chosen map[string][]string) (map[string]string, error) {
	questions, _ := input["questions"].([]any)
	if len(questions) == 0 {
		return nil, errors.New("relay: the tool input has no questions")
	}
	answers := map[string]string{}
	for _, q := range questions {
		m, _ := q.(map[string]any)
		text, _ := m["question"].(string)
		labels := map[string]bool{}
		options, _ := m["options"].([]any)
		for _, o := range options {
			if label, ok := o.(map[string]any)["label"].(string); ok {
				labels[label] = true
			}
		}
		picked := chosen[text]
		multi, _ := m["multiSelect"].(bool)
		if text == "" || len(picked) == 0 || (!multi && len(picked) != 1) {
			return nil, fmt.Errorf("relay: no fitting answer for question %q", text)
		}
		for _, p := range picked {
			if !labels[p] {
				return nil, fmt.Errorf("relay: %q is not an option of question %q", p, text)
			}
		}
		answers[text] = strings.Join(picked, ", ")
	}
	if len(answers) != len(chosen) {
		return nil, errors.New("relay: the answers name a question that was not asked")
	}
	return answers, nil
}

// pathKeys are the input fields that hold a file or folder path.
var pathKeys = []string{"file_path", "notebook_path", "path"}

// relayDetail is what a person judges (contract relay 2.3): the tool name, the command in full or
// the file path, the executor's description, and the working place relative to the repository.
// The whole input goes too, so nothing is judged from a one-line summary. A path inside the
// repository is sent relative to its root and one under the home folder from `~`.
func relayDetail(ctx context.Context, in HookInput, kind string, input map[string]any, st State) map[string]any {
	var repo *Repo
	if in.Cwd != "" {
		if r, err := RepoAt(ctx, in.Cwd); err == nil {
			repo = r
		}
	}
	if repo == nil && st.RepoRoot != "" {
		repo = &Repo{Root: st.RepoRoot, Key: st.RepoKey}
	}
	d := map[string]any{"tool_name": in.ToolName, "executor": "claude_code", "work_location": workLocation(repo, in.Cwd)}
	if kind == "session_question" {
		d["questions"] = input["questions"]
		return d
	}
	shown := make(map[string]any, len(input))
	for k, v := range input {
		shown[k] = v
	}
	for _, k := range pathKeys {
		if p, ok := shown[k].(string); ok && p != "" {
			shown[k] = displayPath(p, repo)
			if _, set := d["file_path"]; !set {
				d["file_path"] = shown[k]
			}
		}
	}
	d["input"] = shown
	if c, ok := input["command"].(string); ok {
		d["command"] = c
	}
	if desc, ok := input["description"].(string); ok && strings.TrimSpace(desc) != "" {
		d["description"] = desc
	}
	if in.PermissionMode != "" {
		d["permission_mode"] = in.PermissionMode
	}
	return d
}

// workLocation names where the session works: the repository, branch and folder from its root,
// or the folder from `~` outside a repository.
func workLocation(repo *Repo, cwd string) map[string]any {
	loc := map[string]any{}
	if repo != nil {
		loc["repo_key"] = repo.Key
		if repo.Remote != "" {
			loc["remote"] = repo.Remote
		}
		if repo.Branch != "" {
			loc["branch"] = repo.Branch
		}
		if rel, ok := repo.Rel(cwd); ok {
			loc["path"] = rel
		} else {
			loc["path"] = strings.TrimSuffix(repo.Prefix, "/")
		}
		loc["in_repo"] = true
		return loc
	}
	if cwd != "" {
		loc["path"] = displayPath(cwd, nil)
	}
	loc["in_repo"] = false
	return loc
}

// displayPath is p relative to the repository root, else from `~`, else as given with `/`.
func displayPath(p string, repo *Repo) string {
	if rel, ok := repo.Rel(p); ok {
		if rel == "" {
			return "."
		}
		return rel
	}
	clean := filepath.Clean(filepath.FromSlash(p))
	if home, err := os.UserHomeDir(); err == nil && filepath.IsAbs(clean) {
		if rel, err := filepath.Rel(home, clean); err == nil && rel != ".." && !strings.HasPrefix(rel, ".."+string(filepath.Separator)) {
			if rel == "." {
				return "~"
			}
			return "~/" + filepath.ToSlash(rel)
		}
	}
	return filepath.ToSlash(p)
}
