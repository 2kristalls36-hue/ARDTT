package main

import (
	"os"
	"path/filepath"
	"testing"
)

func TestUpdateUserRename(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "users.json")
	s := &Store{
		path: path,
		Users: []User{
			{Name: "old", HostID: 2, MaxDevices: 1},
			{Name: "other", HostID: 3, MaxDevices: 1},
		},
	}
	if err := s.save(); err != nil {
		t.Fatal(err)
	}
	traffic := []byte(`{"byName":{"old":{"downBytes":10,"upBytes":2},"other":{"downBytes":1,"upBytes":1}}}`)
	if err := os.WriteFile(filepath.Join(dir, "bypass-traffic.json"), traffic, 0o600); err != nil {
		t.Fatal(err)
	}
	next := "new"
	u, err := s.UpdateUser("old", nil, nil, nil, false, nil, &next)
	if err != nil {
		t.Fatal(err)
	}
	if u.Name != "new" {
		t.Fatalf("renamed: %q", u.Name)
	}
	if _, err := s.FindUser("old"); err == nil {
		t.Fatal("old name still present")
	}
	found, err := s.FindUser("new")
	if err != nil {
		t.Fatal(err)
	}
	if found.HostID != 2 {
		t.Fatalf("host id: %d", found.HostID)
	}
	dup := "other"
	if _, err := s.UpdateUser("new", nil, nil, nil, false, nil, &dup); err == nil {
		t.Fatal("expected duplicate name error")
	}
}

func TestUpdateUserToggleDeactivatedAndLimits(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "users.json")
	s := &Store{
		path: path,
		Users: []User{
			{Name: "alice", HostID: 2, MaxDevices: 1},
		},
	}
	if err := s.save(); err != nil {
		t.Fatal(err)
	}

	off := true
	u, err := s.UpdateUser("alice", nil, nil, &off, false, nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	if !u.Deactivated {
		t.Fatal("expected deactivated after turn-off")
	}

	on := false
	u, err = s.UpdateUser("alice", nil, nil, &on, false, nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	if u.Deactivated {
		t.Fatal("expected active after turn-on")
	}

	maxDevices := 3
	limit := int64(2 * 1024 * 1024 * 1024)
	u, err = s.UpdateUser("alice", &maxDevices, nil, nil, false, &limit, nil)
	if err != nil {
		t.Fatal(err)
	}
	if u.MaxDevices != 3 {
		t.Fatalf("maxDevices: %d", u.MaxDevices)
	}
	if u.TrafficLimitBytes != limit {
		t.Fatalf("traffic: %d", u.TrafficLimitBytes)
	}

	if err := s.DeleteUser("alice"); err != nil {
		t.Fatal(err)
	}
	if _, err := s.FindUser("alice"); err == nil {
		t.Fatal("deleted user still present")
	}
}

func TestBuildProfileIncludesRemainingTraffic(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "users.json")
	s := &Store{
		path: path,
		Users: []User{
			{Name: "alice", HostID: 2, MaxDevices: 1, TrafficLimitBytes: 2000},
		},
		Config: Config{PublicHost: "1.2.3.4", DirectPort: 51820, BypassPort: 56003},
	}
	if err := s.save(); err != nil {
		t.Fatal(err)
	}
	traffic := []byte(`{"byName":{"alice":{"downBytes":400,"upBytes":100}}}`)
	if err := os.WriteFile(filepath.Join(dir, "bypass-traffic.json"), traffic, 0o600); err != nil {
		t.Fatal(err)
	}
	p := s.BuildProfile(s.Users[0])
	if p.TrafficLimitBytes != 2000 {
		t.Fatalf("limit: %d", p.TrafficLimitBytes)
	}
	if p.UsedBytes != 500 {
		t.Fatalf("used: %d", p.UsedBytes)
	}
}
