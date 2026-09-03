package main

import "testing"

func TestCascadeHostFromPeer(t *testing.T) {
	if got := cascadeHostFromPeer("2.26.125.160:51820"); got != "2.26.125.160" {
		t.Fatalf("ipv4: %q", got)
	}
	if got := cascadeHostFromPeer("  "); got != "" {
		t.Fatalf("blank: %q", got)
	}
	if got := cascadeHostFromPeer("2.26.125.160"); got != "2.26.125.160" {
		t.Fatalf("host only: %q", got)
	}
}
