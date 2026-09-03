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
	"net/url"
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
	Name              string            `json:"name"`
	HostID            int               `json:"hostId"`
	DeviceID          string            `json:"deviceId"`
	DeviceIDs         []string          `json:"deviceIds,omitempty"`
	MaxDevices        int               `json:"maxDevices"`
	Password          string            `json:"password"`
	HideIP            bool              `json:"hideIp"`
	ExpiresAt         int64             `json:"expiresAt"` // unix seconds; 0 = no expiry
	Deactivated       bool              `json:"deactivated"`
	TrafficLimitBytes int64             `json:"trafficLimitBytes,omitempty"` // 0 = unlimited
	LastSeenAt            int64             `json:"lastSeenAt,omitempty"`
	LastExternalIP        string            `json:"lastExternalIp,omitempty"`
	DeviceModels          map[string]string `json:"deviceModels,omitempty"`
	DeviceAppVersions     map[string]string `json:"deviceAppVersions,omitempty"`
	DeviceAppVersionCodes map[string]int    `json:"deviceAppVersionCodes,omitempty"`
	CreatedAt         time.Time         `json:"createdAt"`
	DirectPrivateKey  string            `json:"directPrivateKey,omitempty"`
	DirectPublicKey   string            `json:"directPublicKey,omitempty"`
	ServerPublicKey   string            `json:"serverPublicKey,omitempty"`
}

const onlineGraceSeconds = 120

type Store struct {
	Config Config `json:"config"`
	Users  []User `json:"users"`
	mu     sync.Mutex
	path   string
}

type Profile struct {
	Name        string `json:"name"`
	DeviceID    string `json:"deviceId"`
	HostID      int    `json:"hostId"`
	Prefer      string `json:"prefer"`
	HideIP      bool   `json:"hideIp"`
	ExpiresAt   int64  `json:"expiresAt"`
	Deactivated bool   `json:"deactivated"`
	MaxDevices  int    `json:"maxDevices"`
	Direct      struct {
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

// UserPublic is the admin list/detail shape (includes online presence).
type UserPublic struct {
	Name              string            `json:"name"`
	HostID            int               `json:"hostId"`
	DeviceID          string            `json:"deviceId"`
	DeviceIDs         []string          `json:"deviceIds"`
	MaxDevices        int               `json:"maxDevices"`
	HideIP            bool              `json:"hideIp"`
	ExpiresAt         int64             `json:"expiresAt"`
	Deactivated       bool              `json:"deactivated"`
	CreatedAt         string            `json:"createdAt"`
	LastSeenAt        int64             `json:"lastSeenAt"`
	LastExternalIP    string            `json:"lastExternalIp"`
	Online            bool              `json:"online"`
	OfflineForSec     int64             `json:"offlineForSec"`
	DownBytes         int64             `json:"downBytes"`
	UpBytes           int64             `json:"upBytes"`
	TrafficLimitBytes     int64             `json:"trafficLimitBytes"`
	DeviceModels          map[string]string `json:"deviceModels,omitempty"`
	AppVersion            string            `json:"appVersion,omitempty"`
	AppVersionCode        int               `json:"appVersionCode,omitempty"`
	DeviceAppVersions     map[string]string `json:"deviceAppVersions,omitempty"`
	DeviceAppVersionCodes map[string]int    `json:"deviceAppVersionCodes,omitempty"`
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
		u, err := store.CreateUser(*name, 0, 1)
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
		cascade := envOr("NVPN_CASCADE_ENABLED", "0") == "1"
		peer := strings.TrimSpace(os.Getenv("NVPN_CASCADE_PEER_ENDPOINT"))
		cascadeHost := ""
		if cascade {
			cascadeHost = cascadeHostFromPeer(peer)
		}
		writeJSON(w, map[string]any{
			"ok":            true,
			"service":       "provision",
			"deployVersion": resolveDeployVersion(),
			"role":          strings.TrimSpace(envOr("NVPN_ROLE", "entry")),
			"cascade":       cascade,
			"cascadePeer":   peer,
			"cascadeHost":   cascadeHost,
		})
	})
	mux.HandleFunc("/v1/users", func(w http.ResponseWriter, r *http.Request) {
		switch r.Method {
		case http.MethodGet:
			writeJSON(w, store.ListUsersPublic())
		case http.MethodPost:
			var body struct {
				Name       string `json:"name"`
				Days       int    `json:"days"`
				MaxDevices int    `json:"maxDevices"`
			}
			if err := json.NewDecoder(r.Body).Decode(&body); err != nil || body.Name == "" {
				http.Error(w, `{"error":"name required"}`, http.StatusBadRequest)
				return
			}
			u, err := store.CreateUser(body.Name, body.Days, body.MaxDevices)
			if err != nil {
				http.Error(w, fmt.Sprintf(`{"error":%q}`, err.Error()), http.StatusConflict)
				return
			}
			writeJSON(w, store.BuildProfile(u))
		default:
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		}
	})
	mux.HandleFunc("/v1/users/update", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost && r.Method != http.MethodPut && r.Method != http.MethodPatch {
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
			return
		}
		var body struct {
			Name              string `json:"name"`
			NewName           *string `json:"newName"`
			MaxDevices        *int   `json:"maxDevices"`
			Days              *int   `json:"days"`
			Deactivated       *bool  `json:"deactivated"`
			ClearDevices      bool   `json:"clearDevices"`
			TrafficLimitBytes *int64 `json:"trafficLimitBytes"`
			TrafficLimitGb    *int   `json:"trafficLimitGb"`
		}
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil || body.Name == "" {
			http.Error(w, `{"error":"name required"}`, http.StatusBadRequest)
			return
		}
		var limitBytes *int64
		if body.TrafficLimitBytes != nil {
			limitBytes = body.TrafficLimitBytes
		} else if body.TrafficLimitGb != nil {
			v := int64(*body.TrafficLimitGb) * 1024 * 1024 * 1024
			if *body.TrafficLimitGb <= 0 {
				v = 0
			}
			limitBytes = &v
		}
		u, err := store.UpdateUser(body.Name, body.MaxDevices, body.Days, body.Deactivated, body.ClearDevices, limitBytes, body.NewName)
		if err != nil {
			http.Error(w, fmt.Sprintf(`{"error":%q}`, err.Error()), http.StatusNotFound)
			return
		}
		writeJSON(w, store.ToPublic(u))
	})
	mux.HandleFunc("/v1/users/unbind-device", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
			return
		}
		var body struct {
			Name     string `json:"name"`
			DeviceID string `json:"deviceId"`
		}
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil || body.Name == "" || body.DeviceID == "" {
			http.Error(w, `{"error":"name and deviceId required"}`, http.StatusBadRequest)
			return
		}
		u, err := store.UnbindDevice(body.Name, body.DeviceID)
		if err != nil {
			http.Error(w, fmt.Sprintf(`{"error":%q}`, err.Error()), http.StatusNotFound)
			return
		}
		writeJSON(w, store.ToPublic(u))
	})
	mux.HandleFunc("/v1/users/delete", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost && r.Method != http.MethodDelete {
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
			return
		}
		var body struct {
			Name string `json:"name"`
		}
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil || strings.TrimSpace(body.Name) == "" {
			http.Error(w, `{"error":"name required"}`, http.StatusBadRequest)
			return
		}
		if err := store.DeleteUser(body.Name); err != nil {
			http.Error(w, fmt.Sprintf(`{"error":%q}`, err.Error()), http.StatusNotFound)
			return
		}
		writeJSON(w, map[string]any{"ok": true, "name": strings.TrimSpace(body.Name)})
	})
	mux.HandleFunc("/v1/presence", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
			return
		}
		var body struct {
			DeviceID       string `json:"deviceId"`
			Name           string `json:"name"`
			ExternalIP     string `json:"externalIp"`
			DeviceModel    string `json:"deviceModel"`
			AppVersion     string `json:"appVersion"`
			AppVersionCode int    `json:"appVersionCode"`
		}
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			http.Error(w, `{"error":"bad json"}`, http.StatusBadRequest)
			return
		}
		if body.DeviceID == "" && body.Name == "" {
			http.Error(w, `{"error":"deviceId or name required"}`, http.StatusBadRequest)
			return
		}
		ext := strings.TrimSpace(body.ExternalIP)
		if ext == "" {
			ext = clientIP(r)
		}
		u, err := store.TouchPresence(body.DeviceID, body.Name, ext, body.DeviceModel, body.AppVersion, body.AppVersionCode)
		if err != nil {
			http.Error(w, fmt.Sprintf(`{"error":%q}`, err.Error()), http.StatusNotFound)
			return
		}
		writeJSON(w, map[string]any{
			"ok":             true,
			"name":           u.Name,
			"lastSeenAt":     u.LastSeenAt,
			"lastExternalIp": u.LastExternalIP,
		})
	})
	mux.HandleFunc("/v1/profile/", func(w http.ResponseWriter, r *http.Request) {
		name := profileNameFromPath(r.URL.Path)
		u, err := store.FindUser(name)
		if err != nil {
			http.Error(w, `{"error":"not found"}`, http.StatusNotFound)
			return
		}
		// Best-effort presence when profile is fetched (connect / import).
		_, _ = store.TouchPresence(u.DeviceID, u.Name, clientIP(r), "", "", 0)
		u2, _ := store.FindUser(name)
		if u2.Name != "" {
			u = u2
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
		_, _ = store.TouchPresence(body.DeviceID, body.Name, clientIP(r), "", "", 0)
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
	// Service unlock / IP-type check from the VPS WAN (or warp0). Same idea as
	// xykt/IPQuality and lmc999/RegionRestrictionCheck, without running those scripts.
	mux.HandleFunc("/v1/netcheck", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet && r.Method != http.MethodPost {
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
			return
		}
		deviceID := r.URL.Query().Get("deviceId")
		name := r.URL.Query().Get("name")
		viaWarp := false
		if deviceID != "" || name != "" {
			if u, err := store.FindUserByDeviceOrName(deviceID, name); err == nil {
				viaWarp = u.HideIP
			}
		}
		switch strings.ToLower(r.URL.Query().Get("viaWarp")) {
		case "1", "true", "yes":
			viaWarp = true
		case "0", "false", "no":
			viaWarp = false
		}
		refresh := strings.EqualFold(r.URL.Query().Get("refresh"), "1") ||
			strings.EqualFold(r.URL.Query().Get("refresh"), "true")
		writeJSON(w, getNetcheck(viaWarp, refresh))
	})

	ln, err := net.Listen("tcp", listen)
	if err != nil {
		return err
	}
	log.Printf("provision listening on %s (data=%s deploy=%s)", listen, store.path, resolveDeployVersion())
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
		if normalizeUserDevices(u) {
			changed = true
		}
		if u.MaxDevices <= 0 {
			u.MaxDevices = 1
			changed = true
		}
	}
	if changed {
		log.Printf("filled missing AWG user keys / device defaults")
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

func (s *Store) CreateUser(name string, days, maxDevices int) (User, error) {
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
	if maxDevices <= 0 {
		maxDevices = 1
	}
	var expires int64
	if days > 0 {
		expires = time.Now().UTC().Add(time.Duration(days) * 24 * time.Hour).Unix()
	}
	deviceID := "dev-" + dev
	u := User{
		Name:             name,
		HostID:           id,
		DeviceID:         deviceID,
		DeviceIDs:        []string{deviceID},
		MaxDevices:       maxDevices,
		Password:         pass,
		ExpiresAt:        expires,
		CreatedAt:        time.Now().UTC(),
		DirectPrivateKey: priv,
		DirectPublicKey:  pub,
		ServerPublicKey:  s.Config.ServerPublicKey,
	}
	s.Users = append(s.Users, u)
	if err := s.saveLocked(); err != nil {
		return User{}, err
	}
	log.Printf("created user %q host_id=%d max_devices=%d expires=%d", name, id, maxDevices, expires)
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
		if deviceID != "" {
			if u.DeviceID == deviceID || containsString(u.DeviceIDs, deviceID) {
				return u, nil
			}
		}
		if name != "" && u.Name == name {
			return u, nil
		}
	}
	return User{}, fmt.Errorf("not found")
}

func (s *Store) ListUsersPublic() []UserPublic {
	s.mu.Lock()
	defer s.mu.Unlock()
	traffic := loadBypassTrafficLocked(filepath.Dir(s.path))
	out := make([]UserPublic, 0, len(s.Users))
	now := time.Now().Unix()
	for _, u := range s.Users {
		pub := toPublicLocked(u, now)
		if t, ok := traffic[u.Name]; ok {
			pub.DownBytes = t.DownBytes
			pub.UpBytes = t.UpBytes
		}
		out = append(out, pub)
	}
	return out
}

func (s *Store) ToPublic(u User) UserPublic {
	pub := toPublicLocked(u, time.Now().Unix())
	traffic := loadBypassTrafficLocked(filepath.Dir(s.path))
	if t, ok := traffic[u.Name]; ok {
		pub.DownBytes = t.DownBytes
		pub.UpBytes = t.UpBytes
	}
	return pub
}

func toPublicLocked(u User, now int64) UserPublic {
	normalizeUserDevices(&u)
	online := u.LastSeenAt > 0 && now-u.LastSeenAt <= onlineGraceSeconds
	var offlineFor int64
	if u.LastSeenAt > 0 && !online {
		offlineFor = now - u.LastSeenAt
		if offlineFor < 0 {
			offlineFor = 0
		}
	}
	ids := append([]string{}, u.DeviceIDs...)
	appVer, appCode := primaryAppVersion(u)
	return UserPublic{
		Name:              u.Name,
		HostID:            u.HostID,
		DeviceID:          u.DeviceID,
		DeviceIDs:         ids,
		MaxDevices:        maxInt(u.MaxDevices, 1),
		HideIP:            u.HideIP,
		ExpiresAt:         u.ExpiresAt,
		Deactivated:       u.Deactivated,
		CreatedAt:         u.CreatedAt.UTC().Format(time.RFC3339),
		LastSeenAt:        u.LastSeenAt,
		LastExternalIP:    u.LastExternalIP,
		Online:            online,
		OfflineForSec:     offlineFor,
		TrafficLimitBytes:     u.TrafficLimitBytes,
		DeviceModels:          copyDeviceModels(u.DeviceModels),
		AppVersion:            appVer,
		AppVersionCode:        appCode,
		DeviceAppVersions:     copyDeviceModels(u.DeviceAppVersions),
		DeviceAppVersionCodes: copyDeviceIntMap(u.DeviceAppVersionCodes),
	}
}

type bypassTrafficFile struct {
	ByName map[string]struct {
		DownBytes int64 `json:"downBytes"`
		UpBytes   int64 `json:"upBytes"`
	} `json:"byName"`
}

func (s *Store) renameBypassTrafficLocked(oldName, newName string) {
	if oldName == "" || newName == "" || oldName == newName {
		return
	}
	path := filepath.Join(filepath.Dir(s.path), "bypass-traffic.json")
	raw, err := os.ReadFile(path)
	if err != nil {
		return
	}
	var snap bypassTrafficFile
	if json.Unmarshal(raw, &snap) != nil || snap.ByName == nil {
		return
	}
	t, ok := snap.ByName[oldName]
	if !ok {
		return
	}
	delete(snap.ByName, oldName)
	snap.ByName[newName] = t
	out, err := json.MarshalIndent(snap, "", "  ")
	if err != nil {
		return
	}
	_ = os.WriteFile(path, out, 0o600)
}

func loadBypassTrafficLocked(dataDir string) map[string]struct{ DownBytes, UpBytes int64 } {
	out := map[string]struct{ DownBytes, UpBytes int64 }{}
	raw, err := os.ReadFile(filepath.Join(dataDir, "bypass-traffic.json"))
	if err != nil {
		return out
	}
	var snap bypassTrafficFile
	if json.Unmarshal(raw, &snap) != nil || snap.ByName == nil {
		return out
	}
	for name, t := range snap.ByName {
		out[name] = struct{ DownBytes, UpBytes int64 }{t.DownBytes, t.UpBytes}
	}
	return out
}

func (s *Store) UpdateUser(name string, maxDevices, days *int, deactivated *bool, clearDevices bool, trafficLimitBytes *int64, newName *string) (User, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	for i := range s.Users {
		u := &s.Users[i]
		if u.Name != name {
			continue
		}
		if newName != nil {
			next := strings.TrimSpace(*newName)
			if next == "" {
				return User{}, fmt.Errorf("newName required")
			}
			if next != u.Name {
				for _, other := range s.Users {
					if other.Name == next {
						return User{}, fmt.Errorf("user %q already exists", next)
					}
				}
				oldName := u.Name
				u.Name = next
				s.renameBypassTrafficLocked(oldName, next)
			}
		}
		if maxDevices != nil {
			if *maxDevices <= 0 {
				return User{}, fmt.Errorf("maxDevices must be positive")
			}
			u.MaxDevices = *maxDevices
			// Trim excess bindings if limit lowered.
			normalizeUserDevices(u)
			if len(u.DeviceIDs) > u.MaxDevices {
				u.DeviceIDs = u.DeviceIDs[:u.MaxDevices]
				if len(u.DeviceIDs) > 0 {
					u.DeviceID = u.DeviceIDs[0]
				}
			}
		}
		if days != nil && *days > 0 {
			u.ExpiresAt = time.Now().UTC().Add(time.Duration(*days) * 24 * time.Hour).Unix()
		}
		if deactivated != nil {
			u.Deactivated = *deactivated
		}
		if clearDevices {
			u.DeviceIDs = nil
			u.DeviceID = ""
			u.DeviceModels = nil
			u.DeviceAppVersions = nil
			u.DeviceAppVersionCodes = nil
		}
		if trafficLimitBytes != nil {
			if *trafficLimitBytes < 0 {
				return User{}, fmt.Errorf("trafficLimitBytes must be >= 0")
			}
			u.TrafficLimitBytes = *trafficLimitBytes
		}
		normalizeUserDevices(u)
		if err := s.saveLocked(); err != nil {
			return User{}, err
		}
		return *u, nil
	}
	return User{}, fmt.Errorf("not found")
}

func (s *Store) DeleteUser(name string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	name = strings.TrimSpace(name)
	next := make([]User, 0, len(s.Users))
	found := false
	for _, u := range s.Users {
		if u.Name == name {
			found = true
			continue
		}
		next = append(next, u)
	}
	if !found {
		return fmt.Errorf("not found")
	}
	s.Users = next
	if err := s.saveLocked(); err != nil {
		return err
	}
	log.Printf("deleted user %q", name)
	return nil
}

func (s *Store) UnbindDevice(name, deviceID string) (User, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	for i := range s.Users {
		u := &s.Users[i]
		if u.Name != name {
			continue
		}
		normalizeUserDevices(u)
		next := make([]string, 0, len(u.DeviceIDs))
		for _, id := range u.DeviceIDs {
			if id != deviceID {
				next = append(next, id)
			}
		}
		u.DeviceIDs = next
		if u.DeviceModels != nil {
			delete(u.DeviceModels, deviceID)
		}
		if u.DeviceAppVersions != nil {
			delete(u.DeviceAppVersions, deviceID)
		}
		if u.DeviceAppVersionCodes != nil {
			delete(u.DeviceAppVersionCodes, deviceID)
		}
		if u.DeviceID == deviceID {
			if len(next) > 0 {
				u.DeviceID = next[0]
			} else {
				u.DeviceID = ""
			}
		}
		if err := s.saveLocked(); err != nil {
			return User{}, err
		}
		return *u, nil
	}
	return User{}, fmt.Errorf("not found")
}

func (s *Store) TouchPresence(deviceID, name, externalIP, deviceModel, appVersion string, appVersionCode int) (User, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	for i := range s.Users {
		u := &s.Users[i]
		match := false
		if deviceID != "" && (u.DeviceID == deviceID || containsString(u.DeviceIDs, deviceID)) {
			match = true
		}
		if name != "" && u.Name == name {
			match = true
		}
		if !match {
			continue
		}
		u.LastSeenAt = time.Now().Unix()
		if ip := strings.TrimSpace(externalIP); ip != "" && looksLikeIP(ip) && !isPrivateOrTunnelIP(ip) {
			u.LastExternalIP = ip
		}
		// Bind device slot when presence reports a new device id.
		if deviceID != "" {
			normalizeUserDevices(u)
			if !containsString(u.DeviceIDs, deviceID) {
				maxDev := maxInt(u.MaxDevices, 1)
				if len(u.DeviceIDs) < maxDev {
					u.DeviceIDs = append(u.DeviceIDs, deviceID)
					if u.DeviceID == "" {
						u.DeviceID = deviceID
					}
				}
			}
			if containsString(u.DeviceIDs, deviceID) {
				if model := sanitizeDeviceModel(deviceModel); model != "" {
					if u.DeviceModels == nil {
						u.DeviceModels = map[string]string{}
					}
					u.DeviceModels[deviceID] = model
				}
				if ver := sanitizeDeviceModel(appVersion); ver != "" {
					if u.DeviceAppVersions == nil {
						u.DeviceAppVersions = map[string]string{}
					}
					u.DeviceAppVersions[deviceID] = ver
				}
				if appVersionCode > 0 {
					if u.DeviceAppVersionCodes == nil {
						u.DeviceAppVersionCodes = map[string]int{}
					}
					u.DeviceAppVersionCodes[deviceID] = appVersionCode
				}
			}
		}
		normalizeUserDevices(u)
		if err := s.saveLocked(); err != nil {
			return User{}, err
		}
		return *u, nil
	}
	return User{}, fmt.Errorf("not found")
}

// probeEgressIP returns the VPS (or WARP) public IP. api.ipify.org is primary;
// viaWarp binds the request to warp0 so it observes the Cloudflare egress.
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

func cascadeHostFromPeer(peer string) string {
	peer = strings.TrimSpace(peer)
	if peer == "" {
		return ""
	}
	host, _, err := net.SplitHostPort(peer)
	if err == nil && strings.TrimSpace(host) != "" {
		return host
	}
	return peer
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

func profileDNS(directBase string) []string {
	if v := strings.TrimSpace(os.Getenv("NVPN_CASCADE_DNS")); v != "" {
		return []string{v}
	}
	if envOr("NVPN_CASCADE_ENABLED", "0") == "1" {
		return []string{"10.10.0.2"}
	}
	return []string{fmt.Sprintf("%s.1", directBase)}
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
	p.ExpiresAt = u.ExpiresAt
	p.Deactivated = u.Deactivated
	p.MaxDevices = maxInt(u.MaxDevices, 1)
	p.Direct.Endpoint = fmt.Sprintf("%s:%d", host, cfg.DirectPort)
	p.Direct.PrivateKey = u.DirectPrivateKey
	p.Direct.PeerPublicKey = cfg.ServerPublicKey
	p.Direct.Address = fmt.Sprintf("%s.%d/32", directBase, u.HostID)
	p.Direct.DNS = profileDNS(directBase)
	p.Direct.MTU = 1280
	p.Direct.AWG = map[string]any{
		"Jc": 4, "Jmin": 40, "Jmax": 70,
		"S1": 0, "S2": 0, "S3": 0, "S4": 0,
		"H1": "1-100", "H2": "101-200", "H3": "201-300", "H4": "301-400",
	}
	p.Bypass.Peer = fmt.Sprintf("%s:%d", host, cfg.BypassPort)
	p.Bypass.Address = fmt.Sprintf("%s.%d/32", bypassBase, u.HostID)
	p.Bypass.Password = u.Password
	p.Bypass.Workers = defaultWorkers
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

func profileNameFromPath(path string) string {
	raw := filepath.Base(path)
	if unesc, err := url.PathUnescape(raw); err == nil {
		raw = unesc
	}
	return strings.TrimSpace(strings.ReplaceAll(raw, "+", " "))
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

func resolveDeployVersion() string {
	if v := strings.TrimSpace(os.Getenv("NVPN_DEPLOY_VERSION")); v != "" {
		return v
	}
	candidates := []string{
		"/opt/nonamevpn/DEPLOY_VERSION",
		"/data/DEPLOY_VERSION",
		"DEPLOY_VERSION",
	}
	for _, p := range candidates {
		raw, err := os.ReadFile(p)
		if err != nil {
			continue
		}
		if v := strings.TrimSpace(string(raw)); v != "" {
			return v
		}
	}
	return "unknown"
}

func clientIP(r *http.Request) string {
	if xff := r.Header.Get("X-Forwarded-For"); xff != "" {
		parts := strings.Split(xff, ",")
		if len(parts) > 0 {
			ip := strings.TrimSpace(parts[0])
			if looksLikeIP(ip) {
				return ip
			}
		}
	}
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return strings.TrimSpace(r.RemoteAddr)
	}
	return host
}

func isPrivateOrTunnelIP(ip string) bool {
	parsed := net.ParseIP(ip)
	if parsed == nil {
		return true
	}
	if parsed.IsLoopback() || parsed.IsLinkLocalUnicast() || parsed.IsLinkLocalMulticast() {
		return true
	}
	if v4 := parsed.To4(); v4 != nil {
		// 10.0.0.0/8, 172.16/12, 192.168/16, and ARDTT tunnel ranges 10.8/10.9
		if v4[0] == 10 || v4[0] == 127 {
			return true
		}
		if v4[0] == 172 && v4[1] >= 16 && v4[1] <= 31 {
			return true
		}
		if v4[0] == 192 && v4[1] == 168 {
			return true
		}
	}
	return false
}

func normalizeUserDevices(u *User) bool {
	changed := false
	if u.MaxDevices <= 0 {
		u.MaxDevices = 1
		changed = true
	}
	seen := map[string]bool{}
	out := make([]string, 0, len(u.DeviceIDs)+1)
	if u.DeviceID != "" {
		seen[u.DeviceID] = true
		out = append(out, u.DeviceID)
	}
	for _, id := range u.DeviceIDs {
		id = strings.TrimSpace(id)
		if id == "" || seen[id] {
			continue
		}
		seen[id] = true
		out = append(out, id)
		changed = true
	}
	if len(out) != len(u.DeviceIDs) || (len(out) > 0 && u.DeviceID == "") {
		changed = true
	}
	u.DeviceIDs = out
	if u.DeviceID == "" && len(out) > 0 {
		u.DeviceID = out[0]
		changed = true
	}
	if pruneDeviceModels(u) {
		changed = true
	}
	if pruneDeviceAppVersions(u) {
		changed = true
	}
	if pruneDeviceAppVersionCodes(u) {
		changed = true
	}
	return changed
}

func pruneDeviceModels(u *User) bool {
	if len(u.DeviceModels) == 0 {
		if u.DeviceModels != nil {
			u.DeviceModels = nil
			return true
		}
		return false
	}
	allowed := map[string]bool{}
	for _, id := range u.DeviceIDs {
		allowed[id] = true
	}
	next := make(map[string]string, len(u.DeviceIDs))
	changed := false
	for id, model := range u.DeviceModels {
		model = strings.TrimSpace(model)
		if !allowed[id] || model == "" {
			changed = true
			continue
		}
		next[id] = model
	}
	if !changed && len(next) == len(u.DeviceModels) {
		return false
	}
	if len(next) == 0 {
		u.DeviceModels = nil
	} else {
		u.DeviceModels = next
	}
	return true
}

func copyDeviceModels(in map[string]string) map[string]string {
	if len(in) == 0 {
		return nil
	}
	out := make(map[string]string, len(in))
	for k, v := range in {
		if s := strings.TrimSpace(v); s != "" {
			out[k] = s
		}
	}
	if len(out) == 0 {
		return nil
	}
	return out
}

func copyDeviceIntMap(in map[string]int) map[string]int {
	if len(in) == 0 {
		return nil
	}
	out := make(map[string]int, len(in))
	for k, v := range in {
		if strings.TrimSpace(k) != "" && v > 0 {
			out[k] = v
		}
	}
	if len(out) == 0 {
		return nil
	}
	return out
}

func primaryAppVersion(u User) (string, int) {
	ids := append([]string{}, u.DeviceIDs...)
	if u.DeviceID != "" {
		ids = append([]string{u.DeviceID}, ids...)
	}
	seen := map[string]bool{}
	for _, id := range ids {
		id = strings.TrimSpace(id)
		if id == "" || seen[id] {
			continue
		}
		seen[id] = true
		name := ""
		if u.DeviceAppVersions != nil {
			name = strings.TrimSpace(u.DeviceAppVersions[id])
		}
		code := 0
		if u.DeviceAppVersionCodes != nil {
			code = u.DeviceAppVersionCodes[id]
		}
		if name != "" || code > 0 {
			return name, code
		}
	}
	return "", 0
}

func pruneDeviceAppVersions(u *User) bool {
	if len(u.DeviceAppVersions) == 0 {
		if u.DeviceAppVersions != nil {
			u.DeviceAppVersions = nil
			return true
		}
		return false
	}
	allowed := map[string]bool{}
	for _, id := range u.DeviceIDs {
		allowed[id] = true
	}
	next := make(map[string]string, len(u.DeviceIDs))
	changed := false
	for id, ver := range u.DeviceAppVersions {
		ver = strings.TrimSpace(ver)
		if !allowed[id] || ver == "" {
			changed = true
			continue
		}
		next[id] = ver
	}
	if !changed && len(next) == len(u.DeviceAppVersions) {
		return false
	}
	if len(next) == 0 {
		u.DeviceAppVersions = nil
	} else {
		u.DeviceAppVersions = next
	}
	return true
}

func pruneDeviceAppVersionCodes(u *User) bool {
	if len(u.DeviceAppVersionCodes) == 0 {
		if u.DeviceAppVersionCodes != nil {
			u.DeviceAppVersionCodes = nil
			return true
		}
		return false
	}
	allowed := map[string]bool{}
	for _, id := range u.DeviceIDs {
		allowed[id] = true
	}
	next := make(map[string]int, len(u.DeviceIDs))
	changed := false
	for id, code := range u.DeviceAppVersionCodes {
		if !allowed[id] || code <= 0 {
			changed = true
			continue
		}
		next[id] = code
	}
	if !changed && len(next) == len(u.DeviceAppVersionCodes) {
		return false
	}
	if len(next) == 0 {
		u.DeviceAppVersionCodes = nil
	} else {
		u.DeviceAppVersionCodes = next
	}
	return true
}

func sanitizeDeviceModel(raw string) string {
	s := strings.TrimSpace(raw)
	if s == "" {
		return ""
	}
	s = strings.Map(func(r rune) rune {
		if r < 32 || r == 127 {
			return -1
		}
		return r
	}, s)
	s = strings.TrimSpace(s)
	rs := []rune(s)
	const maxRunes = 80
	if len(rs) > maxRunes {
		s = string(rs[:maxRunes])
	}
	return s
}

func containsString(list []string, want string) bool {
	for _, v := range list {
		if v == want {
			return true
		}
	}
	return false
}

func maxInt(a, b int) int {
	if a > b {
		return a
	}
	return b
}
