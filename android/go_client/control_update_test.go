package main

import (
	"context"
	"strings"
	"testing"
	"time"
)

func TestUpdateNetworkStoresHandleAndBumpsEpoch(t *testing.T) {
	ctrl := NewSessionControl()
	rt := &controlRuntime{ctrl: ctrl}
	cmd, err := parseControlLine("V1|r9|1|UPDATE_NETWORK|wifi|123")
	if err != nil {
		t.Fatal(err)
	}
	reply := rt.handle(context.Background(), cmd)
	if reply.Stale {
		t.Fatal("unexpected stale")
	}
	if !strings.Contains(reply.Line, "handle=123") || !strings.Contains(reply.Line, "netEpoch=") {
		t.Fatalf("ack=%q", reply.Line)
	}
	if ctrl.NetworkHandle() != 123 {
		t.Fatalf("handle=%d", ctrl.NetworkHandle())
	}
	if ctrl.SocketsEpoch() == 0 {
		t.Fatal("sockets epoch must bump")
	}
	first := ctrl.SocketsEpoch()
	cmd2, err := parseControlLine("V1|r10|1|UPDATE_NETWORK|cell|9")
	if err != nil {
		t.Fatal(err)
	}
	_ = rt.handle(context.Background(), cmd2)
	if ctrl.SocketsEpoch() <= first {
		t.Fatal("second UPDATE_NETWORK must bump sockets epoch")
	}
}

func TestForbidThenWaitNetOps(t *testing.T) {
	c := NewSessionControl()
	c.SetNetOpsAllowed(false)
	ctx, cancel := context.WithTimeout(context.Background(), 50*time.Millisecond)
	defer cancel()
	if err := c.WaitNetOps(ctx); err == nil {
		t.Fatal("forbidden net ops must not wait successfully")
	}
}
