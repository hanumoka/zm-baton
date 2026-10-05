package report

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"sync"
	"testing"
	"time"

	"github.com/hanumoka/zm-baton/apps/agent/internal/api"
)

// fakeSender records batches. fail returns an error for the n-th call (1-based) or nil.
type fakeSender struct {
	mu      sync.Mutex
	calls   int
	batches [][]api.Event
	fail    func(call int) error
}

func (f *fakeSender) SendEvents(_ context.Context, runID string, events []api.Event) (api.EventResult, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.calls++
	f.batches = append(f.batches, append([]api.Event(nil), events...))
	if f.fail != nil {
		if err := f.fail(f.calls); err != nil {
			return api.EventResult{}, err
		}
	}
	return api.EventResult{Stored: len(events)}, nil
}

func (f *fakeSender) snapshot() [][]api.Event {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([][]api.Event(nil), f.batches...)
}

func TestAddNumbersEventsFromOne(t *testing.T) {
	r := New(&fakeSender{}, "run_a", 7, time.Hour, nil)
	r.now = func() time.Time { return time.Date(2026, 10, 5, 1, 2, 3, 0, time.FixedZone("KST", 9*3600)) }
	for i := 1; i <= 3; i++ {
		ev := r.Add("answer", map[string]any{"text": "x"})
		if ev.Seq != int64(i) || ev.EventID != fmt.Sprintf("run_a:%d", i) || ev.Generation != 7 {
			t.Fatalf("event %d: %+v", i, ev)
		}
		if ev.OccurredAt != "2026-10-04T16:02:03Z" {
			t.Fatalf("occurred_at must be UTC RFC 3339: %q", ev.OccurredAt)
		}
	}
	if ev := r.Add("lifecycle", nil); ev.Payload == nil {
		t.Fatal("nil payload must become an empty object")
	}
	if r.Count() != 4 {
		t.Fatalf("count %d", r.Count())
	}
}

func TestCloseSendsQueuedEventsInOneBatch(t *testing.T) {
	s := &fakeSender{}
	r := New(s, "run_a", 1, time.Hour, nil) // the timer never fires in this test
	r.Start()
	for range 3 {
		r.Add("thought", nil)
	}
	if err := r.Close(context.Background()); err != nil {
		t.Fatal(err)
	}
	b := s.snapshot()
	if len(b) != 1 || len(b[0]) != 3 {
		t.Fatalf("want one batch of 3, got %d batches", len(b))
	}
	for i, ev := range b[0] {
		if ev.Seq != int64(i+1) {
			t.Fatalf("batch out of order: %+v", b[0])
		}
	}
	if totals, dropped := r.Totals(); totals.Stored != 3 || dropped != 0 {
		t.Fatalf("totals %+v dropped %d", totals, dropped)
	}
}

func TestTimerFlushesWithoutClose(t *testing.T) {
	s := &fakeSender{}
	r := New(s, "run_a", 1, 10*time.Millisecond, nil)
	r.Start()
	defer r.Close(context.Background())
	r.Add("answer", nil)
	deadline := time.Now().Add(2 * time.Second)
	for len(s.snapshot()) == 0 {
		if time.Now().After(deadline) {
			t.Fatal("timer never sent the queued event")
		}
		time.Sleep(5 * time.Millisecond)
	}
}

func TestLargeQueueIsSplitInOrder(t *testing.T) {
	s := &fakeSender{}
	r := New(s, "run_a", 1, time.Hour, nil)
	total := maxBatch*2 + 5
	for range total {
		r.Add("usage", nil)
	}
	if err := r.Close(context.Background()); err != nil {
		t.Fatal(err)
	}
	var seq int64
	for _, b := range s.snapshot() {
		if len(b) > maxBatch {
			t.Fatalf("batch of %d exceeds %d", len(b), maxBatch)
		}
		for _, ev := range b {
			seq++
			if ev.Seq != seq {
				t.Fatalf("seq %d where %d expected", ev.Seq, seq)
			}
		}
	}
	if seq != int64(total) {
		t.Fatalf("sent %d of %d", seq, total)
	}
}

func TestTransientFailureResendsSameEvents(t *testing.T) {
	s := &fakeSender{fail: func(call int) error {
		if call == 1 {
			return &api.StatusError{Op: "send events", Status: http.StatusServiceUnavailable}
		}
		return nil
	}}
	r := New(s, "run_a", 1, time.Hour, nil)
	r.Add("answer", nil)
	r.Add("lifecycle", nil)
	if err := r.Close(context.Background()); err != nil {
		t.Fatal(err)
	}
	b := s.snapshot()
	if len(b) != 2 {
		t.Fatalf("want a failed try and a retry, got %d calls", len(b))
	}
	for i := range b[0] {
		if b[0][i].EventID != b[1][i].EventID || b[0][i].Seq != b[1][i].Seq {
			t.Fatalf("retry changed event identity: %+v vs %+v", b[0][i], b[1][i])
		}
	}
}

func TestCloseGivesUpWhenContextEnds(t *testing.T) {
	s := &fakeSender{fail: func(int) error { return errors.New("connection refused") }}
	r := New(s, "run_a", 1, time.Hour, nil)
	r.Add("answer", nil)
	ctx, cancel := context.WithTimeout(context.Background(), 300*time.Millisecond)
	defer cancel()
	if err := r.Close(ctx); err == nil {
		t.Fatal("want an error when events could not be sent")
	}
}

func TestPermanentRefusalDropsBatchAndContinues(t *testing.T) {
	s := &fakeSender{fail: func(call int) error {
		if call == 1 {
			return &api.StatusError{Op: "send events", Status: http.StatusBadRequest}
		}
		return nil
	}}
	r := New(s, "run_a", 1, time.Hour, nil)
	r.Add("answer", nil)
	if err := r.flush(context.Background()); err != nil {
		t.Fatalf("a permanent refusal should not block the queue: %v", err)
	}
	r.Add("lifecycle", nil)
	if err := r.Close(context.Background()); err == nil {
		t.Fatal("Close must report the dropped events")
	}
	b := s.snapshot()
	if len(b) != 2 || b[1][0].Seq != 2 {
		t.Fatalf("want the second event sent after the drop, got %d calls", len(b))
	}
	if _, dropped := r.Totals(); dropped != 1 {
		t.Fatalf("dropped %d, want 1", dropped)
	}
}
