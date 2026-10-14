package agent

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"regexp"
	"time"
)

// A run record is what this device remembers about an executor it started: its session, its PID,
// and whether the connector saw it exit with no child process left. A later run of the same work
// item reuses the earlier run's worktree and session only when the record says so (contract v1
// "실행마다 별도 작업 트리" 6). A connector that died mid-run never wrote "exited", so the next run
// takes a new worktree. Records stay in the connector's state folder and are never sent.

// RunRecord is one executor this device started.
type RunRecord struct {
	RunID     string    `json:"run_id"`
	SessionID string    `json:"session_id"`
	PID       int       `json:"pid"`
	StartedAt time.Time `json:"started_at"`
	// Exited is set once the connector saw the executor exit; ChildrenGone once it also saw that
	// no process the executor started was left (Windows job object). Unknown counts as not gone.
	Exited       bool       `json:"exited"`
	ChildrenGone bool       `json:"children_gone"`
	EndedAt      *time.Time `json:"ended_at,omitempty"`
}

var recordName = regexp.MustCompile(`^run_[0-9A-Za-z-]{1,64}$`)

// runRecords keeps one file per run in dir. With dir empty nothing is kept, and no earlier run is
// ever confirmed ended.
type runRecords struct{ dir string }

func (r runRecords) path(runID string) (string, error) {
	if r.dir == "" {
		return "", errors.New("run records are off")
	}
	if !recordName.MatchString(runID) {
		return "", errors.New("run id cannot name a record")
	}
	return filepath.Join(r.dir, runID+".json"), nil
}

// save writes the record through a temporary file, so a reader never sees half of it.
func (r runRecords) save(rec RunRecord) error {
	p, err := r.path(rec.RunID)
	if err != nil {
		return err
	}
	if err := os.MkdirAll(r.dir, 0o700); err != nil {
		return err
	}
	data, err := json.MarshalIndent(rec, "", "  ")
	if err != nil {
		return err
	}
	tmp, err := os.CreateTemp(r.dir, rec.RunID+".*.tmp")
	if err != nil {
		return err
	}
	_, werr := tmp.Write(data)
	cerr := tmp.Close()
	if werr != nil || cerr != nil {
		os.Remove(tmp.Name())
		return errors.Join(werr, cerr)
	}
	if err := os.Rename(tmp.Name(), p); err != nil {
		os.Remove(tmp.Name())
		return err
	}
	return nil
}

func (r runRecords) load(runID string) (RunRecord, bool) {
	p, err := r.path(runID)
	if err != nil {
		return RunRecord{}, false
	}
	data, err := os.ReadFile(p)
	if err != nil {
		return RunRecord{}, false
	}
	var rec RunRecord
	if json.Unmarshal(data, &rec) != nil || rec.RunID != runID {
		return RunRecord{}, false
	}
	return rec, true
}

// endedConfirmed reports whether this device saw the executor of runID, in session sessionID, exit
// with no child process left. When not, why says so in words for the person.
func (r runRecords) endedConfirmed(runID, sessionID string) (ok bool, why string) {
	rec, found := r.load(runID)
	switch {
	case !found:
		return false, "이 기기에 이전 실행의 기록이 없다"
	case sessionID == "" || rec.SessionID != sessionID:
		return false, "이전 실행의 세션 번호가 이 기기의 기록과 다르다"
	case !rec.Exited:
		return false, "이전 실행기가 끝난 것을 이 기기가 보지 못했다(연결 프로그램이 도중에 꺼졌을 수 있다)"
	case !rec.ChildrenGone:
		return false, "이전 실행기의 하위 프로세스가 모두 끝났는지 확인하지 못했다"
	}
	return true, "이전 실행기와 그 하위 프로세스가 끝난 것을 이 기기가 확인했다"
}
