package session

import (
	"bytes"
	"encoding/json"
	"regexp"
	"strings"
)

// The secret check a relay runs on a tool's input before anything leaves the device (contract
// relay 3, G34). It looks for what is plainly a credential: well-known token shapes, private key
// blocks, credentials inside URLs and assignments of a long value to a secret-sounding name. It
// leans towards false alarms, because a false alarm only means the person answers in the terminal
// as before, while a miss stores a secret on the server. A detail that passes is still deleted
// seven days after its item closes, for what this misses.

// secretShapes are values that are credentials whatever they are assigned to. Each needs enough
// characters after its prefix that ordinary words and identifiers do not match.
var secretShapes = []*regexp.Regexp{
	regexp.MustCompile(`-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----`),
	regexp.MustCompile(`\b(?:AKIA|ASIA)[0-9A-Z]{16}\b`),                                   // AWS access key id
	regexp.MustCompile(`\bgh[pousr]_[A-Za-z0-9]{30,}`),                                    // GitHub tokens
	regexp.MustCompile(`\bgithub_pat_[A-Za-z0-9_]{30,}`),                                  // GitHub fine-grained token
	regexp.MustCompile(`\bglpat-[A-Za-z0-9_-]{20,}`),                                      // GitLab
	regexp.MustCompile(`\bxox[abposr]-[A-Za-z0-9-]{10,}`),                                 // Slack
	regexp.MustCompile(`\bsk-(?:ant-|proj-)?[A-Za-z0-9_-]{20,}`),                          // Anthropic, OpenAI
	regexp.MustCompile(`\bAIza[0-9A-Za-z_-]{35}`),                                         // Google API key
	regexp.MustCompile(`\b[rs]k_(?:live|test)_[0-9A-Za-z]{16,}`),                          // Stripe
	regexp.MustCompile(`\beyJ[A-Za-z0-9_-]{8,}\.eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}`), // JWT
	regexp.MustCompile(`\bzmbs_[A-Za-z0-9_-]{20,}`),                                       // zm-baton's own session token
	regexp.MustCompile(`\bnpm_[A-Za-z0-9]{30,}`),                                          // npm token
	// user:password@ inside a URL. The user part alone (git@host) is not a secret.
	regexp.MustCompile(`[A-Za-z][A-Za-z0-9+.-]*://[^/\s:@'"]+:[^/\s@'"]+@`),
	// An Authorization header or option with a value.
	regexp.MustCompile(`(?i)\bauthorization\s*[:=]\s*["']?(?:bearer|basic|token)\s+[A-Za-z0-9._~+/=-]{8,}`),
}

// secretName is a name whose value is a secret: PASSWORD, db_password, apiKey, client-secret. Short
// or common words (pwd, auth) are left out: they name other things too often.
const secretName = `(?:password|passwd|secret|token|api[_-]?key|apikey|access[_-]?key|private[_-]?key|credentials?)`

var (
	// name = "value" or name: 'value' with a quoted value of 8 or more characters.
	quotedAssign = regexp.MustCompile(`(?i)[A-Za-z0-9_.-]*` + secretName + `[A-Za-z0-9_-]*["']?\s*[:=]\s*["']([^"'\s]{8,})["']`)
	// NAME=value as in a shell or .env line, unquoted, 8 or more characters.
	envAssign = regexp.MustCompile(`(?i)\b[A-Za-z0-9_]*` + secretName + `[A-Za-z0-9_]*=([^\s"'` + "`" + `;&|]{8,})`)
	// --password value, --token=value
	flagValue = regexp.MustCompile(`(?i)--[a-z0-9-]*` + secretName + `[a-z0-9-]*[= ]["']?([^\s"']{6,})`)
)

// placeholder is a value that names a secret instead of holding one: a variable, a template or a
// mask. `$TOKEN`, `${{ secrets.X }}`, `<token>`, `****`, `process.env.X`.
func placeholder(v string) bool {
	if strings.HasPrefix(v, "$") || strings.HasPrefix(v, "%") || strings.HasPrefix(v, "<") || strings.HasPrefix(v, "{") {
		return true
	}
	lower := strings.ToLower(v)
	for _, mark := range []string{"****", "xxxx", "process.env", "os.getenv", "getenv(", "example", "placeholder", "changeme", "redacted"} {
		if strings.Contains(lower, mark) {
			return true
		}
	}
	return false
}

// HasSecret reports whether text looks like it holds a credential.
func HasSecret(text string) bool {
	for _, re := range secretShapes {
		if re.MatchString(text) {
			return true
		}
	}
	for _, re := range []*regexp.Regexp{quotedAssign, envAssign, flagValue} {
		for _, m := range re.FindAllStringSubmatch(text, -1) {
			if !placeholder(m[1]) {
				return true
			}
		}
	}
	return false
}

// InputHasSecret checks every key and string value of a tool input. A value that cannot be read
// counts as a secret (G34.2: what the check cannot classify does not leave the device).
func InputHasSecret(input json.RawMessage) bool {
	if len(bytes.TrimSpace(input)) == 0 {
		return false
	}
	var v any
	dec := json.NewDecoder(bytes.NewReader(input))
	dec.UseNumber()
	if err := dec.Decode(&v); err != nil {
		return true
	}
	return valueHasSecret(v)
}

func valueHasSecret(v any) bool {
	switch t := v.(type) {
	case string:
		return HasSecret(t)
	case map[string]any:
		for k, x := range t {
			if HasSecret(k) || valueHasSecret(x) {
				return true
			}
			// A key that names a secret holding a long string: {"password": "hunter22hunter"}.
			if s, ok := x.(string); ok && len(s) >= 8 && keyIsSecret(k) && !placeholder(s) {
				return true
			}
		}
	case []any:
		for _, x := range t {
			if valueHasSecret(x) {
				return true
			}
		}
	}
	return false
}

var secretKey = regexp.MustCompile(`(?i)^[A-Za-z0-9_.-]*` + secretName + `[A-Za-z0-9_-]*$`)

func keyIsSecret(k string) bool { return secretKey.MatchString(k) }
