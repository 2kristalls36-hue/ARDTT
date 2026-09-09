package main

import (
	"context"
	"net"
	"strings"
	"testing"
	"time"
)

func TestUpdateNetworkSameHandleIsIdempotent(t *testing.T) {
	ctrl := NewSessionControl()
	rt := &controlRuntime{ctrl: ctrl}
	first, err := parseControlLine("V1|r9|1|UPDATE_NETWORK|wifi|123")
	if err != nil {
		t.Fatal(err)
	}
	reply := rt.handle(context.Background(), first)
	if !strings.Contains(reply.Line, "bound=applied") {
		t.Fatalf("first ack=%q", reply.Line)
	}
	epoch := ctrl.SocketsEpoch()
	again, err := parseControlLine("V1|r10|1|UPDATE_NETWORK|wifi|123")
	if err != nil {
		t.Fatal(err)
	}
	reply2 := rt.handle(context.Background(), again)
	if !strings.Contains(reply2.Line, "bound=unchanged") {
		t.Fatalf("repeat ack=%q", reply2.Line)
	}
	if ctrl.SocketsEpoch() != epoch {
		t.Fatalf("repeat UPDATE_NETWORK bumped sockets epoch %d -> %d", epoch, ctrl.SocketsEpoch())
	}
}

type deadlineProbe struct {
	n int
}

func (d *deadlineProbe) SetDeadline(time.Time) error {
	d.n++
	return nil
}

func TestForbidQuiescesTrackedConns(t *testing.T) {
	c := NewSessionControl()
	p := &deadlineProbe{}
	untrack := c.TrackConn(p)
	defer untrack()
	if !c.TryNetOp(func() {}) {
		t.Fatal("allowed TryNetOp")
	}
	c.SetNetOpsAllowed(false)
	if p.n == 0 {
		t.Fatal("FORBID must SetDeadline on tracked conns")
	}
	if c.TryNetOp(func() {}) {
		t.Fatal("FORBID must reject TryNetOp")
	}
}

func TestDialTURNRecordsSelectedHandle(t *testing.T) {
	ln, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	addr := ln.LocalAddr().String()

	ctrl := NewSessionControl()
	if _, changed := ctrl.ApplyNetwork(77); !changed {
		t.Fatal("first handle must apply")
	}
	activeSessionCtrl.Store(ctrl)
	defer activeSessionCtrl.Store(nil)

	var seen []int64
	recordBind = func(_ int, handle int64) { seen = append(seen, handle) }
	defer func() { recordBind = nil }()

	conn, closer, err := dialTURNConn(context.Background(), addr, false)
	if err != nil {
		t.Fatal(err)
	}
	defer closer.Close()
	defer conn.Close()
	if len(seen) == 0 {
		t.Fatal("dial did not bind to a network handle")
	}
	if seen[len(seen)-1] != 77 {
		t.Fatalf("bound handle=%v want 77", seen)
	}
}

func TestWriterDropsWhileForbidden(t *testing.T) {
	if !shouldSendOnWire(nil) {
		t.Fatal("nil ctrl allows send")
	}
	c := NewSessionControl()
	if !shouldSendOnWire(c) {
		t.Fatal("default allows send")
	}
	c.SetNetOpsAllowed(false)
	if shouldSendOnWire(c) {
		t.Fatal("FORBID must stop wire send")
	}
}

func TestUpdateNetworkDoesNotAllowNetOps(t *testing.T) {
	ctrl := NewSessionControl()
	ctrl.SetNetOpsAllowed(false)
	rt := &controlRuntime{ctrl: ctrl}
	cmd, err := parseControlLine("V1|r11|1|UPDATE_NETWORK|cell|9")
	if err != nil {
		t.Fatal(err)
	}
	reply := rt.handle(context.Background(), cmd)
	if reply.Stale {
		t.Fatal("unexpected stale")
	}
	if ctrl.NetOpsAllowed() {
		t.Fatal("UPDATE_NETWORK must not ALLOW")
	}
	if ctrl.NetworkHandle() != 9 {
		t.Fatalf("handle=%d", ctrl.NetworkHandle())
	}
}

func TestTlsClientDialerUsesCurrentHandle(t *testing.T) {
	ctrl := NewSessionControl()
	if _, changed := ctrl.ApplyNetwork(55); !changed {
		t.Fatal("first handle must apply")
	}
	activeSessionCtrl.Store(ctrl)
	defer activeSessionCtrl.Store(nil)

	var seen []int64
	recordBind = func(_ int, handle int64) { seen = append(seen, handle) }
	defer func() { recordBind = nil }()

	dialer := tlsClientDialer()
	if dialer.Control == nil {
		t.Fatal("tls-client dialer must carry bind Control")
	}
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	go func() {
		c, acceptErr := ln.Accept()
		if acceptErr == nil {
			_ = c.Close()
		}
	}()
	conn, err := dialer.Dial("tcp", ln.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	_ = conn.Close()
	if len(seen) == 0 || seen[len(seen)-1] != 55 {
		t.Fatalf("tls-client dialer bound handle=%v want 55", seen)
	}
}

func TestForbidCancelsBoundContext(t *testing.T) {
	c := NewSessionControl()
	ctx, cancel := c.BoundContext(context.Background())
	defer cancel()
	c.SetNetOpsAllowed(false)
	select {
	case <-ctx.Done():
	case <-time.After(time.Second):
		t.Fatal("FORBID must cancel BoundContext")
	}
	c.SetNetOpsAllowed(true)
	ctx2, cancel2 := c.BoundContext(context.Background())
	defer cancel2()
	select {
	case <-ctx2.Done():
		t.Fatal("ALLOW must not leave BoundContext cancelled")
	case <-time.After(30 * time.Millisecond):
	}
}
