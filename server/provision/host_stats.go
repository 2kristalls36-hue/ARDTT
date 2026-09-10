package main

import (
	"bufio"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
)

// HostStats is embedded in GET /health for the admin app gauges.
type HostStats struct {
	CPUPercent     float64 `json:"cpuPercent"`
	CPUCores       float64 `json:"cpuCores"`
	MemUsedBytes   uint64  `json:"memUsedBytes"`
	MemTotalBytes  uint64  `json:"memTotalBytes"`
	MemPercent     float64 `json:"memPercent"`
	DiskUsedBytes  uint64  `json:"diskUsedBytes"`
	DiskTotalBytes uint64  `json:"diskTotalBytes"`
	DiskPercent    float64 `json:"diskPercent"`
	DiskPath       string  `json:"diskPath"`
}

var (
	cpuSampleMu   sync.Mutex
	cpuPrevTotal  uint64
	cpuPrevIdle   uint64
	cpuPrevCgroup uint64
	cpuPrevAt     time.Time
)

func collectHostStats() HostStats {
	cores := hostCPUCores()
	cpuPct := hostCPUPercent(cores)
	memUsed, memTotal := hostMemoryBytes()
	diskPath, diskUsed, diskTotal := hostDiskBytes()
	return HostStats{
		CPUPercent:     round1(clampPct(cpuPct)),
		CPUCores:       round2(cores),
		MemUsedBytes:   memUsed,
		MemTotalBytes:  memTotal,
		MemPercent:     round1(pctOf(memUsed, memTotal)),
		DiskUsedBytes:  diskUsed,
		DiskTotalBytes: diskTotal,
		DiskPercent:    round1(pctOf(diskUsed, diskTotal)),
		DiskPath:       diskPath,
	}
}

func hostCPUCores() float64 {
	if c, ok := cgroupCPUCores(); ok && c > 0 {
		return c
	}
	n := 0
	f, err := os.Open("/proc/cpuinfo")
	if err != nil {
		return 1
	}
	defer f.Close()
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		if strings.HasPrefix(sc.Text(), "processor") {
			n++
		}
	}
	if n < 1 {
		return 1
	}
	return float64(n)
}

func cgroupCPUCores() (float64, bool) {
	raw, err := os.ReadFile("/sys/fs/cgroup/cpu.max")
	if err != nil {
		return 0, false
	}
	fields := strings.Fields(string(raw))
	if len(fields) < 2 || fields[0] == "max" {
		return 0, false
	}
	quota, err1 := strconv.ParseFloat(fields[0], 64)
	period, err2 := strconv.ParseFloat(fields[1], 64)
	if err1 != nil || err2 != nil || period <= 0 {
		return 0, false
	}
	return quota / period, true
}

func hostCPUPercent(cores float64) float64 {
	if pct, ok := cgroupCPUPercent(cores); ok {
		return pct
	}
	return procStatCPUPercent()
}

func cgroupCPUPercent(cores float64) (float64, bool) {
	usage, ok := readCgroupCPUUsageUsec()
	if !ok {
		return 0, false
	}
	cpuSampleMu.Lock()
	defer cpuSampleMu.Unlock()
	now := time.Now()
	if cpuPrevCgroup == 0 || cpuPrevAt.IsZero() {
		cpuPrevCgroup = usage
		cpuPrevAt = now
		return 0, true
	}
	elapsed := now.Sub(cpuPrevAt).Seconds()
	if elapsed < 0.05 {
		return 0, true
	}
	deltaU := usage - cpuPrevCgroup
	cpuPrevCgroup = usage
	cpuPrevAt = now
	if cores <= 0 {
		return 0, true
	}
	return float64(deltaU) / 1e6 / elapsed / cores * 100, true
}

func readCgroupCPUUsageUsec() (uint64, bool) {
	raw, err := os.ReadFile("/sys/fs/cgroup/cpu.stat")
	if err != nil {
		return 0, false
	}
	for _, line := range strings.Split(string(raw), "\n") {
		if strings.HasPrefix(line, "usage_usec ") {
			v, err := strconv.ParseUint(strings.TrimSpace(strings.TrimPrefix(line, "usage_usec ")), 10, 64)
			return v, err == nil
		}
	}
	return 0, false
}

func procStatCPUPercent() float64 {
	total, idle, ok := readProcStat()
	if !ok {
		return 0
	}
	cpuSampleMu.Lock()
	defer cpuSampleMu.Unlock()
	if cpuPrevTotal == 0 {
		cpuPrevTotal, cpuPrevIdle = total, idle
		return 0
	}
	dt := total - cpuPrevTotal
	di := idle - cpuPrevIdle
	cpuPrevTotal, cpuPrevIdle = total, idle
	if dt == 0 {
		return 0
	}
	return (1 - float64(di)/float64(dt)) * 100
}

func readProcStat() (total, idle uint64, ok bool) {
	f, err := os.Open("/proc/stat")
	if err != nil {
		return 0, 0, false
	}
	defer f.Close()
	sc := bufio.NewScanner(f)
	if !sc.Scan() {
		return 0, 0, false
	}
	fields := strings.Fields(sc.Text())
	if len(fields) < 5 || fields[0] != "cpu" {
		return 0, 0, false
	}
	var vals []uint64
	for _, s := range fields[1:] {
		v, err := strconv.ParseUint(s, 10, 64)
		if err != nil {
			return 0, 0, false
		}
		vals = append(vals, v)
	}
	for _, v := range vals {
		total += v
	}
	idle = vals[3]
	if len(vals) > 4 {
		idle += vals[4] // iowait
	}
	return total, idle, true
}

func hostMemoryBytes() (used, total uint64) {
	if u, t, ok := cgroupMemoryBytes(); ok && t > 0 {
		return u, t
	}
	var memTotal, memAvail uint64
	f, err := os.Open("/proc/meminfo")
	if err != nil {
		return 0, 0
	}
	defer f.Close()
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		line := sc.Text()
		switch {
		case strings.HasPrefix(line, "MemTotal:"):
			memTotal = parseMeminfoKB(line) * 1024
		case strings.HasPrefix(line, "MemAvailable:"):
			memAvail = parseMeminfoKB(line) * 1024
		}
	}
	if memTotal == 0 {
		return 0, 0
	}
	if memAvail > memTotal {
		memAvail = memTotal
	}
	return memTotal - memAvail, memTotal
}

func cgroupMemoryBytes() (used, total uint64, ok bool) {
	curRaw, err := os.ReadFile("/sys/fs/cgroup/memory.current")
	if err != nil {
		return 0, 0, false
	}
	used, err = strconv.ParseUint(strings.TrimSpace(string(curRaw)), 10, 64)
	if err != nil {
		return 0, 0, false
	}
	maxRaw, err := os.ReadFile("/sys/fs/cgroup/memory.max")
	if err != nil {
		return 0, 0, false
	}
	maxStr := strings.TrimSpace(string(maxRaw))
	if maxStr == "" || maxStr == "max" {
		return 0, 0, false
	}
	total, err = strconv.ParseUint(maxStr, 10, 64)
	if err != nil || total == 0 {
		return 0, 0, false
	}
	return used, total, true
}

func parseMeminfoKB(line string) uint64 {
	fields := strings.Fields(line)
	if len(fields) < 2 {
		return 0
	}
	v, _ := strconv.ParseUint(fields[1], 10, 64)
	return v
}

func hostDiskBytes() (path string, used, total uint64) {
	candidates := []string{
		strings.TrimSpace(os.Getenv("ARDTT_DATA")),
		"/data",
		"/",
	}
	for _, p := range candidates {
		if p == "" {
			continue
		}
		var st syscall.Statfs_t
		if err := syscall.Statfs(p, &st); err != nil {
			continue
		}
		total = st.Blocks * uint64(st.Bsize)
		free := st.Bavail * uint64(st.Bsize)
		if total == 0 {
			continue
		}
		if free > total {
			free = total
		}
		return filepath.Clean(p), total - free, total
	}
	return "/", 0, 0
}

func pctOf(used, total uint64) float64 {
	if total == 0 {
		return 0
	}
	return float64(used) / float64(total) * 100
}

func clampPct(v float64) float64 {
	if v < 0 {
		return 0
	}
	if v > 100 {
		return 100
	}
	return v
}

func round1(v float64) float64 {
	return float64(int(v*10+0.5)) / 10
}

func round2(v float64) float64 {
	return float64(int(v*100+0.5)) / 100
}
