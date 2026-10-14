package session

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"strings"
	"time"

	"github.com/hanumoka/zm-baton/apps/agent/internal/report"
)

// ErrNotRegistered means this device holds no registration for the executor session.
var ErrNotRegistered = errors.New("this session is not registered with zm-baton")

// ErrTurnedOff means the person turned this session's reporting off.
var ErrTurnedOff = errors.New("this session's reporting is turned off (zm-baton session off)")

// SendCurrentWork posts 「지금 하는 일」 (contract: one line of at most 300 characters, refs to
// related work or documents, sharing on by default and switchable per session). share changes
// the session's choice; nil keeps it. Every summary states the choice it was sent with.
func SendCurrentWork(ctx context.Context, dir, executor, executorSessionID, summary string, refs []string, share *bool) (Result, error) {
	return sendCurrentWork(ctx, dir, executor, executorSessionID, summary, refs, share, true)
}

// sendCurrentWork sends the summary. When the server no longer takes the registration as live
// (HTTP 401: its token lapsed after 12 hours without events), the session registers again, as a
// hook would, and the summary goes once more (registerAgain false).
func sendCurrentWork(ctx context.Context, dir, executor, executorSessionID, summary string, refs []string, share *bool, registerAgain bool) (Result, error) {
	summary = strings.TrimSpace(summary)
	if summary == "" {
		return Result{}, errors.New("summary is empty")
	}
	if n := len([]rune(summary)); n > 300 {
		return Result{}, fmt.Errorf("summary has %d characters; at most 300", n)
	}
	var e Event
	var st State
	err := Update(ctx, dir, executor, executorSessionID, 2*time.Second, func(s *State, exists bool) (bool, error) {
		switch {
		case !exists || s.SessionID == "":
			return !exists, ErrNotRegistered
		case s.Off:
			return false, ErrTurnedOff
		}
		if share != nil {
			s.Private = !*share
		}
		s.NextSeq++
		e = Event{
			EventID: report.NewEventID(time.Now()), LocalSeq: s.NextSeq, Kind: "current_work",
			Payload: map[string]any{"summary": summary, "refs": refs, "share": !s.Private},
		}
		st = *s
		return false, nil
	})
	if err != nil {
		return Result{}, err
	}
	client, err := NewClient(st.Server, 5*time.Second)
	if err != nil {
		return Result{}, err
	}
	res, err := client.Send(ctx, st.SessionID, st.Token, []Event{e})
	var status *StatusError
	if registerAgain && errors.As(err, &status) && status.Status == http.StatusUnauthorized {
		h := &Hooks{Dir: dir, Executor: executor}
		if _, registered, rerr := h.register(ctx, HookInput{SessionID: executorSessionID, Cwd: st.RepoRoot}); rerr != nil {
			return res, fmt.Errorf("%w; registering again failed: %v", err, rerr)
		} else if !registered {
			return res, ErrTurnedOff
		}
		return sendCurrentWork(ctx, dir, executor, executorSessionID, summary, refs, share, false)
	}
	return res, err
}

// OffOutcome says how `zm-baton session off` ended.
type OffOutcome int

const (
	// OffDelivered: the server took the deregistration.
	OffDelivered OffOutcome = iota
	// OffAlreadyOver: the server no longer accepts this registration's token (it ended, expired
	// or was turned off before), so there is nothing left to turn off there.
	OffAlreadyOver
	// OffBefore: the session was turned off earlier and the server already knows.
	OffBefore
)

// TurnOff stops this session's reporting at once and then tells the server (contract: "그 뒤
// 훅은 아무것도 보내지 않는다"). The two are separate: when the server cannot be told, the
// session stays quiet and keeps what a retry needs, and running TurnOff again sends the same
// deregistration once more.
func TurnOff(ctx context.Context, dir, executor, executorSessionID string) (OffOutcome, error) {
	var before bool
	err := Update(ctx, dir, executor, executorSessionID, 2*time.Second, func(s *State, exists bool) (bool, error) {
		switch {
		case !exists || s.SessionID == "":
			return !exists, ErrNotRegistered
		case s.Off && !s.OffPending:
			before = true
		case !s.Off:
			s.NextSeq++
			s.Off, s.OffPending, s.OffEventID, s.OffSeq = true, true, report.NewEventID(time.Now()), s.NextSeq
			s.Waiting = false
		}
		return false, nil
	})
	if err != nil {
		return 0, err
	}
	if before {
		return OffBefore, nil
	}
	return deliverOff(ctx, dir, executor, executorSessionID)
}

// deliverOff sends the pending deregistration and drops the token once the server has taken
// it or no longer accepts the token.
func deliverOff(ctx context.Context, dir, executor, executorSessionID string) (OffOutcome, error) {
	var st State
	pending := false
	err := Update(ctx, dir, executor, executorSessionID, 2*time.Second, func(s *State, exists bool) (bool, error) {
		pending = exists && s.Off && s.OffPending
		st = *s
		return !exists, nil
	})
	if err != nil || !pending {
		return OffBefore, err
	}
	client, err := NewClient(st.Server, 5*time.Second)
	if err != nil {
		return 0, err
	}
	outcome := OffDelivered
	res, err := client.Send(ctx, st.SessionID, st.Token, []Event{{EventID: st.OffEventID, LocalSeq: st.OffSeq, Kind: "deregistered", Payload: map[string]any{}}})
	var status *StatusError
	switch {
	case errors.As(err, &status) && status.Status == http.StatusUnauthorized:
		outcome = OffAlreadyOver
	case err != nil:
		return 0, err
	case res.Rejected > 0:
		return 0, fmt.Errorf("server rejected the deregistration: %s", strings.Join(res.Reasons, "; "))
	}
	// A registration that finished meanwhile may have replaced the pending event; keep that one.
	err = Update(ctx, dir, executor, executorSessionID, 2*time.Second, func(s *State, exists bool) (bool, error) {
		if exists && s.OffPending && s.OffEventID == st.OffEventID {
			s.OffPending, s.Token = false, ""
		}
		return !exists, nil
	})
	return outcome, err
}
