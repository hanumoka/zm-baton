package session

import (
	"context"
	"net/http"

	"github.com/hanumoka/zm-baton/apps/agent/internal/zmdocs"
)

// ImportCount is how many items of one kind an import created, updated, left or skipped.
type ImportCount struct {
	Created   int `json:"created"`
	Updated   int `json:"updated"`
	Unchanged int `json:"unchanged"`
	Skipped   int `json:"skipped"`
}

// ImportChange is one item an import created, updated or skipped.
type ImportChange struct {
	Kind   string   `json:"kind"`
	Ref    string   `json:"ref"`
	Action string   `json:"action"`
	Fields []string `json:"fields"`
	Note   string   `json:"note"`
}

// ImportResult is the server's report of a zm-docs import (POST /api/imports/zm-docs).
type ImportResult struct {
	Applied   bool           `json:"applied"`
	Projects  ImportCount    `json:"projects"`
	WorkItems ImportCount    `json:"work_items"`
	Decisions ImportCount    `json:"decisions"`
	Changes   []ImportChange `json:"changes"`
	Warnings  []string       `json:"warnings"`
}

// ImportZmDocs sends one snapshot of a zm-docs checkout. Without apply the server only reports
// what would change.
func (c *Client) ImportZmDocs(ctx context.Context, repoKey string, snap *zmdocs.Snapshot, apply bool) (ImportResult, error) {
	body := map[string]any{"repo_key": repoKey, "commit": snap.Commit, "files": snap.Files, "apply": apply}
	var out ImportResult
	err := c.do(ctx, http.MethodPost, "/api/imports/zm-docs", "", body, http.StatusOK, &out, "import zm-docs")
	return out, err
}
