package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"path/filepath"
)

// Converts nonameVPN users.json → WDTT passwords.json (+ device RawIP by host_id).

type nvpnStore struct {
	Config struct {
		BypassSubnet string `json:"bypassSubnet"`
	} `json:"config"`
	Users []struct {
		Name     string `json:"name"`
		HostID   int    `json:"hostId"`
		DeviceID string `json:"deviceId"`
		Password string `json:"password"`
	} `json:"users"`
}

type wdttDB struct {
	Passwords map[string]*wdttPass `json:"passwords"`
	Devices   map[string]*wdttDev  `json:"devices"`
}

type wdttPass struct {
	Label      string   `json:"label,omitempty"`
	DeviceID   string   `json:"device_id"`
	DeviceIDs  []string `json:"device_ids"`
	MaxDevices int      `json:"max_devices"`
	ExpiresAt  int64    `json:"expires_at"`
	Ports      string   `json:"ports,omitempty"`
}

type wdttDev struct {
	DeviceID   string `json:"device_id"`
	IP         string `json:"ip,omitempty"`
	RawIP      string `json:"raw_ip,omitempty"`
	RawOwnerID string `json:"raw_owner_id,omitempty"`
}

func main() {
	usersPath := flag.String("users", "/data/users.json", "nvpn users.json")
	outPath := flag.String("out", "/etc/wdtt/passwords.json", "wdtt passwords.json")
	flag.Parse()

	raw, err := os.ReadFile(*usersPath)
	if err != nil {
		fatalf("%v", err)
	}
	var s nvpnStore
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

	base := "10.9.0"
	keepPass := map[string]bool{}
	for _, u := range s.Users {
		if u.Password == "" || u.DeviceID == "" || u.HostID < 2 {
			continue
		}
		keepPass[u.Password] = true
		entry := db.Passwords[u.Password]
		if entry == nil {
			entry = &wdttPass{}
			db.Passwords[u.Password] = entry
		}
		entry.Label = u.Name
		entry.DeviceID = u.DeviceID
		entry.DeviceIDs = []string{u.DeviceID}
		entry.MaxDevices = 1
		entry.ExpiresAt = 0
		entry.Ports = "raw"

		rawIP := fmt.Sprintf("%s.%d", base, u.HostID)
		dev := db.Devices[u.DeviceID]
		if dev == nil {
			dev = &wdttDev{DeviceID: u.DeviceID}
			db.Devices[u.DeviceID] = dev
		}
		dev.RawIP = rawIP
	}

	// Drop passwords no longer in nvpn store (keep device history otherwise)
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
	fmt.Printf("[bypass-sync] wrote %s (%d passwords)\n", *outPath, len(db.Passwords))
}

func fatalf(f string, a ...any) {
	fmt.Fprintf(os.Stderr, f+"\n", a...)
	os.Exit(1)
}
