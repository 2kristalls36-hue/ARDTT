package main

import (
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestSanitizeDeviceModel(t *testing.T) {
	if got := sanitizeDeviceModel("  Pixel 8\n"); got != "Pixel 8" {
		t.Fatalf("trim control chars: %q", got)
	}
	if got := sanitizeDeviceModel(""); got != "" {
		t.Fatalf("empty: %q", got)
	}
	long := make([]rune, 90)
	for i := range long {
		long[i] = 'a'
	}
	if got := sanitizeDeviceModel(string(long)); len([]rune(got)) != 80 {
		t.Fatalf("max length: %d", len([]rune(got)))
	}
}

func TestPruneDeviceModels(t *testing.T) {
	u := User{
		DeviceIDs: []string{"dev-keep"},
		DeviceModels: map[string]string{
			"dev-keep": "Pixel 8",
			"dev-gone": "Old Phone",
		},
	}
	if !pruneDeviceModels(&u) {
		t.Fatal("expected prune")
	}
	if _, ok := u.DeviceModels["dev-gone"]; ok {
		t.Fatal("stale model kept")
	}
	if u.DeviceModels["dev-keep"] != "Pixel 8" {
		t.Fatalf("kept model: %#v", u.DeviceModels)
	}
}

func TestPruneDeviceAppVersions(t *testing.T) {
	u := User{
		DeviceIDs: []string{"dev-keep"},
		DeviceAppVersions: map[string]string{
			"dev-keep": "0.5.112",
			"dev-gone": "0.5.80",
		},
		DeviceAppVersionCodes: map[string]int{
			"dev-keep": 130,
			"dev-gone": 100,
		},
	}
	if !pruneDeviceAppVersions(&u) {
		t.Fatal("expected prune versions")
	}
	if _, ok := u.DeviceAppVersions["dev-gone"]; ok {
		t.Fatal("stale version kept")
	}
	if u.DeviceAppVersions["dev-keep"] != "0.5.112" {
		t.Fatalf("kept version: %#v", u.DeviceAppVersions)
	}
	if !pruneDeviceAppVersionCodes(&u) {
		t.Fatal("expected prune codes")
	}
	if _, ok := u.DeviceAppVersionCodes["dev-gone"]; ok {
		t.Fatal("stale code kept")
	}
	if u.DeviceAppVersionCodes["dev-keep"] != 130 {
		t.Fatalf("kept code: %#v", u.DeviceAppVersionCodes)
	}
}

func TestPrimaryAppVersionPrefersBoundDevice(t *testing.T) {
	u := User{
		DeviceID:  "dev-b",
		DeviceIDs: []string{"dev-a", "dev-b"},
		DeviceAppVersions: map[string]string{
			"dev-a": "0.5.100",
			"dev-b": "0.5.112",
		},
		DeviceAppVersionCodes: map[string]int{
			"dev-a": 120,
			"dev-b": 130,
		},
	}
	name, code := primaryAppVersion(u)
	if name != "0.5.112" || code != 130 {
		t.Fatalf("got %q %d", name, code)
	}
}

func TestTouchPresenceStoresDeviceModelAndAppVersion(t *testing.T) {
	dir := t.TempDir()
	s := &Store{
		path: filepath.Join(dir, "users.json"),
		Users: []User{{
			Name:       "alice",
			DeviceID:   "dev-abc",
			DeviceIDs:  []string{"dev-abc"},
			MaxDevices: 1,
		}},
	}
	u, err := s.TouchPresence("dev-abc", "alice", "203.0.113.10", "Pixel 8", "0.5.113-device-id-fallback", 131)
	if err != nil {
		t.Fatal(err)
	}
	if u.DeviceModels["dev-abc"] != "Pixel 8" {
		t.Fatalf("model: %#v", u.DeviceModels)
	}
	if u.DeviceAppVersions["dev-abc"] != "0.5.113-device-id-fallback" {
		t.Fatalf("version: %#v", u.DeviceAppVersions)
	}
	if u.DeviceAppVersionCodes["dev-abc"] != 131 {
		t.Fatalf("code: %#v", u.DeviceAppVersionCodes)
	}
	if u.LastExternalIP != "203.0.113.10" {
		t.Fatalf("ip: %q", u.LastExternalIP)
	}
}

func TestTouchPresenceUpdatesAppVersion(t *testing.T) {
	dir := t.TempDir()
	s := &Store{
		path: filepath.Join(dir, "users.json"),
		Users: []User{{
			Name:       "alice",
			DeviceID:   "dev-abc",
			DeviceIDs:  []string{"dev-abc"},
			MaxDevices: 1,
		}},
	}
	if _, err := s.TouchPresence("dev-abc", "alice", "", "Pixel 8", "0.5.199", 217); err != nil {
		t.Fatal(err)
	}
	u, err := s.TouchPresence("dev-abc", "alice", "", "Pixel 8", "0.5.201", 219)
	if err != nil {
		t.Fatal(err)
	}
	if u.DeviceAppVersions["dev-abc"] != "0.5.201" {
		t.Fatalf("version: %#v", u.DeviceAppVersions)
	}
	if u.DeviceAppVersionCodes["dev-abc"] != 219 {
		t.Fatalf("code: %#v", u.DeviceAppVersionCodes)
	}
}

func TestTouchPresenceHeartbeatDoesNotRewriteUsersJson(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "users.json")
	s := &Store{
		path: path,
		Users: []User{{
			Name:       "alice",
			DeviceID:   "dev-abc",
			DeviceIDs:  []string{"dev-abc"},
			MaxDevices: 1,
			LastSeenAt: time.Now().Unix(),
		}},
	}
	if _, err := s.TouchPresence("dev-abc", "alice", "", "Pixel 8", "0.5.201", 219); err != nil {
		t.Fatal(err)
	}
	st1, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := s.TouchPresence("dev-abc", "alice", "", "Pixel 8", "0.5.201", 219); err != nil {
		t.Fatal(err)
	}
	st2, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	if !st1.ModTime().Equal(st2.ModTime()) {
		t.Fatal("heartbeat rewrote users.json")
	}
}
