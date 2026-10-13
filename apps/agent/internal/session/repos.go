package session

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"time"
)

// LocalRepo is a repository this device's connector may run in (`zm-baton repo add`). Root and
// Worktrees are local absolute paths: they stay in the config folder and are never sent. The
// server knows the repository by Key only.
type LocalRepo struct {
	Key       string    `json:"repo_key"`
	Name      string    `json:"name"`
	Root      string    `json:"root"`
	Worktrees string    `json:"worktrees"`
	Remote    string    `json:"remote,omitempty"`
	AddedAt   time.Time `json:"added_at"`
}

// reposFile is repos.json. Version lets a later format be told apart.
type reposFile struct {
	Version int         `json:"version"`
	Repos   []LocalRepo `json:"repos"`
}

const reposVersion = 1

// LoadRepos reads repos.json; a device without one has no repositories.
func LoadRepos(dir string) ([]LocalRepo, error) {
	data, err := os.ReadFile(filepath.Join(dir, "repos.json"))
	if errors.Is(err, os.ErrNotExist) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	var f reposFile
	if err := json.Unmarshal(data, &f); err != nil {
		return nil, fmt.Errorf("repos.json: %w", err)
	}
	if f.Version != reposVersion {
		return nil, fmt.Errorf("repos.json has format %d; this zm-baton reads %d", f.Version, reposVersion)
	}
	return f.Repos, nil
}

// UpdateRepos changes repos.json under its own lock, so two commands never drop each other's change.
func UpdateRepos(ctx context.Context, dir string, fn func(repos []LocalRepo) ([]LocalRepo, error)) error {
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return err
	}
	release, err := lockFile(ctx, filepath.Join(dir, "repos.lock"), 5*time.Second)
	if err != nil {
		return err
	}
	defer release()
	repos, err := LoadRepos(dir)
	if err != nil {
		return err
	}
	next, err := fn(repos)
	if err != nil {
		return err
	}
	data, err := json.MarshalIndent(reposFile{Version: reposVersion, Repos: next}, "", "  ")
	if err != nil {
		return err
	}
	return writeFileAtomic(filepath.Join(dir, "repos.json"), data)
}

// RepositoryView is the server's record of a device repository.
type RepositoryView struct {
	DeviceID string `json:"device_id"`
	RepoKey  string `json:"repo_key"`
	Name     string `json:"name"`
	Remote   string `json:"remote"`
}

// AddRepository tells the server this device can run in the repository: key, folder name and the
// cleaned remote, never the path.
func (c *Client) AddRepository(ctx context.Context, deviceID string, repo LocalRepo) (RepositoryView, error) {
	var out RepositoryView
	body := map[string]string{"repo_key": repo.Key, "name": repo.Name, "remote": repo.Remote}
	err := c.do(ctx, http.MethodPost, "/api/devices/"+url.PathEscape(deviceID)+"/repositories", "", body, http.StatusOK, &out, "add repository")
	return out, err
}

// RemoveRepository tells the server the device no longer runs in the repository.
func (c *Client) RemoveRepository(ctx context.Context, deviceID, repoKey string) error {
	path := "/api/devices/" + url.PathEscape(deviceID) + "/repositories?repo_key=" + url.QueryEscape(repoKey)
	return c.do(ctx, http.MethodDelete, path, "", nil, http.StatusOK, nil, "remove repository")
}
