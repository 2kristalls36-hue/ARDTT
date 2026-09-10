#!/usr/bin/env python3
"""Point ardtt-update.json apkUrl at the release tag that will hold the APK."""
import hashlib
import json
import pathlib
import sys


def main() -> None:
    if len(sys.argv) != 5:
        raise SystemExit("usage: rewrite-release-update-json.py TAG VERSION REPO DIST_DIR")
    tag, version, repo, dist_s = sys.argv[1:]
    dist = pathlib.Path(dist_s)
    apk = dist / f"ardtt-{version}-arm64-v8a.apk"
    if not apk.is_file():
        raise SystemExit(f"missing {apk}")
    sha = hashlib.sha256(apk.read_bytes()).hexdigest()
    path = dist / "ardtt-update.json"
    data = {}
    if path.is_file():
        data = json.loads(path.read_text(encoding="utf-8"))
    data["versionName"] = version
    data["apkUrl"] = (
        f"https://github.com/{repo}/releases/download/{tag}/ardtt-{version}-arm64-v8a.apk"
    )
    data["sha256"] = sha
    data["sizeBytes"] = apk.stat().st_size
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("rewrote", path, "apkUrl", data["apkUrl"])


if __name__ == "__main__":
    main()
