// Package report numbers, batches and sends the events of one run
// (contract v1 "보고 한 건의 모양", "전송 경로" 3).
//
// Unsent events stay in memory only. Keeping them on disk is a later stage-1 task.
package report

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"slices"
	"sync"
	"time"

	"github.com/hanumoka/zm-baton/apps/agent/internal/api"
)

// DefaultInterval is how often queued events are sent.
const DefaultInterval = 500 * time.Millisecond

// maxBatch caps the number of events in one POST.
const maxBatch = 200

// Sender delivers one batch. *api.Client implements it.
type Sender interface {
	SendEvents(ctx context.Context, runID string, events []api.Event) (api.EventResult, error)
}

// Reporter gives each event of one run its seq and event_id and sends them in order.
type Reporter struct {
	sender     Sender
	runID      string
	generation int64
	interval   time.Duration
	log        *slog.Logger
	now        func() time.Time

	mu      sync.Mutex
	nextSeq int64
	pending []api.Event
	totals  api.EventResult
	dropped int

	sendMu  sync.Mutex // one flush at a time keeps batches in seq order
	started bool
	closed  bool
	stop    chan struct{}
	done    chan struct{}
}

// New makes a reporter for one claimed run. interval <= 0 means DefaultInterval.
func New(sender Sender, runID string, generation int64, interval time.Duration, log *slog.Logger) *Reporter {
	if interval <= 0 {
		interval = DefaultInterval
	}
	if log == nil {
		log = slog.New(slog.DiscardHandler)
	}
	return &Reporter{
		sender:     sender,
		runID:      runID,
		generation: generation,
		interval:   interval,
		log:        log,
		now:        time.Now,
		stop:       make(chan struct{}),
		done:       make(chan struct{}),
	}
}

// Start sends queued events every interval until Close.
func (r *Reporter) Start() {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.started || r.closed {
		return
	}
	r.started = true
	go r.loop()
}

// Add numbers an event and queues it. seq starts at 1; event_id is "<run id>:<seq>".
func (r *Reporter) Add(kind string, payload map[string]any) api.Event {
	if payload == nil {
		payload = map[string]any{}
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	r.nextSeq++
	ev := api.Event{
		EventID:    fmt.Sprintf("%s:%d", r.runID, r.nextSeq),
		Generation: r.generation,
		Seq:        r.nextSeq,
		Kind:       kind,
		Payload:    payload,
		OccurredAt: r.now().UTC().Format(time.RFC3339Nano),
	}
	r.pending = append(r.pending, ev)
	return ev
}

// Count is the number of events numbered so far.
func (r *Reporter) Count() int64 {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.nextSeq
}

// Totals sums the server's answers so far, plus events dropped after a permanent refusal.
func (r *Reporter) Totals() (api.EventResult, int) {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.totals, r.dropped
}

// Close stops the timer and sends what is left, retrying transient failures until ctx ends.
func (r *Reporter) Close(ctx context.Context) error {
	r.mu.Lock()
	started := r.started
	r.started = false
	r.closed = true
	r.mu.Unlock()
	if started {
		close(r.stop)
		<-r.done
	}
	backoff := 200 * time.Millisecond
	for {
		err := r.flush(ctx)
		if err == nil {
			break
		}
		r.mu.Lock()
		left := len(r.pending)
		r.mu.Unlock()
		select {
		case <-ctx.Done():
			return fmt.Errorf("%d events not sent: %w", left, err)
		case <-time.After(backoff):
		}
		backoff = min(backoff*2, 5*time.Second)
	}
	if _, dropped := r.Totals(); dropped > 0 {
		return fmt.Errorf("%d events refused permanently by the server", dropped)
	}
	return nil
}

func (r *Reporter) loop() {
	defer close(r.done)
	ticker := time.NewTicker(r.interval)
	defer ticker.Stop()
	for {
		select {
		case <-r.stop:
			return
		case <-ticker.C:
			if err := r.flush(context.Background()); err != nil {
				r.log.Warn("sending events failed; will retry", "err", err)
			}
		}
	}
}

// flush sends queued events oldest first and stops at the first transient failure,
// keeping the unsent events for the next try. Resending is safe because event_id is stable.
func (r *Reporter) flush(ctx context.Context) error {
	r.sendMu.Lock()
	defer r.sendMu.Unlock()
	for {
		r.mu.Lock()
		n := min(len(r.pending), maxBatch)
		if n == 0 {
			r.mu.Unlock()
			return nil
		}
		batch := slices.Clone(r.pending[:n])
		r.mu.Unlock()

		res, err := r.sender.SendEvents(ctx, r.runID, batch)
		if err != nil {
			var se *api.StatusError
			if !errors.As(err, &se) || !se.Permanent() {
				return err
			}
			// The server will never take this batch. Drop it so later events are not stuck behind it.
			r.log.Error("server refused an event batch; dropping it",
				"err", err, "first_seq", batch[0].Seq, "count", n)
			r.mu.Lock()
			r.pending = r.pending[n:]
			r.dropped += n
			r.mu.Unlock()
			continue
		}
		if res.Rejected > 0 {
			r.log.Warn("server rejected events", "rejected", res.Rejected, "first_seq", batch[0].Seq)
		}
		r.mu.Lock()
		r.pending = r.pending[n:]
		r.totals.Stored += res.Stored
		r.totals.Duplicates += res.Duplicates
		r.totals.Late += res.Late
		r.totals.Rejected += res.Rejected
		r.mu.Unlock()
	}
}
