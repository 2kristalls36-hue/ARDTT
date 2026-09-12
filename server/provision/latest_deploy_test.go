package main

import "testing"

func rel(draft bool, names ...string) releaseAssets {
	r := releaseAssets{Draft: draft}
	for _, n := range names {
		r.Assets = append(r.Assets, struct {
			Name string `json:"name"`
		}{Name: n})
	}
	return r
}

func TestPickLatestDeployVersion(t *testing.T) {
	cases := []struct {
		name     string
		releases []releaseAssets
		want     string
	}{
		{
			name: "two versions in one release",
			releases: []releaseAssets{rel(false,
				"ardtt-server-1.0.51-linux-amd64.tar.gz",
				"ardtt-server-1.0.51-linux-arm64.tar.gz",
				"ardtt-server-1.0.52-linux-amd64.tar.gz",
			)},
			want: "1.0.52",
		},
		{
			name: "newest release has only apks",
			releases: []releaseAssets{
				rel(false, "ardtt-android-1.2.3.apk", "ardtt-android-1.2.3-arm64.apk"),
				rel(false, "ardtt-server-1.0.48-linux-amd64.tar.gz"),
			},
			want: "1.0.48",
		},
		{
			name: "draft release ignored",
			releases: []releaseAssets{
				rel(true, "ardtt-server-1.0.99-linux-amd64.tar.gz"),
				rel(false, "ardtt-server-1.0.52-linux-amd64.tar.gz"),
			},
			want: "1.0.52",
		},
		{
			name: "partial and tooling assets only",
			releases: []releaseAssets{rel(false,
				"ardtt-server-1.0.52-linux-amd64-hostfiles.tar.gz",
				"ardtt-server-1.0.52-linux-amd64-layer-00-0123456789abcdef.tar.gz",
				"ardtt-docker-engine-29.7.2-linux-amd64.tgz",
				"ardtt-docker-compose-2.32.4-linux-amd64",
			)},
			want: "",
		},
		{
			name:     "index only release",
			releases: []releaseAssets{rel(false, "ardtt-server-1.0.52-linux-arm64.index.json")},
			want:     "1.0.52",
		},
		{
			name: "numeric not lexical comparison",
			releases: []releaseAssets{rel(false,
				"ardtt-server-1.0.9-linux-amd64.tar.gz",
				"ardtt-server-1.0.10-linux-amd64.tar.gz",
			)},
			want: "1.0.10",
		},
		{
			name:     "no releases",
			releases: nil,
			want:     "",
		},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := pickLatestDeployVersion(tc.releases); got != tc.want {
				t.Fatalf("pickLatestDeployVersion() = %q, want %q", got, tc.want)
			}
		})
	}
}
