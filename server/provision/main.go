package main

import (
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"log"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"golang.org/x/crypto/curve25519"
)

const (
	defaultDataDir    = "/data"
	defaultListen     = "0.0.0.0:9100"
	defaultDirectCIDR = "10.8.0.0/24"
	defaultBypassCIDR = "10.9.0.0/24"
	defaultDirectPort = 51820
	defaultBypassPort = 56003
	defaultWorkers    = 3
	minHostID         = 2 // .1 reserved for gateway
	maxHostID         = 254
)

type Config struct {
	PublicHost       string `json:"publicHost"`
	DirectPort       int    `json:"directPort"`
	BypassPort       int    `json:"bypassPort"`
	DirectSubnet     string `json:"directSubnet"`
	BypassSubnet     string `json:"bypassSubnet"`
	Workers          int    `json:"workers"`
	ServerPrivateKey string `json:"serverPrivateKey,omitempty"`
	ServerPublicKey  string `json:"serverPublicKey,omitempty"`
}

type User struct {
	Name             string    `json:"name"`
	HostID           int       `json:"hostId"`
	DeviceID         string    `json:"deviceId"`
	Password         string    `json:"password"`
	HideIP           bool      `json:"hideIp"`
	CreatedAt        time.Time `json:"createdAt"`
	DirectPrivateKey string    `json:"directPrivateKey,omitempty"`
	DirectPublicKey  string    `json:"directPublicKey,omitempty"`
	ServerPublicKey  string    `json:"serverPublicKey,omitempty"`
}

type Store struct {
	Config Config `json:"config"`
	Users  []User `json:"users"`
	mu     sync.Mutex
	path   string
}

type Profile struct {
	Name     string `json:"name"`
	DeviceID string `json:"deviceId"`
	HostID   int    `json:"hostId"`
	Prefer   string `json:"prefer"`
	HideIP   bool   `json:"hideIp"`
	Direct   struct {
		Endpoint      string         `json:"endpoint"`
		PrivateKey    string         `json:"privateKey"`
		PeerPublicKey string         `json:"peerPublicKey"`
		Address       string         `json:"address"`
		DNS           []string       `json:"dns"`
		MTU           int            `json:"mtu"`
		AWG           map[string]any `json:"awg"`
	} `json:"direct"`
	Bypass struct {
		Peer      string `json:"peer"`
		Address   string `json:"address"`
		Password  string `json:"password"`
		Workers   int    `json:"workers"`
		Transport string `json:"transport"`
		Mode      string `json:"mode"`
		Dial      string `json:"dial"`
	} `json:"bypass"`
}

func main() {
	dataDir := flag.String("data", envOr("NVPN_DATA", defaultDataDir), "data directory")
	listen := flag.String("listen", envOr("NVPN_PROVISION_LISTEN", defaultListen), "HTTP listen address (health + API)")
	publicHost := flag.String("public-host", envOr("NVPN_PUBLIC_HOST", ""), "public VPS IP/DNS for profiles")
	cmd := flag.String("cmd", "serve", "serve | create-user | list-users | profile")
	name := flag.String("name", "", "user name (create-user / profile)")
	flag.Parse()

	store, err := loadOrInitStore(*dataDir, *publicHost)
	if err != nil {
		log.Fatalf("store: %v", err)
	}

	switch *cmd {
	case "serve":
		if err := runServer(store, *listen); err != nil {
			log.Fatalf("serve: %v", err)
		}
	case "create-user":
		if *name == "" {
			log.Fatal("-name required")
		}
		u, err := store.CreateUser(*name)
		if err != nil {
			log.Fatalf("create-user: %v", err)
		}
		p := store.BuildProfile(u)
		enc := json.NewEncoder(os.Stdout)
		enc.SetIndent("", "  ")
		_ = enc.Encode(p)
	case "list-users":
		enc := json.NewEncoder(os.Stdout)
		enc.SetIndent("", "  ")
		_ = enc.Encode(store.Users)
	case "profile":
		if *name == "" {
			log.Fatal("-name required")
		}
		u, err := store.FindUser(*name)
		if err != nil {
			log.Fatalf("profile: %v", err)
		}
		enc := json.NewEncoder(os.Stdout)
		enc.SetIndent("", "  ")
		_ = enc.Encode(store.BuildProfile(u))
	default:
		log.Fatalf("unknown -cmd %q", *cmd)
	}
}

func runServer(store *Store, listen string) error {
	mux := http.NewServeMux()
	mux.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"ok":true,"service":"provision"}`))
	})
	mux.HandleFunc("/v1/users", func(w http.ResponseWriter, r *http.Request) {
		switch r.Method {
		case http.MethodGet:
			store.mu.Lock()
			defer store.mu.Unlock()
			writeJSON(w, store.Users)
		case http.MethodPost:
			var body struct {
				Name string `json:"name"`
			}
			if err := json.NewDecoder(r.Body).Decode(&body); err != nil || body.Name == "" {
				http.Error(w, `{"error":"name required"}`, http.StatusBadRequest)
				return
			}
			u, err := store.CreateUser(body.Name)
			if err != nil {
				http.Error(w, fmt.Sprintf(`{"error":%q}`, err.Error()), http.StatusConflict)
				return
			}
			writeJSON(w, store.BuildProfile(u))
		default:
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		}
	})
	mux.HandleFunc("/v1/profile/", func(w http.ResponseWriter, r *http.Request) {
		name := filepath.Base(r.URL.Path)
		u, err := store.FindUser(name)
		if err != nil {
			http.Error(w, `{"error":"not found"}`, http.StatusNotFound)
			return
		}
		writeJSON(w, store.BuildProfile(u))
	})
	mux.HandleFunc("/v1/hide-ip", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost && r.Method != http.MethodPut {
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
			return
		}
		var body struct {
			DeviceID string `json:"deviceId"`
			Name     string `json:"name"`
			HideIP   bool   `json:"hideIp"`
		}
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			http.Error(w, `{"error":"bad json"}`, http.StatusBadRequest)
			return
		}
		if body.DeviceID == "" && body.Name == "" {
			http.Error(w, `{"error":"deviceId or name required"}`, http.StatusBadRequest)
			return
		}
		u, err := store.SetHideIP(body.DeviceID, body.Name, body.HideIP)
		if err != nil {
			http.Error(w, fmt.Sprintf(`{"error":%q}`, err.Error()), http.StatusNotFound)
			return
		}
		store.mu.Lock()
		directBase := subnetBase(store.Config.DirectSubnet)
		bypassBase := subnetBase(store.Config.BypassSubnet)
		store.mu.Unlock()
		writeJSON(w, map[string]any{
			"ok":       true,
			"name":     u.Name,
			"deviceId": u.DeviceID,
			"hostId":   u.HostID,
			"hideIp":   u.HideIP,
			"directIp": fmt.Sprintf("%s.%d", directBase, u.HostID),
			"bypassIp": fmt.Sprintf("%s.%d", bypassBase, u.HostID),
		})
	})
	// Public egress IP as seen by the Internet for this device's VPN traffic.
	// When hideIp is on, probe via warp0 (Cloudflare WARP). Otherwise VPS WAN.
	mux.HandleFunc("/v1/egress-ip", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet && r.Method != http.MethodPost {
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
			return
		}
		deviceID := r.URL.Query().Get("deviceId")
		name := r.URL.Query().Get("name")
		if r.Method == http.MethodPost {
			var body struct {
				DeviceID string `json:"deviceId"`
				Name     string `json:"name"`
			}
			if err := json.NewDecoder(r.Body).Decode(&body); err == nil {
				if deviceID == "" {
					deviceID = body.DeviceID
				}
				if name == "" {
					name = body.Name
				}
			}
		}
		viaWarp := false
		if deviceID != "" || name != "" {
			if u, err := store.FindUserByDeviceOrName(deviceID, name); err == nil {
				viaWarp = u.HideIP
			}
		}
		// Client may force WARP probe right after toggle (users.json already flipped,
		// but allow explicit override for races / diagnostics).
		switch strings.ToLower(r.URL.Query().Get("viaWarp")) {
		case "1", "true", "yes":
			viaWarp = true
		case "0", "false", "no":
			viaWarp = false
		}
		ip, err := probeEgressIP(viaWarp)
		if err != nil {
			http.Error(w, fmt.Sprintf(`{"error":%q}`, err.Error()), http.StatusBadGateway)
			return
		}
		writeJSON(w, map[string]any{
			"ok":      true,
			"ip":      ip,
			"viaWarp": viaWarp,
		})
	})

	ln, err := net.Listen("tcp", listen)
	if err != nil {
		return err
	}
	log.Printf("provision listening on %s (data=%s)", listen, store.path)
	return http.Serve(ln, mux)
}

func loadOrInitStore(dataDir, publicHost string) (*Store, error) {
	if err := os.MkdirAll(dataDir, 0o700); err != nil {
		return nil, err
	}
	path := filepath.Join(dataDir, "users.json")
	s := &Store{path: path}
	raw, err := os.ReadFile(path)
	if err == nil {
		if err := json.Unmarshal(raw, s); err != nil {
			return nil, err
		}
	} else if !errors.Is(err, os.ErrNotExist) {
		return nil, err
	}

	if s.Config.DirectSubnet == "" {
		s.Config.DirectSubnet = defaultDirectCIDR
	}
	if s.Config.BypassSubnet == "" {
		s.Config.BypassSubnet = defaultBypassCIDR
	}
	if s.Config.DirectPort == 0 {
		s.Config.DirectPort = defaultDirectPort
	}
	if s.Config.BypassPort == 0 {
		s.Config.BypassPort = defaultBypassPort
	}
	if s.Config.Workers == 0 {
		s.Config.Workers = defaultWorkers
	}
	if publicHost != "" {
		s.Config.PublicHost = publicHost
	}
	if s.Users == nil {
		s.Users = []User{}
	}
	if err := s.ensureServerKeys(); err != nil {
		return nil, err
	}
	if err := s.ensureUserKeys(); err != nil {
		return nil, err
	}
	if err := s.save(); err != nil {
		return nil, err
	}
	_ = os.WriteFile(filepath.Join(dataDir, "config.json"), mustJSON(s.Config), 0o600)
	_ = os.WriteFile(filepath.Join(dataDir, "server_public.key"), []byte(s.Config.ServerPublicKey+"\n"), 0o644)
	return s, nil
}

func (s *Store) ensureServerKeys() error {
	if s.Config.ServerPrivateKey != "" && s.Config.ServerPublicKey != "" {
		return nil
	}
	priv, pub, err := generateWGKeyPair()
	if err != nil {
		return err
	}
	s.Config.ServerPrivateKey = priv
	s.Config.ServerPublicKey = pub
	log.Printf("generated AWG server keypair pub=%s…", pub[:8])
	return nil
}

func (s *Store) ensureUserKeys() error {
	changed := false
	for i := range s.Users {
		u := &s.Users[i]
		if u.DirectPrivateKey == "" || u.DirectPublicKey == "" {
			priv, pub, err := generateWGKeyPair()
			if err != nil {
				return err
			}
			u.DirectPrivateKey = priv
			u.DirectPublicKey = pub
			changed = true
		}
		if u.ServerPublicKey != s.Config.ServerPublicKey {
			u.ServerPublicKey = s.Config.ServerPublicKey
			changed = true
		}
	}
	if changed {
		log.Printf("filled missing AWG user keys")
	}
	return nil
}

func (s *Store) save() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.saveLocked()
}

func (s *Store) saveLocked() error {
	tmp := s.path + ".tmp"
	raw, err := json.MarshalIndent(struct {
		Config Config `json:"config"`
		Users  []User `json:"users"`
	}{s.Config, s.Users}, "", "  ")
	if err != nil {
		return err
	}
	if err := os.WriteFile(tmp, raw, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, s.path)
}

func (s *Store) CreateUser(name string) (User, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, u := range s.Users {
		if u.Name == name {
			return User{}, fmt.Errorf("user %q already exists", name)
		}
	}
	id, err := s.nextHostIDLocked()
	if err != nil {
		return User{}, err
	}
	pass, err := randomToken(18)
	if err != nil {
		return User{}, err
	}
	dev, err := randomToken(12)
	if err != nil {
		return User{}, err
	}
	priv, pub, err := generateWGKeyPair()
	if err != nil {
		return User{}, err
	}
	u := User{
		Name:             name,
		HostID:           id,
		DeviceID:         "dev-" + dev,
		Password:         pass,
		CreatedAt:        time.Now().UTC(),
		DirectPrivateKey: priv,
		DirectPublicKey:  pub,
		ServerPublicKey:  s.Config.ServerPublicKey,
	}
	s.Users = append(s.Users, u)
	if err := s.saveLocked(); err != nil {
		return User{}, err
	}
	log.Printf("created user %q host_id=%d", name, id)
	return u, nil
}

func (s *Store) FindUser(name string) (User, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, u := range s.Users {
		if u.Name == name {
			return u, nil
		}
	}
	return User{}, fmt.Errorf("not found")
}

func (s *Store) FindUserByDeviceOrName(deviceID, name string) (User, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, u := range s.Users {
		if deviceID != "" && u.DeviceID == deviceID {
			return u, nil
		}
		if name != "" && u.Name == name {
			return u, nil
		}
	}
	return User{}, fmt.Errorf("not found")
}

// probeEgressIP returns the VPS (or WARP) public IPv4 as seen by ifconfig-style services.
// viaWarp binds curl to warp0 so the answer is the Cloudflare egress IP.
func probeEgressIP(viaWarp bool) (string, error) {
	endpoints := []string{
		"https://api.ipify.org",
		"https://ifconfig.me/ip",
		"https://icanhazip.com",
	}
	var last error
	for _, url := range endpoints {
		args := []string{"-sS", "--max-time", "5", "-A", "curl/8.0", "-H", "Accept: text/plain"}
		if viaWarp {
			args = append(args, "--interface", "warp0")
		}
		args = append(args, url)
		out, err := exec.Command("curl", args...).CombinedOutput()
		if err != nil {
			last = fmt.Errorf("%s: %w (%s)", url, err, strings.TrimSpace(string(out)))
			continue
		}
		ip := strings.TrimSpace(string(out))
		if idx := strings.IndexByte(ip, '\n'); idx >= 0 {
			ip = strings.TrimSpace(ip[:idx])
		}
		if looksLikeIP(ip) {
			return ip, nil
		}
		last = fmt.Errorf("%s: not an ip %q", url, ip)
	}
	if last == nil {
		last = errors.New("empty")
	}
	return "", last
}

func looksLikeIP(value string) bool {
	if value == "" || len(value) > 45 {
		return false
	}
	if net.ParseIP(value) != nil {
		return true
	}
	return false
}

// SetHideIP flips per-user WARP egress. Identified by deviceId (preferred) or name.
func (s *Store) SetHideIP(deviceID, name string, hide bool) (User, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	for i := range s.Users {
		u := &s.Users[i]
		if (deviceID != "" && u.DeviceID == deviceID) || (name != "" && u.Name == name) {
			u.HideIP = hide
			if err := s.saveLocked(); err != nil {
				return User{}, err
			}
			log.Printf("hideIp=%v user=%q host_id=%d", hide, u.Name, u.HostID)
			return *u, nil
		}
	}
	return User{}, fmt.Errorf("not found")
}

func (s *Store) nextHostIDLocked() (int, error) {
	used := map[int]bool{}
	for _, u := range s.Users {
		used[u.HostID] = true
	}
	for id := minHostID; id <= maxHostID; id++ {
		if !used[id] {
			return id, nil
		}
	}
	return 0, errors.New("host_id pool exhausted")
}

func (s *Store) BuildProfile(u User) Profile {
	cfg := s.Config
	host := cfg.PublicHost
	if host == "" {
		host = "YOUR_VPS_IP"
	}
	directBase := subnetBase(cfg.DirectSubnet)
	bypassBase := subnetBase(cfg.BypassSubnet)

	var p Profile
	p.Name = u.Name
	p.DeviceID = u.DeviceID
	p.HostID = u.HostID
	p.Prefer = "direct"
	p.HideIP = u.HideIP
	p.Direct.Endpoint = fmt.Sprintf("%s:%d", host, cfg.DirectPort)
	p.Direct.PrivateKey = u.DirectPrivateKey
	p.Direct.PeerPublicKey = cfg.ServerPublicKey
	p.Direct.Address = fmt.Sprintf("%s.%d/32", directBase, u.HostID)
	p.Direct.DNS = []string{fmt.Sprintf("%s.1", directBase)}
	p.Direct.MTU = 1280
	p.Direct.AWG = map[string]any{
		"Jc": 4, "Jmin": 40, "Jmax": 70,
		"S1": 0, "S2": 0, "S3": 0, "S4": 0,
		"H1": "1-100", "H2": "101-200", "H3": "201-300", "H4": "301-400",
	}
	p.Bypass.Peer = fmt.Sprintf("%s:%d", host, cfg.BypassPort)
	p.Bypass.Address = fmt.Sprintf("%s.%d/32", bypassBase, u.HostID)
	p.Bypass.Password = u.Password
	p.Bypass.Workers = cfg.Workers
	p.Bypass.Transport = "tcp"
	p.Bypass.Mode = "raw"
	p.Bypass.Dial = "auto"
	return p
}

func generateWGKeyPair() (privB64, pubB64 string, err error) {
	var priv [32]byte
	if _, err = rand.Read(priv[:]); err != nil {
		return "", "", err
	}
	priv[0] &= 248
	priv[31] &= 127
	priv[31] |= 64
	var pub [32]byte
	curve25519.ScalarBaseMult(&pub, &priv)
	return base64.StdEncoding.EncodeToString(priv[:]), base64.StdEncoding.EncodeToString(pub[:]), nil
}

func subnetBase(cidr string) string {
	ip, _, err := net.ParseCIDR(cidr)
	if err != nil {
		return "10.8.0"
	}
	v4 := ip.To4()
	if v4 == nil {
		return "10.8.0"
	}
	return fmt.Sprintf("%d.%d.%d", v4[0], v4[1], v4[2])
}

func randomToken(n int) (string, error) {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return base64.RawURLEncoding.EncodeToString(b), nil
}

func writeJSON(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json")
	enc := json.NewEncoder(w)
	enc.SetIndent("", "  ")
	_ = enc.Encode(v)
}

func mustJSON(v any) []byte {
	b, _ := json.MarshalIndent(v, "", "  ")
	return append(b, '\n')
}

func envOr(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}
