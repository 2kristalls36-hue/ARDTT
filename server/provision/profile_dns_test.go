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
	t.Setenv("NVPN_CASCADE_DNS", "")
	got = profileDNS("10.8.0")
	if len(got) != 1 || got[0] != "10.10.0.2" {
		t.Fatalf("cascade enabled dns: %#v", got)
	}

	t.Setenv("NVPN_CASCADE_ENABLED", "1")
	t.Setenv("NVPN_CASCADE_DNS", "10.10.0.2")
	got = profileDNS("10.8.0")
	if len(got) != 1 || got[0] != "10.10.0.2" {
		t.Fatalf("explicit cascade dns: %#v", got)
	}
}

func TestProfileDNSIgnoresStaleHopDnsWhenCascadeOff(t *testing.T) {
	// After several in-app updates the entry .env still had
	// NVPN_CASCADE_DNS=10.10.0.2 while NVPN_CASCADE_ENABLED=0.
	t.Setenv("NVPN_CASCADE_ENABLED", "0")
	t.Setenv("NVPN_CASCADE_DNS", "10.10.0.2")
	got := profileDNS("10.8.0")
	if len(got) != 1 || got[0] != "10.8.0.1" {
		t.Fatalf("stale hop dns leaked into standalone profile: %#v", got)
	}
}
