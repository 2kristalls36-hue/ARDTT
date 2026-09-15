package state

import (
	"os"
	"path/filepath"
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

func TestCorruptStateDoesNotCrash(t *testing.T) {
	dir := t.TempDir()
	if err := os.MkdirAll(filepath.Join(dir, "state"), 0o700); err != nil {
		t.Fatal(err)
	}
	p := Path(dir)
	if err := os.WriteFile(p, []byte("not-json{"), 0o600); err != nil {
		t.Fatal(err)
	}
	s, err := Load(dir)
	if err != nil {
		t.Fatal(err)
	}
	if s.LastErrorCode != "STATE_CORRUPT" || s.Phase != PhaseIdle {
		t.Fatalf("%+v", s)
	}
	if _, err := os.Stat(p + ".corrupt"); err != nil {
		t.Fatal("corrupt backup missing")
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
	if _, err := os.Stat(filepath.Join(dir, "state", "deploy.json.tmp")); !os.IsNotExist(err) {
		t.Fatalf("tmp leftover: %v", err)
	}
}
