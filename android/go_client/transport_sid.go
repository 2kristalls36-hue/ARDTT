package main

import (
	crand "crypto/rand"
	"encoding/hex"
	"fmt"
	"sync"
	"time"
)

var processTransportSID string
var processSIDOnce sync.Once

func currentTransportSID() string {
	processSIDOnce.Do(func() {
		b := make([]byte, 12)
		if _, err := crand.Read(b); err != nil {
			processTransportSID = fmt.Sprintf("t%d", time.Now().UnixNano())
			return
		}
		processTransportSID = hex.EncodeToString(b)
	})
	return processTransportSID
}
