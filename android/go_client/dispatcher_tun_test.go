package main

import (
	"bytes"
	"context"
	"os"
	"testing"
	"time"
)

func TestAttachDetachDoesNotDoubleCloseReady(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	stats := NewStats()
	d := NewDispatcherPendingTUN(ctx, stats)
	defer d.Shutdown()

	r1, w1, err := os.Pipe()
	if err != nil {
		t.Fatal(err)
	}
	if err := d.AttachTUN(r1); err != nil {
		t.Fatal(err)
	}
	if d.TunGeneration() != 1 {
		t.Fatalf("gen=%d", d.TunGeneration())
	}
	d.DetachTUN()
	_ = w1.Close()

	r2, w2, err := os.Pipe()
	if err != nil {
		t.Fatal(err)
	}
	defer w2.Close()
	if err := d.AttachTUN(r2); err != nil {
		t.Fatal(err)
	}
	if d.TunGeneration() != 3 { // detach bumps, attach bumps
		t.Fatalf("gen=%d want 3", d.TunGeneration())
	}
}

func TestDownlinkBytesOnlyOnSuccessfulWrite(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	stats := NewStats()
	d := NewDispatcherPendingTUN(ctx, stats)

	r, w, err := os.Pipe()
	if err != nil {
		t.Fatal(err)
	}
	if err := d.AttachTUN(w); err != nil {
		t.Fatal(err)
	}
	pkt := bytes.Repeat([]byte{1}, 64)
	buf := append([]byte(nil), pkt...)
	d.ReturnCh <- buf
	time.Sleep(50 * time.Millisecond)
	got := make([]byte, 64)
	_ = r.SetReadDeadline(time.Now().Add(time.Second))
	if _, err := r.Read(got); err != nil {
		t.Fatal(err)
	}
	if stats.TotalBytesDown.Load() != 64 {
		t.Fatalf("down=%d", stats.TotalBytesDown.Load())
	}
	d.DetachTUN()
	failPkt := append([]byte(nil), pkt...)
	select {
	case d.ReturnCh <- failPkt:
	case <-time.After(time.Second):
		t.Fatal("return ch blocked")
	}
	time.Sleep(50 * time.Millisecond)
	if stats.TotalBytesDown.Load() != 64 {
		t.Fatalf("failed write counted as delivery down=%d", stats.TotalBytesDown.Load())
	}
	cancel()
	d.Shutdown()
	_ = r.Close()
}

// Concurrent AttachTUN/DetachTUN while readLoop is unblocked used to race
// on d.tunFile (CI go test -race on Go 1.25). Counters and logs must use the
// loop-local snapshot, not the field without tunMu.
func TestAttachTUNDoesNotRaceWithReadLoop(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	d := NewDispatcherPendingTUN(ctx, NewStats())
	defer d.Shutdown()

	slot := &WorkerSlot{
		ID:     1,
		SendCh: make(chan []byte, 256),
		PrioCh: make(chan []byte, 32),
	}
	d.Register(slot)
	go func() {
		for {
			select {
			case <-ctx.Done():
				return
			case pkt := <-slot.SendCh:
				putPktBuf(pkt)
			case pkt := <-slot.PrioCh:
				putPktBuf(pkt)
			}
		}
	}()

	payload := []byte{0x45, 0, 0, 28, 0, 0, 0, 0, 64, 0, 0, 0, 127, 0, 0, 1, 127, 0, 0, 1, 1, 2, 3, 4, 5, 6, 7, 8}
	for i := 0; i < 40; i++ {
		r, w, err := os.Pipe()
		if err != nil {
			t.Fatal(err)
		}
		if err := d.AttachTUN(r); err != nil {
			t.Fatal(err)
		}
		_, _ = w.Write(payload)
		time.Sleep(2 * time.Millisecond)
		d.DetachTUN()
		_ = w.Close()
	}
	cancel()
}
