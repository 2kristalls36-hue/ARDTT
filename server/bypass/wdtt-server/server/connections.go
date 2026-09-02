package main

import (
	"errors"
	"net"
	"sync"
	"time"
)

var credentialConnections = struct {
	sync.Mutex
	items map[string]map[net.Conn]string
}{items: make(map[string]map[net.Conn]string)}

func trackCredentialConnection(password, deviceID string, conn net.Conn) func() {
	ownerID := wrapKeyID(password)
	credentialConnections.Lock()
	connections := credentialConnections.items[ownerID]
	if connections == nil {
		connections = make(map[net.Conn]string)
		credentialConnections.items[ownerID] = connections
	}
	connections[conn] = deviceID
	credentialConnections.Unlock()
	return func() {
		credentialConnections.Lock()
		delete(connections, conn)
		if len(connections) == 0 {
			delete(credentialConnections.items, ownerID)
		}
		credentialConnections.Unlock()
	}
}

func disconnectCredentialConnections(password string) {
	disconnectCredentialDeviceConnections(password, "")
}

func disconnectCredentialDeviceConnections(password, deviceID string) {
	ownerID := wrapKeyID(password)
	credentialConnections.Lock()
	connections := credentialConnections.items[ownerID]
	list := make([]net.Conn, 0, len(connections))
	for conn, activeDeviceID := range connections {
		if deviceID != "" && activeDeviceID != deviceID {
			continue
		}
		list = append(list, conn)
		delete(connections, conn)
	}
	if len(connections) == 0 {
		delete(credentialConnections.items, ownerID)
	}
	credentialConnections.Unlock()
	for _, conn := range list {
		conn.Close()
	}
}

// directConn is net.Conn over an already WRAP-decrypted UDP flow.
type directConn struct {
	pc   net.PacketConn
	addr net.Addr
}

func (c *directConn) Read(b []byte) (int, error) {
	for {
		n, _, err := c.pc.ReadFrom(b)
		if err != nil {
			var netErr net.Error
			if errors.As(err, &netErr) {
				return 0, err
			}
			continue
		}
		return n, nil
	}
}
func (c *directConn) Write(b []byte) (int, error)        { return c.pc.WriteTo(b, c.addr) }
func (c *directConn) Close() error                       { return c.pc.Close() }
func (c *directConn) LocalAddr() net.Addr                { return c.pc.LocalAddr() }
func (c *directConn) RemoteAddr() net.Addr               { return c.addr }
func (c *directConn) SetDeadline(t time.Time) error      { return c.pc.SetDeadline(t) }
func (c *directConn) SetReadDeadline(t time.Time) error  { return c.pc.SetReadDeadline(t) }
func (c *directConn) SetWriteDeadline(t time.Time) error { return c.pc.SetWriteDeadline(t) }
