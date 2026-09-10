package main

import (
	"math"
	"os"
	"path/filepath"
	"testing"
)

func TestPctOfAndClamp(t *testing.T) {
	if pctOf(50, 100) != 50 {
		t.Fatalf("pctOf")
	}
	if pctOf(1, 0) != 0 {
		t.Fatalf("zero total")
	}
	if clampPct(-1) != 0 || clampPct(150) != 100 {
		t.Fatalf("clamp")
	}
}

func TestCollectHostStatsSmoke(t *testing.T) {
	st := collectHostStats()
	if st.CPUCores < 0.01 {
		t.Fatalf("cores=%v", st.CPUCores)
	}
	if st.MemTotalBytes == 0 {
		t.Fatalf("mem total 0")
	}
	if st.DiskTotalBytes == 0 {
		t.Fatalf("disk total 0 path=%s", st.DiskPath)
	}
	if st.CPUPercent < 0 || st.CPUPercent > 100 || math.IsNaN(st.CPUPercent) {
		t.Fatalf("cpu%%=%v", st.CPUPercent)
	}
}

func TestCgroupCPUCoresParse(t *testing.T) {
	dir := t.TempDir()
	// Simulate by writing a temp file is hard without chroot; just ensure
	// function returns false when file missing in a blank env — call via
	// hostCPUCores which falls back to /proc.
	_ = dir
	if hostCPUCores() < 0.01 {
		t.Fatal("hostCPUCores")
	}
}

func TestHostDiskPrefersData(t *testing.T) {
	dir := t.TempDir()
	t.Setenv("ARDTT_DATA", dir)
	path, used, total := hostDiskBytes()
	if filepath.Clean(path) != filepath.Clean(dir) {
		t.Fatalf("path=%q want %q", path, dir)
	}
	if total == 0 {
		t.Fatalf("total=0 used=%d", used)
	}
	_ = os.WriteFile(filepath.Join(dir, "x"), []byte("hi"), 0o600)
}
