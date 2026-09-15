package state

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestSaveLoadRoundTrip(t *testing.T) {
	dir := t.TempDir()
	in := State{
		DeploymentID:    "abc",
		DesiredVersion:  "1.0.54",
		CurrentVersion:  "1.0.53",
		PreviousVersion: "1.0.52",
		Phase:           PhaseHealth,
		RollbackAllowed: true,
		ImageDigest:     "sha256:deadbeef",
	}
	if err := Save(dir, in); err != nil {
		t.Fatal(err)
	}
	out, err := Load(dir)
	if err != nil {
		t.Fatal(err)
	}
	if out.DesiredVersion != "1.0.54" || out.Phase != PhaseHealth || out.CurrentVersion != "1.0.53" {
		t.Fatalf("%+v", out)
	}
	if out.SchemaVersion != SchemaVersion || out.ControlProtocol != 2 {
		t.Fatalf("%+v", out)
	}
	if _, err := os.Stat(Path(dir)); err != nil {
		t.Fatal(err)
	}
}

func TestLoadMissingIsIdle(t *testing.T) {
	dir := t.TempDir()
	s, err := Load(dir)
	if err != nil {
		t.Fatal(err)
	}
	if s.Phase != PhaseIdle {
		t.Fatalf("%+v", s)
	}
}

func TestCorruptStateIsRecoveryRequired(t *testing.T) {
	dir := t.TempDir()
	if err := os.MkdirAll(filepath.Join(dir, "state"), 0o700); err != nil {
		t.Fatal(err)
	}
	p := Path(dir)
	if err := os.WriteFile(p, []byte("not-json{"), 0o600); err != nil {
		t.Fatal(err)
	}
	s, err := Load(dir)
	if s.LastErrorCode != "STATE_CORRUPT" || s.Phase != PhaseRecoveryRequired {
		t.Fatalf("%+v", s)
	}
	if !errors.Is(err, ErrCorrupt) {
		t.Fatalf("want ErrCorrupt, got %v", err)
	}
	matches, _ := filepath.Glob(filepath.Join(dir, "state", "deploy.json.corrupt.*"))
	if len(matches) != 1 {
		t.Fatalf("corrupt evidence: %v", matches)
	}
	s2, err := Load(dir)
	if err != nil {
		t.Fatal(err)
	}
	if s2.Phase != PhaseRecoveryRequired {
		t.Fatalf("second load %+v", s2)
	}
	if !BlocksNewDeploy(s2) {
		t.Fatal("recovery must block deploy")
	}
}

func TestEmptyAndTruncatedStateAreCorrupt(t *testing.T) {
	for _, body := range []string{"", "{}", "null", `{"phase":"commit"`} {
		dir := t.TempDir()
		if err := os.MkdirAll(filepath.Join(dir, "state"), 0o700); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(Path(dir), []byte(body), 0o600); err != nil {
			t.Fatal(err)
		}
		s, err := Load(dir)
		if s.Phase != PhaseRecoveryRequired {
			t.Fatalf("body %q -> %+v err=%v", body, s, err)
		}
	}
}

func TestSaveUsesRandomTempAndDirSync(t *testing.T) {
	dir := t.TempDir()
	synced := ""
	orig := syncDirFn
	t.Cleanup(func() { syncDirFn = orig })
	syncDirFn = func(p string) error {
		synced = p
		return orig(p)
	}
	if err := Save(dir, State{Phase: PhaseFetch, DesiredVersion: "1"}); err != nil {
		t.Fatal(err)
	}
	if synced != filepath.Join(dir, "state") {
		t.Fatalf("dir fsync path %q", synced)
	}
	matches, _ := filepath.Glob(filepath.Join(dir, "state", ".deploy-*.tmp"))
	if len(matches) != 0 {
		t.Fatalf("tmp leftover: %v", matches)
	}
	if _, err := os.Stat(filepath.Join(dir, "state", "deploy.json.tmp")); !os.IsNotExist(err) {
		t.Fatalf("predictable tmp must not exist: %v", err)
	}
}

func TestSaveDirFsyncFailure(t *testing.T) {
	dir := t.TempDir()
	orig := syncDirFn
	t.Cleanup(func() { syncDirFn = orig })
	syncDirFn = func(string) error { return errors.New("fsync dir boom") }
	if err := Save(dir, State{Phase: PhaseCommit}); err == nil {
		t.Fatal("expected dir fsync error")
	}
}

func TestSaveReplacesAtomically(t *testing.T) {
	dir := t.TempDir()
	if err := Save(dir, State{Phase: PhaseFetch, DesiredVersion: "1"}); err != nil {
		t.Fatal(err)
	}
	if err := Save(dir, State{Phase: PhaseCommit, DesiredVersion: "2"}); err != nil {
		t.Fatal(err)
	}
	s, err := Load(dir)
	if err != nil {
		t.Fatal(err)
	}
	if s.Phase != PhaseCommit || s.DesiredVersion != "2" {
		t.Fatalf("%+v", s)
	}
}

func TestReconcileIdleAndCommit(t *testing.T) {
	dir := t.TempDir()
	if err := Save(dir, State{Phase: PhaseCommit, CurrentVersion: "1.0.54"}); err != nil {
		t.Fatal(err)
	}
	s, err := Reconcile(dir)
	if err != nil {
		t.Fatal(err)
	}
	if s.Phase != PhaseIdle {
		t.Fatalf("%+v", s)
	}
}

func TestReconcileStagingAllowsRetry(t *testing.T) {
	dir := t.TempDir()
	if err := Save(dir, State{Phase: PhaseStage, DesiredVersion: "1.0.54"}); err != nil {
		t.Fatal(err)
	}
	s, err := Reconcile(dir)
	if err != nil {
		t.Fatal(err)
	}
	if s.Phase != PhaseIdle {
		t.Fatalf("%+v", s)
	}
}

func TestReconcileHealthDoesNotGuessVersion(t *testing.T) {
	dir := t.TempDir()
	if err := os.MkdirAll(filepath.Join(dir, "current"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(filepath.Join(dir, "previous"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "current", "docker-compose.yml"), []byte("c"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "previous", "docker-compose.yml"), []byte("p"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := Save(dir, State{Phase: PhaseHealth, DesiredVersion: "B", CurrentVersion: "A"}); err != nil {
		t.Fatal(err)
	}
	s, err := Reconcile(dir)
	if !errors.Is(err, ErrRecoveryRequired) {
		t.Fatalf("err=%v s=%+v", err, s)
	}
	if s.Phase != PhaseRecoveryRequired {
		t.Fatalf("%+v", s)
	}
}

func TestReconcileRollbackFailedBlocks(t *testing.T) {
	dir := t.TempDir()
	if err := Save(dir, State{Phase: PhaseRollbackFailed, LastErrorCode: "ROLLBACK_FAILED"}); err != nil {
		t.Fatal(err)
	}
	s, err := Reconcile(dir)
	if !errors.Is(err, ErrRecoveryRequired) {
		t.Fatalf("err=%v", err)
	}
	if s.Phase != PhaseRollbackFailed && s.Phase != PhaseRecoveryRequired {
		t.Fatalf("%+v", s)
	}
}

func TestSavedJSONRoundTripValid(t *testing.T) {
	dir := t.TempDir()
	if err := Save(dir, State{Phase: PhaseIdle}); err != nil {
		t.Fatal(err)
	}
	b, err := os.ReadFile(Path(dir))
	if err != nil {
		t.Fatal(err)
	}
	if !json.Valid(b) {
		t.Fatalf("invalid json %s", b)
	}
	if !strings.Contains(string(b), `"phase": "idle"`) {
		t.Fatalf("%s", b)
	}
}
