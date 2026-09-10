package main

import (
	"io"
	"net"
	"strings"
	"testing"
	"time"
)

func TestRequestRawConfigSendsSid(t *testing.T) {
	client, server := net.Pipe()
	defer client.Close()
	defer server.Close()
	got := make(chan string, 1)
	go func() {
		buf := make([]byte, 256)
		_ = server.SetReadDeadline(time.Now().Add(2 * time.Second))
		n, err := server.Read(buf)
		if err != nil {
			got <- "read:" + err.Error()
			return
		}
		got <- string(buf[:n])
		_, _ = server.Write([]byte("RAWCONF:10.9.0.2|1.1.1.1|1280"))
	}()
	ip, dns, mtu, err := RequestRawConfig(client, "dev", "pw", "sid-abc")
	if err != nil {
		t.Fatal(err)
	}
	payload := <-got
	if !strings.Contains(payload, "GETCONF_RAW:dev|pw|sid-abc") {
		t.Fatalf("payload=%q", payload)
	}
	if ip != "10.9.0.2" || dns != "1.1.1.1" || mtu != 1280 {
		t.Fatalf("parsed %s %s %d", ip, dns, mtu)
	}
}

func TestSendAuthSendsSid(t *testing.T) {
	client, server := net.Pipe()
	defer client.Close()
	defer server.Close()
	got := make(chan string, 1)
	go func() {
		buf := make([]byte, 256)
		_ = server.SetReadDeadline(time.Now().Add(2 * time.Second))
		n, err := server.Read(buf)
		if err != nil {
			got <- "read:" + err.Error()
			return
		}
		got <- string(buf[:n])
	}()
	if err := SendAuth(client, "dev", "pw", "sid-xyz"); err != nil {
		t.Fatal(err)
	}
	payload := <-got
	if !strings.Contains(payload, "AUTH:dev|pw|sid-xyz") {
		t.Fatalf("payload=%q", payload)
	}
}

func TestLegacyRawHandshakeOmitsSid(t *testing.T) {
	client, server := net.Pipe()
	defer client.Close()
	defer server.Close()
	go io.Copy(io.Discard, server)
	if err := SendAuth(client, "dev", "pw", ""); err != nil {
		t.Fatal(err)
	}
}
