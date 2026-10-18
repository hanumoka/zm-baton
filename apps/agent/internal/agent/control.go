package agent

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/hanumoka/zm-baton/apps/agent/internal/api"
	"github.com/hanumoka/zm-baton/apps/agent/internal/claude"
)

// While a claimed run goes on, the connector renews its lease (contract v1 "실행 권리 청구" 5), and
// each renewal's answer carries what a person asked for meanwhile: a stop, or instructions for the
// executor (FN-0410). The steps:
//
//  1. Every RenewInterval the control renews the lease and reports what became of the
//     instructions it took since the last renewal.
//  2. When the answer says stop, or the server no longer holds the run for this device, the
//     control closes its stop channel; the runner stops the executor through its pinned process.
//  3. Instructions go to the executor's stdin as stream-json user messages, once each. Claude Code
//     echoes a message back once it took it into the conversation; only then is it delivered.
//  4. When the executor ends, what was written but not echoed, or never written, is undelivered.
//     The final states ride in the end report, which the reporter delivers reliably.

// defaultRenewInterval is how often a running job renews its lease and hears what a person asked
// for. The contract's starting value is 15 seconds; without a wake-up signal yet, a shorter one
// keeps a stop or an instruction from waiting long. The lease itself stays 45 seconds.
const defaultRenewInterval = 5 * time.Second

// inputPrefix tells the executor that a message came from a person while it ran (agent norm 6).
const inputPrefix = "사람이 실행 중에 보낸 지시: "

// Stop reasons a control reports.
const (
	stopCancel = "cancel" // a person asked to stop the run
	stopGone   = "gone"   // the server no longer holds the run for this device (ended, e.g. lost)
)

type control struct {
	client   *api.Client
	runID    string
	device   string
	gen      int64
	interval time.Duration
	log      *slog.Logger

	mu       sync.Mutex
	why      string
	stopCh   chan struct{}
	unsent   map[string]string // states not yet acknowledged by a renewal
	states   map[string]string // every instruction seen, by id, with its latest state
	order    []string          // ids in the order they arrived
	texts    map[string]string // instruction text by id, until written
	pipe     *inputPipe
	disabled bool // the server does not renew leases; keep running without

	renewMu sync.Mutex // one renewal at a time
	quit    chan struct{}
	done    chan struct{}
	once    sync.Once
}

// startControl begins renewing the claim's lease. With no client (tests of pure logic) it does nothing.
func startControl(ctx context.Context, client *api.Client, c api.Claim, device string, interval time.Duration, log *slog.Logger) *control {
	if interval <= 0 {
		interval = defaultRenewInterval
	}
	ctl := &control{
		client: client, runID: c.RunID, device: device, gen: c.Generation, interval: interval, log: log,
		stopCh: make(chan struct{}), unsent: map[string]string{}, states: map[string]string{}, texts: map[string]string{},
		quit: make(chan struct{}), done: make(chan struct{}),
	}
	go ctl.loop(ctx)
	return ctl
}

func (c *control) loop(ctx context.Context) {
	defer close(c.done)
	t := time.NewTicker(c.interval)
	defer t.Stop()
	for {
		select {
		case <-c.quit:
			return
		case <-ctx.Done():
			return
		case <-t.C:
			if !c.renew(ctx) {
				return
			}
		}
	}
}

// renewNow renews at once, for example right before the executor starts. It returns false once
// renewing is over: the server dropped the run or does not renew leases.
func (c *control) renewNow(ctx context.Context) bool { return c.renew(ctx) }

func (c *control) renew(ctx context.Context) bool {
	c.renewMu.Lock()
	defer c.renewMu.Unlock()
	c.mu.Lock()
	if c.disabled || c.why == stopGone {
		c.mu.Unlock()
		return false
	}
	reports := make([]api.InputReport, 0, len(c.unsent))
	for _, id := range c.order {
		if st, ok := c.unsent[id]; ok {
			reports = append(reports, api.InputReport{ID: id, State: st})
		}
	}
	c.mu.Unlock()

	rctx, cancel := context.WithTimeout(ctx, 10*time.Second)
	lease, err := c.client.Renew(rctx, c.runID, c.device, c.gen, reports)
	cancel()
	switch {
	case errors.Is(err, api.ErrNoLeases):
		c.log.Warn("the server does not renew leases; the run goes on without (upgrade the server)")
		c.mu.Lock()
		c.disabled = true
		c.mu.Unlock()
		return false
	case errors.Is(err, api.ErrGone), errors.Is(err, api.ErrUnauthorized):
		c.log.Warn("the server no longer holds this run for this device; stopping the executor")
		c.requestStop(stopGone)
		return false
	case err != nil:
		// The lease may lapse; the server waits the reconnection grace before it gives up the run.
		c.log.Warn("could not renew the lease", "err", err)
		return true
	}
	c.mu.Lock()
	for _, r := range reports {
		if c.unsent[r.ID] == r.State {
			delete(c.unsent, r.ID)
		}
	}
	var fresh []string
	for _, in := range lease.Inputs {
		if _, seen := c.states[in.ID]; seen {
			continue
		}
		c.states[in.ID] = "pending"
		c.texts[in.ID] = in.Text
		c.order = append(c.order, in.ID)
		fresh = append(fresh, in.ID)
	}
	pipe := c.pipe
	c.mu.Unlock()
	if lease.Stop == stopCancel {
		c.requestStop(stopCancel)
	}
	if pipe != nil {
		c.deliver(pipe, fresh)
	}
	return true
}

func (c *control) requestStop(why string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.why != "" {
		if why == stopGone {
			c.why = stopGone // stop renewing too
		}
		return
	}
	c.why = why
	close(c.stopCh)
}

// stopped is closed once a stop was asked for.
func (c *control) stopped() <-chan struct{} { return c.stopCh }

// stopReason is "" until a stop was asked for.
func (c *control) stopReason() string {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.why
}

// attach hands the executor's input to the control; instructions that waited are written now.
func (c *control) attach(p *inputPipe) {
	c.mu.Lock()
	c.pipe = p
	var waiting []string
	for _, id := range c.order {
		if c.states[id] == "pending" {
			waiting = append(waiting, id)
		}
	}
	c.mu.Unlock()
	c.deliver(p, waiting)
}

// deliver writes each instruction once; one the executor can no longer take is undelivered.
func (c *control) deliver(p *inputPipe, ids []string) {
	for _, id := range ids {
		c.mu.Lock()
		text := c.texts[id]
		c.mu.Unlock()
		state := "undelivered"
		if p.write(id, inputPrefix+text) {
			state = "sent"
		}
		c.mu.Lock()
		delete(c.texts, id)
		if c.states[id] == "pending" {
			c.setState(id, state)
		}
		c.mu.Unlock()
	}
}

// replayed is called with each message the executor echoed back: that instruction is delivered.
func (c *control) replayed(text string) {
	c.mu.Lock()
	p := c.pipe
	c.mu.Unlock()
	if p == nil {
		return
	}
	if id := p.replayed(text); id != "" {
		c.mu.Lock()
		c.setState(id, "delivered")
		c.mu.Unlock()
	}
}

// setState records a state change; the caller holds mu.
func (c *control) setState(id, state string) {
	if c.states[id] == state {
		return
	}
	c.states[id] = state
	c.unsent[id] = state
}

// finish stops renewing and returns every instruction's final state for the end report: what was
// not delivered by now never will be.
func (c *control) finish() []any {
	c.once.Do(func() { close(c.quit) })
	<-c.done
	c.mu.Lock()
	defer c.mu.Unlock()
	out := make([]any, 0, len(c.order))
	for _, id := range c.order {
		st := c.states[id]
		if st != "delivered" {
			st = "undelivered"
		}
		out = append(out, map[string]any{"id": id, "state": st})
	}
	return out
}

// inputPipe is the executor's stdin in stream-json mode. Every write is one user message. It
// remembers what it wrote until the executor echoes it back.
type inputPipe struct {
	closing atomic.Bool // set first, so no write starts once the run's result arrived
	mu      sync.Mutex
	w       io.WriteCloser
	closed  bool
	waiting []written
}

type written struct{ id, text string } // id "" is the prompt

func newInputPipe(w io.WriteCloser) *inputPipe { return &inputPipe{w: w} }

// write sends one user message; false when the input is closed or the write failed.
func (p *inputPipe) write(id, text string) bool {
	if p.closing.Load() {
		return false
	}
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.closed {
		return false
	}
	if _, err := p.w.Write(claude.UserMessage(text)); err != nil {
		return false
	}
	p.waiting = append(p.waiting, written{id, text})
	return true
}

// close ends the input. Claude Code finishes what it took and exits. Safe to call more than once,
// and from any goroutine: the output reader calls it through a goroutine so it never waits on a write.
func (p *inputPipe) close() {
	p.closing.Store(true)
	p.mu.Lock()
	defer p.mu.Unlock()
	if !p.closed {
		p.closed = true
		_ = p.w.Close() // the executor may be gone already; nothing to do about it
	}
}

// replayed matches an echoed message to what was written: the same text first, else the oldest,
// since the executor takes messages in the order they were written. It returns the instruction's
// id, or "" for the prompt or no match.
func (p *inputPipe) replayed(text string) string {
	p.mu.Lock()
	defer p.mu.Unlock()
	if len(p.waiting) == 0 {
		return ""
	}
	at := 0
	for i, w := range p.waiting {
		if strings.TrimSpace(w.text) == strings.TrimSpace(text) {
			at = i
			break
		}
	}
	id := p.waiting[at].id
	p.waiting = append(p.waiting[:at], p.waiting[at+1:]...)
	return id
}
