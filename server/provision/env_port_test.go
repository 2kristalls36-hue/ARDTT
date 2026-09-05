package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestEnvPort(t *testing.T) {
	t.Setenv("ARDTT_DIRECT_PORT", "51821")
	if got := envPort("ARDTT_DIRECT_PORT"); got != 51821 {
		t.Fatalf("got %d", got)
	}
	t.Setenv("ARDTT_DIRECT_PORT", "not-a-port")
	if got := envPort("ARDTT_DIRECT_PORT"); got != 0 {
		t.Fatalf("invalid should be 0, got %d", got)
	}
	t.Setenv("ARDTT_DIRECT_PORT", "")
	t.Setenv("NVPN_DIRECT_PORT", "56010")
	if got := envPort("ARDTT_DIRECT_PORT"); got != 56010 {
		t.Fatalf("legacy NVPN fallback got %d", got)
	}
}

func TestLoadOrInitStoreSyncsPortsFromEnv(t *testing.T) {
	dir := t.TempDir()
	t.Setenv("ARDTT_DIRECT_PORT", "51825")
	t.Setenv("ARDTT_BYPASS_PORT", "56010")
	s, err := loadOrInitStore(dir, "203.0.113.9")
	if err != nil {
		t.Fatal(err)
	}
	if s.Config.DirectPort != 51825 {
		t.Fatalf("DirectPort=%d", s.Config.DirectPort)
	}
	if s.Config.BypassPort != 56010 {
		t.Fatalf("BypassPort=%d", s.Config.BypassPort)
	}
	raw, err := os.ReadFile(filepath.Join(dir, "users.json"))
	if err != nil {
		t.Fatal(err)
	}
	text := string(raw)
	if !strings.Contains(text, `"directPort": 51825`) || !strings.Contains(text, `"bypassPort": 56010`) {
		t.Fatalf("users.json missing synced ports: %s", text)
	}
}
