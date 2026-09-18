package main

import (
	"encoding/json"
	"net/http"
	"os"
	"sync"
	"time"
)

const auditRotateBytes = 5 * 1024 * 1024

type AuditLog struct {
	path string
	mu   sync.Mutex
}

func NewAuditLog(path string) *AuditLog {
	if path == "" {
		return nil
	}
	return &AuditLog{path: path}
}

func (a *AuditLog) Admin(r *http.Request, result string) {
	if a == nil || r == nil {
		return
	}
	rec := map[string]any{
		"ts":     time.Now().UTC().Format(time.RFC3339Nano),
		"op":     "admin",
		"method": r.Method,
		"path":   r.URL.Path,
		"ip":     remoteIP(r),
		"result": result,
	}
	line, err := json.Marshal(rec)
	if err != nil {
		return
	}
	line = append(line, '\n')
	a.mu.Lock()
	defer a.mu.Unlock()
	a.rotateLocked()
	f, err := os.OpenFile(a.path, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0o600)
	if err != nil {
		return
	}
	_, _ = f.Write(line)
	_ = f.Close()
}

func (a *AuditLog) rotateLocked() {
	st, err := os.Stat(a.path)
	if err != nil || st.Size() < auditRotateBytes {
		return
	}
	_ = os.Rename(a.path, a.path+".1")
}
