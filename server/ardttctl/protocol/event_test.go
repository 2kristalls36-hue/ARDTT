package protocol

import (
	"bytes"
	"strings"
	"testing"
)

func TestEmitWritesJSONLThenLegacy(t *testing.T) {
	p := 0.42
	var buf bytes.Buffer
	err := Emit(&buf, Event{
		Type:     TypeProgress,
		Phase:    "download",
		Progress: &p,
		Message:  "слои",
	})
	if err != nil {
		t.Fatal(err)
	}
	out := buf.String()
	lines := strings.Split(strings.TrimSuffix(out, "\n"), "\n")
	if len(lines) != 2 {
		t.Fatalf("want 2 lines, got %q", out)
	}
	ev, ok := ParseLine(lines[0])
	if !ok || ev.Type != TypeProgress || ev.Message != "слои" {
		t.Fatalf("json line: %+v ok=%v", ev, ok)
	}
	if ev.Progress == nil || *ev.Progress != 0.42 {
		t.Fatalf("progress: %v", ev.Progress)
	}
	if lines[1] != "ARDTT_PROGRESS|0.42|слои" {
		t.Fatalf("legacy: %q", lines[1])
	}
}

func TestErrorLegacyHasCode(t *testing.T) {
	var buf bytes.Buffer
	if err := Emit(&buf, Event{Type: TypeError, Code: "DISK_FULL", Message: "мало места"}); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(buf.String(), "ARDTT_ERROR|code=DISK_FULL|мало места") {
		t.Fatal(buf.String())
	}
}

func TestParseIgnoresLegacyAndUnknownJSON(t *testing.T) {
	if _, ok := ParseLine("ARDTT_PROGRESS|0.1|hi"); ok {
		t.Fatal("legacy must not parse as protocol 2")
	}
	if _, ok := ParseLine(`{"protocol":1,"type":"progress"}`); ok {
		t.Fatal("protocol 1 must be rejected")
	}
	if _, ok := ParseLine(`{"not":"an event"}`); ok {
		t.Fatal("missing type")
	}
}

func TestDoneLegacyFields(t *testing.T) {
	leg := Event{Type: TypeDone, Done: map[string]string{
		"deploy_version": "1.0.54",
		"direct_port":    "51820",
	}}.Legacy()
	if !strings.Contains(leg, "deploy_version=1.0.54") || !strings.HasPrefix(leg, "ARDTT_DONE|") {
		t.Fatal(leg)
	}
}

func TestDoneDryRunFirst(t *testing.T) {
	leg := Event{Type: TypeDone, Done: map[string]string{
		"install_dir": "/opt/ardtt",
		"dry_run":     "1",
	}}.Legacy()
	if !strings.HasPrefix(leg, "ARDTT_DONE|dry_run=1|") {
		t.Fatal(leg)
	}
}

func TestDoneExtraKeysSorted(t *testing.T) {
	leg := Event{Type: TypeDone, Done: map[string]string{
		"zeta":        "1",
		"alpha":       "2",
		"install_dir": "/opt/ardtt",
	}}.Legacy()
	if !strings.HasPrefix(leg, "ARDTT_DONE|install_dir=/opt/ardtt|") {
		t.Fatal(leg)
	}
	if !strings.Contains(leg, "|alpha=2|zeta=1") && !strings.HasSuffix(leg, "|alpha=2|zeta=1") {
		t.Fatal(leg)
	}
}
