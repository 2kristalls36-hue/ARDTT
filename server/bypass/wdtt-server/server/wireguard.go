package main

import (
	"fmt"
	"log"
	"strings"

	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
)

// ==================== WireGuard ====================

type loopbackOnlyBind struct {
	conn.Bind
}

func (b *loopbackOnlyBind) Open(port uint16) ([]conn.ReceiveFunc, uint16, error) {
	receivers, actualPort, err := b.Bind.Open(port)
	if err != nil {
		return nil, 0, err
	}
	wrapped := make([]conn.ReceiveFunc, len(receivers))
	for i, receive := range receivers {
		inner := receive
		wrapped[i] = func(packets [][]byte, sizes []int, endpoints []conn.Endpoint) (int, error) {
			for {
				n, receiveErr := inner(packets, sizes, endpoints)
				if receiveErr != nil {
					return 0, receiveErr
				}
				kept := 0
				for j := 0; j < n; j++ {
					if endpoints[j] == nil || !endpoints[j].DstIP().IsLoopback() {
						continue
					}
					if kept != j {
						packets[kept] = packets[j]
						sizes[kept] = sizes[j]
						endpoints[kept] = endpoints[j]
					}
					kept++
				}
				if kept > 0 {
					return kept, nil
				}
			}
		}
	}
	return wrapped, actualPort, nil
}

func (b *loopbackOnlyBind) Send(bufs [][]byte, endpoint conn.Endpoint) error {
	if endpoint == nil || !endpoint.DstIP().IsLoopback() {
		return fmt.Errorf("WireGuard endpoint outside loopback rejected")
	}
	return b.Bind.Send(bufs, endpoint)
}

func startUserspaceWG(keys *wgKeys, wgPort int) (*device.Device, error) {
    log.Println("[WG] WireGuard полностью отключён (raw-режим)")
    return nil, nil
}

func configureInterface(ifaceName string) error {
	for _, cmd := range [][]string{
		{"ip", "addr", "add", wgServerCIDR, "dev", ifaceName},
		{"ip", "link", "set", "mtu", fmt.Sprintf("%d", wgMTU), "dev", ifaceName},
		{"ip", "link", "set", ifaceName, "up"},
	} {
		out, err := runCmd(cmd[0], cmd[1:]...)
		if err != nil && !strings.Contains(out, "File exists") {
			return fmt.Errorf("%s: %s", strings.Join(cmd, " "), out)
		}
	}
	return nil
}

func buildClientConfig(serverPublic, clientPrivate, clientIP, clientPort string) string {
	return fmt.Sprintf(`[Interface]
PrivateKey = %s
Address = %s/32
DNS = %s
MTU = %d

[Peer]
PublicKey = %s
AllowedIPs = 0.0.0.0/0
Endpoint = 127.0.0.1:%s
PersistentKeepalive = %d`,
		clientPrivate, clientIP, dns, wgMTU,
		serverPublic, clientPort, keepalive,
	)
}
