package main

import "testing"

func TestAssignDeviceRawIPsGivesSecondaryDeviceOwnAddress(t *testing.T) {
	db := &wdttDB{Devices: map[string]*wdttDev{}}
	reserved := reservedPrimaryIPs([]int{5, 8}, "10.9.0")
	assignDeviceRawIPs(db, []string{"phone-a", "phone-b"}, "10.9.0", 5, reserved)
	if db.Devices["phone-a"].RawIP != "10.9.0.5" {
		t.Fatalf("primary=%q", db.Devices["phone-a"].RawIP)
	}
	if db.Devices["phone-b"].RawIP == "" || db.Devices["phone-b"].RawIP == "10.9.0.5" {
		t.Fatalf("secondary shared primary IP: %q", db.Devices["phone-b"].RawIP)
	}
	if db.Devices["phone-b"].RawIP == "10.9.0.8" {
		t.Fatal("secondary took another profile hostId")
	}
}

func TestAssignDeviceRawIPsDoesNotKeepForeignHostId(t *testing.T) {
	db := &wdttDB{Devices: map[string]*wdttDev{
		"phone-b": {DeviceID: "phone-b", RawIP: "10.9.0.8"},
	}}
	reserved := reservedPrimaryIPs([]int{5, 8}, "10.9.0")
	assignDeviceRawIPs(db, []string{"phone-a", "phone-b"}, "10.9.0", 5, reserved)
	if db.Devices["phone-b"].RawIP == "10.9.0.8" {
		t.Fatal("kept another profile hostId as secondary")
	}
	if db.Devices["phone-b"].RawIP == "10.9.0.1" {
		t.Fatal("secondary took gateway")
	}
}

func TestAssignDeviceRawIPsKeepsStableSecondaryAddress(t *testing.T) {
	db := &wdttDB{Devices: map[string]*wdttDev{
		"phone-a": {DeviceID: "phone-a", RawIP: "10.9.0.5"},
		"phone-b": {DeviceID: "phone-b", RawIP: "10.9.0.40"},
	}}
	reserved := reservedPrimaryIPs([]int{5}, "10.9.0")
	assignDeviceRawIPs(db, []string{"phone-a", "phone-b"}, "10.9.0", 5, reserved)
	if db.Devices["phone-b"].RawIP != "10.9.0.40" {
		t.Fatalf("reshuffled stable IP: %q", db.Devices["phone-b"].RawIP)
	}
}

func TestAssignDeviceRawIPsMigratesSharedHostId(t *testing.T) {
	db := &wdttDB{Devices: map[string]*wdttDev{
		"phone-a": {DeviceID: "phone-a", RawIP: "10.9.0.5"},
		"phone-b": {DeviceID: "phone-b", RawIP: "10.9.0.5"},
	}}
	reserved := reservedPrimaryIPs([]int{5}, "10.9.0")
	assignDeviceRawIPs(db, []string{"phone-a", "phone-b"}, "10.9.0", 5, reserved)
	if db.Devices["phone-a"].RawIP != "10.9.0.5" {
		t.Fatalf("primary moved: %q", db.Devices["phone-a"].RawIP)
	}
	if db.Devices["phone-b"].RawIP == "10.9.0.5" {
		t.Fatal("secondary still shares hostId IP")
	}
}

func TestWorkersOfOneDeviceKeepSameIP(t *testing.T) {
	db := &wdttDB{Devices: map[string]*wdttDev{}}
	reserved := reservedPrimaryIPs([]int{5}, "10.9.0")
	assignDeviceRawIPs(db, []string{"phone-a"}, "10.9.0", 5, reserved)
	first := db.Devices["phone-a"].RawIP
	assignDeviceRawIPs(db, []string{"phone-a"}, "10.9.0", 5, reserved)
	if db.Devices["phone-a"].RawIP != first {
		t.Fatalf("single device IP changed %q → %q", first, db.Devices["phone-a"].RawIP)
	}
}
