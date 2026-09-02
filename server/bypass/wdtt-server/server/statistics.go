package main

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"os"
	"path/filepath"
	"sync"
	"sync/atomic"
	"time"
)

var (
	totalBytesFromClient int64
	totalBytesToClient   int64
	activeConns          int32
	totalConns           int64
	natType              string = "init"
	serverStartTime      time.Time
)

type rawTrafficCounter struct {
	up   int64
	down int64
}

var (
	rawDeviceTrafficMu sync.Mutex
	rawDeviceTraffic   = make(map[string]*rawTrafficCounter)
)

func addRawUplinkBytes(deviceID string, n int64) {
	if deviceID == "" || deviceID == "unknown" {
		return
	}
	rawDeviceTrafficMu.Lock()
	c := rawDeviceTraffic[deviceID]
	if c == nil {
		c = &rawTrafficCounter{}
		rawDeviceTraffic[deviceID] = c
	}
	c.up += n
	rawDeviceTrafficMu.Unlock()
}

func addRawDownlinkBytes(deviceID string, n int64) {
	if deviceID == "" || deviceID == "unknown" {
		return
	}
	rawDeviceTrafficMu.Lock()
	c := rawDeviceTraffic[deviceID]
	if c == nil {
		c = &rawTrafficCounter{}
		rawDeviceTraffic[deviceID] = c
	}
	c.down += n
	rawDeviceTrafficMu.Unlock()
}

func flushRawDeviceTrafficLocked() {
	rawDeviceTrafficMu.Lock()
	if len(rawDeviceTraffic) == 0 {
		rawDeviceTrafficMu.Unlock()
		return
	}
	snapshot := rawDeviceTraffic
	rawDeviceTraffic = make(map[string]*rawTrafficCounter)
	rawDeviceTrafficMu.Unlock()

	for deviceID, c := range snapshot {
		if c.up == 0 && c.down == 0 {
			continue
		}
		if dev, ok := db.Devices[deviceID]; ok {
			dev.UpBytes += c.up
			dev.DownBytes += c.down
			if entry := generatedOwnerEntryLocked(dev, deviceID); entry != nil {
				entry.UpBytes += c.up
				entry.DownBytes += c.down
			}
		}
	}
}

func statsLoop(ctx context.Context, configDir string) {
	serverStartTime = time.Now()
	statsFile := filepath.Join(configDir, "server.log")
	ticker := time.NewTicker(10 * time.Second)
	defer ticker.Stop()

	saveTicks := 0
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			fromC := atomic.LoadInt64(&totalBytesFromClient)
			toC := atomic.LoadInt64(&totalBytesToClient)
			active := atomic.LoadInt32(&activeConns)
			total := atomic.LoadInt64(&totalConns)
			uptime := time.Since(serverStartTime)

			log.Printf("[STAT] active=%d total=%d nat=%s up=%.2fMB down=%.2fMB",
				active, total, natType,
				float64(fromC)/1024/1024,
				float64(toC)/1024/1024,
			)

			dbMutex.Lock()
			flushRawDeviceTrafficLocked()
			numPasswords := len(db.Passwords)
			numDevices := len(db.Devices)
			saveTicks++
			if saveTicks >= 6 {
				saveTicks = 0
				saveDB()
			}
			dbMutex.Unlock()

			statsJSON, _ := json.Marshal(map[string]interface{}{
				"active":    active,
				"total":     total,
				"nat":       natType,
				"uptime":    formatUptime(uptime),
				"down_gb":   fmt.Sprintf("%.2f", float64(toC)/(1024*1024*1024)),
				"up_gb":     fmt.Sprintf("%.2f", float64(fromC)/(1024*1024*1024)),
				"passwords": numPasswords,
				"devices":   numDevices,
				"timestamp": time.Now().Unix(),
			})
			_ = os.WriteFile(statsFile, statsJSON, 0644)
		}
	}
}

func formatUptime(d time.Duration) string {
	days := int(d.Hours()) / 24
	hours := int(d.Hours()) % 24
	mins := int(d.Minutes()) % 60
	if days > 0 {
		return fmt.Sprintf("%dd %dh %dm", days, hours, mins)
	}
	if hours > 0 {
		return fmt.Sprintf("%dh %dm", hours, mins)
	}
	return fmt.Sprintf("%dm", mins)
}
