package main

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"os"
	"path/filepath"
	"sync"
	"time"

	"golang.org/x/crypto/hkdf"
)

type ClientDevice struct {
	DeviceID   string `json:"device_id"`
	IP         string `json:"ip,omitempty"`
	PrivKey    string `json:"priv_key,omitempty"`
	PubKey     string `json:"pub_key,omitempty"`
	DownBytes  int64  `json:"down_bytes"`
	UpBytes    int64  `json:"up_bytes"`
	OwnerID    string `json:"owner_id,omitempty"`
	RawIP      string `json:"raw_ip,omitempty"`
	RawOwnerID string `json:"raw_owner_id,omitempty"`
}

type PasswordEntry struct {
	Label         string   `json:"label,omitempty"`
	DeviceID      string   `json:"device_id"`
	DeviceIDs     []string `json:"device_ids"`
	MaxDevices    int      `json:"max_devices"`
	ExpiresAt     int64    `json:"expires_at"`
	DownBytes     int64    `json:"down_bytes"`
	UpBytes       int64    `json:"up_bytes"`
	VkHash        string   `json:"vk_hash,omitempty"`
	Ports         string   `json:"ports,omitempty"`
	IsDeactivated bool     `json:"is_deactivated,omitempty"`
}

var (
	activeDevices   = make(map[string]int32)
	activeDevicesMu sync.Mutex
)

type Database struct {
	MainPassword string                    `json:"-"`
	Passwords    map[string]*PasswordEntry `json:"passwords"`
	Devices      map[string]*ClientDevice  `json:"devices"`
}

var (
	db      *Database
	dbMutex sync.Mutex
	dbFile  string
)

var serverWrapKeys = newWrapKeyStore()

func passwordEntryHasDevice(entry *PasswordEntry, deviceID string) bool {
	if entry == nil {
		return false
	}
	if entry.DeviceID == deviceID {
		return true
	}
	for _, id := range entry.DeviceIDs {
		if id == deviceID {
			return true
		}
	}
	return false
}

func deviceOwnerIDLocked(deviceID string) (string, bool) {
	dev := db.Devices[deviceID]
	if dev == nil {
		return "", true
	}
	owners := make(map[string]struct{}, 2)
	if dev.OwnerID != "" {
		owners[dev.OwnerID] = struct{}{}
	}
	if dev.RawOwnerID != "" {
		owners[dev.RawOwnerID] = struct{}{}
	}
	for password, entry := range db.Passwords {
		if passwordEntryHasDevice(entry, deviceID) {
			owners[wrapKeyID(password)] = struct{}{}
		}
	}
	if len(owners) > 1 {
		return "", false
	}
	for ownerID := range owners {
		return ownerID, true
	}
	return "", true
}

func authorizeDeviceOwnerLocked(deviceID, password string, isMain bool, entry *PasswordEntry) bool {
	dev := db.Devices[deviceID]
	if dev == nil {
		return true
	}
	ownerID, consistent := deviceOwnerIDLocked(deviceID)
	if !consistent {
		return false
	}
	requestedOwnerID := wrapKeyID(password)
	if ownerID != "" {
		return ownerID == requestedOwnerID
	}
	if !isMain && !passwordEntryHasDevice(entry, deviceID) {
		return false
	}
	dev.OwnerID = requestedOwnerID
	return true
}

func setDeviceOwner(dev *ClientDevice, password string) {
	if dev != nil {
		dev.OwnerID = wrapKeyID(password)
	}
}

func generatedOwnerEntryLocked(dev *ClientDevice, deviceID string) *PasswordEntry {
	if dev == nil {
		return nil
	}
	if dev.OwnerID != "" {
		for password, entry := range db.Passwords {
			if wrapKeyID(password) == dev.OwnerID && passwordEntryHasDevice(entry, deviceID) {
				return entry
			}
		}
		return nil
	}
	var ownerPassword string
	var ownerEntry *PasswordEntry
	for password, entry := range db.Passwords {
		if !passwordEntryHasDevice(entry, deviceID) {
			continue
		}
		if ownerEntry != nil && password != ownerPassword {
			return nil
		}
		ownerPassword = password
		ownerEntry = entry
	}
	if ownerEntry != nil {
		dev.OwnerID = wrapKeyID(ownerPassword)
	}
	return ownerEntry
}

func entryDeviceIDs(entry *PasswordEntry) []string {
	if entry == nil {
		return nil
	}
	ids := append([]string(nil), entry.DeviceIDs...)
	if len(ids) == 0 && entry.DeviceID != "" && entry.DeviceID != "multi" {
		ids = append(ids, entry.DeviceID)
	}
	return ids
}

func removeEntryDeviceBinding(entry *PasswordEntry, deviceID string) {
	ids := entryDeviceIDs(entry)
	filtered := ids[:0]
	for _, id := range ids {
		if id != deviceID {
			filtered = append(filtered, id)
		}
	}
	entry.DeviceIDs = filtered
	switch len(filtered) {
	case 0:
		entry.DeviceID = ""
	case 1:
		entry.DeviceID = filtered[0]
	default:
		entry.DeviceID = "multi"
	}
}

func reconcileDeviceOwnershipLocked() {
	claims := make(map[string]map[string]struct{})
	for password, entry := range db.Passwords {
		ownerID := wrapKeyID(password)
		for _, deviceID := range entryDeviceIDs(entry) {
			if claims[deviceID] == nil {
				claims[deviceID] = make(map[string]struct{})
			}
			claims[deviceID][ownerID] = struct{}{}
		}
	}
	for deviceID, owners := range claims {
		dev := db.Devices[deviceID]
		if dev == nil {
			for password, entry := range db.Passwords {
				if _, claimed := owners[wrapKeyID(password)]; claimed {
					removeEntryDeviceBinding(entry, deviceID)
				}
			}
			continue
		}
		knownOwner := dev.OwnerID
		if knownOwner == "" {
			knownOwner = dev.RawOwnerID
		}
		if knownOwner == "" && len(owners) == 1 {
			for ownerID := range owners {
				knownOwner = ownerID
			}
			dev.OwnerID = knownOwner
		}
		if knownOwner == "" || len(owners) > 1 {
			for _, entry := range db.Passwords {
				removeEntryDeviceBinding(entry, deviceID)
			}
			delete(db.Devices, deviceID)
			log.Printf("[SECURITY] dropped ambiguous device binding %s", deviceID)
			continue
		}
		dev.OwnerID = knownOwner
		for password, entry := range db.Passwords {
			if passwordEntryHasDevice(entry, deviceID) && wrapKeyID(password) != knownOwner {
				removeEntryDeviceBinding(entry, deviceID)
			}
		}
	}
}

func (entry *PasswordEntry) canConnectAndBind(deviceID string) bool {
	limit := entry.MaxDevices
	if limit <= 0 {
		limit = 1
	}
	for _, id := range entry.DeviceIDs {
		if id == deviceID {
			return true
		}
	}
	if len(entry.DeviceIDs) == 0 && entry.DeviceID != "" {
		if entry.DeviceID == deviceID {
			entry.DeviceIDs = []string{deviceID}
			return true
		}
		if limit == 1 {
			return false
		}
		entry.DeviceIDs = append(entry.DeviceIDs, entry.DeviceID)
	}
	if len(entry.DeviceIDs) < limit {
		entry.DeviceIDs = append(entry.DeviceIDs, deviceID)
		if len(entry.DeviceIDs) == 1 {
			entry.DeviceID = deviceID
		} else {
			entry.DeviceID = "multi"
		}
		return true
	}
	return false
}

type wrapKeyEntry struct {
	id  string
	key []byte
}

type wrapKeyStore struct {
	mu      sync.RWMutex
	entries []wrapKeyEntry
}

func newWrapKeyStore() *wrapKeyStore {
	return &wrapKeyStore{}
}

func deriveWrapKey(password string) ([]byte, error) {
	if password == "" {
		return nil, errors.New("empty password")
	}
	key := make([]byte, wrapKeyLen)
	reader := hkdf.New(
		sha256.New,
		[]byte(password),
		[]byte("WDTT-WRAP-v1"),
		[]byte("rtp-obfs/chacha20poly1305"),
	)
	if _, err := io.ReadFull(reader, key); err != nil {
		return nil, fmt.Errorf("derive wrap key: %w", err)
	}
	return key, nil
}

func wrapKeyID(password string) string {
	sum := sha256.Sum256([]byte("WDTT-WRAP-ID-v1\x00" + password))
	return hex.EncodeToString(sum[:8])
}

func zeroBytes(b []byte) {
	for i := range b {
		b[i] = 0
	}
}

func (s *wrapKeyStore) SetPasswords(mainPassword string, generated []string) error {
	next := make([]wrapKeyEntry, 0, len(generated)+1)
	seen := make(map[string]struct{}, len(generated)+1)

	if mainPassword != "" {
		key, err := deriveWrapKey(mainPassword)
		if err != nil {
			return err
		}
		id := "pass:" + wrapKeyID(mainPassword)
		next = append(next, wrapKeyEntry{id: id, key: key})
		seen[id] = struct{}{}
	}

	for _, password := range generated {
		if password == "" {
			continue
		}
		id := "pass:" + wrapKeyID(password)
		if _, exists := seen[id]; exists {
			continue
		}
		key, err := deriveWrapKey(password)
		if err != nil {
			for _, entry := range next {
				zeroBytes(entry.key)
			}
			return err
		}
		next = append(next, wrapKeyEntry{id: id, key: key})
		seen[id] = struct{}{}
	}

	s.mu.Lock()
	old := s.entries
	s.entries = next
	s.mu.Unlock()
	for _, entry := range old {
		evictAEAD(entry.key)
		zeroBytes(entry.key)
	}
	return nil
}

func (s *wrapKeyStore) RemovePassword(password string) {
	id := "pass:" + wrapKeyID(password)

	s.mu.Lock()
	defer s.mu.Unlock()
	for i, entry := range s.entries {
		if entry.id != id {
			continue
		}
		evictAEAD(entry.key)
		zeroBytes(entry.key)
		copy(s.entries[i:], s.entries[i+1:])
		s.entries[len(s.entries)-1] = wrapKeyEntry{}
		s.entries = s.entries[:len(s.entries)-1]
		return
	}
}

func (s *wrapKeyStore) Count() int {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return len(s.entries)
}

func (s *wrapKeyStore) Unwrap(raw, dst []byte) ([]byte, string, int, error) {
	if !obfsIsRTPPacket(raw) {
		return nil, "", 0, errors.New("wrap: non-obfs packet")
	}

	s.mu.RLock()
	defer s.mu.RUnlock()
	if len(s.entries) == 0 {
		return nil, "", 0, errors.New("wrap: no active keys")
	}
	for _, entry := range s.entries {
		m, err := obfsUnwrapPacket(entry.key, raw, dst)
		if err == nil {
			return append([]byte(nil), entry.key...), entry.id, m, nil
		}
	}
	return nil, "", 0, errors.New("wrap: auth failed")
}

func refreshWrapKeysFromDBLocked() error {
	passwords := make([]string, 0, len(db.Passwords))
	for password, entry := range db.Passwords {
		if !isPasswordExpired(entry) && !entry.IsDeactivated {
			passwords = append(passwords, password)
		}
	}
	return serverWrapKeys.SetPasswords(db.MainPassword, passwords)
}

func reloadDB() error {
	dbMutex.Lock()
	defer dbMutex.Unlock()

	if db == nil || dbFile == "" {
		return errors.New("database is not initialized")
	}

	flushRawDeviceTrafficLocked()

	data, err := os.ReadFile(dbFile)
	if err != nil {
		return fmt.Errorf("read db file: %w", err)
	}

	oldDB := db
	newDB := &Database{
		Passwords: make(map[string]*PasswordEntry),
		Devices:   make(map[string]*ClientDevice),
	}
	if err := json.Unmarshal(data, newDB); err != nil {
		return fmt.Errorf("parse db json: %w", err)
	}
	newDB.MainPassword = oldDB.MainPassword
	if newDB.Passwords == nil {
		newDB.Passwords = make(map[string]*PasswordEntry)
	}
	if newDB.Devices == nil {
		newDB.Devices = make(map[string]*ClientDevice)
	}

	// bypass-sync rewrites passwords.json from users.json and can clobber
	// traffic counters we have already flushed in memory but not yet saved.
	for id, oldDev := range oldDB.Devices {
		newDev, ok := newDB.Devices[id]
		if !ok {
			continue
		}
		if newDev.UpBytes < oldDev.UpBytes {
			newDev.UpBytes = oldDev.UpBytes
		}
		if newDev.DownBytes < oldDev.DownBytes {
			newDev.DownBytes = oldDev.DownBytes
		}
	}
	for pass, oldEntry := range oldDB.Passwords {
		newEntry, ok := newDB.Passwords[pass]
		if !ok {
			continue
		}
		if newEntry.UpBytes < oldEntry.UpBytes {
			newEntry.UpBytes = oldEntry.UpBytes
		}
		if newEntry.DownBytes < oldEntry.DownBytes {
			newEntry.DownBytes = oldEntry.DownBytes
		}
	}

	db = newDB
	cleanupExpiredPasswordsLocked()
	if err := refreshWrapKeysFromDBLocked(); err != nil {
		return fmt.Errorf("refresh wrap keys: %w", err)
	}

	for pass := range oldDB.Passwords {
		newEntry, still := db.Passwords[pass]
		if !still || (newEntry != nil && newEntry.IsDeactivated) {
			disconnectCredentialConnections(pass)
		}
	}
	return nil
}

func initDB(dir, mainPass string) {
	if err := os.MkdirAll(dir, 0700); err != nil {
		log.Fatalf("[DB] mkdir: %v", err)
	}
	if err := os.Chmod(dir, 0700); err != nil {
		log.Fatalf("[DB] chmod: %v", err)
	}
	dbFile = filepath.Join(dir, "passwords.json")
	db = &Database{
		Passwords: make(map[string]*PasswordEntry),
		Devices:   make(map[string]*ClientDevice),
	}
	data, err := os.ReadFile(dbFile)
	if err == nil {
		if err := json.Unmarshal(data, db); err != nil {
			log.Fatalf("[DB] corrupt %s: %v", dbFile, err)
		}
	} else if !os.IsNotExist(err) {
		log.Fatalf("[DB] read %s: %v", dbFile, err)
	}
	if db.Passwords == nil {
		db.Passwords = make(map[string]*PasswordEntry)
	}
	if db.Devices == nil {
		db.Devices = make(map[string]*ClientDevice)
	}
	db.MainPassword = mainPass
	reconcileDeviceOwnershipLocked()
	if err := saveDB(); err != nil {
		log.Fatalf("[DB] save: %v", err)
	}
	if err := refreshWrapKeysFromDBLocked(); err != nil {
		log.Fatalf("[WRAP] init keys: %v", err)
	}
}

func saveDB() error {
	data, err := json.MarshalIndent(db, "", "  ")
	if err != nil {
		log.Printf("[DB] marshal: %v", err)
		return err
	}
	tmp, err := os.CreateTemp(filepath.Dir(dbFile), ".passwords-*.tmp")
	if err != nil {
		log.Printf("[DB] tempfile: %v", err)
		return err
	}
	tmpName := tmp.Name()
	defer os.Remove(tmpName)
	if err = tmp.Chmod(0600); err == nil {
		_, err = tmp.Write(data)
	}
	if err == nil {
		err = tmp.Sync()
	}
	closeErr := tmp.Close()
	if err == nil {
		err = closeErr
	}
	if err == nil {
		err = os.Rename(tmpName, dbFile)
	}
	if err == nil {
		if dir, openErr := os.Open(filepath.Dir(dbFile)); openErr == nil {
			err = dir.Sync()
			dir.Close()
		} else {
			err = openErr
		}
	}
	if err != nil {
		log.Printf("[DB] atomic save: %v", err)
	}
	return err
}

func isPasswordExpired(entry *PasswordEntry) bool {
	if entry == nil {
		return true
	}
	if entry.ExpiresAt == 0 {
		return false
	}
	return time.Now().Unix() > entry.ExpiresAt
}

func getNextRawIP() string {
	used := make(map[string]bool)
	for _, dev := range db.Devices {
		if dev.RawIP != "" {
			used[dev.RawIP] = true
		}
	}
	for b4 := 2; b4 <= 254; b4++ {
		ip := fmt.Sprintf("10.9.0.%d", b4)
		if ip == rawServerAddr {
			continue
		}
		if !used[ip] {
			return ip
		}
	}
	return ""
}

func cleanupExpiredPasswordsLocked() int {
	removed := 0
	for p, entry := range db.Passwords {
		if !isPasswordExpired(entry) {
			continue
		}
		disconnectCredentialConnections(p)
		if entry != nil {
			for _, deviceID := range entryDeviceIDs(entry) {
				delete(db.Devices, deviceID)
			}
		}
		delete(db.Passwords, p)
		serverWrapKeys.RemovePassword(p)
		removed++
	}
	return removed
}

func cleanupExpiredPasswords() int {
	dbMutex.Lock()
	defer dbMutex.Unlock()
	removed := cleanupExpiredPasswordsLocked()
	if removed > 0 {
		saveDB()
	}
	return removed
}

func expiredPasswordJanitor(ctx context.Context) {
	ticker := time.NewTicker(1 * time.Hour)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			if removed := cleanupExpiredPasswords(); removed > 0 {
				log.Printf("[DB] expired passwords removed: %d", removed)
			}
		}
	}
}
