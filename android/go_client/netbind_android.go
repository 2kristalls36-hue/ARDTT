//go:build android

package main

/*
#cgo LDFLAGS: -landroid
#include <android/multinetwork.h>
#include <errno.h>

static int go_android_setsocknetwork(int fd, unsigned long long handle) {
	int rc = android_setsocknetwork((net_handle_t)handle, fd);
	if (rc == 0) {
		return 0;
	}
	if (errno != 0) {
		return errno;
	}
	return -1;
}
*/
import "C"
import "syscall"

func bindSocketToNetwork(fd int, handle int64) error {
	if handle == 0 {
		return nil
	}
	if errno := C.go_android_setsocknetwork(C.int(fd), C.ulonglong(handle)); errno != 0 {
		return syscall.Errno(errno)
	}
	return nil
}
