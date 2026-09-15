package state

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"time"
)

const SchemaVersion = 1

const (
	PhaseIdle             = "idle"
	PhaseFetch            = "fetch"
	PhaseVerify           = "verify"
	PhaseStage            = "stage"
	PhasePreflight        = "preflight"
	PhaseStart            = "start"
	PhaseHealth           = "health"
	PhaseCommit           = "commit"
	PhaseRollback         = "rollback"
	PhaseRollbackFailed   = "rollback_failed"
	PhaseRecoveryRequired = "recovery_required"
)

var (
	ErrCorrupt          = errors.New("STATE_CORRUPT")
	ErrRecoveryRequired = errors.New("RECOVERY_REQUIRED")
	ErrSaveFailed       = errors.New("STATE_SAVE_FAILED")
	syncFileFn          = func(f *os.File) error { return f.Sync() }
	syncDirFn           = fsyncDir
	createTempFn        = os.CreateTemp
)

type State struct {
	SchemaVersion   int    `json:"schemaVersion"`
	DeploymentID    string `json:"deploymentId"`
	DesiredVersion  string `json:"desiredVersion"`
	CurrentVersion  string `json:"currentVersion"`
	PreviousVersion string `json:"previousVersion"`
	Phase           string `json:"phase"`
	StartedAt       string `json:"startedAt,omitempty"`
	UpdatedAt       string `json:"updatedAt"`
	ImageDigest     string `json:"imageDigest,omitempty"`
	ConfigSchema    string `json:"configSchema,omitempty"`
	DataSchema      string `json:"dataSchema,omitempty"`
	ControlProtocol int    `json:"controlProtocolVersion"`
	RollbackAllowed bool   `json:"rollbackAllowed"`
	LastError       string `json:"lastError,omitempty"`
	LastErrorCode   string `json:"lastErrorCode,omitempty"`
}

func Path(installDir string) string {
	return filepath.Join(installDir, "state", "deploy.json")
}

func fsyncDir(path string) error {
	f, err := os.Open(path)
	if err != nil {
		return err
	}
	defer f.Close()
	return f.Sync()
}

func randomSuffix() string {
	var b [8]byte
	if _, err := rand.Read(b[:]); err != nil {
		return fmt.Sprintf("%d", time.Now().UnixNano())
	}
	return hex.EncodeToString(b[:])
}

func preserveCorrupt(p string, b []byte) (string, error) {
	dir := filepath.Dir(p)
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return "", err
	}
	bak := filepath.Join(dir, "deploy.json.corrupt."+randomSuffix())
	if err := os.WriteFile(bak, b, 0o600); err != nil {
		return "", err
	}
	return bak, nil
}

func isEmptyJSON(b []byte) bool {
	s := strings.TrimSpace(string(b))
	return s == "" || s == "{}" || s == "null"
}

func Load(installDir string) (State, error) {
	p := Path(installDir)
	b, err := os.ReadFile(p)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return State{SchemaVersion: SchemaVersion, Phase: PhaseIdle, ControlProtocol: 2}, nil
		}
		return State{}, err
	}
	if isEmptyJSON(b) || !json.Valid(b) {
		return loadCorrupt(installDir, p, b, "empty or invalid JSON")
	}
	var s State
	if err := json.Unmarshal(b, &s); err != nil {
		return loadCorrupt(installDir, p, b, err.Error())
	}
	if s.SchemaVersion == 0 {
		s.SchemaVersion = SchemaVersion
	}
	if s.Phase == "" {
		return loadCorrupt(installDir, p, b, "missing phase")
	}
	return s, nil
}

func loadCorrupt(installDir, p string, b []byte, why string) (State, error) {
	bak, err := preserveCorrupt(p, b)
	s := State{
		SchemaVersion:   SchemaVersion,
		Phase:           PhaseRecoveryRequired,
		ControlProtocol: 2,
		LastError:       "corrupt state: " + why,
		LastErrorCode:   "STATE_CORRUPT",
	}
	if err != nil {
		s.LastError = "corrupt state; evidence not saved: " + err.Error()
		return s, errors.Join(ErrCorrupt, err)
	}
	_ = bak
	if err := Save(installDir, s); err != nil {
		return s, errors.Join(ErrCorrupt, err)
	}
	return s, ErrCorrupt
}

func Save(installDir string, s State) error {
	if s.SchemaVersion == 0 {
		s.SchemaVersion = SchemaVersion
	}
	if s.ControlProtocol == 0 {
		s.ControlProtocol = 2
	}
	if s.ConfigSchema == "" {
		s.ConfigSchema = "1"
	}
	if s.DataSchema == "" {
		s.DataSchema = "1"
	}
	s.UpdatedAt = time.Now().UTC().Format(time.RFC3339)
	dir := filepath.Join(installDir, "state")
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return err
	}
	b, err := json.MarshalIndent(s, "", "  ")
	if err != nil {
		return err
	}
	if !json.Valid(b) {
		return fmt.Errorf("%w: serialized state is not valid JSON", ErrSaveFailed)
	}
	b = append(b, '\n')
	tmp, err := createTempFn(dir, ".deploy-*.tmp")
	if err != nil {
		return err
	}
	tmpName := tmp.Name()
	defer func() { _ = os.Remove(tmpName) }()
	if err := tmp.Chmod(0o600); err != nil {
		tmp.Close()
		return err
	}
	if _, err := tmp.Write(b); err != nil {
		tmp.Close()
		return err
	}
	if err := syncFileFn(tmp); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Close(); err != nil {
		return err
	}
	if err := os.Rename(tmpName, Path(installDir)); err != nil {
		return err
	}
	if err := syncDirFn(dir); err != nil {
		return fmt.Errorf("%w: directory fsync: %v", ErrSaveFailed, err)
	}
	return nil
}

func BlocksNewDeploy(s State) bool {
	switch s.Phase {
	case PhaseRecoveryRequired, PhaseStart, PhaseHealth, PhaseRollback, PhaseRollbackFailed:
		return true
	default:
		return false
	}
}
