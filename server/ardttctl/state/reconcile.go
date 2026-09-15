package state

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
)

// Reconcile maps an interrupted phase onto a unique next action.
// Ambiguous live candidates are never guessed: the result stays recovery_required.
func Reconcile(installDir string) (State, error) {
	s, err := Load(installDir)
	if err != nil && s.Phase != PhaseRecoveryRequired {
		return s, err
	}
	if s.Phase == PhaseRecoveryRequired {
		return s, ErrRecoveryRequired
	}
	currentOK := fileExists(filepath.Join(installDir, "current", "docker-compose.yml"))
	previousOK := fileExists(filepath.Join(installDir, "previous", "docker-compose.yml"))
	sameLive := samePointerTarget(installDir, "current", "previous")
	phase := s.Phase
	if phase == PhaseStartCandidate {
		phase = PhaseStart
	}
	if phase == PhaseReadiness {
		phase = PhaseHealth
	}

	switch phase {
	case PhaseIdle:
		return s, nil
	case PhaseCommit:
		if !commitLooksComplete(installDir, s) {
			s.Phase = PhaseRecoveryRequired
			s.LastErrorCode = "RECOVERY_REQUIRED"
			s.LastError = "commit interrupted: current pointer/metadata do not match recorded version"
			if err := Save(installDir, s); err != nil {
				return s, err
			}
			return s, ErrRecoveryRequired
		}
		s.Phase = PhaseIdle
		s.LastError = ""
		s.LastErrorCode = ""
		if err := Save(installDir, s); err != nil {
			return s, err
		}
		return s, nil
	case PhaseFetch, PhaseVerify, PhaseStage, PhasePreflight, PhasePrepare:
		// Pointers still on the old current. Retry from the start is unique.
		s.Phase = PhaseIdle
		if err := Save(installDir, s); err != nil {
			return s, err
		}
		return s, nil
	case PhaseStart, PhaseHealth:
		if currentOK && (sameLive || !previousOK && s.PreviousVersion == "") {
			// Snapshot already retargeted previous at the still-live current,
			// or first install never created previous. Either is unique: stay
			// on the existing current and allow a retry.
			if sameLive || s.PreviousVersion == "" {
				s.Phase = PhaseIdle
				if err := Save(installDir, s); err != nil {
					return s, err
				}
				return s, nil
			}
		}
		if currentOK && previousOK {
			s.Phase = PhaseRecoveryRequired
			s.LastErrorCode = "RECOVERY_REQUIRED"
			s.LastError = "interrupted while candidate was live; will not pick a version"
			if err := Save(installDir, s); err != nil {
				return s, err
			}
			return s, ErrRecoveryRequired
		}
		if currentOK && !previousOK {
			// First install interrupted: leave recovery, do not invent previous.
			s.Phase = PhaseRecoveryRequired
			s.LastErrorCode = "RECOVERY_REQUIRED"
			s.LastError = "interrupted first install; current present without rollback pointer"
			_ = Save(installDir, s)
			return s, ErrRecoveryRequired
		}
		s.Phase = PhaseRecoveryRequired
		s.LastErrorCode = "RECOVERY_REQUIRED"
		s.LastError = "interrupted start/health with no unique pointer set"
		_ = Save(installDir, s)
		return s, ErrRecoveryRequired
	case PhaseRollback:
		if currentOK && previousOK {
			// Retry rollback is unique: current should already match previous or can be retargeted.
			return s, nil
		}
		s.Phase = PhaseRecoveryRequired
		s.LastErrorCode = "RECOVERY_REQUIRED"
		s.LastError = "interrupted rollback; pointers incomplete"
		_ = Save(installDir, s)
		return s, ErrRecoveryRequired
	case PhaseRollbackFailed:
		return s, ErrRecoveryRequired
	default:
		s.Phase = PhaseRecoveryRequired
		s.LastErrorCode = "RECOVERY_REQUIRED"
		s.LastError = "unknown phase " + s.Phase
		_ = Save(installDir, s)
		return s, ErrRecoveryRequired
	}
}

func commitLooksComplete(installDir string, s State) bool {
	curCompose := filepath.Join(installDir, "current", "docker-compose.yml")
	if !fileExists(curCompose) {
		return false
	}
	if s.CurrentVersion == "" {
		return true
	}
	envVer := envFileVal(filepath.Join(installDir, "current", ".env"), "ARDTT_DEPLOY_VERSION")
	if envVer == "" {
		envVer = strings.TrimSpace(readFile(filepath.Join(installDir, "current", "DEPLOY_VERSION")))
	}
	if envVer == "" {
		return false
	}
	return envVer == s.CurrentVersion
}

func envFileVal(path, key string) string {
	b, err := os.ReadFile(path)
	if err != nil {
		return ""
	}
	prefix := key + "="
	for _, line := range strings.Split(string(b), "\n") {
		line = strings.TrimSpace(line)
		if strings.HasPrefix(line, prefix) {
			return strings.Trim(strings.TrimSpace(strings.TrimPrefix(line, prefix)), "\"'")
		}
	}
	return ""
}

func readFile(path string) string {
	b, err := os.ReadFile(path)
	if err != nil {
		return ""
	}
	return string(b)
}

func samePointerTarget(installDir, a, b string) bool {
	pa := filepath.Join(installDir, a)
	pb := filepath.Join(installDir, b)
	ra, err := filepath.EvalSymlinks(pa)
	if err != nil {
		return false
	}
	rb, err := filepath.EvalSymlinks(pb)
	if err != nil {
		return false
	}
	return ra != "" && ra == rb
}

func DiagnoseMap(installDir string, s State) map[string]any {
	cur := filepath.Join(installDir, "current")
	fi, err := os.Lstat(cur)
	isLink := err == nil && fi.Mode()&os.ModeSymlink != 0
	out := map[string]any{
		"installDir":       installDir,
		"phase":            s.Phase,
		"currentVersion":   s.CurrentVersion,
		"previousVersion":  s.PreviousVersion,
		"desiredVersion":   s.DesiredVersion,
		"currentIsSymlink": isLink,
		"hasPrevious":      fileExists(filepath.Join(installDir, "previous", "docker-compose.yml")),
		"hasData":          dirExists(filepath.Join(installDir, "data")),
		"lastErrorCode":    s.LastErrorCode,
		"rollbackAllowed":  s.RollbackAllowed,
	}
	if s.Phase == PhaseRecoveryRequired || s.Phase == PhaseRollbackFailed {
		out["blocksDeploy"] = true
	}
	return out
}

func fileExists(p string) bool {
	_, err := os.Stat(p)
	return err == nil
}

func dirExists(p string) bool {
	fi, err := os.Stat(p)
	return err == nil && fi.IsDir()
}

func MustJSON(v any) string {
	b, _ := json.Marshal(v)
	return string(b)
}
