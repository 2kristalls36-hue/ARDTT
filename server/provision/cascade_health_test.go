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

func TestShouldProxyEgressToExit(t *testing.T) {
	if !shouldProxyEgressToExit("entry", true, false, "2.26.125.160") {
		t.Fatal("entry cascade hideIp-off should ask the exit")
	}
	if shouldProxyEgressToExit("entry", true, true, "2.26.125.160") {
		t.Fatal("hideIp-on probes local warp0, not the exit")
	}
	if shouldProxyEgressToExit("exit", true, false, "45.129.2.3") {
		t.Fatal("exit must not proxy (loop)")
	}
	if shouldProxyEgressToExit("entry", false, false, "2.26.125.160") {
		t.Fatal("standalone entry uses local WAN")
	}
	if shouldProxyEgressToExit("entry", true, false, "") {
		t.Fatal("missing cascade host")
	}
}

func TestProvisionPeerBaseURL(t *testing.T) {
	t.Setenv("NVPN_PROVISION_LISTEN", "0.0.0.0:9100")
	if got := provisionPeerBaseURL("2.26.125.160"); got != "http://2.26.125.160:9100" {
		t.Fatalf("got %q", got)
	}
}
