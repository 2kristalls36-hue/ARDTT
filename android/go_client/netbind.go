package main

import (
	"fmt"
	"net"
	"syscall"
	"time"
)

// recordBind, if set, observes every bindSocketToNetwork call (tests).
var recordBind func(fd int, handle int64)

func currentNetworkHandle() int64 {
	if c := activeSessionCtrl.Load(); c != nil {
		return c.NetworkHandle()
	}
	return 0
}

func bindControl(handle int64) func(network, address string, c syscall.RawConn) error {
	return func(network, address string, c syscall.RawConn) error {
		if handle == 0 {
			return nil
		}
		var inner error
		err := c.Control(func(fd uintptr) {
			inner = bindSocketToNetwork(int(fd), handle)
			if recordBind != nil {
				recordBind(int(fd), handle)
			}
		})
		if err != nil {
			return err
		}
		if inner != nil {
			return fmt.Errorf("bind socket to network %d: %w", handle, inner)
		}
		return nil
	}
}

func dialerForCurrentNetwork(timeout time.Duration) *net.Dialer {
	return &net.Dialer{
		Timeout: timeout,
		Control: bindControl(currentNetworkHandle()),
	}
}
