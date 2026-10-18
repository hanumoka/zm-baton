package agent

import (
	"context"
	"path/filepath"
	"strings"
	"testing"

	"github.com/hanumoka/zm-baton/apps/agent/internal/api"
)

func TestServerWorkerRefusesFallbackSubscriptionAndUnboundedClaims(t *testing.T) {
	t.Setenv("ANTHROPIC_API_KEY", fakeKeyValue)
	repo := handoffRepo(t)
	for _, scenario := range []string{"no account", "subscription", "no limits", "no cost", "no turns", "no time", "no configuration folder"} {
		t.Run(scenario, func(t *testing.T) {
			f, client := newFakeServer(t)
			h := accountHandoff(t, client, "account", filepath.Join(t.TempDir(), "executors"), repo)
			h.runner.cfg.ServerWorker = true
			claim := delegated("run_server", repo.Key, "", "Read")
			claim.Account = &keyAccount
			claim.Limits = &api.Limits{MaxTurns: 5, MaxMinutes: 2, MaxBudgetUSD: 1}
			switch scenario {
			case "no account":
				claim.Account = nil
			case "subscription":
				claim.Account = &subscription
			case "no limits":
				claim.Limits = nil
			case "no cost":
				claim.Limits.MaxBudgetUSD = 0
			case "no turns":
				claim.Limits.MaxTurns = 0
			case "no time":
				claim.Limits.MaxMinutes = 0
			case "no configuration folder":
				h.runner.cfg.ExecutorDir = ""
			}
			result := h.Execute(context.Background(), claim, repo)
			if result.EndReason != EndFailed {
				t.Fatalf("unsafe shared execution was not refused: %+v", result)
			}
			ended := lastPayload(t, f.runEvents(claim.RunID))
			if ended["executor_started"] != false {
				t.Fatal("executor started before the server-worker check")
			}
			check := readinessCheck(t, ended, "account")
			detail, _ := check["detail"].(string)
			if check["ok"] != false || !strings.HasPrefix(detail, "공용 서버 실행") {
				t.Fatalf("failed for another reason instead of the server-worker policy: %v", check)
			}
		})
	}
}

func TestServerWorkerExecutesAnExplicitBoundedAPIAccount(t *testing.T) {
	t.Setenv("ANTHROPIC_API_KEY", fakeKeyValue)
	repo := handoffRepo(t)
	f, client := newFakeServer(t)
	executors := filepath.Join(t.TempDir(), "executors")
	t.Setenv("ZMB_FAKE_CONFIG_UNDER", executors)
	h := accountHandoff(t, client, "account", executors, repo)
	h.runner.cfg.ServerWorker = true
	claim := delegated("run_serverkey", repo.Key, "", "Read")
	claim.Account = &keyAccount
	claim.Limits = &api.Limits{MaxTurns: 5, MaxMinutes: 2, MaxBudgetUSD: 1}
	if result := h.Execute(context.Background(), claim, repo); result.EndReason != EndSubmitted {
		t.Fatalf("bounded API execution failed: %+v, events: %+v", result, f.runEvents(claim.RunID))
	}
	if lastPayload(t, f.runEvents(claim.RunID))["executor_started"] != true {
		t.Fatal("executor was not started")
	}
}
