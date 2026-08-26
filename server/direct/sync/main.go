package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"text/template"
)

type storeFile struct {
	Config config `json:"config"`
	Users  []user `json:"users"`
}

type config struct {
	DirectPort       int    `json:"directPort"`
	DirectSubnet     string `json:"directSubnet"`
	ServerPrivateKey string `json:"serverPrivateKey"`
	ServerPublicKey  string `json:"serverPublicKey"`
}

type user struct {
	Name            string `json:"name"`
	HostID          int    `json:"hostId"`
	DirectPublicKey string `json:"directPublicKey"`
}

const confTmpl = `[Interface]
PrivateKey = {{.PrivateKey}}
Address = {{.Address}}
ListenPort = {{.ListenPort}}
Jc = 4
Jmin = 40
Jmax = 70
S1 = 0
S2 = 0
S3 = 0
S4 = 0
H1 = 1-100
H2 = 101-200
H3 = 201-300
H4 = 301-400
{{range .Peers}}
[Peer]
PublicKey = {{.PublicKey}}
AllowedIPs = {{.AllowedIPs}}
{{end}}`

func main() {
	usersPath := flag.String("users", "/data/users.json", "provision users.json")
	outPath := flag.String("out", "/etc/amneziawg/awg0.conf", "output AWG conf")
	flag.Parse()

	raw, err := os.ReadFile(*usersPath)
	if err != nil {
		fatalf("read users: %v", err)
	}
	var s storeFile
	if err := json.Unmarshal(raw, &s); err != nil {
		fatalf("parse users: %v", err)
	}
	if s.Config.ServerPrivateKey == "" {
		fatalf("serverPrivateKey missing — start provision first")
	}
	port := s.Config.DirectPort
	if port == 0 {
		port = 51820
	}
	base := subnetBase(s.Config.DirectSubnet)
	if base == "" {
		base = "10.8.0"
	}

	type peer struct {
		PublicKey  string
		AllowedIPs string
	}
	var peers []peer
	for _, u := range s.Users {
		if u.DirectPublicKey == "" || u.HostID < 2 {
			continue
		}
		peers = append(peers, peer{
			PublicKey:  u.DirectPublicKey,
			AllowedIPs: fmt.Sprintf("%s.%d/32", base, u.HostID),
		})
	}

	data := struct {
		PrivateKey string
		Address    string
		ListenPort int
		Peers      []peer
	}{
		PrivateKey: s.Config.ServerPrivateKey,
		Address:    fmt.Sprintf("%s.1/24", base),
		ListenPort: port,
		Peers:      peers,
	}

	if err := os.MkdirAll(filepath.Dir(*outPath), 0o700); err != nil {
		fatalf("mkdir: %v", err)
	}
	f, err := os.OpenFile(*outPath, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o600)
	if err != nil {
		fatalf("open out: %v", err)
	}
	defer f.Close()
	t := template.Must(template.New("awg").Parse(confTmpl))
	if err := t.Execute(f, data); err != nil {
		fatalf("render: %v", err)
	}
	fmt.Printf("[direct-sync] wrote %s (%d peers)\n", *outPath, len(peers))
}

func subnetBase(cidr string) string {
	cidr = strings.TrimSpace(cidr)
	if cidr == "" {
		return ""
	}
	host, _, ok := strings.Cut(cidr, "/")
	if !ok {
		host = cidr
	}
	parts := strings.Split(host, ".")
	if len(parts) != 4 {
		return ""
	}
	return strings.Join(parts[:3], ".")
}

func fatalf(format string, args ...any) {
	fmt.Fprintf(os.Stderr, format+"\n", args...)
	os.Exit(1)
}
