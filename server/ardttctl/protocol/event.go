package protocol

import (
	"encoding/json"
	"fmt"
	"io"
	"sort"
	"strings"
)

// Version is the machine-readable control protocol. Legacy ARDTT_* lines
// remain required for APKs that only sniff those prefixes.
const Version = 2

const (
	TypeProgress = "progress"
	TypeInfo     = "info"
	TypeWarn     = "warn"
	TypeError    = "error"
	TypeDone     = "done"
	TypeHealth   = "health"
	TypeStatus   = "status"
)

// Event is one JSON Lines record. Unknown fields on the reader side must be ignored.
type Event struct {
	Protocol     int               `json:"protocol"`
	Type         string            `json:"type"`
	Phase        string            `json:"phase,omitempty"`
	Progress     *float64          `json:"progress,omitempty"`
	Code         string            `json:"code,omitempty"`
	Message      string            `json:"message,omitempty"`
	Version      string            `json:"version,omitempty"`
	DeploymentID string            `json:"deploymentId,omitempty"`
	Component    string            `json:"component,omitempty"`
	Status       string            `json:"status,omitempty"`
	Done         map[string]string `json:"done,omitempty"`
}

func (e Event) JSONLine() string {
	if e.Protocol == 0 {
		e.Protocol = Version
	}
	b, err := json.Marshal(e)
	if err != nil {
		return `{"protocol":2,"type":"error","code":"EMIT_FAILED","message":"json marshal"}`
	}
	return string(b)
}

// Legacy is the ARDTT_* line old Android parses. Empty if the event has no legacy form.
func (e Event) Legacy() string {
	msg := strings.TrimSpace(e.Message)
	switch e.Type {
	case TypeProgress:
		frac := "0"
		if e.Progress != nil {
			frac = trimFloat(*e.Progress)
		}
		if msg == "" {
			msg = e.Phase
		}
		return "ARDTT_PROGRESS|" + frac + "|" + msg
	case TypeInfo:
		return "ARDTT_INFO|" + msg
	case TypeWarn:
		return "ARDTT_WARN|" + msg
	case TypeError:
		if e.Code != "" {
			return "ARDTT_ERROR|code=" + e.Code + "|" + msg
		}
		return "ARDTT_ERROR|" + msg
	case TypeDone:
		if len(e.Done) > 0 {
			return "ARDTT_DONE|" + joinDone(e.Done)
		}
		if e.Version != "" {
			return "ARDTT_DONE|deploy_version=" + e.Version
		}
		return "ARDTT_DONE|"
	default:
		return ""
	}
}

func Emit(w io.Writer, e Event) error {
	if e.Protocol == 0 {
		e.Protocol = Version
	}
	line := e.JSONLine() + "\n"
	if _, err := io.WriteString(w, line); err != nil {
		return err
	}
	if leg := e.Legacy(); leg != "" {
		if _, err := io.WriteString(w, leg+"\n"); err != nil {
			return err
		}
	}
	return nil
}

func ParseLine(line string) (Event, bool) {
	line = strings.TrimSpace(line)
	if line == "" || line[0] != '{' {
		return Event{}, false
	}
	var e Event
	if err := json.Unmarshal([]byte(line), &e); err != nil {
		return Event{}, false
	}
	if e.Protocol != Version {
		return Event{}, false
	}
	if e.Type == "" {
		return Event{}, false
	}
	return e, true
}

func trimFloat(v float64) string {
	s := fmt.Sprintf("%.4f", v)
	s = strings.TrimRight(s, "0")
	s = strings.TrimRight(s, ".")
	if s == "" || s == "-0" {
		return "0"
	}
	return s
}

func joinDone(m map[string]string) string {
	keys := make([]string, 0, len(m))
	for k := range m {
		keys = append(keys, k)
	}
	// Stable-ish: prefer known order then the rest.
	order := []string{
		"dry_run", "rollback",
		"install_dir", "public_host", "deploy_version", "direct_port", "bypass_port",
		"provision_port", "telemetry_port", "role", "cascade",
	}
	seen := map[string]bool{}
	var parts []string
	for _, k := range order {
		if v, ok := m[k]; ok {
			parts = append(parts, k+"="+v)
			seen[k] = true
		}
	}
	rest := make([]string, 0)
	for k := range m {
		if !seen[k] {
			rest = append(rest, k)
		}
	}
	sort.Strings(rest)
	for _, k := range rest {
		parts = append(parts, k+"="+m[k])
	}
	return strings.Join(parts, "|")
}
