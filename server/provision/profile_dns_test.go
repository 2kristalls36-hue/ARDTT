package main

import "testing"

func TestProfileDNSStandaloneAndCascade(t *testing.T) {
	t.Setenv("NVPN_CASCADE_DNS", "")
	t.Setenv("NVPN_CASCADE_ENABLED", "")
	got := profileDNS("10.8.0")
	if len(got) != 1 || got[0] != "10.8.0.1" {
		t.Fatalf("standalone dns: %#v", got)
	}

	t.Setenv("NVPN_CASCADE_ENABLED", "1")
	got = profileDNS("10.8.0")
	if len(got) != 1 || got[0] != "10.10.0.2" {
		t.Fatalf("cascade enabled dns: %#v", got)
	}

	t.Setenv("NVPN_CASCADE_DNS", "10.10.0.2")
	got = profileDNS("10.8.0")
	if len(got) != 1 || got[0] != "10.10.0.2" {
		t.Fatalf("explicit cascade dns: %#v", got)
	}
}
