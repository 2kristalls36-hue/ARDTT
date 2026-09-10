package main

import (
	"strings"
	"testing"
	"time"
)

func TestRecvTunFDTimesOutWithoutClient(t *testing.T) {
	sock := t.TempDir() + "/tun.sock"
	start := time.Now()
	_, err := recvTunFDTimeout(sock, 200*time.Millisecond)
	if err == nil {
		t.Fatal("expected timeout without FD")
	}
	if time.Since(start) > 2*time.Second {
		t.Fatalf("ATTACH_TUN wait blocked too long: %s", time.Since(start))
	}
	if !strings.Contains(err.Error(), "accept") && !strings.Contains(strings.ToLower(err.Error()), "timeout") &&
		!strings.Contains(strings.ToLower(err.Error()), "deadline") {
		t.Fatalf("unexpected error: %v", err)
	}
}
