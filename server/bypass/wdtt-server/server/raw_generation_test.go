package main

import (
	"io"
	"net"
	"testing"
)

func TestDownlinkSkipsStaleGenerationAndKeepsOtherDevice(t *testing.T) {
	r := &rawRouter{sessions: make(map[string]*rawClientSessions)}
	ipA := "10.9.0.2"
	ipB := "10.9.0.3"

	oldC, oldS := net.Pipe()
	defer oldC.Close()
	go io.Copy(io.Discard, oldC)
	gen1 := r.beginClientGeneration(ipA)
	wOld := r.register(ipA, oldS, "deviceA", gen1)

	ctrlC, ctrlS := net.Pipe()
	defer ctrlC.Close()
	go io.Copy(io.Discard, ctrlC)
	genB := r.beginClientGeneration(ipB)
	wB := r.register(ipB, ctrlS, "deviceB", genB)

	newC, newS := net.Pipe()
	defer newC.Close()
	go io.Copy(io.Discard, newC)
	gen2 := r.beginClientGeneration(ipA)
	if gen2 == gen1 {
		t.Fatalf("GETCONF_RAW must bump generation, got %d", gen2)
	}
	wNew := r.register(ipA, newS, "deviceA", gen2)

	authC, authS := net.Pipe()
	defer authC.Close()
	go io.Copy(io.Discard, authC)
	wAuth := r.register(ipA, authS, "deviceA", r.currentGeneration(ipA))

	for i := 0; i < 8; i++ {
		w := r.pickDownlinkConn(ipA, 64)
		if w == nil {
			t.Fatal("expected live generation worker")
		}
		if w == wOld {
			t.Fatal("stale generation received downlink")
		}
		if w != wNew && w != wAuth {
			t.Fatalf("unexpected worker gen=%d", w.gen)
		}
	}

	pickedB := r.pickDownlinkConn(ipB, 64)
	if pickedB != wB {
		t.Fatal("control device lost its downlink")
	}
}

func TestAuthJoinsCurrentGenerationWithoutBump(t *testing.T) {
	r := &rawRouter{sessions: make(map[string]*rawClientSessions)}
	ip := "10.9.0.4"
	c1, s1 := net.Pipe()
	defer c1.Close()
	go io.Copy(io.Discard, c1)
	gen := r.beginClientGeneration(ip)
	_ = r.register(ip, s1, "dev", gen)
	c2, s2 := net.Pipe()
	defer c2.Close()
	go io.Copy(io.Discard, c2)
	wAuth := r.register(ip, s2, "dev", r.currentGeneration(ip))
	if wAuth.gen != gen {
		t.Fatalf("AUTH gen=%d want %d", wAuth.gen, gen)
	}
	if r.currentGeneration(ip) != gen {
		t.Fatal("AUTH must not bump generation")
	}
}
