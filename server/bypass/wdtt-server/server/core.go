package main

const (
	// RAW TUN for Path B. Subnet matches ARDTT provision (10.9.0.{hostId}).
	rawIfaceName  = "wdttraw0"
	rawServerAddr = "10.9.0.1"
	rawServerCIDR = rawServerAddr + "/24"
	// Raw path carries RTP-obfs (12 B header + 16 B AEAD tag + optional
	// padding) plus TURN framing. 1280 matches cascade0 and Direct; 1300
	// black-holed large inner packets on the hop (keepalives lived, pages stalled).
	rawMTU = 1280

	wrapKeyLen = 32
)

var dns = "8.8.8.8"
