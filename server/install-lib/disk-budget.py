#!/usr/bin/env python3
"""Peak disk budget for an ARDTT deploy. No host mutation.

Projected reclaimable bytes are advisory only. They are never subtracted
from required space. After an opt-in ARDTT cleanup, pass the remeasured
available bytes (and optionally actuallyReclaimedBytes for diagnostics).
"""
from __future__ import annotations

import argparse
import json
import sys


def i(v, default=0) -> int:
    try:
        return int(v)
    except (TypeError, ValueError):
        return default


def parse_available(d: dict, key: str):
    if key not in d:
        return None
    try:
        v = int(d[key])
    except (TypeError, ValueError):
        return None
    return v


def compute(d: dict) -> dict:
    candidate = max(0, i(d.get("candidateReleaseBytes")))
    download = max(0, i(d.get("downloadOrIncomingBytes") or d.get("downloadBytesCompressed")))
    extract = max(0, i(d.get("extractionOrStagingOverhead") or d.get("hostFilesStageBytes")))
    cache_write = max(0, i(d.get("cacheWriteBytesCompressed")))
    missing = max(0, i(d.get("missingDockerLayerBytes") or d.get("dockerImportBytesRaw")))
    rollback = max(0, i(d.get("rollbackReserveBytes")))
    state_log = max(0, i(d.get("stateAndLogOverheadBytes"), 64 * 1024 * 1024))
    if "stateAndLogOverheadBytes" not in d:
        state_log = 64 * 1024 * 1024
    temp = max(0, i(d.get("temporaryOverheadBytes")))
    reclaim_advisory = max(0, i(d.get("safelyReclaimableArdttBytes") or d.get("reclaimableArdttBytes")))
    actually = max(0, i(d.get("actuallyReclaimedBytes")))
    margin = i(d.get("safetyMarginBytes"))
    subtotal = candidate + download + extract + cache_write + missing + rollback + state_log + temp
    if "safetyMarginBytes" not in d or margin < 0:
        margin = max(256 * 1024 * 1024, subtotal // 10)
    # Never subtract projected reclaim. actuallyReclaimed is diagnostic only:
    # callers must remeasure available after cleanup.
    required = max(0, subtotal + margin)

    install_dev = str(d.get("installFsDev") or "")
    docker_dev = str(d.get("dockerFsDev") or "")
    tmp_dev = str(d.get("tmpFsDev") or install_dev)
    same_install_docker = bool(install_dev) and install_dev == docker_dev
    same_install_tmp = bool(install_dev) and install_dev == tmp_dev

    install_need = candidate + download + extract + cache_write + rollback + state_log + margin
    docker_need = missing
    if same_install_docker:
        install_need = required
        docker_need = required
    else:
        extra = max(128 * 1024 * 1024, missing // 10) if missing else 0
        docker_need = missing + extra

    tmp_need = 0 if same_install_tmp else extract

    floor = max(0, i(d.get("minDiskBytes")))
    required = max(required, floor)
    if same_install_docker:
        install_need = max(install_need, floor)
    else:
        install_need = max(install_need, min(floor, install_need + floor // 4) if floor else install_need)

    install_avail = parse_available(d, "installAvailableBytes")
    docker_avail = parse_available(d, "dockerAvailableBytes")
    tmp_avail = parse_available(d, "tmpAvailableBytes")
    if tmp_avail is None:
        tmp_avail = install_avail
    inode_avail = parse_available(d, "installInodesAvailable")
    if inode_avail is None:
        inode_avail = 10**9
    inode_need = i(d.get("inodeNeed"), 1000)

    ok = True
    phase = "ok"
    code = ""
    filesystem = str(d.get("installMount") or d.get("installPath") or "/opt/ardtt")
    estimation = str(d.get("estimationMethod") or "manifest-or-local-sizes")

    if install_avail is None or install_avail < 0:
        ok = False
        phase = "measure"
        code = "DISK_MEASUREMENT_FAILED"
    elif inode_avail < inode_need:
        ok = False
        phase = "inodes"
        code = "INSUFFICIENT_DISK"
        filesystem = str(d.get("installMount") or filesystem)
    elif install_avail < install_need:
        ok = False
        phase = str(d.get("phase") or "preflight")
        code = "INSUFFICIENT_DISK"
        filesystem = str(d.get("installMount") or filesystem)
    elif docker_avail is None or docker_avail < 0:
        ok = False
        phase = "measure"
        code = "DISK_MEASUREMENT_FAILED"
        filesystem = str(d.get("dockerMount") or d.get("dockerRoot") or "/var/lib/docker")
    elif docker_avail < docker_need:
        ok = False
        phase = "docker-store"
        code = "INSUFFICIENT_DISK"
        filesystem = str(d.get("dockerMount") or d.get("dockerRoot") or "/var/lib/docker")
    elif tmp_need and (tmp_avail is None or tmp_avail < 0):
        ok = False
        phase = "measure"
        code = "DISK_MEASUREMENT_FAILED"
        filesystem = str(d.get("tmpMount") or "TMPDIR")
    elif tmp_need and tmp_avail < tmp_need:
        ok = False
        phase = "tmpdir"
        code = "INSUFFICIENT_DISK"
        filesystem = str(d.get("tmpMount") or "TMPDIR")

    avail_out = 0
    if phase == "docker-store" and docker_avail is not None:
        avail_out = docker_avail
    elif install_avail is not None:
        avail_out = install_avail

    return {
        "ok": ok,
        "phase": phase,
        "code": code,
        "filesystem": filesystem,
        "estimationMethod": estimation,
        "requiredBytes": required,
        "installRequiredBytes": install_need,
        "dockerRequiredBytes": docker_need,
        "tmpRequiredBytes": tmp_need,
        "availableBytes": avail_out,
        "installAvailableBytes": 0 if install_avail is None else install_avail,
        "dockerAvailableBytes": 0 if docker_avail is None else docker_avail,
        "reclaimableArdttBytes": reclaim_advisory,
        "actuallyReclaimedBytes": actually,
        "safetyMarginBytes": margin,
        "candidateReleaseBytes": candidate,
        "downloadOrIncomingBytes": download,
        "downloadBytesCompressed": download,
        "hostFilesStageBytes": extract,
        "cacheWriteBytesCompressed": cache_write,
        "extractionOrStagingOverhead": extract,
        "missingDockerLayerBytes": missing,
        "dockerImportBytesRaw": missing,
        "rollbackReserveBytes": rollback,
        "temporaryOverheadBytes": temp,
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
    try:
        d = json.loads(raw or "{}")
        if not isinstance(d, dict):
            raise ValueError("payload is not an object")
    except (json.JSONDecodeError, ValueError) as exc:
        json.dump({"ok": False, "code": "DISK_MEASUREMENT_FAILED", "phase": "measure", "message": str(exc)}, sys.stdout, indent=2)
        sys.stdout.write("\n")
        return 2
    out = compute(d)
    json.dump(out, sys.stdout, indent=2)
    sys.stdout.write("\n")
    return 0 if out.get("ok") else 2


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
