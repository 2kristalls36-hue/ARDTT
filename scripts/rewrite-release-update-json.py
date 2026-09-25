#!/usr/bin/env python3
"""Point ardtt-update.json apkUrl at the release tag that will hold the APK."""
import argparse
import hashlib
import json
import pathlib


def main() -> None:
    parser = argparse.ArgumentParser(
        prog="rewrite-release-update-json.py",
        description="Point ardtt-update.json apkUrl at the release tag that will hold the APK.",
    )
    parser.add_argument("tag")
    parser.add_argument("version")
    parser.add_argument("repo")
    parser.add_argument("dist_dir")
    parser.add_argument("--version-code", type=int, default=None, dest="version_code")
    args = parser.parse_args()
    if args.version_code is not None and args.version_code <= 0:
        raise SystemExit("--version-code must be an integer greater than 0")
    tag, version, repo, dist_s = args.tag, args.version, args.repo, args.dist_dir
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
    if args.version_code is not None:
        data["versionCode"] = args.version_code
    data["apkUrl"] = (
        f"https://github.com/{repo}/releases/download/{tag}/ardtt-{version}-arm64-v8a.apk"
    )
    data["sha256"] = sha
    data["sizeBytes"] = apk.stat().st_size
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("rewrote", path, "apkUrl", data["apkUrl"])


if __name__ == "__main__":
    main()
