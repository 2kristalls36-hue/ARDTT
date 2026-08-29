package main

import "testing"

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
