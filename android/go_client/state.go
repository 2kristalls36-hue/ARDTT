package main

import (
	"log"
	"os"
	"path/filepath"
	"strings"
	"sync"
)

const stateDirEnv = "ARDTT_STATE_DIR"

var logStateDirOnce sync.Once

// stateDir is a writable folder for vk_profile.json and captcha fingerprints.
// On Android the APK/native dir is read-only; the app sets ARDTT_STATE_DIR.
func stateDir() string {
	dir := strings.TrimSpace(os.Getenv(stateDirEnv))
	if dir == "" {
		if wd, err := os.Getwd(); err == nil {
			wd = strings.TrimSpace(wd)
			if wd != "" && wd != "/" {
				dir = wd
			}
		}
	}
	if dir == "" {
		dir = os.TempDir()
	}
	if err := os.MkdirAll(dir, 0o700); err != nil {
		log.Printf("[КЛИЕНТ] не удалось создать каталог состояния %s: %v", dir, err)
	}
	logStateDirOnce.Do(func() {
		log.Printf("[КЛИЕНТ] каталог состояния: %s", dir)
	})
	return dir
}

func statePath(name string) string {
	return filepath.Join(stateDir(), name)
}
