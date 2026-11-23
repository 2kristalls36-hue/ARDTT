package main

import (
	"encoding/json"
	"testing"
)

func TestDeactivatedUserIsNotADirectPeer(t *testing.T) {
	raw := []byte(`{
		"users": [
			{"name": "on", "hostId": 2, "directPublicKey": "pub-on"},
			{"name": "off", "hostId": 3, "directPublicKey": "pub-off", "deactivated": true},
			{"name": "nokey", "hostId": 4},
			{"name": "low", "hostId": 1, "directPublicKey": "pub-low"}
		]
	}`)
	var store storeFile
	if err := json.Unmarshal(raw, &store); err != nil {
		t.Fatal(err)
	}
	if !store.Users[1].Deactivated {
		t.Fatal("deactivated flag was not decoded")
	}
	peers := collectPeers(store.Users, "10.8.0")
	if len(peers) != 1 {
		t.Fatalf("peers: %+v", peers)
	}
	if peers[0].PublicKey != "pub-on" || peers[0].AllowedIPs != "10.8.0.2/32" {
		t.Fatalf("peer: %+v", peers[0])
	}
}

func TestListenPortIsTheContainerPortNotThePublishedOne(t *testing.T) {
	// users.json may carry directPort=51821 (host port after 51820 was busy);
	// awg0 must still listen on the container port Docker forwards to.
	if got := listenPort(0, ""); got != 51820 {
		t.Fatalf("default: got %d, want 51820", got)
	}
	if got := listenPort(0, "51820"); got != 51820 {
		t.Fatalf("env: got %d", got)
	}
	if got := listenPort(0, " 40000 "); got != 40000 {
		t.Fatalf("env trimmed: got %d", got)
	}
	if got := listenPort(51900, "51820"); got != 51900 {
		t.Fatalf("flag wins: got %d", got)
	}
	if got := listenPort(0, "nope"); got != 51820 {
		t.Fatalf("bad env: got %d", got)
	}
}
