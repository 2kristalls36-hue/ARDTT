package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"

	"github.com/2kristalls36-hue/ARDTT/server/ardttctl/protocol"
	"github.com/2kristalls36-hue/ARDTT/server/ardttctl/state"
)

func main() {
	if len(os.Args) < 2 {
		usage()
		os.Exit(2)
	}
	switch os.Args[1] {
	case "emit":
		os.Exit(runEmit(os.Args[2:]))
	case "status":
		os.Exit(runStatus())
	case "diagnose":
		os.Exit(runDiagnose())
	case "health":
		os.Exit(runHealth())
	case "state":
		os.Exit(runState(os.Args[2:]))
	case "reconcile":
		os.Exit(runReconcile())
	case "help", "-h", "--help":
		usage()
	default:
		fmt.Fprintf(os.Stderr, "unknown command %q\n", os.Args[1])
		usage()
		os.Exit(2)
	}
}

func usage() {
	fmt.Fprint(os.Stderr, `ardttctl — host deployment controller (protocol 2 + legacy ARDTT_*).

Commands:
  emit progress|info|warn|error|done [flags]
  status
  diagnose
  health
  state get
  state set --phase P [--desired V] [--current V] [--previous V] [--id ID] [--digest D] [--error MSG] [--code C] [--force]
  state reconcile
  reconcile

Install dir: ARDTT_INSTALL_DIR (default /opt/ardtt).
`)
}

func installDir() string {
	if v := strings.TrimSpace(os.Getenv("ARDTT_INSTALL_DIR")); v != "" {
		return v
	}
	return "/opt/ardtt"
}

func runEmit(args []string) int {
	if len(args) < 1 {
		fmt.Fprintln(os.Stderr, "emit: missing type")
		return 2
	}
	ev := protocol.Event{Protocol: protocol.Version, Type: args[0]}
	fs := map[string]string{}
	noLegacy := false
	for i := 1; i < len(args); i++ {
		a := args[i]
		switch {
		case a == "--no-legacy":
			noLegacy = true
		case a == "--frac" && i+1 < len(args):
			i++
			f, err := strconv.ParseFloat(args[i], 64)
			if err != nil {
				fmt.Fprintf(os.Stderr, "bad --frac: %v\n", err)
				return 2
			}
			ev.Progress = &f
		case a == "--message" && i+1 < len(args):
			i++
			ev.Message = args[i]
		case a == "--phase" && i+1 < len(args):
			i++
			ev.Phase = args[i]
		case a == "--code" && i+1 < len(args):
			i++
			ev.Code = args[i]
		case a == "--version" && i+1 < len(args):
			i++
			ev.Version = args[i]
		case a == "--filesystem" && i+1 < len(args):
			i++
			ev.Filesystem = args[i]
		case a == "--required-bytes" && i+1 < len(args):
			i++
			ev.RequiredBytes = parseInt64Ptr(args[i])
		case a == "--available-bytes" && i+1 < len(args):
			i++
			ev.AvailableBytes = parseInt64Ptr(args[i])
		case a == "--reclaimable-bytes" && i+1 < len(args):
			i++
			ev.ReclaimableArdttBytes = parseInt64Ptr(args[i])
		case a == "--safety-margin-bytes" && i+1 < len(args):
			i++
			ev.SafetyMarginBytes = parseInt64Ptr(args[i])
		case strings.HasPrefix(a, "--") && i+1 < len(args):
			key := strings.TrimPrefix(a, "--")
			i++
			fs[key] = args[i]
		}
	}
	if ev.Type == protocol.TypeDone && len(fs) > 0 {
		ev.Done = fs
	}
	if noLegacy {
		fmt.Println(ev.JSONLine())
		return 0
	}
	if err := protocol.Emit(os.Stdout, ev); err != nil {
		return 1
	}
	return 0
}

func runStatus() int {
	dir := installDir()
	st, err := state.Load(dir)
	if err != nil && st.Phase != state.PhaseRecoveryRequired {
		_ = protocol.Emit(os.Stdout, protocol.Event{Type: protocol.TypeError, Code: "STATE", Message: err.Error()})
		return 1
	}
	ver := readTrim(filepath.Join(dir, "DEPLOY_VERSION"))
	if st.CurrentVersion == "" {
		st.CurrentVersion = ver
	}
	status := "idle"
	if st.Phase != "" && st.Phase != state.PhaseIdle && st.Phase != state.PhaseCommit {
		status = st.Phase
	}
	if state.BlocksNewDeploy(st) {
		status = st.Phase
	}
	ev := protocol.Event{
		Type:         protocol.TypeStatus,
		Phase:        st.Phase,
		Version:      st.CurrentVersion,
		DeploymentID: st.DeploymentID,
		Status:       status,
		Message:      fmt.Sprintf("deploy=%s phase=%s previous=%s", nz(st.CurrentVersion, ver), st.Phase, st.PreviousVersion),
	}
	_ = protocol.Emit(os.Stdout, ev)
	fmt.Printf("ARDTT_INFO|status current=%s desired=%s phase=%s\n", nz(st.CurrentVersion, ver), st.DesiredVersion, st.Phase)
	return 0
}

func runDiagnose() int {
	dir := installDir()
	st, _ := state.Load(dir)
	d := state.DiagnoseMap(dir, st)
	d["currentVersion"] = nz(st.CurrentVersion, readTrim(filepath.Join(dir, "DEPLOY_VERSION")))
	if st.ImageDigest != "" {
		d["imageDigest"] = st.ImageDigest
	}
	if b, err := os.ReadFile(filepath.Join(dir, "instance.json")); err == nil {
		var obj map[string]any
		if json.Unmarshal(b, &obj) == nil {
			if c, ok := obj["containerName"].(string); ok {
				d["container"] = c
			} else if c, ok := obj["container"].(string); ok {
				d["container"] = c
			}
		}
	}
	enc, _ := json.Marshal(d)
	fmt.Printf("{\"protocol\":2,\"type\":\"diagnose\",\"message\":%s}\n", strconv.Quote(string(enc)))
	fmt.Printf("ARDTT_INFO|diagnose current=%s phase=%s previous_ok=%v data=%v symlink=%v\n",
		d["currentVersion"], d["phase"], d["hasPrevious"], d["hasData"], d["currentIsSymlink"])
	return 0
}

func runReconcile() int {
	dir := installDir()
	st, err := state.Reconcile(dir)
	enc := json.NewEncoder(os.Stdout)
	enc.SetIndent("", "  ")
	_ = enc.Encode(st)
	if errors.Is(err, state.ErrRecoveryRequired) || state.BlocksNewDeploy(st) {
		fmt.Fprintf(os.Stderr, "RECOVERY_REQUIRED phase=%s code=%s\n", st.Phase, st.LastErrorCode)
		return 2
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}
	return 0
}

func runHealth() int {
	dir := installDir()
	name := containerName(dir)
	if name == "" {
		_ = protocol.Emit(os.Stdout, protocol.Event{Type: protocol.TypeHealth, Status: "unknown", Message: "no instance"})
		fmt.Println("ARDTT_INFO|health unknown: no instance.json")
		return 0
	}
	out, err := exec.Command("docker", "inspect", "-f",
		"{{.State.Status}} {{.State.OOMKilled}} {{.State.Restarting}}", name).CombinedOutput()
	msg := strings.TrimSpace(string(out))
	status := "unhealthy"
	if err == nil {
		parts := strings.Fields(msg)
		if len(parts) >= 1 && parts[0] == "running" {
			status = "healthy"
			if len(parts) >= 3 && (parts[1] == "true" || parts[2] == "true") {
				status = "unhealthy"
			}
		} else if len(parts) >= 1 {
			status = parts[0]
		}
	} else {
		msg = strings.TrimSpace(string(out) + " " + err.Error())
	}
	_ = protocol.Emit(os.Stdout, protocol.Event{Type: protocol.TypeHealth, Component: "container", Status: status, Message: name + " " + msg})
	fmt.Printf("ARDTT_INFO|health %s %s\n", status, name)
	if status != "healthy" {
		return 1
	}
	return 0
}

func runState(args []string) int {
	if len(args) < 1 {
		fmt.Fprintln(os.Stderr, "state get|set")
		return 2
	}
	dir := installDir()
	switch args[0] {
	case "get":
		st, err := state.Load(dir)
		if err != nil && st.Phase != state.PhaseRecoveryRequired {
			fmt.Fprintln(os.Stderr, err)
			return 1
		}
		enc := json.NewEncoder(os.Stdout)
		enc.SetIndent("", "  ")
		_ = enc.Encode(st)
		return 0
	case "reconcile":
		return runReconcile()
	case "set":
		st, err := state.Load(dir)
		if err != nil && st.Phase != state.PhaseRecoveryRequired {
			fmt.Fprintln(os.Stderr, err)
			return 1
		}
		force := false
		origPhase := st.Phase
		for i := 1; i < len(args); i++ {
			if args[i] == "--force" {
				force = true
				continue
			}
			if i+1 >= len(args) {
				break
			}
			switch args[i] {
			case "--phase":
				i++
				st.Phase = args[i]
			case "--desired":
				i++
				st.DesiredVersion = args[i]
			case "--current":
				i++
				st.CurrentVersion = args[i]
			case "--previous":
				i++
				st.PreviousVersion = args[i]
			case "--id":
				i++
				st.DeploymentID = args[i]
			case "--digest":
				i++
				st.ImageDigest = args[i]
			case "--error":
				i++
				st.LastError = args[i]
			case "--code":
				i++
				st.LastErrorCode = args[i]
			}
		}
		if origPhase == state.PhaseRecoveryRequired && !force && st.Phase != state.PhaseRecoveryRequired {
			fmt.Fprintln(os.Stderr, "RECOVERY_REQUIRED: refuse to start a new deploy over corrupt/ambiguous state")
			return 2
		}
		if st.Phase == state.PhaseStart || st.Phase == state.PhaseFetch {
			if st.StartedAt == "" {
				st.StartedAt = st.UpdatedAt
			}
		}
		st.RollbackAllowed = fileExists(filepath.Join(dir, "previous", "docker-compose.yml"))
		if err := state.Save(dir, st); err != nil {
			fmt.Fprintln(os.Stderr, err)
			return 1
		}
		return 0
	default:
		fmt.Fprintln(os.Stderr, "state get|set")
		return 2
	}
}

func readTrim(p string) string {
	b, err := os.ReadFile(p)
	if err != nil {
		return ""
	}
	return strings.TrimSpace(string(b))
}

func fileExists(p string) bool {
	_, err := os.Stat(p)
	return err == nil
}

func dirExists(p string) bool {
	fi, err := os.Stat(p)
	return err == nil && fi.IsDir()
}

func parseInt64Ptr(v string) *int64 {
	n, err := strconv.ParseInt(v, 10, 64)
	if err != nil {
		return nil
	}
	return &n
}

func nz(a, b string) string {
	if strings.TrimSpace(a) != "" {
		return a
	}
	return b
}

func containerName(dir string) string {
	b, err := os.ReadFile(filepath.Join(dir, "instance.json"))
	if err != nil {
		return ""
	}
	var obj map[string]any
	if json.Unmarshal(b, &obj) != nil {
		return ""
	}
	for _, k := range []string{"containerName", "container", "ardttContainer"} {
		if c, ok := obj[k].(string); ok && c != "" {
			return c
		}
	}
	return ""
}
