package main

import "testing"

func TestProfileNameFromPathPlusAndSpace(t *testing.T) {
	cases := map[string]string{
		"/v1/profile/Юля+тест": "Юля тест",
		"/v1/profile/Юля тест": "Юля тест",
		"/v1/profile/client-2808": "client-2808",
		"/v1/profile/%D0%AE%D0%BB%D1%8F+%D1%82%D0%B5%D1%81%D1%82": "Юля тест",
	}
	for path, want := range cases {
		if got := profileNameFromPath(path); got != want {
			t.Fatalf("path %q: got %q want %q", path, got, want)
		}
	}
}
