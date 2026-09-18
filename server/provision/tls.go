package main

import (
	"bufio"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/hex"
	"encoding/pem"
	"fmt"
	"io"
	"math/big"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"sync"
	"time"
)

// TLSState holds the provision leaf cert and its SHA-256 fingerprint (hex).
type TLSState struct {
	Cert        tls.Certificate
	Fingerprint string
}

func EnsureTLS(dir string) (*TLSState, error) {
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return nil, err
	}
	certPath := filepath.Join(dir, "provision.crt")
	keyPath := filepath.Join(dir, "provision.key")
	if _, err := os.Stat(certPath); err == nil {
		if _, err := os.Stat(keyPath); err == nil {
			return loadTLS(certPath, keyPath)
		}
	}
	return createTLS(certPath, keyPath)
}

func loadTLS(certPath, keyPath string) (*TLSState, error) {
	cert, err := tls.LoadX509KeyPair(certPath, keyPath)
	if err != nil {
		return nil, err
	}
	fp, err := fingerprintOf(cert)
	if err != nil {
		return nil, err
	}
	return &TLSState{Cert: cert, Fingerprint: fp}, nil
}

func createTLS(certPath, keyPath string) (*TLSState, error) {
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return nil, err
	}
	serial, err := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 128))
	if err != nil {
		return nil, err
	}
	tmpl := &x509.Certificate{
		SerialNumber: serial,
		Subject:      pkix.Name{CommonName: "ardtt-provision", Organization: []string{"ARDTT"}},
		NotBefore:    time.Now().Add(-time.Hour),
		NotAfter:     time.Now().Add(10 * 365 * 24 * time.Hour),
		KeyUsage:     x509.KeyUsageDigitalSignature | x509.KeyUsageKeyEncipherment,
		ExtKeyUsage:  []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		DNSNames:     []string{"localhost", "ardtt"},
		IPAddresses:  []net.IP{net.ParseIP("127.0.0.1"), net.ParseIP("::1")},
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &key.PublicKey, key)
	if err != nil {
		return nil, err
	}
	certPEM := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})
	keyDER, err := x509.MarshalECPrivateKey(key)
	if err != nil {
		return nil, err
	}
	keyPEM := pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: keyDER})
	if err := os.WriteFile(certPath, certPEM, 0o644); err != nil {
		return nil, err
	}
	if err := os.WriteFile(keyPath, keyPEM, 0o600); err != nil {
		return nil, err
	}
	cert, err := tls.X509KeyPair(certPEM, keyPEM)
	if err != nil {
		return nil, err
	}
	fp := sha256.Sum256(der)
	return &TLSState{Cert: cert, Fingerprint: hex.EncodeToString(fp[:])}, nil
}

func fingerprintOf(cert tls.Certificate) (string, error) {
	if len(cert.Certificate) == 0 {
		return "", fmt.Errorf("empty certificate")
	}
	sum := sha256.Sum256(cert.Certificate[0])
	return hex.EncodeToString(sum[:]), nil
}

type prefixConn struct {
	net.Conn
	r io.Reader
}

func (c *prefixConn) Read(p []byte) (int, error) { return c.r.Read(p) }

type closeNotifyConn struct {
	net.Conn
	once sync.Once
	fn   func()
}

func (c *closeNotifyConn) Close() error {
	err := c.Conn.Close()
	c.once.Do(func() {
		if c.fn != nil {
			c.fn()
		}
	})
	return err
}

type onceListener struct {
	ch   chan net.Conn
	done chan struct{}
	addr net.Addr
}

func (l *onceListener) Accept() (net.Conn, error) {
	select {
	case c, ok := <-l.ch:
		if !ok {
			return nil, net.ErrClosed
		}
		return c, nil
	case <-l.done:
		return nil, net.ErrClosed
	}
}

func (l *onceListener) Close() error {
	select {
	case <-l.done:
	default:
		close(l.done)
	}
	return nil
}

func (l *onceListener) Addr() net.Addr {
	if l.addr != nil {
		return l.addr
	}
	return &net.TCPAddr{IP: net.IPv4zero, Port: 0}
}

func serveProvision(ln net.Listener, h http.Handler, tlsState *TLSState) error {
	if tlsState == nil || len(tlsState.Cert.Certificate) == 0 {
		srv := &http.Server{Handler: h, ReadHeaderTimeout: 10 * time.Second}
		return srv.Serve(ln)
	}
	for {
		c, err := ln.Accept()
		if err != nil {
			return err
		}
		go serveProvisionConn(c, h, tlsState)
	}
}

func serveProvisionConn(c net.Conn, h http.Handler, tlsState *TLSState) {
	_ = c.SetReadDeadline(time.Now().Add(10 * time.Second))
	br := bufio.NewReader(c)
	peek, err := br.Peek(1)
	_ = c.SetReadDeadline(time.Time{})
	wrapped := &prefixConn{Conn: c, r: br}
	var conn net.Conn = wrapped
	if err == nil && len(peek) > 0 && peek[0] == 0x16 {
		conn = tls.Server(wrapped, &tls.Config{
			Certificates: []tls.Certificate{tlsState.Cert},
			MinVersion:   tls.VersionTLS12,
		})
	}
	ln := &onceListener{
		ch:   make(chan net.Conn, 1),
		done: make(chan struct{}),
		addr: c.LocalAddr(),
	}
	ln.ch <- &closeNotifyConn{Conn: conn, fn: func() { _ = ln.Close() }}
	srv := &http.Server{
		Handler:           h,
		ReadHeaderTimeout: 10 * time.Second,
		TLSNextProto:      map[string]func(*http.Server, *tls.Conn, http.Handler){},
	}
	_ = srv.Serve(ln)
}
