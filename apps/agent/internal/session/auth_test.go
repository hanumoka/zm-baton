package session

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"
)

// authServer fakes the sign-in endpoints a server offers (docs/stage1/auth.md). The tokens are synthetic.
type authServer struct {
	mu       sync.Mutex
	mode     string
	polls    []string // what each poll answers, in order; "" hands the token over
	seen     []string // method, path and Authorization of every request
	approved bool
}

const fakeToken = "zmbd_synthetic-device-token"

func (s *authServer) handler(t *testing.T) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		s.mu.Lock()
		defer s.mu.Unlock()
		s.seen = append(s.seen, r.Method+" "+r.URL.Path+" "+r.Header.Get("Authorization"))
		w.Header().Set("Content-Type", "application/json")
		switch {
		case r.URL.Path == "/api/auth/state" && s.mode == "":
			w.WriteHeader(http.StatusNotFound) // a server from before sign-in
		case r.URL.Path == "/api/auth/state":
			_, _ = w.Write([]byte(`{"mode":"` + s.mode + `","secure":false}`))
		case r.Method == http.MethodPost && r.URL.Path == "/api/devices":
			w.WriteHeader(http.StatusCreated)
			if s.mode == "" {
				_, _ = w.Write([]byte(`{"device_id":"dev_new"}`))
				return
			}
			_, _ = w.Write([]byte(`{"device_id":"dev_new","device_token":"` + fakeToken + `"}`))
		case r.Method == http.MethodPost && r.URL.Path == "/api/devices/dev_old/token":
			_, _ = w.Write([]byte(`{"device_id":"dev_old","device_token":"` + fakeToken + `"}`))
		case r.Method == http.MethodPost && r.URL.Path == "/api/device-logins":
			var body map[string]string
			_ = json.NewDecoder(r.Body).Decode(&body)
			if body["name"] == "" {
				t.Errorf("device login without a name: %v", body)
			}
			_, _ = w.Write([]byte(`{"device_code":"zmbc_synthetic","user_code":"BCDF-GHJK","verification_path":"/#me",
				"verification_path_complete":"/#me?device_login=BCDF-GHJK","expires_in":600,"interval":2}`))
		case r.Method == http.MethodPost && r.URL.Path == "/api/device-logins/token":
			next := "authorization_pending"
			if len(s.polls) > 0 {
				next, s.polls = s.polls[0], s.polls[1:]
			}
			if next == "" {
				_, _ = w.Write([]byte(`{"device_id":"dev_old","device_token":"` + fakeToken + `"}`))
				return
			}
			w.WriteHeader(http.StatusBadRequest)
			_, _ = w.Write([]byte(`{"error":"` + next + `"}`))
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}
}

func startAuthServer(t *testing.T, mode string, polls ...string) (*authServer, string) {
	s := &authServer{mode: mode, polls: polls}
	srv := httptest.NewServer(s.handler(t))
	t.Cleanup(srv.Close)
	return s, srv.URL
}

func TestLoginOnALocalServerRegistersADeviceAndKeepsItsToken(t *testing.T) {
	_, url := startAuthServer(t, "local")
	var shown bytes.Buffer
	res, err := Login(context.Background(), LoginOptions{Server: url, Name: "pc", OS: "windows", Show: &shown})
	if err != nil {
		t.Fatal(err)
	}
	if res.Config != (Config{url, "dev_new", fakeToken}) || res.Rekeyed || res.Mode != "local" {
		t.Fatalf("login = %+v", res)
	}
	if strings.Contains(shown.String(), fakeToken) {
		t.Fatal("the token was shown")
	}
}

func TestLoginGivesASavedDeviceWithoutATokenATokenForItself(t *testing.T) {
	s, url := startAuthServer(t, "local")
	res, err := Login(context.Background(), LoginOptions{Server: url, Name: "pc", Existing: &Config{Server: url, DeviceID: "dev_old"}})
	if err != nil {
		t.Fatal(err)
	}
	if !res.Rekeyed || res.Config.DeviceID != "dev_old" || res.Config.DeviceToken != fakeToken {
		t.Fatalf("login = %+v", res)
	}
	// --force registers a new device instead, and a saved login of another server is not reused.
	for _, opt := range []LoginOptions{
		{Server: url, Name: "pc", Existing: &Config{Server: url, DeviceID: "dev_old"}, Force: true},
		{Server: url, Name: "pc", Existing: &Config{Server: "http://elsewhere", DeviceID: "dev_old"}},
	} {
		res, err := Login(context.Background(), opt)
		if err != nil || res.Rekeyed || res.Config.DeviceID != "dev_new" {
			t.Fatalf("login %+v = %+v, %v", opt, res, err)
		}
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if strings.Join(s.seen, "\n") == "" {
		t.Fatal("no request")
	}
}

func TestLoginOnAServerFromBeforeTokensRegistersAsBefore(t *testing.T) {
	_, url := startAuthServer(t, "")
	res, err := Login(context.Background(), LoginOptions{Server: url, Name: "pc", Existing: &Config{Server: url, DeviceID: "dev_gone"}})
	if err != nil {
		t.Fatal(err)
	}
	if res.Config.DeviceID != "dev_new" || res.Config.DeviceToken != "" {
		t.Fatalf("login = %+v", res)
	}
}

func TestLoginOnARequiredServerShowsTheCodeAndWaitsForTheApproval(t *testing.T) {
	_, url := startAuthServer(t, "required", "authorization_pending", "slow_down", "")
	var shown bytes.Buffer
	var waits []time.Duration
	res, err := Login(context.Background(), LoginOptions{
		Server: url, Name: "pc", OS: "windows", Existing: &Config{Server: url, DeviceID: "dev_old"}, Show: &shown,
		Sleep: func(_ context.Context, d time.Duration) error { waits = append(waits, d); return nil },
	})
	if err != nil {
		t.Fatal(err)
	}
	if res.Config != (Config{url, "dev_old", fakeToken}) || !res.Rekeyed || res.Mode != "required" {
		t.Fatalf("login = %+v", res)
	}
	out := shown.String()
	for _, want := range []string{url + "/#me?device_login=BCDF-GHJK", "코드: BCDF-GHJK", "연결 허락"} {
		if !strings.Contains(out, want) {
			t.Errorf("shown lacks %q:\n%s", want, out)
		}
	}
	if strings.Contains(out, fakeToken) || strings.Contains(out, "zmbc_") {
		t.Fatal("a secret was shown")
	}
	// RFC 8628 3.5: slow_down adds five seconds to the interval.
	if len(waits) != 3 || waits[0] != 2*time.Second || waits[1] != 2*time.Second || waits[2] != 7*time.Second {
		t.Fatalf("waits = %v", waits)
	}
}

func TestLoginOnARequiredServerStopsWhenDeniedOrExpired(t *testing.T) {
	for code, want := range map[string]string{"access_denied": "거절", "expired_token": "기한", "invalid_grant": "invalid_grant"} {
		_, url := startAuthServer(t, "required", code)
		_, err := Login(context.Background(), LoginOptions{
			Server: url, Name: "pc", Sleep: func(context.Context, time.Duration) error { return nil },
		})
		if err == nil || !strings.Contains(err.Error(), want) {
			t.Errorf("%s: err = %v", code, err)
		}
	}
}

func TestTheDeviceTokenGoesOnTheDevicesCallsAndNeverInPlaceOfASessionToken(t *testing.T) {
	s, url := startAuthServer(t, "required")
	client, err := ClientFor(Config{Server: url, DeviceID: "dev_new", DeviceToken: fakeToken}, time.Second)
	if err != nil {
		t.Fatal(err)
	}
	ctx := context.Background()
	_, _ = client.Register(ctx, RegisterRequest{DeviceID: "dev_new", Executor: "claude_code", ExecutorSessionID: "s"})
	_, _ = client.Send(ctx, "ses_1", "zmbs_session", nil)
	_, _ = client.AddRepository(ctx, "dev_new", LocalRepo{Key: "git:abc", Name: "demo"})
	var sink bytes.Buffer
	_, _ = client.Export(ctx, &sink)
	plain, err := NewClient(url, time.Second)
	if err != nil {
		t.Fatal(err)
	}
	_, _ = plain.Register(ctx, RegisterRequest{DeviceID: "dev_new", Executor: "claude_code", ExecutorSessionID: "s"})
	s.mu.Lock()
	defer s.mu.Unlock()
	want := []string{
		"POST /api/sessions Bearer " + fakeToken,
		"POST /api/sessions/ses_1/events Bearer zmbs_session",
		"POST /api/devices/dev_new/repositories Bearer " + fakeToken,
		"GET /api/export Bearer " + fakeToken,
		"POST /api/sessions ",
	}
	if strings.Join(s.seen, "\n") != strings.Join(want, "\n") {
		t.Fatalf("requests:\n%s\nwant:\n%s", strings.Join(s.seen, "\n"), strings.Join(want, "\n"))
	}
}

func TestConfigKeepsTheTokenAndReadsAConfigFromBeforeTokens(t *testing.T) {
	dir := t.TempDir()
	if err := SaveConfig(dir, Config{Server: "http://127.0.0.1:18081", DeviceID: "dev_a", DeviceToken: fakeToken}); err != nil {
		t.Fatal(err)
	}
	got, err := LoadConfig(dir)
	if err != nil || got.DeviceToken != fakeToken {
		t.Fatalf("load = %+v, %v", got, err)
	}
	if err := writeFileAtomic(dir+"/config.json", []byte(`{"server":"http://127.0.0.1:18081","device_id":"dev_old"}`)); err != nil {
		t.Fatal(err)
	}
	got, err = LoadConfig(dir)
	if err != nil || got.DeviceID != "dev_old" || got.DeviceToken != "" {
		t.Fatalf("old config = %+v, %v", got, err)
	}
}
