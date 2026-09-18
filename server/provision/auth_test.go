package main

import (
	"bytes"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func testAPI(t *testing.T) (*Store, *Credentials, http.Handler) {
	t.Helper()
	dir := t.TempDir()
	store, err := loadOrInitStore(dir, "203.0.113.9")
	if err != nil {
		t.Fatal(err)
	}
	creds := &Credentials{
		AdminToken:    "admin-token-aaaaaaaaaaaaaaaa",
		CascadeSecret: "cascade-secret-bbbbbbbbbbbb",
		Audit:         NewAuditLog(filepath.Join(dir, "audit.log")),
	}
	tlsState := &TLSState{Fingerprint: "ab" + strings.Repeat("cd", 31)}
	return store, creds, newAPIMux(store, creds, tlsState)
}

func apiReq(method, path, token, body string) *http.Request {
	var rdr io.Reader
	if body != "" {
		rdr = strings.NewReader(body)
	}
	r := httptest.NewRequest(method, path, rdr)
	r.RemoteAddr = "203.0.113.10:54321"
	if token != "" {
		r.Header.Set("Authorization", "Bearer "+token)
	}
	if body != "" {
		r.Header.Set("Content-Type", "application/json")
	}
	return r
}

func do(h http.Handler, r *http.Request) *httptest.ResponseRecorder {
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, r)
	return rec
}

func TestUsersListRequiresAdminBearer(t *testing.T) {
	_, creds, h := testAPI(t)
	rec := do(h, apiReq(http.MethodGet, "/v1/users", "", ""))
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("no token: status %d body %s", rec.Code, rec.Body.String())
	}
	rec = do(h, apiReq(http.MethodGet, "/v1/users", "wrong-token", ""))
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("wrong token: status %d", rec.Code)
	}
	rec = do(h, apiReq(http.MethodGet, "/v1/users", creds.AdminToken, ""))
	if rec.Code != http.StatusOK {
		t.Fatalf("admin: status %d body %s", rec.Code, rec.Body.String())
	}
	if strings.Contains(rec.Body.String(), "directPrivateKey") || strings.Contains(rec.Body.String(), "deviceToken") {
		t.Fatalf("list leaked secrets: %s", rec.Body.String())
	}
}

func TestCreateUserReturnsDeviceTokenAndRequiresAdmin(t *testing.T) {
	_, creds, h := testAPI(t)
	body := `{"name":"alice","days":0,"maxDevices":1}`
	rec := do(h, apiReq(http.MethodPost, "/v1/users", "", body))
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("create without token: %d", rec.Code)
	}
	rec = do(h, apiReq(http.MethodPost, "/v1/users", creds.AdminToken, body))
	if rec.Code != http.StatusOK {
		t.Fatalf("create: %d %s", rec.Code, rec.Body.String())
	}
	var p Profile
	if err := json.Unmarshal(rec.Body.Bytes(), &p); err != nil {
		t.Fatal(err)
	}
	if p.DeviceToken == "" {
		t.Fatal("profile missing deviceToken")
	}
	if p.Direct.PrivateKey == "" {
		t.Fatal("create profile should still include keys for the owner")
	}
}

func TestProfileRequiresMatchingDeviceToken(t *testing.T) {
	store, creds, h := testAPI(t)
	alice, err := store.CreateUser("alice", 0, 1)
	if err != nil {
		t.Fatal(err)
	}
	bob, err := store.CreateUser("bob", 0, 1)
	if err != nil {
		t.Fatal(err)
	}
	rec := do(h, apiReq(http.MethodGet, "/v1/profile/alice", "", ""))
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("no token: %d", rec.Code)
	}
	rec = do(h, apiReq(http.MethodGet, "/v1/profile/alice", bob.DeviceToken, ""))
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("bob token on alice: %d", rec.Code)
	}
	rec = do(h, apiReq(http.MethodGet, "/v1/profile/alice", alice.DeviceToken, ""))
	if rec.Code != http.StatusOK {
		t.Fatalf("alice token: %d %s", rec.Code, rec.Body.String())
	}
	rec = do(h, apiReq(http.MethodGet, "/v1/profile/alice", creds.AdminToken, ""))
	if rec.Code != http.StatusOK {
		t.Fatalf("admin token: %d", rec.Code)
	}
}

func TestPresenceCannotTouchAnotherUser(t *testing.T) {
	store, _, h := testAPI(t)
	alice, err := store.CreateUser("alice", 0, 1)
	if err != nil {
		t.Fatal(err)
	}
	bob, err := store.CreateUser("bob", 0, 1)
	if err != nil {
		t.Fatal(err)
	}
	body := `{"name":"bob","deviceId":"` + bob.DeviceID + `"}`
	rec := do(h, apiReq(http.MethodPost, "/v1/presence", alice.DeviceToken, body))
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("alice token on bob presence: %d %s", rec.Code, rec.Body.String())
	}
	body = `{"name":"alice","deviceId":"` + alice.DeviceID + `"}`
	rec = do(h, apiReq(http.MethodPost, "/v1/presence", alice.DeviceToken, body))
	if rec.Code != http.StatusOK {
		t.Fatalf("own presence: %d %s", rec.Code, rec.Body.String())
	}
}

func TestCascadePeerRequiresSecretOrAdmin(t *testing.T) {
	_, creds, h := testAPI(t)
	body := `{"publicKey":"dGVzdFB1YmxpY0tleUZvckNhc2NhZGVIb3AxMjM="}`
	rec := do(h, apiReq(http.MethodPost, "/v1/cascade/peer", "", body))
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("no auth: %d", rec.Code)
	}
	rec = do(h, apiReq(http.MethodPost, "/v1/cascade/peer", creds.CascadeSecret, body))
	if rec.Code != http.StatusOK {
		t.Fatalf("cascade bearer: %d %s", rec.Code, rec.Body.String())
	}

	mac := hmac.New(sha256.New, []byte(creds.CascadeSecret))
	mac.Write([]byte(body))
	r := apiReq(http.MethodPost, "/v1/cascade/peer", "", body)
	r.Header.Set("X-Ardtt-Cascade-HMAC", hex.EncodeToString(mac.Sum(nil)))
	rec = do(h, r)
	if rec.Code != http.StatusOK {
		t.Fatalf("hmac: %d %s", rec.Code, rec.Body.String())
	}
}

func TestHideIPPrefixesLoopbackWithoutToken(t *testing.T) {
	_, _, h := testAPI(t)
	r := apiReq(http.MethodGet, "/v1/hide-ip-prefixes", "", "")
	r.RemoteAddr = "127.0.0.1:9"
	rec := do(h, r)
	if rec.Code != http.StatusOK {
		t.Fatalf("loopback: %d %s", rec.Code, rec.Body.String())
	}
	r = apiReq(http.MethodGet, "/v1/hide-ip-prefixes", "", "")
	r.Header.Set("X-Forwarded-For", "127.0.0.1")
	rec = do(h, r)
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("spoofed XFF must not count as loopback: %d", rec.Code)
	}
}

func TestHealthUnauthenticatedIncludesCertFingerprint(t *testing.T) {
	_, _, h := testAPI(t)
	rec := do(h, apiReq(http.MethodGet, "/health", "", ""))
	if rec.Code != http.StatusOK {
		t.Fatalf("health: %d", rec.Code)
	}
	if !strings.Contains(rec.Body.String(), `"provisionCertFp"`) {
		t.Fatalf("missing provisionCertFp: %s", rec.Body.String())
	}
	if !strings.Contains(rec.Body.String(), `"ok": true`) && !strings.Contains(rec.Body.String(), `"ok":true`) {
		t.Fatalf("health shape: %s", rec.Body.String())
	}
}

func TestTokenEqRejectsEmptyAndWrong(t *testing.T) {
	if tokenEq("", "") {
		t.Fatal("empty tokens must not match")
	}
	if tokenEq("abc", "") {
		t.Fatal("empty supplied")
	}
	if tokenEq("short", "longer-token-value") {
		t.Fatal("length mismatch should be false, not panic")
	}
	if !tokenEq("same-token-value", "same-token-value") {
		t.Fatal("equal tokens")
	}
}

func TestLoadCredentialsCreatesAdminTokenFile(t *testing.T) {
	dir := t.TempDir()
	t.Setenv("ARDTT_ADMIN_TOKEN", "")
	t.Setenv("ARDTT_CASCADE_SECRET", "")
	creds, err := LoadCredentials(dir)
	if err != nil {
		t.Fatal(err)
	}
	if len(creds.AdminToken) != 64 {
		t.Fatalf("admin token hex len %d", len(creds.AdminToken))
	}
	st, err := os.Stat(filepath.Join(dir, "admin.token"))
	if err != nil {
		t.Fatal(err)
	}
	if st.Mode().Perm() != 0o600 {
		t.Fatalf("mode %o", st.Mode().Perm())
	}
	again, err := LoadCredentials(dir)
	if err != nil {
		t.Fatal(err)
	}
	if again.AdminToken != creds.AdminToken {
		t.Fatal("token rotated on reload")
	}
}

func TestExistingUserGetsDeviceTokenOnLoad(t *testing.T) {
	dir := t.TempDir()
	raw := []byte(`{"config":{"publicHost":"203.0.113.9","directPort":51820,"bypassPort":56003,"directSubnet":"10.8.0.0/24","bypassSubnet":"10.9.0.0/24","workers":3},"users":[{"name":"legacy","hostId":2,"deviceId":"dev-old","maxDevices":1}]}`)
	if err := os.WriteFile(filepath.Join(dir, "users.json"), raw, 0o600); err != nil {
		t.Fatal(err)
	}
	store, err := loadOrInitStore(dir, "203.0.113.9")
	if err != nil {
		t.Fatal(err)
	}
	u, err := store.FindUser("legacy")
	if err != nil {
		t.Fatal(err)
	}
	if u.DeviceToken == "" {
		t.Fatal("upgrade path must mint deviceToken")
	}
}

func TestAuditLogWritesAdminJSON(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "audit.log")
	a := NewAuditLog(path)
	r := httptest.NewRequest(http.MethodGet, "/v1/users", nil)
	r.RemoteAddr = "192.0.2.8:1"
	a.Admin(r, "ok")
	b, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Contains(b, []byte(`"path":"/v1/users"`)) {
		t.Fatalf("audit: %s", b)
	}
	if bytes.Contains(b, []byte("Bearer")) {
		t.Fatal("audit leaked authorization header")
	}
}

func TestRateLimitReturns429(t *testing.T) {
	dir := t.TempDir()
	store, err := loadOrInitStore(dir, "203.0.113.9")
	if err != nil {
		t.Fatal(err)
	}
	creds := &Credentials{
		AdminToken: "admin-token-aaaaaaaaaaaaaaaa",
		Limiter:    NewIPRateLimiter(1, 1),
	}
	h := newAPIMux(store, creds, nil)
	r1 := apiReq(http.MethodGet, "/v1/users", creds.AdminToken, "")
	r2 := apiReq(http.MethodGet, "/v1/users", creds.AdminToken, "")
	if do(h, r1).Code != http.StatusOK {
		t.Fatal("first request")
	}
	if got := do(h, r2).Code; got != http.StatusTooManyRequests {
		t.Fatalf("second: %d", got)
	}
}
