package main

import (
	"crypto/tls"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"testing"
)

func TestEnsureTLSStableFingerprint(t *testing.T) {
	dir := t.TempDir()
	a, err := EnsureTLS(dir)
	if err != nil {
		t.Fatal(err)
	}
	if len(a.Fingerprint) != 64 {
		t.Fatalf("fp len %d", len(a.Fingerprint))
	}
	b, err := EnsureTLS(dir)
	if err != nil {
		t.Fatal(err)
	}
	if a.Fingerprint != b.Fingerprint {
		t.Fatal("fingerprint changed after reload")
	}
	st, err := os.Stat(filepath.Join(dir, "provision.key"))
	if err != nil {
		t.Fatal(err)
	}
	if st.Mode().Perm() != 0o600 {
		t.Fatalf("key mode %o", st.Mode().Perm())
	}
}

func TestServeProvisionAcceptsHTTPAndHTTPS(t *testing.T) {
	dir := t.TempDir()
	state, err := EnsureTLS(dir)
	if err != nil {
		t.Fatal(err)
	}
	mux := http.NewServeMux()
	mux.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{"ok":true}`))
	})
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	errCh := make(chan error, 1)
	go func() { errCh <- serveProvision(ln, mux, state) }()

	httpURL := "http://" + ln.Addr().String() + "/health"
	resp, err := http.Get(httpURL)
	if err != nil {
		t.Fatal(err)
	}
	_ = resp.Body.Close()
	if resp.StatusCode != 200 {
		t.Fatalf("http %d", resp.StatusCode)
	}

	client := &http.Client{Transport: &http.Transport{
		TLSClientConfig: &tls.Config{InsecureSkipVerify: true},
	}}
	httpsURL := "https://" + ln.Addr().String() + "/health"
	resp, err = client.Get(httpsURL)
	if err != nil {
		t.Fatal(err)
	}
	_ = resp.Body.Close()
	if resp.StatusCode != 200 {
		t.Fatalf("https %d", resp.StatusCode)
	}
}
