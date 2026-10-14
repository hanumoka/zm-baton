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
	"time"
)

// ClientFor is a client for the saved login's server that sends this device's token on the device's
// own calls. The token never goes to any other server.
func ClientFor(cfg Config, timeout time.Duration) (*Client, error) {
	c, err := NewClient(cfg.Server, timeout)
	if err != nil {
		return nil, err
	}
	c.deviceToken = cfg.DeviceToken
	return c, nil
}

// AuthState is what a server says about its sign-in (GET /api/auth/state).
type AuthState struct {
	// Mode is "local" or "required"; an older server has no such endpoint and counts as local.
	Mode   string `json:"mode"`
	Secure bool   `json:"secure"`
}

// AuthState asks the server which mode it is in.
func (c *Client) AuthState(ctx context.Context) (AuthState, error) {
	var out AuthState
	err := c.do(ctx, http.MethodGet, "/api/auth/state", "", nil, http.StatusOK, &out, "read the sign-in mode")
	var status *StatusError
	if errors.As(err, &status) && status.Status == http.StatusNotFound {
		return AuthState{Mode: "local"}, nil
	}
	return out, err
}

// Rekey gets a new device token for a device registered before tokens (local mode only: a server in
// required mode answers 401, and the device then logs in with a code instead).
func (c *Client) Rekey(ctx context.Context, deviceID string) (DeviceLogin, error) {
	var out DeviceLogin
	err := c.do(ctx, http.MethodPost, "/api/devices/"+url.PathEscape(deviceID)+"/token", "", nil, http.StatusOK, &out, "get a device token")
	return out, err
}

// DeviceSelf is the device the server sees behind this device's token (GET /api/devices/self).
type DeviceSelf struct {
	DeviceID string `json:"device_id"`
	Name     string `json:"name"`
	UserID   string `json:"user_id"`
	Mode     string `json:"mode"`
}

// Self checks this device's token with the server.
func (c *Client) Self(ctx context.Context) (DeviceSelf, error) {
	var out DeviceSelf
	err := c.do(ctx, http.MethodGet, "/api/devices/self", "", nil, http.StatusOK, &out, "check the device token")
	return out, err
}

// CodeLogin is the server's answer when a device asks to log in with a code (RFC 8628 shape).
type CodeLogin struct {
	DeviceCode               string `json:"device_code"`
	UserCode                 string `json:"user_code"`
	VerificationPathComplete string `json:"verification_path_complete"`
	ExpiresIn                int    `json:"expires_in"`
	Interval                 int    `json:"interval"`
}

// StartCodeLogin asks the server for a code a signed-in person approves on the Web. deviceID is this
// device's own id when it asks for a token for itself, empty for a new device.
func (c *Client) StartCodeLogin(ctx context.Context, name, osName, deviceID string) (CodeLogin, error) {
	var out CodeLogin
	body := map[string]string{"name": name, "os": osName}
	if deviceID != "" {
		body["device_id"] = deviceID
	}
	err := c.do(ctx, http.MethodPost, "/api/device-logins", "", body, http.StatusOK, &out, "start a device login")
	return out, err
}

// PollCodeLogin asks once whether the code was approved. It returns the login when it was, or the
// server's RFC 8628 error code (authorization_pending, slow_down, access_denied, expired_token,
// invalid_grant).
func (c *Client) PollCodeLogin(ctx context.Context, deviceCode string) (DeviceLogin, string, error) {
	data, err := json.Marshal(map[string]string{"device_code": deviceCode})
	if err != nil {
		return DeviceLogin{}, "", err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.base+"/api/device-logins/token", bytes.NewReader(data))
	if err != nil {
		return DeviceLogin{}, "", err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "application/json")
	resp, err := c.http.Do(req)
	if err != nil {
		return DeviceLogin{}, "", err
	}
	defer resp.Body.Close()
	answer, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil {
		return DeviceLogin{}, "", fmt.Errorf("device login: read answer: %w", err)
	}
	switch resp.StatusCode {
	case http.StatusOK:
		var out DeviceLogin
		if err := json.Unmarshal(answer, &out); err != nil {
			return DeviceLogin{}, "", fmt.Errorf("device login: decode answer: %w", err)
		}
		return out, "", nil
	case http.StatusBadRequest:
		var refusal struct {
			Error string `json:"error"`
		}
		if json.Unmarshal(answer, &refusal) == nil && refusal.Error != "" {
			return DeviceLogin{}, refusal.Error, nil
		}
	}
	return DeviceLogin{}, "", &StatusError{Op: "device login", Status: resp.StatusCode}
}

// LoginOptions say how `zm-baton login` logs this device in.
type LoginOptions struct {
	Server string
	Name   string
	OS     string
	// Existing is the saved login, if any. A device saved without a token asks for a token for itself
	// (it keeps its id, its repositories and its history) unless Force is set.
	Existing *Config
	Force    bool
	// Show is where the code and the page to open are written for the person (required mode).
	Show io.Writer
	// Sleep waits between polls; tests replace it.
	Sleep func(context.Context, time.Duration) error
}

// LoginResult says what `zm-baton login` did.
type LoginResult struct {
	Config Config
	// Mode is the server's sign-in mode.
	Mode string
	// Rekeyed is true when an existing device got a token for itself rather than a new device being made.
	Rekeyed bool
}

// Login logs this device in, in order:
//  1. It asks the server its mode (an older server counts as local).
//  2. Local mode: it registers a new device, or gets a token for the saved device that has none.
//  3. Required mode: it asks for a code, shows the person the page to open and the code, and polls
//     until the person approves it on the Web, denies it, or the code expires (10 minutes).
//
// The token it gets is returned in Config and is never printed.
func Login(ctx context.Context, opt LoginOptions) (LoginResult, error) {
	client, err := NewClient(opt.Server, 10*time.Second)
	if err != nil {
		return LoginResult{}, err
	}
	state, err := client.AuthState(ctx)
	if err != nil {
		return LoginResult{}, err
	}
	reuse := ""
	if opt.Existing != nil && !opt.Force && opt.Existing.Server == opt.Server && opt.Existing.DeviceToken == "" {
		reuse = opt.Existing.DeviceID
	}
	if state.Mode != "required" {
		if reuse != "" {
			got, err := client.Rekey(ctx, reuse)
			var status *StatusError
			if err == nil {
				return LoginResult{Config{opt.Server, got.DeviceID, got.DeviceToken}, state.Mode, true}, nil
			}
			// A server from before tokens has no such endpoint, and a device the server no longer knows
			// (revoked) cannot get one: register a new device as before.
			if !errors.As(err, &status) || (status.Status != http.StatusNotFound && status.Status != http.StatusMethodNotAllowed) {
				return LoginResult{}, err
			}
		}
		got, err := client.RegisterDevice(ctx, opt.Name, opt.OS)
		if err != nil {
			return LoginResult{}, err
		}
		return LoginResult{Config{opt.Server, got.DeviceID, got.DeviceToken}, state.Mode, false}, nil
	}
	got, err := codeLogin(ctx, client, opt, reuse)
	if err != nil {
		return LoginResult{}, err
	}
	return LoginResult{Config{opt.Server, got.DeviceID, got.DeviceToken}, state.Mode, got.DeviceID == reuse && reuse != ""}, nil
}

func codeLogin(ctx context.Context, client *Client, opt LoginOptions, deviceID string) (DeviceLogin, error) {
	start, err := client.StartCodeLogin(ctx, opt.Name, opt.OS, deviceID)
	if err != nil {
		return DeviceLogin{}, err
	}
	if opt.Show != nil {
		fmt.Fprintf(opt.Show, "이 서버는 로그인을 요구한다. 웹에 로그인한 브라우저에서 아래 주소를 열고, 코드가 같은지 본 뒤 「연결 허락」을 누른다.\n")
		fmt.Fprintf(opt.Show, "  %s%s\n  코드: %s (%d분 동안 쓸 수 있다)\n", client.base, start.VerificationPathComplete, start.UserCode, max(start.ExpiresIn/60, 1))
	}
	sleep := opt.Sleep
	if sleep == nil {
		sleep = func(ctx context.Context, d time.Duration) error {
			select {
			case <-ctx.Done():
				return ctx.Err()
			case <-time.After(d):
				return nil
			}
		}
	}
	interval := time.Duration(max(start.Interval, 1)) * time.Second
	deadline := time.Now().Add(time.Duration(max(start.ExpiresIn, 60)) * time.Second)
	for time.Now().Before(deadline) {
		if err := sleep(ctx, interval); err != nil {
			return DeviceLogin{}, err
		}
		got, code, err := client.PollCodeLogin(ctx, start.DeviceCode)
		if err != nil {
			return DeviceLogin{}, err
		}
		switch code {
		case "":
			return got, nil
		case "authorization_pending":
		case "slow_down":
			interval += 5 * time.Second // RFC 8628 3.5
		case "access_denied":
			return DeviceLogin{}, errors.New("웹에서 이 기기의 연결을 거절했다")
		case "expired_token":
			return DeviceLogin{}, errors.New("코드의 기한이 지났다. zm-baton login을 다시 실행한다")
		default:
			return DeviceLogin{}, fmt.Errorf("기기 로그인이 끝나지 않았다(%s). zm-baton login을 다시 실행한다", code)
		}
	}
	return DeviceLogin{}, errors.New("코드의 기한이 지났다. zm-baton login을 다시 실행한다")
}
