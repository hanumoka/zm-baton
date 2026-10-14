package api

import (
	"context"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
)

// The device token goes on every call of the connector, and a 401 answer is ErrUnauthorized, so the
// connector can stop and say what to do (docs/stage1/auth.md). The token is synthetic.
func TestTheDeviceTokenGoesOnEveryCallAndA401IsUnauthorized(t *testing.T) {
	const token = "zmbd_synthetic"
	var mu sync.Mutex
	var seen []string
	refuse := false
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		seen = append(seen, r.Header.Get("Authorization"))
		no := refuse
		mu.Unlock()
		if no {
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
		switch r.URL.Path {
		case "/api/runs":
			io.WriteString(w, `[]`)
		case "/api/runs/run_1/claim":
			io.WriteString(w, `{"run_id":"run_1","work_item_id":"wrk_1","generation":1,"lease_seconds":45}`)
		case "/api/runs/run_1/events":
			io.WriteString(w, `{"stored":0,"duplicates":0,"late":0,"rejected":0}`)
		case "/api/runs/run_1/lease":
			io.WriteString(w, `{"state":"running","lease_seconds":45,"inputs":[]}`)
		}
	}))
	defer srv.Close()
	plain, err := NewClient(srv.URL)
	if err != nil {
		t.Fatal(err)
	}
	c := plain.WithToken(token)
	ctx := context.Background()
	if _, err := c.PendingRunsFor(ctx, "dev_a"); err != nil {
		t.Fatal(err)
	}
	if _, err := c.Claim(ctx, "run_1", "dev_a"); err != nil {
		t.Fatal(err)
	}
	if _, err := c.SendEvents(ctx, "run_1", nil); err != nil {
		t.Fatal(err)
	}
	if _, err := c.Renew(ctx, "run_1", "dev_a", 1, nil); err != nil {
		t.Fatal(err)
	}
	if _, err := plain.PendingRuns(ctx); err != nil {
		t.Fatal(err)
	}
	mu.Lock()
	want := []string{"Bearer " + token, "Bearer " + token, "Bearer " + token, "Bearer " + token, ""}
	for i := range want {
		if seen[i] != want[i] {
			t.Errorf("call %d sent %q, want %q", i, seen[i], want[i])
		}
	}
	refuse = true
	mu.Unlock()

	_, err = c.PendingRunsFor(ctx, "dev_a")
	if !errors.Is(err, ErrUnauthorized) {
		t.Fatalf("poll after revoke: %v", err)
	}
	if _, err := c.Claim(ctx, "run_1", "dev_a"); !errors.Is(err, ErrUnauthorized) {
		t.Fatalf("claim after revoke: %v", err)
	}
	var status *StatusError
	if !errors.As(err, &status) || status.Status != http.StatusUnauthorized {
		t.Fatalf("the 401 is still a StatusError: %v", err)
	}
	if errors.Is(&StatusError{Op: "x", Status: http.StatusForbidden}, ErrUnauthorized) {
		t.Fatal("only a 401 is ErrUnauthorized")
	}
}
