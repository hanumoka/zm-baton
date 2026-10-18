package main

import (
	"bytes"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"sync/atomic"
	"testing"

	"github.com/hanumoka/zm-baton/apps/agent/internal/session"
)

func TestServerWorkerRevocationExitsWithoutRestartableStatus(t *testing.T) {
	const token = "fixture-device-token"
	var requests atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requests.Add(1)
		if r.Header.Get("Authorization") != "Bearer "+token {
			t.Error("poll did not carry the configured device token")
		}
		w.WriteHeader(http.StatusUnauthorized)
	}))
	defer server.Close()
	home := loggedIn(t, server.URL)
	if err := session.SaveConfig(home, session.Config{Server: server.URL, DeviceID: "dev_cli", DeviceToken: token}); err != nil {
		t.Fatal(err)
	}
	// Revocation must stop before invoking any executor. The test executable only needs to exist.
	executable, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	var out bytes.Buffer
	code := run([]string{"agent", "--server-worker", "--claude", executable}, nil, &out, &out)
	if code != 78 || requests.Load() != 1 {
		t.Fatalf("revoked worker: exit=%d requests=%d output=%s", code, requests.Load(), out.String())
	}
	if strings.Contains(out.String(), token) {
		t.Fatal("device token appeared in the worker log")
	}
}
