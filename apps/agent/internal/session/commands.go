package session

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/hanumoka/zm-baton/apps/agent/internal/report"
)

// ErrNotRegistered means this device holds no registration for the executor session.
var ErrNotRegistered = errors.New("this session is not registered with zm-baton")

// SendCurrentWork posts 「지금 하는 일」 (contract: one line of at most 300 characters, refs to
// related work or documents, sharing on by default and switchable per session).
func SendCurrentWork(ctx context.Context, dir, executor, executorSessionID, summary string, refs []string, share *bool) (Result, error) {
	summary = strings.TrimSpace(summary)
	if summary == "" {
		return Result{}, errors.New("summary is empty")
	}
	if n := len([]rune(summary)); n > 300 {
		return Result{}, fmt.Errorf("summary has %d characters; at most 300", n)
	}
	payload := map[string]any{"summary": summary, "refs": refs}
	if share != nil {
		payload["share"] = *share
	}
	return sendOne(ctx, dir, executor, executorSessionID, Event{Kind: "current_work", Payload: payload}, false)
}

// TurnOff deregisters the session and keeps a marker so later hooks of the same executor
// session send nothing (contract: "그 뒤 훅은 아무것도 보내지 않는다").
func TurnOff(ctx context.Context, dir, executor, executorSessionID string) (Result, error) {
	return sendOne(ctx, dir, executor, executorSessionID, Event{Kind: "deregistered", Payload: map[string]any{}}, true)
}

func sendOne(ctx context.Context, dir, executor, executorSessionID string, e Event, turnOff bool) (Result, error) {
	var st State
	var registered bool
	err := Update(dir, executor, executorSessionID, 2*time.Second, func(s *State, exists bool) (bool, error) {
		if !exists || s.Off || s.SessionID == "" {
			return !exists, nil
		}
		registered = true
		s.NextSeq++
		e.LocalSeq = s.NextSeq
		e.EventID = report.NewEventID(time.Now())
		st = *s
		if turnOff {
			// The marker stays; the token goes, because nothing may use it any more.
			s.Off, s.Token = true, ""
		}
		return false, nil
	})
	if err != nil {
		return Result{}, err
	}
	if !registered {
		return Result{}, ErrNotRegistered
	}
	client, err := NewClient(st.Server, 5*time.Second)
	if err != nil {
		return Result{}, err
	}
	return client.Send(ctx, st.SessionID, st.Token, []Event{e})
}
