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
	if !shouldProxyEgressToExit("entry", true, "2.26.125.160") {
		t.Fatal("cascade entry must ask the exit for WAN and WARP probes")
	}
	if shouldProxyEgressToExit("exit", true, "45.129.2.3") {
		t.Fatal("exit must not proxy (loop)")
	}
	if shouldProxyEgressToExit("entry", false, "2.26.125.160") {
		t.Fatal("standalone entry uses local WAN")
	}
	if shouldProxyEgressToExit("entry", true, "") {
		t.Fatal("missing cascade host")
	}
}

func TestHideIPPrefixes(t *testing.T) {
	s := &Store{
		Config: Config{DirectSubnet: "10.8.0.0/24", BypassSubnet: "10.9.0.0/24"},
		Users: []User{
			{Name: "on", HostID: 5, HideIP: true},
			{Name: "off", HostID: 6, HideIP: false},
			{Name: "dead", HostID: 7, HideIP: true, Deactivated: true},
		},
	}
	got := s.HideIPPrefixes()
	if len(got) != 2 || got[0] != "10.8.0.5/32" || got[1] != "10.9.0.5/32" {
		t.Fatalf("got %v", got)
	}
}

func TestProvisionPeerBaseURL(t *testing.T) {
	t.Setenv("ARDTT_PROVISION_LISTEN", "0.0.0.0:9100")
	if got := provisionPeerBaseURL("2.26.125.160"); got != "http://2.26.125.160:9100" {
		t.Fatalf("got %q", got)
	}
}
