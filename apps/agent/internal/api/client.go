// Package api is the HTTP client for the stage-1 compat server (apps/server).
//
// Wire names follow contract v1 ("실행 권리 청구", "보고 한 건의 모양"): snake_case JSON.
package api

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

// ProtocolVersion is the report protocol version sent with every event batch. Version 2 adds the
// Git change list (`git_changes`) and says in the end report whether the executor started.
const ProtocolVersion = 2

// PendingRun is one run waiting to be claimed (GET /api/runs?state=requested).
type PendingRun struct {
	ID         string `json:"id"`
	WorkItemID string `json:"work_item_id"`
	Executor   string `json:"executor"`
	// Empty for a compat-test run. A delegated run names the one device that may claim it
	// and the repository it works in.
	DelegationID string `json:"delegation_id,omitempty"`
	DeviceID     string `json:"device_id,omitempty"`
	RepoKey      string `json:"repo_key,omitempty"`
	// Purpose is PurposeMaster for the master's contradiction check, which needs no repository;
	// empty for a work item's own run. An older server leaves it out.
	Purpose string `json:"purpose,omitempty"`
}

// PurposeMaster marks the master's run (contract v1 "문서 모순 후보" 5): no repository, no worktree,
// read-only tools, and the answer travels back in the end report.
const PurposeMaster = "master_check"

// Claim is the server's answer to a successful claim.
type Claim struct {
	RunID        string `json:"run_id"`
	WorkItemID   string `json:"work_item_id"`
	Generation   int64  `json:"generation"`
	LeaseSeconds int    `json:"lease_seconds"`
	// Delegation and Instruction are set for a delegated run, nil and empty for a compat-test run.
	Delegation  *Delegation `json:"delegation,omitempty"`
	Instruction string      `json:"instruction,omitempty"`
	// Resume is set when the run continues an earlier one (contract "실행 권리 청구" 1-2).
	Resume *Resume `json:"resume,omitempty"`
	// Account is the AI account the run goes on; nil for a run handed over before accounts, or by an
	// older server. The connector keeps only its sign-in method in the executor's environment.
	Account *Account `json:"account,omitempty"`
	// Limits are the run's usage limits; nil or a zero field means no limit.
	Limits *Limits `json:"limits,omitempty"`
	// Purpose is PurposeMaster for the master's run; empty otherwise.
	Purpose string `json:"purpose,omitempty"`
}

// Account names the AI account a run goes on (contract "실행할 때 넘기는 것" 인증). It is a reference:
// AuthSource says where this device keeps the sign-in, never a value.
type Account struct {
	ID         string `json:"id"`
	Name       string `json:"name"`
	AuthMethod string `json:"auth_method"` // subscription_login or api_key
	AuthSource string `json:"auth_source"` // device_login or env:ANTHROPIC_API_KEY
}

// Limits are a run's usage limits (FN-0409): turns and the price-list cost go to Claude Code's own
// --max-turns and --max-budget-usd, minutes to the connector's timer. BudgetFrom is "run" or "daily".
type Limits struct {
	MaxTurns     int     `json:"max_turns,omitempty"`
	MaxBudgetUSD float64 `json:"max_budget_usd,omitempty"`
	MaxMinutes   int     `json:"max_minutes,omitempty"`
	BudgetFrom   string  `json:"budget_from,omitempty"`
}

// Resume names the run a continuing run follows. On the device that ran it, the server also sends
// that run's executor session and worktree; the connector reuses them only after confirming that
// the earlier executor and its child processes ended (contract "실행마다 별도 작업 트리" 6).
type Resume struct {
	RunID          string `json:"run_id"`
	ContinueReason string `json:"continue_reason"`
	SameDevice     bool   `json:"same_device"`
	SessionID      string `json:"session_id,omitempty"`
	// Workspace holds repo_key, base_commit, branch and base_ref; never a path on the device.
	Workspace map[string]any `json:"workspace,omitempty"`
	// SameAccount is false when the earlier run went on another AI account; then the server sends
	// neither its session nor its worktree. An older server leaves it out: nil.
	SameAccount *bool `json:"same_account,omitempty"`
}

// InputReport says what became of one instruction a person sent: sent, delivered or undelivered.
type InputReport struct {
	ID    string `json:"id"`
	State string `json:"state"`
}

// Input is an instruction a person sent to the running executor.
type Input struct {
	ID   string `json:"id"`
	Text string `json:"text"`
}

// Lease is the server's answer to a lease renewal (contract "실행 권리 청구" 5).
type Lease struct {
	State        string `json:"state"`
	LeaseSeconds int    `json:"lease_seconds"`
	// Stop is "cancel" when a person asked to stop the run.
	Stop   string  `json:"stop,omitempty"`
	Inputs []Input `json:"inputs"`
}

// ErrGone means the server no longer holds the run for this device: it ended (for example judged
// lost) or was claimed again. The connector must stop working on it.
var ErrGone = errors.New("the server no longer holds this run for this device")

// ErrNoLeases means the server predates lease renewal; the run goes on without renewing.
var ErrNoLeases = errors.New("the server does not renew leases")

// Delegation is what the connector turns into executor options (contract v1 "실행할 때 넘기는 것").
type Delegation struct {
	ID           string   `json:"id"`
	Executor     string   `json:"executor"`
	RepoKey      string   `json:"repo_key"`
	BaseBranch   string   `json:"base_branch,omitempty"`
	AllowedTools []string `json:"allowed_tools"`
}

// Event is one report. The run id travels in the URL, not in the event.
type Event struct {
	EventID    string         `json:"event_id"`
	Generation int64          `json:"generation"`
	Seq        int64          `json:"seq"`
	Kind       string         `json:"kind"`
	Payload    map[string]any `json:"payload"`
	OccurredAt string         `json:"occurred_at"`
}

// EventBatch is the body of POST /api/runs/{id}/events.
type EventBatch struct {
	ProtocolVersion int     `json:"protocol_version"`
	Events          []Event `json:"events"`
}

// EventResult counts what the server did with one batch.
type EventResult struct {
	Stored     int `json:"stored"`
	Duplicates int `json:"duplicates"`
	Late       int `json:"late"`
	Rejected   int `json:"rejected"`
}

// ErrConflict means another device already claimed the run (HTTP 409).
var ErrConflict = errors.New("run is not claimable")

// ErrUnauthorized means the server refused this device's token or wants one (HTTP 401): the device
// was revoked, or the server requires device tokens and this device has none (docs/stage1/auth.md).
var ErrUnauthorized = errors.New("the server refused this device: run zm-baton login to get a device token again")

// StatusError is a non-2xx answer other than a claim conflict.
type StatusError struct {
	Op     string
	Status int
}

func (e *StatusError) Error() string {
	if e.Status == http.StatusUnauthorized {
		return fmt.Sprintf("%s: server answered HTTP %d: %v", e.Op, e.Status, ErrUnauthorized)
	}
	return fmt.Sprintf("%s: server answered HTTP %d", e.Op, e.Status)
}

// Unwrap makes a 401 answer match ErrUnauthorized.
func (e *StatusError) Unwrap() error {
	if e.Status == http.StatusUnauthorized {
		return ErrUnauthorized
	}
	return nil
}

// Permanent reports whether sending the same request again cannot succeed.
func (e *StatusError) Permanent() bool {
	return e.Status >= 400 && e.Status < 500 && e.Status != http.StatusRequestTimeout &&
		e.Status != http.StatusTooManyRequests
}

// Client talks to one server. It is safe for concurrent use.
type Client struct {
	base string
	http *http.Client
	// token is this device's device token, sent as `Authorization: Bearer` on every call. Empty for a
	// device registered before tokens, which a server in local mode still takes.
	token string
}

// WithToken returns a client that sends the device token on every call. The token goes to this
// client's server only and is never logged.
func (c *Client) WithToken(token string) *Client {
	cp := *c
	cp.token = token
	return &cp
}

// NewClient checks the base URL. Credentials in the URL are refused so they never reach logs.
func NewClient(baseURL string) (*Client, error) {
	u, err := url.Parse(baseURL)
	if err != nil {
		return nil, fmt.Errorf("server URL: %w", err)
	}
	if (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" {
		return nil, errors.New("server URL must look like http://host:port")
	}
	if u.User != nil || u.RawQuery != "" || u.Fragment != "" {
		return nil, errors.New("server URL must not carry user info, a query or a fragment")
	}
	return &Client{
		base: strings.TrimRight(u.String(), "/"),
		http: &http.Client{Timeout: 10 * time.Second},
	}, nil
}

// PendingRuns polls for runs in state requested.
func (c *Client) PendingRuns(ctx context.Context) ([]PendingRun, error) {
	return c.PendingRunsFor(ctx, "")
}

// PendingRunsFor polls for the delegated runs deviceID may claim; "" asks for every requested run.
func (c *Client) PendingRunsFor(ctx context.Context, deviceID string) ([]PendingRun, error) {
	path := "/api/runs?state=requested"
	if deviceID != "" {
		path += "&device_id=" + url.QueryEscape(deviceID)
	}
	var runs []PendingRun
	status, err := c.do(ctx, http.MethodGet, path, nil, &runs)
	if err != nil {
		return nil, err
	}
	if status != http.StatusOK {
		return nil, &StatusError{Op: "poll runs", Status: status}
	}
	return runs, nil
}

// Claim asks for the right to execute a run. A lost race returns ErrConflict.
func (c *Client) Claim(ctx context.Context, runID, deviceID string) (Claim, error) {
	var claim Claim
	body := map[string]string{"device_id": deviceID}
	status, err := c.do(ctx, http.MethodPost, "/api/runs/"+url.PathEscape(runID)+"/claim", body, &claim)
	if err != nil {
		return Claim{}, err
	}
	switch status {
	case http.StatusOK:
		return claim, nil
	case http.StatusConflict:
		return Claim{}, ErrConflict
	default:
		return Claim{}, &StatusError{Op: "claim run", Status: status}
	}
}

// SendEvents posts one batch. Resending the same events is safe: the server dedups by event_id.
func (c *Client) SendEvents(ctx context.Context, runID string, events []Event) (EventResult, error) {
	var result EventResult
	body := EventBatch{ProtocolVersion: ProtocolVersion, Events: events}
	status, err := c.do(ctx, http.MethodPost, "/api/runs/"+url.PathEscape(runID)+"/events", body, &result)
	if err != nil {
		return EventResult{}, err
	}
	if status != http.StatusOK {
		return EventResult{}, &StatusError{Op: "send events", Status: status}
	}
	return result, nil
}

// Renew renews the lease of a run this device holds and reports what became of the instructions it
// took. The answer says whether to stop and which instructions wait.
func (c *Client) Renew(ctx context.Context, runID, deviceID string, generation int64, reports []InputReport) (Lease, error) {
	if reports == nil {
		reports = []InputReport{}
	}
	body := map[string]any{"device_id": deviceID, "generation": generation, "inputs": reports}
	var lease Lease
	status, err := c.do(ctx, http.MethodPost, "/api/runs/"+url.PathEscape(runID)+"/lease", body, &lease)
	if err != nil {
		return Lease{}, err
	}
	switch status {
	case http.StatusOK:
		return lease, nil
	case http.StatusConflict:
		return Lease{}, ErrGone
	case http.StatusNotFound, http.StatusMethodNotAllowed:
		return Lease{}, ErrNoLeases
	default:
		return Lease{}, &StatusError{Op: "renew lease", Status: status}
	}
}

// do sends one request. out is decoded only for 200 answers.
func (c *Client) do(ctx context.Context, method, path string, in, out any) (int, error) {
	var body io.Reader
	if in != nil {
		data, err := json.Marshal(in)
		if err != nil {
			return 0, fmt.Errorf("encode request: %w", err)
		}
		body = bytes.NewReader(data)
	}
	req, err := http.NewRequestWithContext(ctx, method, c.base+path, body)
	if err != nil {
		return 0, err
	}
	req.Header.Set("Accept", "application/json")
	if in != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	if c.token != "" {
		req.Header.Set("Authorization", "Bearer "+c.token)
	}
	resp, err := c.http.Do(req)
	if err != nil {
		return 0, err
	}
	defer resp.Body.Close()
	data, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil {
		return resp.StatusCode, fmt.Errorf("read answer: %w", err)
	}
	if resp.StatusCode == http.StatusOK && out != nil {
		if err := json.Unmarshal(data, out); err != nil {
			return resp.StatusCode, fmt.Errorf("decode answer: %w", err)
		}
	}
	return resp.StatusCode, nil
}
