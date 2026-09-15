#!/usr/bin/env python3
"""Peak disk budget for an ARDTT deploy. No host mutation."""
from __future__ import annotations

import argparse
import json
import sys


def i(v, default=0) -> int:
    try:
        return int(v)
    except (TypeError, ValueError):
        return default


def compute(d: dict) -> dict:
    candidate = max(0, i(d.get("candidateReleaseBytes")))
    download = max(0, i(d.get("downloadOrIncomingBytes")))
    extract = max(0, i(d.get("extractionOrStagingOverhead")))
    missing = max(0, i(d.get("missingDockerLayerBytes")))
    rollback = max(0, i(d.get("rollbackReserveBytes")))
    state_log = max(0, i(d.get("stateAndLogOverheadBytes"), 64 * 1024 * 1024))
    if "stateAndLogOverheadBytes" not in d:
        state_log = 64 * 1024 * 1024
    reclaim = max(0, i(d.get("safelyReclaimableArdttBytes")))
    subtotal = candidate + download + extract + missing + rollback + state_log
    margin = i(d.get("safetyMarginBytes"))
    if "safetyMarginBytes" not in d or margin < 0:
        margin = max(256 * 1024 * 1024, subtotal // 10)
    required = max(0, subtotal + margin - reclaim)

    install_dev = str(d.get("installFsDev") or "")
    docker_dev = str(d.get("dockerFsDev") or "")
    tmp_dev = str(d.get("tmpFsDev") or install_dev)
    same_install_docker = bool(install_dev) and install_dev == docker_dev
    same_install_tmp = bool(install_dev) and install_dev == tmp_dev

    # Host-file peak lives on the install filesystem. Docker layers live on DockerRootDir.
    install_need = candidate + download + extract + rollback + state_log + margin
    if same_install_docker:
        install_need = required
    else:
        install_need = max(0, candidate + download + extract + rollback + state_log + max(256 * 1024 * 1024, (candidate + download + extract) // 10) - reclaim)

    docker_need = missing
    if not same_install_docker:
        extra = max(128 * 1024 * 1024, missing // 10) if missing else 0
        docker_need = missing + extra
    else:
        docker_need = required

    tmp_need = 0 if same_install_tmp else extract

    floor = max(0, i(d.get("minDiskBytes")))
    required = max(required, floor)
    install_need = max(install_need, floor if same_install_docker else min(floor, install_need + floor // 4))

    install_avail = i(d.get("installAvailableBytes"))
    docker_avail = i(d.get("dockerAvailableBytes"))
    tmp_avail = i(d.get("tmpAvailableBytes"), install_avail)
    inode_avail = i(d.get("installInodesAvailable"), 10**9)
    inode_need = i(d.get("inodeNeed"), 1000)

    ok = True
    phase = "ok"
    filesystem = str(d.get("installMount") or d.get("installPath") or "/opt/ardtt")
    if inode_avail < inode_need:
        ok = False
        phase = "inodes"
        filesystem = str(d.get("installMount") or filesystem)
    elif install_avail and install_avail < install_need:
        ok = False
        phase = str(d.get("phase") or "preflight")
        filesystem = str(d.get("installMount") or filesystem)
    elif docker_avail and docker_avail < docker_need:
        ok = False
        phase = "docker-store"
        filesystem = str(d.get("dockerMount") or d.get("dockerRoot") or "/var/lib/docker")
    elif tmp_need and tmp_avail and tmp_avail < tmp_need:
        ok = False
        phase = "tmpdir"
        filesystem = str(d.get("tmpMount") or "TMPDIR")

    return {
        "ok": ok,
        "phase": phase,
        "filesystem": filesystem,
        "requiredBytes": required,
        "installRequiredBytes": install_need,
        "dockerRequiredBytes": docker_need,
        "tmpRequiredBytes": tmp_need,
        "availableBytes": install_avail if phase != "docker-store" else docker_avail,
        "installAvailableBytes": install_avail,
        "dockerAvailableBytes": docker_avail,
        "reclaimableArdttBytes": reclaim,
        "safetyMarginBytes": margin,
        "candidateReleaseBytes": candidate,
        "downloadOrIncomingBytes": download,
        "extractionOrStagingOverhead": extract,
        "missingDockerLayerBytes": missing,
        "rollbackReserveBytes": rollback,
        "stateAndLogOverheadBytes": state_log,
        "sameInstallDockerFs": same_install_docker,
        "sameInstallTmpFs": same_install_tmp,
        "inodeAvail": inode_avail,
        "inodeNeed": inode_need,
    }


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("cmd", nargs="?", default="compute")
    ap.add_argument("--json", default="-")
    args = ap.parse_args(argv[1:])
    if args.json == "-":
        raw = sys.stdin.read()
    else:
        with open(args.json, encoding="utf-8") as fh:
            raw = fh.read()
    d = json.loads(raw or "{}")
    out = compute(d)
    json.dump(out, sys.stdout, indent=2)
    sys.stdout.write("\n")
    return 0 if out.get("ok") else 2


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
