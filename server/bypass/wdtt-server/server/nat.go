package main

import (
	"fmt"
	"log"
	"os"
	"os/exec"
)

func setupRawNAT(rawIface string) error {
	os.WriteFile("/proc/sys/net/ipv4/ip_forward", []byte("1"), 0644)

	extIface := getDefaultInterface()
	log.Printf("[RAW-NAT] wan=%s cidr=%s", extIface, rawServerCIDR)

	switch {
	case commandExists("iptables"):
		for i := 0; i < 5; i++ {
			exec.Command("iptables", "-t", "nat", "-D", "POSTROUTING", "-s", rawServerCIDR, "-o", extIface, "-m", "comment", "--comment", "WDTT_RAW_MANAGED", "-j", "MASQUERADE").Run()
		}
		exec.Command("iptables", "-t", "nat", "-I", "POSTROUTING", "1", "-s", rawServerCIDR, "-o", extIface, "-m", "comment", "--comment", "WDTT_RAW_MANAGED", "-j", "MASQUERADE").Run()
		setupForwardRules(rawIface)
		setupRawMSSClamping()
		natType = "MASQUERADE iptables"
	case commandExists("nft"):
		exec.Command("nft", "add", "table", "ip", "wdttraw").Run()
		exec.Command("nft", "add", "chain", "ip", "wdttraw", "postrouting", "{ type nat hook postrouting priority 100; }").Run()
		exec.Command("nft", "add", "rule", "ip", "wdttraw", "postrouting", "ip", "saddr", rawServerCIDR, "oifname", extIface, "masquerade").Run()
		setupForwardRules(rawIface)
		setupRawMSSClamping()
		natType = "MASQUERADE nft"
	default:
		return fmt.Errorf("no iptables/nft for RAW NAT")
	}
	log.Printf("[RAW-NAT] %s", natType)
	return nil
}

func setupRawMSSClamping() {
	if commandExists("iptables") {
		for _, dir := range []string{"-s", "-d"} {
			exec.Command("iptables", "-t", "mangle", "-D", "FORWARD", dir, rawServerCIDR,
				"-p", "tcp", "-m", "tcp", "--tcp-flags", "SYN,RST", "SYN",
				"-m", "comment", "--comment", "WDTT_RAW_MANAGED",
				"-j", "TCPMSS", "--clamp-mss-to-pmtu").Run()
			exec.Command("iptables", "-t", "mangle", "-I", "FORWARD", dir, rawServerCIDR,
				"-p", "tcp", "-m", "tcp", "--tcp-flags", "SYN,RST", "SYN",
				"-m", "comment", "--comment", "WDTT_RAW_MANAGED",
				"-j", "TCPMSS", "--clamp-mss-to-pmtu").Run()
		}
		return
	}
	if commandExists("nft") {
		exec.Command("nft", "add", "table", "inet", "wdttraw_mangle").Run()
		exec.Command("nft", "add", "chain", "inet", "wdttraw_mangle", "forward",
			"{ type filter hook forward priority -150; policy accept; }").Run()
		exec.Command("nft", "add", "rule", "inet", "wdttraw_mangle", "forward",
			"ip", "saddr", rawServerCIDR, "tcp", "flags", "syn",
			"tcp", "option", "maxseg", "size", "set", "rt", "mtu").Run()
		exec.Command("nft", "add", "rule", "inet", "wdttraw_mangle", "forward",
			"ip", "daddr", rawServerCIDR, "tcp", "flags", "syn",
			"tcp", "option", "maxseg", "size", "set", "rt", "mtu").Run()
	}
}

func setupForwardRules(iface string) {
	if commandExists("iptables") {
		for i := 0; i < 5; i++ {
			exec.Command("iptables", "-D", "FORWARD", "-i", iface, "-m", "comment", "--comment", "WDTT_MANAGED", "-j", "ACCEPT").Run()
			exec.Command("iptables", "-D", "FORWARD", "-o", iface, "-m", "comment", "--comment", "WDTT_MANAGED", "-j", "ACCEPT").Run()
		}
		exec.Command("iptables", "-A", "FORWARD", "-i", iface, "-m", "comment", "--comment", "WDTT_MANAGED", "-j", "ACCEPT").Run()
		exec.Command("iptables", "-A", "FORWARD", "-o", iface, "-m", "comment", "--comment", "WDTT_MANAGED", "-j", "ACCEPT").Run()
		return
	}
	if commandExists("nft") {
		exec.Command("nft", "add", "table", "inet", "wdtt").Run()
		exec.Command("nft", "add", "chain", "inet", "wdtt", "forward", "{ type filter hook forward priority 0; policy accept; }").Run()
		exec.Command("nft", "add", "rule", "inet", "wdtt", "forward", "iifname", iface, "accept").Run()
		exec.Command("nft", "add", "rule", "inet", "wdtt", "forward", "oifname", iface, "accept").Run()
	}
}
