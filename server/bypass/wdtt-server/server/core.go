package main

const (
	// RAW TUN for Path B. Subnet matches ARDTT provision (10.9.0.{hostId}).
	rawIfaceName  = "wdttraw0"
	rawServerAddr = "10.9.0.1"
	rawServerCIDR = rawServerAddr + "/24"
	// Raw path carries RTP-obfs (12 B header + 16 B AEAD tag + optional
	// padding) plus TURN framing. MTU 1300 stays under Ethernet 1500.
	rawMTU = 1300

	wrapKeyLen = 32
)

var dns = "8.8.8.8"
