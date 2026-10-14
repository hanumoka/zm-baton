package main

import (
	"bytes"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"

	"github.com/hanumoka/zm-baton/apps/agent/internal/session"
)

// The CLI with device tokens (docs/stage1/auth.md). The tokens are synthetic.

const cliToken = "zmbd_synthetic-cli"

type cliServer struct {
	mu      sync.Mutex
	mode    string
	revoked bool
	auth    []string // path and Authorization of every request
}

func newCLIServer(t *testing.T, mode string) (*cliServer, string) {
	s := &cliServer{mode: mode}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		s.mu.Lock()
		defer s.mu.Unlock()
		s.auth = append(s.auth, r.URL.Path+" "+r.Header.Get("Authorization"))
		w.Header().Set("Content-Type", "application/json")
		if s.revoked && r.Header.Get("Authorization") != "" {
			w.WriteHeader(http.StatusUnauthorized)
			io.WriteString(w, `{"message":"이 기기는 폐기됐다"}`)
			return
		}
		switch r.URL.Path {
		case "/actuator/health":
			io.WriteString(w, `{"status":"UP"}`)
		case "/api/auth/state":
			io.WriteString(w, `{"mode":"`+s.mode+`"}`)
		case "/api/devices":
			w.WriteHeader(http.StatusCreated)
			io.WriteString(w, `{"device_id":"dev_cli","device_token":"`+cliToken+`"}`)
		case "/api/devices/dev_cli/token":
			io.WriteString(w, `{"device_id":"dev_cli","device_token":"`+cliToken+`"}`)
		case "/api/devices/self":
			io.WriteString(w, `{"device_id":"dev_cli","name":"cli-pc","user_id":"usr_owner","mode":"`+s.mode+`"}`)
		case "/api/runs":
			if s.mode == "required" && r.Header.Get("Authorization") == "" {
				w.WriteHeader(http.StatusUnauthorized)
				io.WriteString(w, `{"message":"이 서버는 기기 토큰을 요구한다"}`)
				return
			}
			io.WriteString(w, `[]`)
		case "/api/export":
			io.WriteString(w, `{"format":"zm-baton.export","format_version":1,"provenance":{},"collections":{}}`)
		default:
			http.NotFound(w, r)
		}
	}))
	t.Cleanup(srv.Close)
	return s, srv.URL
}

func TestLoginKeepsTheTokenOutOfTheOutputAndAGivenDeviceGetsOneForItself(t *testing.T) {
	_, url := newCLIServer(t, "local")
	home := t.TempDir()
	t.Setenv("ZM_BATON_HOME", home)
	var out bytes.Buffer
	if code := run([]string{"login", "--server", url, "--name", "cli-pc"}, nil, &out, &out); code != 0 {
		t.Fatalf("login exited %d: %s", code, out.String())
	}
	if strings.Contains(out.String(), cliToken) {
		t.Fatal("the token was printed")
	}
	cfg, err := session.LoadConfig(home)
	if err != nil || cfg.DeviceToken != cliToken || cfg.DeviceID != "dev_cli" {
		t.Fatalf("config = %+v, %v", cfg, err)
	}
	out.Reset()
	if code := run([]string{"login"}, nil, &out, &out); code != 0 || !strings.Contains(out.String(), "기기 토큰 있음") {
		t.Fatalf("second login exited %d: %s", code, out.String())
	}

	// A device registered before tokens: login gets a token for the same device.
	if err := session.SaveConfig(home, session.Config{Server: url, DeviceID: "dev_cli"}); err != nil {
		t.Fatal(err)
	}
	out.Reset()
	if code := run([]string{"login"}, nil, &out, &out); code != 0 || !strings.Contains(out.String(), "이 기기의 기기 토큰을 받았다: dev_cli") {
		t.Fatalf("rekey login exited %d: %s", code, out.String())
	}
}

func TestStatusSaysWhetherTheDeviceHasATokenAndWhatTheServerNeeds(t *testing.T) {
	s, url := newCLIServer(t, "required")
	home := t.TempDir()
	t.Setenv("ZM_BATON_HOME", home)
	if err := session.SaveConfig(home, session.Config{Server: url, DeviceID: "dev_cli"}); err != nil {
		t.Fatal(err)
	}
	var out bytes.Buffer
	if code := run([]string{"status"}, nil, &out, &out); code != 0 || !strings.Contains(out.String(), "기기 토큰: 없음") ||
		!strings.Contains(out.String(), "이 서버는 기기 토큰을 요구한다") {
		t.Fatalf("status exited %d: %s", code, out.String())
	}
	if err := session.SaveConfig(home, session.Config{Server: url, DeviceID: "dev_cli", DeviceToken: cliToken}); err != nil {
		t.Fatal(err)
	}
	out.Reset()
	if code := run([]string{"status"}, nil, &out, &out); code != 0 || !strings.Contains(out.String(), "기기 토큰 확인: 정상") {
		t.Fatalf("status exited %d: %s", code, out.String())
	}
	s.mu.Lock()
	s.revoked = true
	s.mu.Unlock()
	out.Reset()
	if code := run([]string{"status"}, nil, &out, &out); code != 0 || !strings.Contains(out.String(), "서버가 받지 않았다") {
		t.Fatalf("status of a revoked device exited %d: %s", code, out.String())
	}
	if strings.Contains(out.String(), cliToken) {
		t.Fatal("the token was printed")
	}
}

func TestTheAgentStopsWithAHintWhenTheServerRefusesTheDevice(t *testing.T) {
	_, url := newCLIServer(t, "required")
	loggedIn(t, url) // a device registered before tokens: no token
	var out bytes.Buffer
	code := run([]string{"agent", "--claude", "go", "--poll-interval", "10ms"}, nil, &out, &out)
	if code != 1 || !strings.Contains(out.String(), "zm-baton login") {
		t.Fatalf("agent exited %d: %s", code, out.String())
	}
}

func TestExportSendsTheTokenToTheSavedServerOnly(t *testing.T) {
	saved, savedURL := newCLIServer(t, "required")
	other, otherURL := newCLIServer(t, "required")
	home := t.TempDir()
	t.Setenv("ZM_BATON_HOME", home)
	if err := session.SaveConfig(home, session.Config{Server: savedURL, DeviceID: "dev_cli", DeviceToken: cliToken}); err != nil {
		t.Fatal(err)
	}
	var out bytes.Buffer
	if code := run([]string{"export", "--out", t.TempDir() + "/a.json"}, nil, &out, &out); code != 0 {
		t.Fatalf("export exited %d: %s", code, out.String())
	}
	if code := run([]string{"export", "--out", t.TempDir() + "/b.json", "--server", otherURL}, nil, &out, &out); code != 0 {
		t.Fatalf("export to another server exited %d: %s", code, out.String())
	}
	saved.mu.Lock()
	other.mu.Lock()
	defer saved.mu.Unlock()
	defer other.mu.Unlock()
	if strings.Join(saved.auth, "\n") != "/api/export Bearer "+cliToken {
		t.Errorf("saved server saw %q", saved.auth)
	}
	if strings.Join(other.auth, "\n") != "/api/export " {
		t.Errorf("another server saw %q", other.auth)
	}
}

func TestA401SaysHowToGetATokenAgain(t *testing.T) {
	var out bytes.Buffer
	code := report(&out, &session.StatusError{Op: "add repository", Status: http.StatusUnauthorized, Message: "이 서버는 기기 토큰을 요구한다"})
	if code != 1 || !strings.Contains(out.String(), "zm-baton login으로 기기 토큰을 다시 받는다") {
		t.Fatalf("report = %d: %s", code, out.String())
	}
}
