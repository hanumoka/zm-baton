package session

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
)

// Export streams the server's export bundle (GET /api/export, FN-1404) into w and returns how
// many bytes it wrote. The bundle can be larger than the 1 MiB other answers are held to, so it is
// copied as it arrives instead of being read into memory.
func (c *Client) Export(ctx context.Context, w io.Writer) (int64, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, c.base+"/api/export", nil)
	if err != nil {
		return 0, err
	}
	req.Header.Set("Accept", "application/json")
	resp, err := c.http.Do(req)
	if err != nil {
		return 0, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		data, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20)) // the refusal reason, best effort
		var refusal struct {
			Message string `json:"message"`
		}
		_ = json.Unmarshal(data, &refusal) // a body that is not a refusal leaves Message empty
		return 0, &StatusError{Op: "export", Status: resp.StatusCode, Message: oneLine(refusal.Message, 300)}
	}
	n, err := io.Copy(w, resp.Body)
	if err != nil {
		return n, fmt.Errorf("export: read answer: %w", err)
	}
	return n, nil
}

// ExportSummary is the part of an export bundle a person is shown after writing it.
type ExportSummary struct {
	Format        string `json:"format"`
	FormatVersion int    `json:"format_version"`
	Provenance    struct {
		ExportedAt string `json:"exported_at"`
		Server     struct {
			Version string `json:"version"`
			Commit  string `json:"commit"`
		} `json:"server"`
		Database struct {
			MigrationVersion string `json:"migration_version"`
		} `json:"database"`
	} `json:"provenance"`
	Collections map[string]struct {
		Count int `json:"count"`
	} `json:"collections"`
}

// ReadExportSummary reads the summary fields of an export bundle and skips the records.
func ReadExportSummary(r io.Reader) (ExportSummary, error) {
	var s ExportSummary
	if err := json.NewDecoder(r).Decode(&s); err != nil {
		return s, fmt.Errorf("the export is not a JSON bundle: %w", err)
	}
	if s.Format != "zm-baton.export" {
		return s, fmt.Errorf("the export has format %q, not zm-baton.export", s.Format)
	}
	return s, nil
}
