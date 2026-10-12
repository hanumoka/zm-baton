package session

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

// ProtocolVersion of the session API (contract v1, "바꾸는 방법").
const ProtocolVersion = 2

// Client talks to the zm-baton server's session API.
type Client struct {
	base string
	http *http.Client
}

// NewClient checks the server URL. Credentials in the URL are refused so they never reach logs,
// and no error repeats the URL: url.Parse quotes its input, which may carry them.
func NewClient(server string, timeout time.Duration) (*Client, error) {
	u, err := url.Parse(server)
	if err != nil {
		return nil, errors.New("server URL is not a valid URL; run zm-baton login again")
	}
	if (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" {
		return nil, errors.New("server URL must look like http://host:port")
	}
	if u.User != nil || u.RawQuery != "" || u.Fragment != "" {
		return nil, errors.New("server URL must not carry user info, a query or a fragment")
	}
	return &Client{base: strings.TrimRight(u.String(), "/"), http: &http.Client{Timeout: timeout}}, nil
}

// RepositoryInfo is what the server learns about the repository: never an absolute path.
type RepositoryInfo struct {
	RepoKey string `json:"repo_key,omitempty"`
	Remote  string `json:"remote,omitempty"`
	Prefix  string `json:"prefix"`
	Branch  string `json:"branch,omitempty"`
}

// RegisterRequest is the body of POST /api/sessions.
type RegisterRequest struct {
	ProtocolVersion   int             `json:"protocol_version"`
	DeviceID          string          `json:"device_id"`
	Executor          string          `json:"executor"`
	ExecutorVersion   string          `json:"executor_version,omitempty"`
	ExecutorSessionID string          `json:"executor_session_id"`
	Repository        *RepositoryInfo `json:"repository,omitempty"`
}

// Registered is the server's answer to a registration.
type Registered struct {
	SessionID  string `json:"session_id"`
	Token      string `json:"token"`
	Reused     bool   `json:"reused"`
	StatusCard string `json:"status_card"`
}

// Event is one session event (contract "세션이 보내는 사건").
type Event struct {
	EventID  string         `json:"event_id"`
	LocalSeq int64          `json:"local_seq"`
	Kind     string         `json:"kind"`
	Payload  map[string]any `json:"payload"`
}

// Result counts what the server did with one batch; Reasons explain rejected events.
type Result struct {
	Stored     int      `json:"stored"`
	Duplicates int      `json:"duplicates"`
	Rejected   int      `json:"rejected"`
	Reasons    []string `json:"reasons"`
}

// StatusError is a non-2xx answer.
type StatusError struct {
	Op     string
	Status int
}

func (e *StatusError) Error() string {
	return fmt.Sprintf("%s: server answered HTTP %d", e.Op, e.Status)
}

// RegisterDevice is `zm-baton login`.
func (c *Client) RegisterDevice(ctx context.Context, name, osName string) (string, error) {
	var out struct {
		DeviceID string `json:"device_id"`
	}
	if err := c.do(ctx, http.MethodPost, "/api/devices", "", map[string]string{"name": name, "os": osName}, http.StatusCreated, &out, "register device"); err != nil {
		return "", err
	}
	return out.DeviceID, nil
}

// Register registers (or re-registers) an executor session.
func (c *Client) Register(ctx context.Context, req RegisterRequest) (Registered, error) {
	req.ProtocolVersion = ProtocolVersion
	var out Registered
	err := c.do(ctx, http.MethodPost, "/api/sessions", "", req, http.StatusCreated, &out, "register session")
	return out, err
}

// Send posts session events with the session token.
func (c *Client) Send(ctx context.Context, sessionID, token string, events []Event) (Result, error) {
	var out Result
	body := map[string]any{"protocol_version": ProtocolVersion, "events": events}
	err := c.do(ctx, http.MethodPost, "/api/sessions/"+url.PathEscape(sessionID)+"/events", token, body, http.StatusOK, &out, "send events")
	return out, err
}

// StatusCard reads the session's current status card.
func (c *Client) StatusCard(ctx context.Context, sessionID, token string) (string, error) {
	var out struct {
		StatusCard string `json:"status_card"`
	}
	err := c.do(ctx, http.MethodGet, "/api/sessions/"+url.PathEscape(sessionID)+"/status-card", token, nil, http.StatusOK, &out, "read status card")
	return out.StatusCard, err
}

// Health checks that the server answers.
func (c *Client) Health(ctx context.Context) error {
	return c.do(ctx, http.MethodGet, "/actuator/health", "", nil, http.StatusOK, nil, "health")
}

func (c *Client) do(ctx context.Context, method, path, token string, in any, want int, out any, op string) error {
	var body io.Reader
	if in != nil {
		data, err := json.Marshal(in)
		if err != nil {
			return fmt.Errorf("encode request: %w", err)
		}
		body = bytes.NewReader(data)
	}
	req, err := http.NewRequestWithContext(ctx, method, c.base+path, body)
	if err != nil {
		return err
	}
	req.Header.Set("Accept", "application/json")
	if in != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	resp, err := c.http.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	data, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil {
		return fmt.Errorf("%s: read answer: %w", op, err)
	}
	if resp.StatusCode != want {
		return &StatusError{Op: op, Status: resp.StatusCode}
	}
	if out != nil {
		if err := json.Unmarshal(data, out); err != nil {
			return fmt.Errorf("%s: decode answer: %w", op, err)
		}
	}
	return nil
}
