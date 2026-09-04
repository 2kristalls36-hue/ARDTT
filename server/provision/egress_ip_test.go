package main

import "testing"

func TestResolveEgressViaWarp(t *testing.T) {
	if !resolveEgressViaWarp(true, true, "0") {
		t.Fatal("known hideIp=true must ignore viaWarp=0 (2ip.ru mismatch)")
	}
	if resolveEgressViaWarp(true, false, "1") {
		t.Fatal("known hideIp=false must ignore viaWarp=1")
	}
	if resolveEgressViaWarp(true, false, "") {
		t.Fatal("known hideIp=false with no query is VPS WAN")
	}
	if !resolveEgressViaWarp(true, true, "") {
		t.Fatal("known hideIp=true with no query is WARP")
	}
	if !resolveEgressViaWarp(false, false, "1") {
		t.Fatal("anonymous viaWarp=1 is WARP for diagnostics")
	}
	if resolveEgressViaWarp(false, false, "0") {
		t.Fatal("anonymous viaWarp=0 is VPS WAN")
	}
	if resolveEgressViaWarp(false, false, "") {
		t.Fatal("anonymous default is VPS WAN")
	}
}
