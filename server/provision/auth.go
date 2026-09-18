package main

import (
	"bytes"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strings"
)

const adminTokenBytes = 32

// Credentials are loaded from data/ (or env override) once at serve start.
type Credentials struct {
	AdminToken    string
	CascadeSecret string
	Audit         *AuditLog
	Limiter       *IPRateLimiter
}

func LoadCredentials(dataDir string) (*Credentials, error) {
	if err := os.MkdirAll(dataDir, 0o700); err != nil {
		return nil, err
	}
	admin, err := loadOrCreateTokenFile(filepath.Join(dataDir, "admin.token"), strings.TrimSpace(os.Getenv("ARDTT_ADMIN_TOKEN")))
	if err != nil {
		return nil, fmt.Errorf("admin token: %w", err)
	}
	cascade, err := loadOptionalTokenFile(filepath.Join(dataDir, "cascade.secret"), strings.TrimSpace(os.Getenv("ARDTT_CASCADE_SECRET")))
	if err != nil {
		return nil, fmt.Errorf("cascade secret: %w", err)
	}
	return &Credentials{
		AdminToken:    admin,
		CascadeSecret: cascade,
		Audit:         NewAuditLog(filepath.Join(dataDir, "audit.log")),
		Limiter:       NewIPRateLimiter(8, 24),
	}, nil
}

func loadOrCreateTokenFile(path, override string) (string, error) {
	if override != "" {
		return override, nil
	}
	raw, err := os.ReadFile(path)
	if err == nil {
		tok := strings.TrimSpace(string(raw))
		if tok != "" {
			return tok, nil
		}
	} else if !os.IsNotExist(err) {
		return "", err
	}
	tok, err := randomHex(adminTokenBytes)
	if err != nil {
		return "", err
	}
	if err := writeSecretFile(path, tok); err != nil {
		return "", err
	}
	return tok, nil
}

func loadOptionalTokenFile(path, override string) (string, error) {
	if override != "" {
		return override, nil
	}
	raw, err := os.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) {
			return "", nil
		}
		return "", err
	}
	return strings.TrimSpace(string(raw)), nil
}

func writeSecretFile(path, tok string) error {
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, []byte(tok+"\n"), 0o600); err != nil {
		return err
	}
	if err := os.Rename(tmp, path); err != nil {
		return err
	}
	return os.Chmod(path, 0o600)
}

func randomHex(n int) (string, error) {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return hex.EncodeToString(b), nil
}

func tokenEq(a, b string) bool {
	if a == "" || b == "" {
		return false
	}
	ab := []byte(a)
	bb := []byte(b)
	if len(ab) != len(bb) {
		return false
	}
	return subtle.ConstantTimeCompare(ab, bb) == 1
}

func bearerToken(r *http.Request) string {
	h := strings.TrimSpace(r.Header.Get("Authorization"))
	if len(h) >= 7 && strings.EqualFold(h[:7], "Bearer ") {
		return strings.TrimSpace(h[7:])
	}
	if v := strings.TrimSpace(r.Header.Get("X-Ardtt-Token")); v != "" {
		return v
	}
	return ""
}

func (c *Credentials) AdminOK(tok string) bool {
	if c == nil {
		return false
	}
	return tokenEq(tok, c.AdminToken)
}

func (c *Credentials) cascadeOK(r *http.Request, body []byte) bool {
	if c == nil || c.CascadeSecret == "" {
		return false
	}
	if tokenEq(bearerToken(r), c.CascadeSecret) {
		return true
	}
	got := strings.TrimSpace(r.Header.Get("X-Ardtt-Cascade-HMAC"))
	if got == "" {
		return false
	}
	mac := hmac.New(sha256.New, []byte(c.CascadeSecret))
	_, _ = mac.Write(body)
	want := hex.EncodeToString(mac.Sum(nil))
	return tokenEq(strings.ToLower(got), strings.ToLower(want))
}

func remoteIP(r *http.Request) string {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return strings.TrimSpace(r.RemoteAddr)
	}
	return host
}

func isLoopbackRequest(r *http.Request) bool {
	ip := net.ParseIP(remoteIP(r))
	return ip != nil && ip.IsLoopback()
}

func unauthorized(w http.ResponseWriter) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("WWW-Authenticate", `Bearer realm="ardtt-provision"`)
	w.WriteHeader(http.StatusUnauthorized)
	_, _ = w.Write([]byte("{\"error\":\"unauthorized\"}\n"))
}

func readReplayBody(r *http.Request) []byte {
	if r.Body == nil {
		return nil
	}
	b, _ := io.ReadAll(io.LimitReader(r.Body, 1<<20))
	_ = r.Body.Close()
	r.Body = io.NopCloser(bytes.NewReader(b))
	return b
}

func requireAdmin(w http.ResponseWriter, r *http.Request, creds *Credentials) bool {
	if creds.AdminOK(bearerToken(r)) {
		if creds != nil && creds.Audit != nil {
			creds.Audit.Admin(r, "ok")
		}
		return true
	}
	unauthorized(w)
	return false
}

func requireAdminOrCascade(w http.ResponseWriter, r *http.Request, creds *Credentials) bool {
	body := readReplayBody(r)
	if creds.AdminOK(bearerToken(r)) || creds.cascadeOK(r, body) {
		if creds != nil && creds.Audit != nil {
			creds.Audit.Admin(r, "ok")
		}
		return true
	}
	if r.URL.Path == "/v1/hide-ip-prefixes" && r.Method == http.MethodGet && isLoopbackRequest(r) {
		return true
	}
	unauthorized(w)
	return false
}

func requireDeviceOrAdmin(w http.ResponseWriter, r *http.Request, store *Store, creds *Credentials, deviceID, name string) bool {
	tok := bearerToken(r)
	if creds.AdminOK(tok) {
		return true
	}
	u, err := store.FindUserByDeviceToken(tok)
	if err != nil {
		unauthorized(w)
		return false
	}
	if name != "" && u.Name != name {
		unauthorized(w)
		return false
	}
	if deviceID != "" && u.DeviceID != deviceID && !containsString(u.DeviceIDs, deviceID) {
		unauthorized(w)
		return false
	}
	return true
}

func (s *Store) FindUserByDeviceToken(tok string) (User, error) {
	if tok == "" {
		return User{}, fmt.Errorf("not found")
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, u := range s.Users {
		if tokenEq(tok, u.DeviceToken) {
			return u, nil
		}
	}
	return User{}, fmt.Errorf("not found")
}
