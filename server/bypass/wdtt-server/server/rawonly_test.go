package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestGetNextRawIPUsesProvisionSubnet(t *testing.T) {
	dbMutex.Lock()
	old := db
	db = &Database{
		Passwords: map[string]*PasswordEntry{},
		Devices: map[string]*ClientDevice{
			"a": {DeviceID: "a", RawIP: "10.9.0.2"},
			"b": {DeviceID: "b", RawIP: "10.9.0.3"},
		},
	}
	dbMutex.Unlock()
	t.Cleanup(func() {
		dbMutex.Lock()
		db = old
		dbMutex.Unlock()
	})

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

func TestRawMTUFitsCascadeHop(t *testing.T) {
	if rawMTU > 1280 {
		t.Fatalf("rawMTU=%d exceeds cascade0 MTU 1280", rawMTU)
	}
}

func TestSetPasswordsIdenticalSkipsAEADEvict(t *testing.T) {
	keys := newWrapKeyStore()
	if err := keys.SetPasswords("secret", []string{"keep"}); err != nil {
		t.Fatal(err)
	}
	key, err := deriveWrapKey("secret")
	if err != nil {
		t.Fatal(err)
	}
	first, err := getAEAD(key)
	if err != nil {
		t.Fatal(err)
	}
	if err := keys.SetPasswords("secret", []string{"keep"}); err != nil {
		t.Fatal(err)
	}
	second, err := getAEAD(key)
	if err != nil {
		t.Fatal(err)
	}
	if first != second {
		t.Fatal("identical SetPasswords evicted the live AEAD cache")
	}
	if keys.Count() != 2 {
		t.Fatalf("count=%d want 2", keys.Count())
	}
}

func TestSetPasswordsRemovedCredentialDropsKey(t *testing.T) {
	keys := newWrapKeyStore()
	if err := keys.SetPasswords("secret", []string{"keep", "gone"}); err != nil {
		t.Fatal(err)
	}
	if keys.Count() != 3 {
		t.Fatalf("before=%d", keys.Count())
	}
	if err := keys.SetPasswords("secret", []string{"keep"}); err != nil {
		t.Fatal(err)
	}
	if keys.Count() != 2 {
		t.Fatalf("after=%d want 2", keys.Count())
	}
}

func TestObfsWrapUnwrapRoundtrip(t *testing.T) {
	key, err := deriveWrapKey("secret")
	if err != nil {
		t.Fatal(err)
	}
	cfg, err := NewObfsConfig("audio")
	if err != nil {
		t.Fatal(err)
	}
	state, err := NewObfsState()
	if err != nil {
		t.Fatal(err)
	}
	payload := []byte("GETCONF_RAW:dev1|secret")
	wire, err := obfsWrapPacket(key, payload, cfg, state, nil)
	if err != nil {
		t.Fatal(err)
	}
	plain := make([]byte, 1600)
	n, err := obfsUnwrapPacket(key, wire, plain)
	if err != nil {
		t.Fatal(err)
	}
	if string(plain[:n]) != string(payload) {
		t.Fatalf("got %q", plain[:n])
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

func TestCloneUDPAddrDoesNotAlias(t *testing.T) {
	orig := &net.UDPAddr{IP: net.IPv4(10, 9, 0, 2), Port: 443, Zone: "eth0"}
	cloned := cloneUDPAddr(orig)
	if cloned == orig || cloned.IP == nil {
		t.Fatal("expected a distinct UDPAddr")
	}
	orig.IP[len(orig.IP)-1] = 99
	orig.Port = 1
	if cloned.IP.Equal(orig.IP) || cloned.Port != 443 || cloned.Zone != "eth0" {
		t.Fatalf("clone aliased original: %#v", cloned)
	}
}

func TestUDPDemuxCloseUnblocksAcceptAndRead(t *testing.T) {
	demux, err := listenUDPDemux(&net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 0})
	if err != nil {
		t.Fatal(err)
	}

	local := demux.Addr().(*net.UDPAddr)
	client, err := net.DialUDP("udp", nil, local)
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	if _, err := client.Write([]byte("open")); err != nil {
		t.Fatal(err)
	}
	pc, _, err := demux.Accept()
	if err != nil {
		t.Fatal(err)
	}
	if _, _, err := pc.ReadFrom(make([]byte, 64)); err != nil {
		t.Fatal(err)
	}

	acceptDone := make(chan error, 1)
	go func() {
		_, _, err := demux.Accept()
		acceptDone <- err
	}()

	readDone := make(chan error, 1)
	go func() {
		_, _, err := pc.ReadFrom(make([]byte, 64))
		readDone <- err
	}()

	time.Sleep(20 * time.Millisecond)
	if err := demux.Close(); err != nil {
		t.Fatal(err)
	}

	for _, ch := range []chan error{acceptDone, readDone} {
		select {
		case err := <-ch:
			if !errors.Is(err, net.ErrClosed) {
				t.Fatalf("want net.ErrClosed, got %v", err)
			}
		case <-time.After(time.Second):
			t.Fatal("Close did not unblock demux")
		}
	}
}

func TestDirectConnReadReturnsBareErrClosed(t *testing.T) {
	c := &directConn{pc: stubPacketConn{readErr: net.ErrClosed}, addr: &net.UDPAddr{}}
	done := make(chan error, 1)
	go func() {
		_, err := c.Read(make([]byte, 16))
		done <- err
	}()
	select {
	case err := <-done:
		if !errors.Is(err, net.ErrClosed) {
			t.Fatalf("got %v", err)
		}
	case <-time.After(time.Second):
		t.Fatal("Read busy-looped on net.ErrClosed")
	}
}

func TestDirectConnReadReturnsAfterSessionClose(t *testing.T) {
	demux, err := listenUDPDemux(&net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 0})
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
	if _, err := client.Write([]byte("open")); err != nil {
		t.Fatal(err)
	}
	pc, addr, err := demux.Accept()
	if err != nil {
		t.Fatal(err)
	}
	c := &directConn{pc: pc, addr: addr}
	if _, err := c.Read(make([]byte, 64)); err != nil {
		t.Fatal(err)
	}

	done := make(chan error, 1)
	go func() {
		_, err := c.Read(make([]byte, 64))
		done <- err
	}()
	time.Sleep(20 * time.Millisecond)
	if err := c.Close(); err != nil {
		t.Fatal(err)
	}
	select {
	case err := <-done:
		if !errors.Is(err, net.ErrClosed) {
			t.Fatalf("got %v", err)
		}
	case <-time.After(time.Second):
		t.Fatal("Read hung after Close")
	}
}

func TestIsNetTimeoutUnwraps(t *testing.T) {
	if !isNetTimeout(timeoutError{}) {
		t.Fatal("plain timeoutError")
	}
	if !isNetTimeout(fmt.Errorf("wrap: %w", timeoutError{})) {
		t.Fatal("wrapped timeoutError")
	}
	if isNetTimeout(net.ErrClosed) {
		t.Fatal("closed is not a timeout")
	}
	if isNetTimeout(nil) {
		t.Fatal("nil")
	}
}

func TestReloadDBNotInitialized(t *testing.T) {
	dbMutex.Lock()
	oldDB, oldFile := db, dbFile
	db, dbFile = nil, ""
	dbMutex.Unlock()
	t.Cleanup(func() {
		dbMutex.Lock()
		db, dbFile = oldDB, oldFile
		dbMutex.Unlock()
	})
	if err := reloadDB(); err == nil {
		t.Fatal("expected error")
	}
}

func TestReloadDBMergesTrafficDropsKeysAndDisconnects(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "passwords.json")

	prevKeys := serverWrapKeys
	serverWrapKeys = newWrapKeyStore()
	t.Cleanup(func() { serverWrapKeys = prevKeys })

	rawDeviceTrafficMu.Lock()
	prevTraffic := rawDeviceTraffic
	rawDeviceTraffic = map[string]*rawTrafficCounter{
		"dev1": {up: 100, down: 20},
	}
	rawDeviceTrafficMu.Unlock()
	t.Cleanup(func() {
		rawDeviceTrafficMu.Lock()
		rawDeviceTraffic = prevTraffic
		rawDeviceTrafficMu.Unlock()
	})

	dbMutex.Lock()
	oldDB, oldFile := db, dbFile
	dbFile = path
	db = &Database{
		MainPassword: "owner",
		Passwords: map[string]*PasswordEntry{
			"keep": {DeviceID: "dev1", DeviceIDs: []string{"dev1"}, UpBytes: 50, DownBytes: 9},
			"gone": {DeviceID: "dev2", DeviceIDs: []string{"dev2"}},
			"dead": {DeviceID: "dev3", DeviceIDs: []string{"dev3"}},
		},
		Devices: map[string]*ClientDevice{
			"dev1": {DeviceID: "dev1", RawIP: "10.9.0.2", UpBytes: 40, DownBytes: 8},
		},
	}
	if err := saveDB(); err != nil {
		dbMutex.Unlock()
		t.Fatal(err)
	}
	if err := refreshWrapKeysFromDBLocked(); err != nil {
		dbMutex.Unlock()
		t.Fatal(err)
	}
	dbMutex.Unlock()
	t.Cleanup(func() {
		dbMutex.Lock()
		db, dbFile = oldDB, oldFile
		dbMutex.Unlock()
	})

	if serverWrapKeys.Count() != 4 { // owner + keep + gone + dead
		t.Fatalf("wrap keys before reload: %d", serverWrapKeys.Count())
	}

	serverEnd, clientEnd := net.Pipe()
	defer clientEnd.Close()
	untrack := trackCredentialConnection("gone", "dev2", serverEnd)
	defer untrack()
	defer serverEnd.Close()

	disconnected := make(chan struct{})
	go func() {
		buf := make([]byte, 8)
		_, _ = clientEnd.Read(buf)
		close(disconnected)
	}()

	onDisk := Database{
		Passwords: map[string]*PasswordEntry{
			"keep": {DeviceID: "dev1", DeviceIDs: []string{"dev1"}, UpBytes: 1, DownBytes: 1},
			"dead": {DeviceID: "dev3", DeviceIDs: []string{"dev3"}, IsDeactivated: true},
		},
		Devices: map[string]*ClientDevice{
			"dev1": {DeviceID: "dev1", RawIP: "10.9.0.2", UpBytes: 5, DownBytes: 7},
		},
	}
	data, err := json.Marshal(onDisk)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, data, 0o600); err != nil {
		t.Fatal(err)
	}

	if err := reloadDB(); err != nil {
		t.Fatal(err)
	}

	if _, still := db.Passwords["gone"]; still {
		t.Fatal("deleted password still present")
	}
	if !db.Passwords["dead"].IsDeactivated {
		t.Fatal("deactivated password lost")
	}
	dev := db.Devices["dev1"]
	if dev.UpBytes != 140 { // 40 flushed+100, file had 5
		t.Fatalf("up bytes=%d want 140", dev.UpBytes)
	}
	if dev.DownBytes != 28 { // 8+20 vs file 7
		t.Fatalf("down bytes=%d want 28", dev.DownBytes)
	}
	if db.Passwords["keep"].UpBytes != 150 { // 50+100 vs file 1
		t.Fatalf("password up=%d want 150", db.Passwords["keep"].UpBytes)
	}
	if serverWrapKeys.Count() != 2 { // owner + keep; dead deactivated
		t.Fatalf("wrap keys after reload: %d", serverWrapKeys.Count())
	}

	select {
	case <-disconnected:
	case <-time.After(time.Second):
		t.Fatal("deleted password did not disconnect the session")
	}
}

func TestWrapListenerRoundTripAndClose(t *testing.T) {
	keys := newWrapKeyStore()
	if err := keys.SetPasswords("secret", nil); err != nil {
		t.Fatal(err)
	}
	ln, err := listenWrapped(&net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 0}, keys)
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()

	local := ln.Addr().(*net.UDPAddr)
	client, err := net.DialUDP("udp", nil, local)
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()

	key, err := deriveWrapKey("secret")
	if err != nil {
		t.Fatal(err)
	}
	cfg, err := NewObfsConfig("audio")
	if err != nil {
		t.Fatal(err)
	}
	state, err := NewObfsState()
	if err != nil {
		t.Fatal(err)
	}
	payload := []byte("GETCONF_RAW:dev1|secret")
	wire, err := obfsWrapPacket(key, payload, cfg, state, nil)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := client.Write(wire); err != nil {
		t.Fatal(err)
	}

	pc, addr, err := ln.Accept()
	if err != nil {
		t.Fatal(err)
	}
	defer pc.Close()

	plain := make([]byte, 1600)
	n, _, err := pc.ReadFrom(plain)
	if err != nil {
		t.Fatal(err)
	}
	if string(plain[:n]) != string(payload) {
		t.Fatalf("got %q", plain[:n])
	}

	reply := []byte("RAWCONF:10.9.0.2|8.8.8.8|1280")
	if _, err := pc.WriteTo(reply, addr); err != nil {
		t.Fatal(err)
	}
	buf := make([]byte, 2048)
	if err := client.SetReadDeadline(time.Now().Add(time.Second)); err != nil {
		t.Fatal(err)
	}
	rn, err := client.Read(buf)
	if err != nil {
		t.Fatal(err)
	}
	out := make([]byte, 1600)
	got, err := obfsUnwrapPacket(key, buf[:rn], out)
	if err != nil {
		t.Fatal(err)
	}
	if string(out[:got]) != string(reply) {
		t.Fatalf("reply %q", out[:got])
	}

	c := &directConn{pc: pc, addr: addr}
	done := make(chan error, 1)
	go func() {
		_, err := c.Read(make([]byte, 64))
		done <- err
	}()
	time.Sleep(20 * time.Millisecond)
	if err := c.Close(); err != nil {
		t.Fatal(err)
	}
	select {
	case err := <-done:
		if !errors.Is(err, net.ErrClosed) {
			t.Fatalf("got %v", err)
		}
	case <-time.After(time.Second):
		t.Fatal("WRAP Read hung after Close")
	}
}

type stubPacketConn struct {
	readErr error
}

func (s stubPacketConn) ReadFrom([]byte) (int, net.Addr, error) { return 0, nil, s.readErr }
func (s stubPacketConn) WriteTo([]byte, net.Addr) (int, error)  { return 0, nil }
func (s stubPacketConn) Close() error                           { return nil }
func (s stubPacketConn) LocalAddr() net.Addr                    { return &net.UDPAddr{} }
func (s stubPacketConn) SetDeadline(time.Time) error            { return nil }
func (s stubPacketConn) SetReadDeadline(time.Time) error        { return nil }
func (s stubPacketConn) SetWriteDeadline(time.Time) error       { return nil }
