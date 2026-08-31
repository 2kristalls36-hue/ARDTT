package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestStatePathUsesEnv(t *testing.T) {
	dir := t.TempDir()
	t.Setenv(stateDirEnv, dir)
	got := statePath("vk_profile.json")
	want := filepath.Join(dir, "vk_profile.json")
	if got != want {
		t.Fatalf("got %q want %q", got, want)
	}
}

func TestStatePathRejectsRootCwdWhenEnvEmpty(t *testing.T) {
	t.Setenv(stateDirEnv, "")
	got := statePath("vk_profile.json")
	if got == "/vk_profile.json" || strings.HasPrefix(got, "//") {
		t.Fatalf("must not write to /: %q", got)
	}
	if filepath.Base(got) != "vk_profile.json" {
		t.Fatalf("unexpected name: %q", got)
	}
}

func TestStateDirCreatesFolder(t *testing.T) {
	parent := t.TempDir()
	dir := filepath.Join(parent, "bypass")
	t.Setenv(stateDirEnv, dir)
	_ = statePath("captcha_browser_fp")
	st, err := os.Stat(dir)
	if err != nil {
		t.Fatal(err)
	}
	if !st.IsDir() {
		t.Fatalf("not a directory: %s", dir)
	}
}
