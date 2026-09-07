package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

// Converts ARDTT users.json → WDTT passwords.json.
// Primary device of a profile keeps hostId as RAW IP; extra devices get a
// distinct address so rawRouter downlink is not shared across phones.
// Writes a provision-readable traffic snapshot to /data/bypass-traffic.json.

type ardttStore struct {
	Config struct {
		BypassSubnet string `json:"bypassSubnet"`
	} `json:"config"`
	Users []struct {
		Name        string   `json:"name"`
		HostID      int      `json:"hostId"`
		DeviceID    string   `json:"deviceId"`
		DeviceIDs   []string `json:"deviceIds"`
		MaxDevices  int      `json:"maxDevices"`
		Password    string   `json:"password"`
		ExpiresAt   int64    `json:"expiresAt"`
		Deactivated bool     `json:"deactivated"`
	} `json:"users"`
}

type wdttDB struct {
	Passwords map[string]*wdttPass `json:"passwords"`
	Devices   map[string]*wdttDev  `json:"devices"`
}

type wdttPass struct {
	Label         string   `json:"label,omitempty"`
	DeviceID      string   `json:"device_id"`
	DeviceIDs     []string `json:"device_ids"`
	MaxDevices    int      `json:"max_devices"`
	ExpiresAt     int64    `json:"expires_at"`
	Ports         string   `json:"ports,omitempty"`
	DownBytes     int64    `json:"down_bytes,omitempty"`
	UpBytes       int64    `json:"up_bytes,omitempty"`
	IsDeactivated bool     `json:"is_deactivated,omitempty"`
}

type wdttDev struct {
	DeviceID   string `json:"device_id"`
	IP         string `json:"ip,omitempty"`
	RawIP      string `json:"raw_ip,omitempty"`
	RawOwnerID string `json:"raw_owner_id,omitempty"`
	DownBytes  int64  `json:"down_bytes,omitempty"`
	UpBytes    int64  `json:"up_bytes,omitempty"`
}

type trafficSnap struct {
	ByName map[string]trafficCounters `json:"byName"`
}

type trafficCounters struct {
	DownBytes int64 `json:"downBytes"`
	UpBytes   int64 `json:"upBytes"`
}

func main() {
	usersPath := flag.String("users", "/data/users.json", "ardtt users.json")
	outPath := flag.String("out", "/etc/wdtt/passwords.json", "wdtt passwords.json")
	trafficPath := flag.String("traffic", "/data/bypass-traffic.json", "provision traffic snapshot")
	trafficOnly := flag.Bool("traffic-only", false, "only refresh traffic snapshot from passwords.json")
	flag.Parse()

	if *trafficOnly {
		if err := exportTrafficOnly(*usersPath, *outPath, *trafficPath); err != nil {
			fatalf("%v", err)
		}
		return
	}

	raw, err := os.ReadFile(*usersPath)
	if err != nil {
		fatalf("%v", err)
	}
	var s ardttStore
	if err := json.Unmarshal(raw, &s); err != nil {
		fatalf("%v", err)
	}

	db := wdttDB{
		Passwords: map[string]*wdttPass{},
		Devices:   map[string]*wdttDev{},
	}
	// Merge existing traffic counters if present
	if prev, err := os.ReadFile(*outPath); err == nil {
		_ = json.Unmarshal(prev, &db)
		if db.Passwords == nil {
			db.Passwords = map[string]*wdttPass{}
		}
		if db.Devices == nil {
			db.Devices = map[string]*wdttDev{}
		}
	}

	base := subnetBase(s.Config.BypassSubnet)
	hostIDs := make([]int, 0, len(s.Users))
	for _, u := range s.Users {
		hostIDs = append(hostIDs, u.HostID)
	}
	reserved := reservedPrimaryIPs(hostIDs, base)
	keepPass := map[string]bool{}
	snap := trafficSnap{ByName: map[string]trafficCounters{}}
	for _, u := range s.Users {
		if u.Password == "" || u.HostID < 2 {
			continue
		}
		deviceIDs := append([]string{}, u.DeviceIDs...)
		if u.DeviceID != "" && !contains(deviceIDs, u.DeviceID) {
			deviceIDs = append([]string{u.DeviceID}, deviceIDs...)
		}
		if len(deviceIDs) == 0 {
			continue
		}
		primary := deviceIDs[0]
		keepPass[u.Password] = true
		entry := db.Passwords[u.Password]
		if entry == nil {
			entry = &wdttPass{}
			db.Passwords[u.Password] = entry
		}
		entry.Label = u.Name
		entry.DeviceID = primary
		entry.DeviceIDs = deviceIDs
		entry.MaxDevices = u.MaxDevices
		if entry.MaxDevices <= 0 {
			entry.MaxDevices = 1
		}
		entry.ExpiresAt = u.ExpiresAt
		entry.IsDeactivated = u.Deactivated
		entry.Ports = "raw"
		// DownBytes / UpBytes preserved from previous passwords.json merge.

		snap.ByName[u.Name] = trafficCounters{
			DownBytes: entry.DownBytes,
			UpBytes:   entry.UpBytes,
		}

		assignDeviceRawIPs(&db, deviceIDs, base, u.HostID, reserved)
	}

	// Drop passwords no longer in ardtt store (keep device history otherwise)
	for pass := range db.Passwords {
		if !keepPass[pass] {
			delete(db.Passwords, pass)
		}
	}

	if err := os.MkdirAll(filepath.Dir(*outPath), 0o700); err != nil {
		fatalf("%v", err)
	}
	out, err := json.MarshalIndent(db, "", "  ")
	if err != nil {
		fatalf("%v", err)
	}
	tmp := *outPath + ".tmp"
	if err := os.WriteFile(tmp, append(out, '\n'), 0o600); err != nil {
		fatalf("%v", err)
	}
	if err := os.Rename(tmp, *outPath); err != nil {
		fatalf("%v", err)
	}

	if err := writeTrafficSnapshot(*trafficPath, snap); err != nil {
		fmt.Fprintf(os.Stderr, "[bypass-sync] traffic snapshot: %v\n", err)
	}
	fmt.Printf("[bypass-sync] wrote %s (%d passwords)\n", *outPath, len(db.Passwords))
}

func writeTrafficSnapshot(path string, snap trafficSnap) error {
	if path == "" {
		return nil
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return err
	}
	raw, err := json.MarshalIndent(snap, "", "  ")
	if err != nil {
		return err
	}
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, append(raw, '\n'), 0o644); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}

func exportTrafficOnly(usersPath, passwordsPath, trafficPath string) error {
	rawUsers, err := os.ReadFile(usersPath)
	if err != nil {
		return err
	}
	var s ardttStore
	if err := json.Unmarshal(rawUsers, &s); err != nil {
		return err
	}
	db := wdttDB{}
	if prev, err := os.ReadFile(passwordsPath); err == nil {
		_ = json.Unmarshal(prev, &db)
	}
	snap := trafficSnap{ByName: map[string]trafficCounters{}}
	for _, u := range s.Users {
		if u.Name == "" || u.Password == "" {
			continue
		}
		entry := db.Passwords[u.Password]
		if entry == nil {
			snap.ByName[u.Name] = trafficCounters{}
			continue
		}
		snap.ByName[u.Name] = trafficCounters{
			DownBytes: entry.DownBytes,
			UpBytes:   entry.UpBytes,
		}
	}
	return writeTrafficSnapshot(trafficPath, snap)
}

func subnetBase(cidr string) string {
	ip := strings.Split(strings.TrimSpace(cidr), "/")[0]
	parts := strings.Split(ip, ".")
	if len(parts) >= 3 {
		return fmt.Sprintf("%s.%s.%s", parts[0], parts[1], parts[2])
	}
	return "10.9.0"
}

func contains(list []string, want string) bool {
	for _, v := range list {
		if v == want {
			return true
		}
	}
	return false
}

func reservedPrimaryIPs(hostIDs []int, base string) map[string]bool {
	used := map[string]bool{fmt.Sprintf("%s.1", base): true}
	for _, id := range hostIDs {
		if id >= 2 {
			used[fmt.Sprintf("%s.%d", base, id)] = true
		}
	}
	return used
}

func assignDeviceRawIPs(db *wdttDB, deviceIDs []string, base string, hostID int, reserved map[string]bool) {
	if len(deviceIDs) == 0 || hostID < 2 {
		return
	}
	primaryIP := fmt.Sprintf("%s.%d", base, hostID)
	for i, id := range deviceIDs {
		if id == "" {
			continue
		}
		dev := db.Devices[id]
		if dev == nil {
			dev = &wdttDev{DeviceID: id}
			db.Devices[id] = dev
		}
		if i == 0 {
			dev.RawIP = primaryIP
			reserved[primaryIP] = true
			continue
		}
		if dev.RawIP != "" &&
			dev.RawIP != primaryIP &&
			!reserved[dev.RawIP] &&
			!ipTakenByOther(db, id, dev.RawIP) {
			reserved[dev.RawIP] = true
			continue
		}
		next := nextFreeRawIP(db, base, reserved)
		if next == "" {
			next = primaryIP
		}
		dev.RawIP = next
		reserved[next] = true
	}
}

func ipTakenByOther(db *wdttDB, deviceID, ip string) bool {
	for id, dev := range db.Devices {
		if id == deviceID || dev == nil {
			continue
		}
		if dev.RawIP == ip {
			return true
		}
	}
	return false
}

func nextFreeRawIP(db *wdttDB, base string, reserved map[string]bool) string {
	used := map[string]bool{}
	for ip, on := range reserved {
		if on {
			used[ip] = true
		}
	}
	for _, dev := range db.Devices {
		if dev != nil && dev.RawIP != "" {
			used[dev.RawIP] = true
		}
	}
	for b4 := 2; b4 <= 254; b4++ {
		ip := fmt.Sprintf("%s.%d", base, b4)
		if !used[ip] {
			return ip
		}
	}
	return ""
}

func fatalf(f string, a ...any) {
	fmt.Fprintf(os.Stderr, f+"\n", a...)
	os.Exit(1)
}
