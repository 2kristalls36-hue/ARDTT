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
