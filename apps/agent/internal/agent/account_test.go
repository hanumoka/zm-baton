package agent

import (
	"context"
	"encoding/json"
	"log/slog"
	"os"
	"path/filepath"
	"runtime"
	"slices"
	"strings"
	"testing"
	"time"

	"github.com/hanumoka/zm-baton/apps/agent/internal/api"
)

// The values below stand in for credentials in tests; none is real or shaped like a real key.
const fakeKeyValue = "zmb-test-credential-value-not-a-key"

var (
	subscription = api.Account{ID: "acc_owner_login", Name: "이 PC의 Claude Code 로그인", AuthMethod: AuthSubscription, AuthSource: "device_login"}
	keyAccount   = api.Account{ID: "acc_key_1", Name: "내 API 키", AuthMethod: AuthAPIKey, AuthSource: "env:ANTHROPIC_API_KEY"}
)

func TestASubscriptionSignInTakesEveryOtherWayOut(t *testing.T) {
	environ := []string{"PATH=/bin", "ANTHROPIC_API_KEY=" + fakeKeyValue, "CLAUDE_CODE_OAUTH_TOKEN=x", "CLAUDE_CONFIG_DIR=/person"}
	s, present, err := signInFor(subscription, environ, "/zmb/executors")
	if err != nil {
		t.Fatal(err)
	}
	if s.Method != AuthSubscription || s.ConfigDir != "" || len(s.Set) != 0 {
		t.Fatalf("sign-in %+v", s)
	}
	if !slices.Equal(present, []string{"ANTHROPIC_API_KEY", "CLAUDE_CODE_OAUTH_TOKEN"}) {
		t.Fatalf("present %v", present)
	}
	env := executorEnviron(environ, s.Remove, s.Set)
	if !slices.Equal(env, []string{"PATH=/bin", "CLAUDE_CONFIG_DIR=/person"}) {
		t.Fatalf("a subscription run keeps the person's configuration folder and drops every credential: %v", env)
	}
	if d := s.Detail(subscription, present); !strings.Contains(d, "ANTHROPIC_API_KEY") || strings.Contains(d, fakeKeyValue) {
		t.Fatalf("detail %q", d)
	}
}

func TestAnAPIKeySignInNeedsTheVariableAndGetsAFolderOfItsOwn(t *testing.T) {
	if _, _, err := signInFor(keyAccount, []string{"PATH=/bin"}, "/zmb/executors"); err == nil || !strings.Contains(err.Error(), "ANTHROPIC_API_KEY") {
		t.Fatalf("a key account without the variable: %v", err)
	}
	if _, _, err := signInFor(keyAccount, []string{"ANTHROPIC_API_KEY= "}, ""); err == nil {
		t.Fatal("an empty variable is no key")
	}
	environ := []string{"ANTHROPIC_API_KEY=" + fakeKeyValue, "ANTHROPIC_AUTH_TOKEN=x", "CLAUDE_CONFIG_DIR=/person"}
	dir := filepath.Join(t.TempDir(), "executors")
	s, present, err := signInFor(keyAccount, environ, dir)
	if err != nil {
		t.Fatal(err)
	}
	want := filepath.Join(dir, "acc_key_1", "claude")
	if s.Method != AuthAPIKey || s.ConfigDir != want || !slices.Contains(present, "ANTHROPIC_AUTH_TOKEN") || slices.Contains(present, "ANTHROPIC_API_KEY") {
		t.Fatalf("sign-in %+v present %v", s, present)
	}
	env := executorEnviron(environ, s.Remove, s.Set)
	if !slices.Equal(env, []string{"ANTHROPIC_API_KEY=" + fakeKeyValue, "CLAUDE_CONFIG_DIR=" + want}) {
		t.Fatalf("env %v", env)
	}
	if _, _, err := signInFor(api.Account{ID: "../x", AuthMethod: AuthAPIKey, AuthSource: "env:ANTHROPIC_API_KEY"}, environ, dir); err == nil {
		t.Fatal("an account id that is not one must not become a folder")
	}
	if _, _, err := signInFor(api.Account{ID: "acc_x", AuthMethod: "cloud_account", AuthSource: "env:AWS"}, environ, dir); err == nil {
		t.Fatal("an unknown method is refused before the executor")
	}
}

func TestEnvironmentNamesCompareAsTheOperatingSystemDoes(t *testing.T) {
	env := executorEnviron([]string{"Anthropic_Api_Key=v", "PATH=/bin", "=C:=C:\\"}, []string{"ANTHROPIC_API_KEY"}, nil)
	if runtime.GOOS == "windows" {
		if slices.Contains(env, "Anthropic_Api_Key=v") {
			t.Fatalf("Windows names have no case: %v", env)
		}
	} else if !slices.Contains(env, "Anthropic_Api_Key=v") {
		t.Fatalf("elsewhere case matters: %v", env)
	}
	if !slices.Contains(env, "=C:=C:\\") {
		t.Fatalf("Windows' drive entries stay: %v", env)
	}
	if observedMethod("none") != AuthSubscription || observedMethod("ANTHROPIC_API_KEY") != AuthAPIKey {
		t.Fatal("observed method")
	}
}

func TestTheAccountsTimeLimitAppliesUnlessTheFlagIsShorter(t *testing.T) {
	for _, c := range []struct {
		flag   time.Duration
		limits *api.Limits
		want   time.Duration
		limit  bool
	}{
		{0, nil, defaultRunTimeout, false},
		{10 * time.Minute, nil, 10 * time.Minute, false},
		{0, &api.Limits{MaxMinutes: 60}, time.Hour, true},
		{10 * time.Minute, &api.Limits{MaxMinutes: 60}, 10 * time.Minute, false},
		{2 * time.Hour, &api.Limits{MaxMinutes: 60}, time.Hour, true},
		{0, &api.Limits{MaxTurns: 5}, defaultRunTimeout, false},
	} {
		if got, limit := runTimeout(c.flag, c.limits); got != c.want || limit != c.limit {
			t.Fatalf("runTimeout(%v, %+v) = %v %v, want %v %v", c.flag, c.limits, got, limit, c.want, c.limit)
		}
	}
}

// accountHandoff is a handoff whose API-key runs get their folders under executors.
func accountHandoff(t *testing.T, c *api.Client, mode, executors string, repo Repo) *Handoff {
	t.Helper()
	t.Setenv(fakeEnv, mode)
	h, err := NewHandoff(Config{
		Client: c, DeviceID: "dev_test", ClaudePath: os.Args[0], PollInterval: 20 * time.Millisecond,
		FlushInterval: 20 * time.Millisecond, ChangesInterval: 20 * time.Millisecond, RenewInterval: 20 * time.Millisecond,
		StateDir: filepath.Join(t.TempDir(), "runs"), ExecutorDir: executors, Log: slog.New(slog.DiscardHandler),
	}, func() (map[string]Repo, error) { return map[string]Repo{repo.Key: repo}, nil }, nil)
	if err != nil {
		t.Fatal(err)
	}
	return h
}

func accountAnswer(evs []api.Event) string {
	for _, e := range evs {
		if e.Kind == "answer" {
			s, _ := e.Payload["text"].(string)
			return s
		}
	}
	return ""
}

func readinessCheck(t *testing.T, ended map[string]any, name string) map[string]any {
	t.Helper()
	r, _ := ended["readiness"].(map[string]any)
	checks, _ := r["checks"].([]any)
	for _, c := range checks {
		if m, _ := c.(map[string]any); m["name"] == name {
			return m
		}
	}
	t.Fatalf("no readiness check %q in %v", name, ended["readiness"])
	return nil
}

func TestASubscriptionRunNeverSeesAnotherCredentialAndReportsHowItSignedIn(t *testing.T) {
	repo := handoffRepo(t)
	person := t.TempDir()
	t.Setenv("ANTHROPIC_API_KEY", fakeKeyValue)
	t.Setenv("ANTHROPIC_AUTH_TOKEN", fakeKeyValue)
	t.Setenv("CLAUDE_CODE_OAUTH_TOKEN", fakeKeyValue)
	t.Setenv("CLAUDE_CODE_USE_BEDROCK", "1")
	t.Setenv("CLAUDE_CONFIG_DIR", person)
	t.Setenv("ZMB_FAKE_PERSON_CONFIG", person)
	f, c := newFakeServer(t)
	claim := delegated("run_sub", repo.Key, "main", "Read")
	claim.Account = &subscription
	claim.Limits = &api.Limits{MaxTurns: 7, MaxBudgetUSD: 2.5, MaxMinutes: 60, BudgetFrom: "run"}
	f.answers["run_sub"] = claim
	f.pending = []api.PendingRun{{ID: "run_sub", Executor: "claude_code", DelegationID: "dlg_run_sub", DeviceID: "dev_test", RepoKey: repo.Key}}
	h := accountHandoff(t, c, "account", filepath.Join(t.TempDir(), "executors"), repo)

	got, err := h.Run(context.Background(), true)
	if err != nil || got.EndReason != EndSubmitted {
		t.Fatalf("run %+v %v", got, err)
	}
	evs := f.runEvents("run_sub")
	if a := accountAnswer(evs); a != "key=absent auth_token=absent oauth=absent bedrock=absent config=kept max-turns=7 max-budget=2.50" {
		t.Fatalf("the executor saw %q", a)
	}
	started, ended := evs[0].Payload, evs[len(evs)-1].Payload
	if started["auth_method"] != AuthSubscription {
		t.Fatalf("started %v", started)
	}
	auth, _ := ended["auth"].(map[string]any)
	if auth["method"] != AuthSubscription || auth["api_key_source"] != "none" || auth["config_isolated"] != false {
		t.Fatalf("ended auth %v", ended["auth"])
	}
	if _, hit := ended["limit_hit"]; hit {
		t.Fatalf("a submitted run names no limit: %v", ended)
	}
	check := readinessCheck(t, ended, "account")
	detail, _ := check["detail"].(string)
	if check["ok"] != true || !strings.Contains(detail, "ANTHROPIC_API_KEY") || !strings.Contains(detail, "CLAUDE_CODE_USE_BEDROCK") {
		t.Fatalf("account check %v", check)
	}
	raw, _ := json.Marshal(evs)
	if strings.Contains(string(raw), fakeKeyValue) || strings.Contains(string(raw), person) {
		t.Fatal("a credential value or the person's folder reached a report")
	}
	var usage map[string]any
	for _, e := range evs {
		if e.Kind == "usage" && e.Payload["source"] == "result" {
			usage = e.Payload
		}
	}
	if models, _ := usage["model_usage"].(map[string]any); models["claude-opus-5-5"] == nil || usage["total_cost_usd"] != 0.0170312 {
		t.Fatalf("usage %v", usage)
	}
}

func TestAnAPIKeyRunKeepsOnlyTheKeyAndGetsItsOwnFolder(t *testing.T) {
	repo := handoffRepo(t)
	executors := filepath.Join(t.TempDir(), "executors")
	t.Setenv("ANTHROPIC_API_KEY", fakeKeyValue)
	t.Setenv("ANTHROPIC_AUTH_TOKEN", fakeKeyValue)
	t.Setenv("CLAUDE_CONFIG_DIR", t.TempDir())
	t.Setenv("ZMB_FAKE_CONFIG_UNDER", executors)
	f, c := newFakeServer(t)
	claim := delegated("run_key", repo.Key, "main", "Read")
	claim.Account = &keyAccount
	f.answers["run_key"] = claim
	f.pending = []api.PendingRun{{ID: "run_key", Executor: "claude_code", DelegationID: "dlg_run_key", DeviceID: "dev_test", RepoKey: repo.Key}}
	h := accountHandoff(t, c, "account", executors, repo)

	if got, err := h.Run(context.Background(), true); err != nil || got.EndReason != EndSubmitted {
		t.Fatalf("run %+v %v", got, err)
	}
	evs := f.runEvents("run_key")
	if a := accountAnswer(evs); a != "key=present auth_token=absent oauth=absent bedrock=absent config=own max-turns= max-budget=" {
		t.Fatalf("the executor saw %q", a)
	}
	auth, _ := evs[len(evs)-1].Payload["auth"].(map[string]any)
	if auth["method"] != AuthAPIKey || auth["api_key_source"] != "ANTHROPIC_API_KEY" || auth["config_isolated"] != true {
		t.Fatalf("ended auth %v", auth)
	}
	if fi, err := os.Stat(filepath.Join(executors, "acc_key_1", "claude")); err != nil || !fi.IsDir() {
		t.Fatalf("the account's folder: %v", err)
	}
	raw, _ := json.Marshal(evs)
	if strings.Contains(string(raw), fakeKeyValue) || strings.Contains(string(raw), executors) {
		t.Fatal("the key or a local folder reached a report")
	}
}

func TestAnAPIKeyAccountWithoutTheVariableStopsBeforeTheExecutor(t *testing.T) {
	repo := handoffRepo(t)
	t.Setenv("ANTHROPIC_API_KEY", "") // restored after the test
	os.Unsetenv("ANTHROPIC_API_KEY")
	f, c := newFakeServer(t)
	claim := delegated("run_nokey", repo.Key, "main", "Read")
	claim.Account = &keyAccount
	f.answers["run_nokey"] = claim
	f.pending = []api.PendingRun{{ID: "run_nokey", Executor: "claude_code", DelegationID: "dlg_run_nokey", DeviceID: "dev_test", RepoKey: repo.Key}}
	h := accountHandoff(t, c, "account", "", repo)

	if got, _ := h.Run(context.Background(), true); got.EndReason != EndFailed {
		t.Fatalf("result %+v", got)
	}
	evs := f.runEvents("run_nokey")
	ended := evs[len(evs)-1].Payload
	if ended["executor_started"] != false {
		t.Fatalf("the executor must not start: %v", ended)
	}
	check := readinessCheck(t, ended, "account")
	if check["ok"] != false || !strings.Contains(check["detail"].(string), "ANTHROPIC_API_KEY") {
		t.Fatalf("account check %v", check)
	}
}

func TestATurnLimitEndsTheRunWithTheLimitNamed(t *testing.T) {
	repo := handoffRepo(t)
	f, c := newFakeServer(t)
	claim := delegated("run_turns", repo.Key, "main", "Read")
	claim.Account = &subscription
	claim.Limits = &api.Limits{MaxTurns: 3}
	f.answers["run_turns"] = claim
	f.pending = []api.PendingRun{{ID: "run_turns", Executor: "claude_code", DelegationID: "dlg_run_turns", DeviceID: "dev_test", RepoKey: repo.Key}}
	h := accountHandoff(t, c, "account-maxturns", "", repo)

	if got, _ := h.Run(context.Background(), true); got.EndReason != EndFailed {
		t.Fatalf("result %+v", got)
	}
	evs := f.runEvents("run_turns")
	ended := evs[len(evs)-1].Payload
	if ended["limit_hit"] != "max_turns" || ended["result_seen"] != true {
		t.Fatalf("ended %v", ended)
	}
	if !strings.Contains(accountAnswer(evs), "max-turns=3") {
		t.Fatalf("answer %q", accountAnswer(evs))
	}
}

func TestTheAccountsTimeLimitStopsTheRunAndIsNamed(t *testing.T) {
	f, c := newFakeServer(t)
	r := newTestRunner(t, c, "hang", 0)
	got := r.ExecuteJob(context.Background(), api.Claim{RunID: "run_minutes", Generation: 3}, func(context.Context, *slog.Logger) (Job, error) {
		return Job{Workdir: t.TempDir(), Prompt: "x", AllowedTools: "Read", Timeout: 300 * time.Millisecond, TimeoutIsLimit: true}, nil
	})
	if got.EndReason != EndFailed {
		t.Fatalf("result %+v", got)
	}
	evs := f.runEvents("run_minutes")
	ended := evs[len(evs)-1].Payload
	if ended["limit_hit"] != "max_minutes" || ended["result_seen"] != false {
		t.Fatalf("ended %v", ended)
	}
	var text string
	for _, e := range evs {
		if e.Kind == "error" {
			text, _ = e.Payload["text"].(string)
		}
	}
	if !strings.Contains(text, "per-run time limit") {
		t.Fatalf("error %q", text)
	}
}
