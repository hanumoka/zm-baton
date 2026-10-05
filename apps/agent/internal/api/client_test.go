package api

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestNewClientRejectsBadURLs(t *testing.T) {
	for _, u := range []string{"", "localhost:18081", "ftp://host", "http://user:pw@host", "http://host?x=1"} {
		if _, err := NewClient(u); err == nil {
			t.Errorf("NewClient(%q) accepted", u)
		}
	}
	if _, err := NewClient("http://localhost:18081/"); err != nil {
		t.Fatal(err)
	}
}

func TestWireFormat(t *testing.T) {
	var gotBody map[string]any
	mux := http.NewServeMux()
	mux.HandleFunc("GET /api/runs", func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Query().Get("state") != "requested" {
			http.Error(w, "state", http.StatusBadRequest)
			return
		}
		io.WriteString(w, `[{"id":"run_1","work_item_id":"wrk_1","executor":"claude_code"}]`)
	})
	mux.HandleFunc("POST /api/runs/{id}/claim", func(w http.ResponseWriter, r *http.Request) {
		if r.PathValue("id") == "run_taken" {
			w.WriteHeader(http.StatusConflict)
			return
		}
		json.NewDecoder(r.Body).Decode(&gotBody)
		io.WriteString(w, `{"run_id":"run_1","work_item_id":"wrk_1","generation":4,"lease_seconds":45}`)
	})
	mux.HandleFunc("POST /api/runs/{id}/events", func(w http.ResponseWriter, r *http.Request) {
		json.NewDecoder(r.Body).Decode(&gotBody)
		io.WriteString(w, `{"stored":1,"duplicates":0,"late":0,"rejected":0}`)
	})
	srv := httptest.NewServer(mux)
	defer srv.Close()
	c, err := NewClient(srv.URL)
	if err != nil {
		t.Fatal(err)
	}
	ctx := context.Background()

	runs, err := c.PendingRuns(ctx)
	if err != nil || len(runs) != 1 || runs[0] != (PendingRun{ID: "run_1", WorkItemID: "wrk_1", Executor: "claude_code"}) {
		t.Fatalf("pending runs %+v err %v", runs, err)
	}

	claim, err := c.Claim(ctx, "run_1", "dev_a")
	if err != nil || claim.Generation != 4 || claim.LeaseSeconds != 45 || gotBody["device_id"] != "dev_a" {
		t.Fatalf("claim %+v err %v body %v", claim, err, gotBody)
	}
	if _, err := c.Claim(ctx, "run_taken", "dev_a"); !errors.Is(err, ErrConflict) {
		t.Fatalf("409 must map to ErrConflict, got %v", err)
	}

	ev := Event{EventID: "run_1:1", Generation: 4, Seq: 1, Kind: "lifecycle",
		Payload: map[string]any{"phase": "started"}, OccurredAt: "2026-10-05T00:00:00Z"}
	res, err := c.SendEvents(ctx, "run_1", []Event{ev})
	if err != nil || res.Stored != 1 {
		t.Fatalf("send %+v err %v", res, err)
	}
	if gotBody["protocol_version"] != 1.0 {
		t.Fatalf("protocol_version missing: %v", gotBody)
	}
	sent := gotBody["events"].([]any)[0].(map[string]any)
	for _, k := range []string{"event_id", "generation", "seq", "kind", "payload", "occurred_at"} {
		if _, ok := sent[k]; !ok {
			t.Errorf("event field %q missing: %v", k, sent)
		}
	}
}

func TestStatusErrorPermanence(t *testing.T) {
	for status, want := range map[int]bool{400: true, 404: true, 408: false, 429: false, 500: false, 503: false} {
		if got := (&StatusError{Status: status}).Permanent(); got != want {
			t.Errorf("HTTP %d permanent=%v, want %v", status, got, want)
		}
	}
}
