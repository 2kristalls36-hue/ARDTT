package main

import (
	"net"
	"testing"
	"time"
)

func TestGetNextRawIPUsesProvisionSubnet(t *testing.T) {
	db = &Database{
		Passwords: map[string]*PasswordEntry{},
		Devices: map[string]*ClientDevice{
			"a": {DeviceID: "a", RawIP: "10.9.0.2"},
			"b": {DeviceID: "b", RawIP: "10.9.0.3"},
		},
	}
	got := getNextRawIP()
	if got != "10.9.0.4" {
		t.Fatalf("getNextRawIP()=%q want 10.9.0.4", got)
	}
}

func TestWrapKeyDerivationIsStable(t *testing.T) {
	a, err := deriveWrapKey("secret")
	if err != nil {
		t.Fatal(err)
	}
	b, err := deriveWrapKey("secret")
	if err != nil {
		t.Fatal(err)
	}
	if len(a) != wrapKeyLen || string(a) != string(b) {
		t.Fatalf("WRAP key not stable")
	}
	other, err := deriveWrapKey("other")
	if err != nil {
		t.Fatal(err)
	}
	if string(a) == string(other) {
		t.Fatal("different passwords produced the same WRAP key")
	}
}

func TestUDPDemuxAcceptAndDeadline(t *testing.T) {
	laddr := &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 0}
	demux, err := listenUDPDemux(laddr)
	if err != nil {
		t.Fatal(err)
	}
	defer demux.Close()

	local := demux.Addr().(*net.UDPAddr)
	client, err := net.DialUDP("udp", nil, local)
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()

	if _, err := client.Write([]byte("hello-raw")); err != nil {
		t.Fatal(err)
	}

	pc, addr, err := demux.Accept()
	if err != nil {
		t.Fatal(err)
	}
	defer pc.Close()
	if addr == nil {
		t.Fatal("missing remote addr")
	}

	buf := make([]byte, 64)
	n, _, err := pc.ReadFrom(buf)
	if err != nil {
		t.Fatal(err)
	}
	if string(buf[:n]) != "hello-raw" {
		t.Fatalf("got %q", buf[:n])
	}

	if err := pc.SetReadDeadline(time.Now().Add(40 * time.Millisecond)); err != nil {
		t.Fatal(err)
	}
	_, _, err = pc.ReadFrom(buf)
	if err == nil {
		t.Fatal("expected read deadline")
	}
	if ne, ok := err.(net.Error); !ok || !ne.Timeout() {
		t.Fatalf("want timeout net.Error, got %v", err)
	}
}
