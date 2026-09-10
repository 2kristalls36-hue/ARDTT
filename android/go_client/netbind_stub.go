//go:build !android

package main

func bindSocketToNetwork(fd int, handle int64) error {
	_ = fd
	_ = handle
	return nil
}
