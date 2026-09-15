package state

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"time"
)

const SchemaVersion = 1

const (
	PhaseIdle           = "idle"
	PhaseFetch          = "fetch"
	PhaseVerify         = "verify"
	PhaseStage          = "stage"
	PhasePreflight      = "preflight"
	PhaseStart          = "start"
	PhaseHealth         = "health"
	PhaseCommit         = "commit"
	PhaseRollback       = "rollback"
	PhaseRollbackFailed = "rollback_failed"
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

func Load(installDir string) (State, error) {
	p := Path(installDir)
	b, err := os.ReadFile(p)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return State{SchemaVersion: SchemaVersion, Phase: PhaseIdle, ControlProtocol: 2}, nil
		}
		return State{}, err
	}
	var s State
	if err := json.Unmarshal(b, &s); err != nil {
		bak := p + ".corrupt"
		_ = os.WriteFile(bak, b, 0o600)
		return State{SchemaVersion: SchemaVersion, Phase: PhaseIdle, ControlProtocol: 2, LastError: "corrupt state", LastErrorCode: "STATE_CORRUPT"}, nil
	}
	if s.SchemaVersion == 0 {
		s.SchemaVersion = SchemaVersion
	}
	if s.Phase == "" {
		s.Phase = PhaseIdle
	}
	return s, nil
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
	b = append(b, '\n')
	tmp := filepath.Join(dir, "deploy.json.tmp")
	f, err := os.OpenFile(tmp, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o600)
	if err != nil {
		return err
	}
	if _, err := f.Write(b); err != nil {
		f.Close()
		return err
	}
	if err := f.Sync(); err != nil {
		f.Close()
		return err
	}
	if err := f.Close(); err != nil {
		return err
	}
	return os.Rename(tmp, Path(installDir))
}
