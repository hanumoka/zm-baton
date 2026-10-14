package agent

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"runtime"
	"strings"

	"github.com/hanumoka/zm-baton/apps/agent/internal/api"
)

// How a delegated run signs in (contract v1 "실행할 때 넘기는 것" 인증, DEC-0095 5). The run goes on
// one AI account, and the executor must sign in only the way that account says:
//
//  1. The connector removes from the executor's environment every variable that would make Claude
//     Code sign in another way. Claude Code takes the first of: a cloud provider (CLAUDE_CODE_USE_*),
//     ANTHROPIC_AUTH_TOKEN, ANTHROPIC_API_KEY, an apiKeyHelper, CLAUDE_CODE_OAUTH_TOKEN, a profile
//     (ANTHROPIC_PROFILE), then the /login of the subscription. A subscription run with
//     ANTHROPIC_API_KEY left in the shell would bill the key instead.
//  2. An API-key account needs ANTHROPIC_API_KEY in the connector's own environment on this device.
//     The connector only checks that it is there; it never reads the value into anything it keeps,
//     logs or sends, and it never moves a key from another variable (that is the vault's run
//     injection, core module 5, stage 2).
//  3. An API-key run gets a configuration folder of its own (CLAUDE_CONFIG_DIR), one per account, so
//     the person's CLAUDE.md, skills and plugins of their own setup stay out of it and its sessions
//     stay apart. A subscription run keeps the person's folder: Claude Code 2.1.296 keeps the login
//     there, and an empty folder is not signed in (measured), while copying the login is forbidden
//     (AGENTS.md 6.1). Its settings files are already ignored by --restricted.
//  4. The executor's init line says what it really used (apiKeySource), and the end report carries it.

// credentialVars are the variables that choose how Claude Code signs in, in its own order.
var credentialVars = []string{
	"CLAUDE_CODE_USE_BEDROCK", "CLAUDE_CODE_USE_VERTEX", "CLAUDE_CODE_USE_FOUNDRY",
	"ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_API_KEY", "CLAUDE_CODE_OAUTH_TOKEN", "ANTHROPIC_PROFILE",
}

// Auth methods (data model runs.auth_method).
const (
	AuthSubscription = "subscription_login"
	AuthAPIKey       = "api_key"
)

// apiKeyVar is the one variable an API-key account may name: the one Claude Code reads.
const apiKeyVar = "ANTHROPIC_API_KEY"

// accountID is what an account id must look like before it becomes part of a folder name.
var accountID = regexp.MustCompile(`^acc_[A-Za-z0-9_-]{1,80}$`)

// SignIn is how one run signs in: the variables to drop and set, and what to report.
type SignIn struct {
	Method string // AuthSubscription or AuthAPIKey
	// Remove are the variable names taken out of the executor's environment; Set are added.
	Remove []string
	Set    []string
	// ConfigDir is the run's own CLAUDE_CONFIG_DIR, or "" to keep the person's.
	ConfigDir string
}

// Detail says in words what the sign-in does, for the readiness check. Names only, never a value.
func (s SignIn) Detail(account api.Account, present []string) string {
	method := "구독 로그인(이 PC의 Claude Code 로그인)"
	if s.Method == AuthAPIKey {
		method = "API 키(이 PC의 환경 변수 " + apiKeyVar + ")"
	}
	d := fmt.Sprintf("계정 %s: %s", account.Name, method)
	if len(present) > 0 {
		d += ". 다른 인증 방식의 환경 변수 " + strings.Join(present, ", ") + "를 실행기 환경에서 뺐다"
	}
	if s.ConfigDir != "" {
		d += ". 이 계정 전용 설정 폴더로 띄운다"
	}
	return d
}

// signInFor plans the sign-in of a run on account. environ is the connector's environment;
// executorDir, when set, holds one configuration folder per API-key account. It returns the plan and
// the names of credential variables present in environ that the plan removes.
func signInFor(account api.Account, environ []string, executorDir string) (SignIn, []string, error) {
	var keep string
	var s SignIn
	switch {
	case account.AuthMethod == AuthSubscription && account.AuthSource == "device_login":
		s.Method = AuthSubscription
	case account.AuthMethod == AuthAPIKey && account.AuthSource == "env:"+apiKeyVar:
		s.Method, keep = AuthAPIKey, apiKeyVar
		if v, ok := lookupEnv(environ, apiKeyVar); !ok || strings.TrimSpace(v) == "" {
			return s, nil, errors.New("이 계정의 자격이 없다: 이 PC에서 zm-baton agent를 띄운 환경에 " + apiKeyVar + "가 없다")
		}
		if executorDir != "" {
			if !accountID.MatchString(account.ID) {
				return s, nil, fmt.Errorf("계정 번호 %q로 설정 폴더를 만들 수 없다", account.ID)
			}
			s.ConfigDir = filepath.Join(executorDir, account.ID, "claude")
			s.Set = append(s.Set, "CLAUDE_CONFIG_DIR="+s.ConfigDir)
		}
	default:
		return s, nil, fmt.Errorf("이 연결 프로그램은 인증 방식 %q(자격 위치 %q)를 모른다. zm-baton을 새 판으로 바꾼다", account.AuthMethod, account.AuthSource)
	}
	var present []string
	for _, name := range credentialVars {
		if name == keep {
			continue
		}
		s.Remove = append(s.Remove, name)
		if _, ok := lookupEnv(environ, name); ok {
			present = append(present, name)
		}
	}
	if s.ConfigDir != "" {
		s.Remove = append(s.Remove, "CLAUDE_CONFIG_DIR")
	}
	return s, present, nil
}

// executorEnviron is environ without the names in remove, plus set. Windows compares variable
// names without case, as it stores them.
func executorEnviron(environ, remove, set []string) []string {
	out := make([]string, 0, len(environ)+len(set))
	for _, kv := range environ {
		name, _, _ := strings.Cut(kv, "=")
		if name == "" || !containsName(remove, name) {
			out = append(out, kv)
		}
	}
	return append(out, set...)
}

// observedMethod is the method a non-empty apiKeySource of the init line means: "none" is the
// subscription login (Claude Code 2.1.296, measured), a variable name an API key.
func observedMethod(source string) string {
	if source == "none" {
		return AuthSubscription
	}
	return AuthAPIKey
}

func lookupEnv(environ []string, name string) (string, bool) {
	for _, kv := range environ {
		k, v, ok := strings.Cut(kv, "=")
		if ok && sameName(k, name) {
			return v, true
		}
	}
	return "", false
}

func containsName(list []string, name string) bool {
	for _, n := range list {
		if sameName(n, name) {
			return true
		}
	}
	return false
}

func sameName(a, b string) bool {
	if runtime.GOOS == "windows" {
		return strings.EqualFold(a, b)
	}
	return a == b
}

// ensureConfigDir makes an API-key account's configuration folder. zm-baton never opens what Claude
// Code keeps inside it.
func ensureConfigDir(dir string) error {
	if dir == "" {
		return nil
	}
	return os.MkdirAll(dir, 0o700)
}
