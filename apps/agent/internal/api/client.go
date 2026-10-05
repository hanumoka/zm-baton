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

// ProtocolVersion is the report protocol version sent with every event batch.
const ProtocolVersion = 1

// PendingRun is one run waiting to be claimed (GET /api/runs?state=requested).
type PendingRun struct {
	ID         string `json:"id"`
	WorkItemID string `json:"work_item_id"`
	Executor   string `json:"executor"`
}

// Claim is the server's answer to a successful claim.
type Claim struct {
	RunID        string `json:"run_id"`
	WorkItemID   string `json:"work_item_id"`
	Generation   int64  `json:"generation"`
	LeaseSeconds int    `json:"lease_seconds"`
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

// StatusError is a non-2xx answer other than a claim conflict.
type StatusError struct {
	Op     string
	Status int
}

func (e *StatusError) Error() string {
	return fmt.Sprintf("%s: server answered HTTP %d", e.Op, e.Status)
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
	var runs []PendingRun
	status, err := c.do(ctx, http.MethodGet, "/api/runs?state=requested", nil, &runs)
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
