package main

import (
	"context"
	"testing"
	"time"
)

func TestParseControlAndStaleGen(t *testing.T) {
	cmd, err := parseControlLine("V1|r7|3|PAUSE_USER_DATA")
	if err != nil {
		t.Fatal(err)
	}
	if cmd.ReqID != "r7" || cmd.SessionGen != 3 || cmd.Name != "PAUSE_USER_DATA" {
		t.Fatalf("parsed %+v", cmd)
	}
	ok := controlAck(cmd, 3, "ok", "")
	if ok.Stale || !containsAll(ok.Line, "V1|r7|3|ACK|PAUSE_USER_DATA|ok") {
		t.Fatalf("ack=%q", ok.Line)
	}
	stale := controlAck(cmd, 4, "ok", "")
	if !stale.Stale {
		t.Fatal("expected stale")
	}
}

func TestParseControlArgsAndAckPayload(t *testing.T) {
	cmd, err := parseControlLine("V1|r9|1|UPDATE_NETWORK|wifi|123")
	if err != nil {
		t.Fatal(err)
	}
	if cmd.Name != "UPDATE_NETWORK" || len(cmd.Args) != 2 || cmd.Args[0] != "wifi" {
		t.Fatalf("parsed %+v", cmd)
	}
	ack := controlAck(cmd, 1, "ok", "tunGen=3")
	if ack.Stale || !containsAll(ack.Line, "ACK|UPDATE_NETWORK|ok|tunGen=3") {
		t.Fatalf("ack=%q", ack.Line)
	}
}

func TestLegacyPauseIsNotControl(t *testing.T) {
	_, err := parseControlLine("PAUSE")
	if err == nil {
		t.Fatal("PAUSE must not parse as V1 control")
	}
}

func containsAll(s, sub string) bool {
	return len(s) >= len(sub) && (s == sub || len(sub) == 0 ||
		(len(s) > 0 && (s == sub || stringIndex(s, sub) >= 0)))
}

func stringIndex(s, sub string) int {
	for i := 0; i+len(sub) <= len(s); i++ {
		if s[i:i+len(sub)] == sub {
			return i
		}
	}
	return -1
}

func TestSessionControlNetOpsWait(t *testing.T) {
	c := NewSessionControl()
	c.SetNetOpsAllowed(false)
	if c.NetOpsAllowed() {
		t.Fatal("net ops should be forbidden")
	}
	c.SetNetOpsAllowed(true)
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	if err := c.WaitNetOps(ctx); err != nil {
		t.Fatal(err)
	}
}
