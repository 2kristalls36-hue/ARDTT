package main

import (
	"context"
	"errors"
	"flag"
	"log"
	"net"
	"os"
	"os/signal"
	"sync"
	"syscall"
	"time"
)

func main() {
	listenRaw := flag.String("listen-raw", "", "UDP address for RAW/WRAP clients")
	configDir := flag.String("config-dir", "/etc/wdtt", "directory with passwords.json")
	mainPass := flag.String("password", "", "owner password")
	mainPassFile := flag.String("password-file", "", "file with owner password")
	dnsFlag := flag.String("dns", "8.8.8.8", "DNS pushed to RAW clients")
	flag.Parse()

	if *listenRaw == "" {
		log.Fatal("ARDTT RAW server requires -listen-raw")
	}

	mainPasswordValue, err := loadOptionalSecret(*mainPass, *mainPassFile)
	if err != nil {
		log.Fatalf("[CONFIG] owner password: %v", err)
	}

	dns = *dnsFlag
	log.SetFlags(log.Ldate | log.Ltime | log.Lmicroseconds)
	log.Println("ARDTT RAW bypass (WRAP + TUN, no DTLS/WireGuard)")

	initDB(*configDir, mainPasswordValue)
	enableBBR()

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	sig := make(chan os.Signal, 2)
	signal.Notify(sig, syscall.SIGTERM, syscall.SIGINT, syscall.SIGHUP)
	go func() {
		for s := range sig {
			if s == syscall.SIGHUP {
				log.Println("[SYS] SIGHUP — reload passwords.json")
				if err := reloadDB(); err != nil {
					log.Printf("[ERR] password reload: %v", err)
				} else {
					log.Printf("[SYS] WRAP keys reloaded (%d)", serverWrapKeys.Count())
				}
				continue
			}
			cancel()
			dbMutex.Lock()
			flushRawDeviceTrafficLocked()
			saveDB()
			dbMutex.Unlock()
			time.Sleep(2 * time.Second)
			os.Exit(0)
		}
	}()

	go statsLoop(ctx, *configDir)
	go expiredPasswordJanitor(ctx)

	if serverWrapKeys.Count() == 0 {
		log.Fatal("[WRAP] no active passwords")
	}

	router, err := newRawRouter()
	if err != nil {
		log.Fatalf("[RAW] %v", err)
	}

	rawAddr, err := net.ResolveUDPAddr("udp", *listenRaw)
	if err != nil {
		log.Fatalf("[RAW] address: %v", err)
	}
	rawWrapListener, err := listenWrapped(rawAddr, serverWrapKeys)
	if err != nil {
		log.Fatalf("[RAW] %v", err)
	}
	context.AfterFunc(ctx, func() { _ = rawWrapListener.Close() })
	log.Printf("RAW WRAP %s | TUN %s (%s) MTU %d | keys %d",
		*listenRaw, rawIfaceName, rawServerCIDR, rawMTU, serverWrapKeys.Count())

	var wg sync.WaitGroup
	go func() {
		for {
			pc, remoteAddr, acceptErr := rawWrapListener.Accept()
			if acceptErr != nil {
				if errors.Is(acceptErr, net.ErrClosed) {
					return
				}
				select {
				case <-ctx.Done():
					return
				case <-time.After(50 * time.Millisecond):
				}
				continue
			}
			wg.Add(1)
			go func(pc net.PacketConn, addr net.Addr) {
				defer wg.Done()
				c := &directConn{pc: pc, addr: addr}
				defer c.Close()
				handleConnRaw(ctx, c, router)
			}(pc, remoteAddr)
		}
	}()

	log.Println("[SERVER] ready")
	<-ctx.Done()
	wg.Wait()
}
