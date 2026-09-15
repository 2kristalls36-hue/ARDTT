#!/usr/bin/env python3
"""Atomic current/previous pointers into /opt/ardtt/releases/<id>.

Never copies a release tree. Never follows a symlink out of releases/.
Subcommands: replace | migrate | recover | validate | relpath | clean-next
"""
from __future__ import annotations

import os
import re
import shutil
import sys
import time
from pathlib import Path

ALLOWED_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._+-]{0,127}$")
POINTER_NAMES = ("current", "previous", "current-release")


def die(msg: str, code: int = 1) -> None:
    print(msg, file=sys.stderr)
    raise SystemExit(code)


def real(p: str) -> str:
    return os.path.realpath(p)


def fsync_dir(path: str) -> None:
    fd = os.open(path, os.O_RDONLY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def releases_root(install: str) -> str:
    return real(os.path.join(install, "releases"))


def under_releases(install: str, path: str) -> bool:
    rels = releases_root(install)
    os.makedirs(rels, mode=0o755, exist_ok=True)
    rels = real(rels)
    try:
        target = real(path)
    except OSError:
        return False
    try:
        common = os.path.commonpath([rels, target])
    except ValueError:
        return False
    return common == rels and target != rels


def rel_from_install(install: str, path: str) -> str:
    inst = real(install)
    target = real(path)
    rel = os.path.relpath(target, inst)
    if rel.startswith("..") or os.path.isabs(rel):
        die(f"path escapes install dir: {path}")
    if not rel.startswith("releases/") or rel == "releases":
        die(f"path is not a release tree: {path}")
    if ".." in Path(rel).parts:
        die(f"path traversal: {rel}")
    return rel.replace("\\", "/")


def release_id_ok(ver: str) -> bool:
    return bool(ver) and bool(ALLOWED_ID.match(ver))


def compose_ok(path: str) -> bool:
    return os.path.isfile(os.path.join(path, "docker-compose.yml"))


def read_version(tree: str) -> str:
    env = os.path.join(tree, ".env")
    if os.path.isfile(env):
        with open(env, encoding="utf-8", errors="replace") as fh:
            for line in fh:
                line = line.strip()
                if line.startswith("ARDTT_DEPLOY_VERSION="):
                    ver = line.split("=", 1)[1].strip().strip("\"'")
                    if release_id_ok(ver):
                        return ver
    for name in ("DEPLOY_VERSION",):
        p = os.path.join(tree, name)
        if os.path.isfile(p):
            ver = Path(p).read_text(encoding="utf-8", errors="replace").strip()
            if release_id_ok(ver):
                return ver
    return ""


def clean_next(install: str, name: str) -> None:
    prefix = f"{name}.next."
    try:
        for ent in os.listdir(install):
            if not ent.startswith(prefix):
                continue
            p = os.path.join(install, ent)
            if os.path.islink(p) or os.path.isfile(p):
                try:
                    os.unlink(p)
                except OSError:
                    pass
    except FileNotFoundError:
        pass


def validate_pointer(install: str, name: str) -> str:
    if name not in POINTER_NAMES:
        die(f"bad pointer name {name}")
    path = os.path.join(install, name)
    if not os.path.lexists(path):
        die(f"{name} missing")
    if not os.path.islink(path):
        die(f"{name} is not a symlink")
    target = real(path)
    if not under_releases(install, target):
        die(f"{name} escapes releases/: {target}")
    if not compose_ok(target):
        die(f"{name} target has no docker-compose.yml")
    return rel_from_install(install, target)


def replace_symlink(install: str, name: str, rel_target: str) -> None:
    if name not in POINTER_NAMES:
        die(f"bad pointer name {name}")
    if os.path.isabs(rel_target) or ".." in Path(rel_target).parts:
        die(f"bad relative target {rel_target}")
    if not rel_target.startswith("releases/") or rel_target == "releases":
        die("target must be releases/<id>")
    dest = os.path.join(install, name)
    abs_target = os.path.join(install, rel_target)
    if not under_releases(install, abs_target):
        die(f"target not inside releases/: {rel_target}")
    if not os.path.isdir(abs_target):
        die(f"target is not a directory: {rel_target}")
    if not compose_ok(abs_target):
        die(f"target has no docker-compose.yml: {rel_target}")
    if os.path.isdir(dest) and not os.path.islink(dest):
        die(f"{name} is a legacy directory; migrate first")
    clean_next(install, name)
    nonce = f"{os.getpid():x}{os.urandom(4).hex()}"
    tmp = os.path.join(install, f"{name}.next.{nonce}")
    try:
        os.symlink(rel_target, tmp)
        if real(tmp) != real(abs_target):
            die("temporary pointer realpath mismatch")
        if not under_releases(install, tmp):
            die("temporary pointer escaped releases/")
        os.rename(tmp, dest)
        fsync_dir(install)
    except Exception:
        try:
            if os.path.lexists(tmp):
                os.unlink(tmp)
        except OSError:
            pass
        raise


def unique_id(install: str, ver: str) -> str:
    base = ver if release_id_ok(ver) else f"legacy-{int(time.time())}"
    dest = os.path.join(install, "releases", base)
    if not os.path.exists(dest):
        return base
    i = 0
    while True:
        cand = f"{base}.migrated-{int(time.time())}-{i}"
        if release_id_ok(cand) and not os.path.exists(os.path.join(install, "releases", cand)):
            return cand
        i += 1
        if i > 20:
            die("could not allocate release id")


def migrate_pointer(install: str, name: str) -> str | None:
    if name not in POINTER_NAMES:
        die(f"bad pointer name {name}")
    os.makedirs(os.path.join(install, "releases"), mode=0o755, exist_ok=True)
    os.makedirs(os.path.join(install, "state"), mode=0o700, exist_ok=True)
    clean_next(install, name)
    path = os.path.join(install, name)
    if os.path.islink(path):
        try:
            return validate_pointer(install, name)
        except SystemExit:
            # dangling or escaped: leave for recover()
            return None
    if not os.path.exists(path):
        return None
    if not os.path.isdir(path):
        die(f"{name} exists and is not a directory or symlink")
    if not compose_ok(path):
        return None
    ver = read_version(path)
    if not ver:
        ver = unique_id(install, f"legacy-{name}")
    dest = os.path.join(install, "releases", ver)
    path_real = real(path)
    if os.path.exists(dest):
        dest_real = real(dest)
        if path_real == dest_real:
            replace_symlink(install, name, f"releases/{ver}")
            return f"releases/{ver}"
        # Keep existing release; park the directory copy, then point at dest if valid.
        if compose_ok(dest) and under_releases(install, dest):
            park_id = unique_id(install, f"{ver}.copy")
            park = os.path.join(install, "releases", park_id)
            os.rename(path, park)
            fsync_dir(os.path.join(install, "releases"))
            replace_symlink(install, name, f"releases/{ver}")
            # Directory current/previous was a leftover copy of dest.
            shutil.rmtree(park, ignore_errors=True)
            return f"releases/{ver}"
        ver = unique_id(install, ver)
        dest = os.path.join(install, "releases", ver)
    os.rename(path, dest)
    fsync_dir(os.path.join(install, "releases"))
    replace_symlink(install, name, f"releases/{ver}")
    return f"releases/{ver}"


def recover(install: str) -> None:
    os.makedirs(os.path.join(install, "releases"), mode=0o755, exist_ok=True)
    for name in POINTER_NAMES:
        clean_next(install, name)
        path = os.path.join(install, name)
        if os.path.isdir(path) and not os.path.islink(path):
            migrate_pointer(install, name)
            continue
        if os.path.islink(path):
            try:
                validate_pointer(install, name)
            except SystemExit:
                try:
                    os.unlink(path)
                except OSError:
                    pass
    current = os.path.join(install, "current")
    if os.path.lexists(current):
        return
    # Recreate current from DEPLOY_VERSION / previous if unique.
    ver = ""
    for p in (
        os.path.join(install, "data", "DEPLOY_VERSION"),
        os.path.join(install, "DEPLOY_VERSION"),
    ):
        if os.path.isfile(p):
            ver = Path(p).read_text(encoding="utf-8", errors="replace").strip()
            if release_id_ok(ver):
                break
            ver = ""
    if ver:
        dest = os.path.join(install, "releases", ver)
        if compose_ok(dest) and under_releases(install, dest):
            replace_symlink(install, "current", f"releases/{ver}")
            return
    prev = os.path.join(install, "previous")
    if os.path.islink(prev):
        try:
            rel = validate_pointer(install, "previous")
            replace_symlink(install, "current", rel)
        except SystemExit:
            return


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        die("atomic-pointer.py replace|migrate|recover|validate|relpath|clean-next ...")
    cmd = argv[1]
    if cmd == "replace":
        if len(argv) != 5:
            die("replace INSTALL_DIR NAME REL_TARGET")
        replace_symlink(argv[2], argv[3], argv[4])
        return 0
    if cmd == "migrate":
        if len(argv) != 4:
            die("migrate INSTALL_DIR NAME")
        migrate_pointer(argv[2], argv[3])
        return 0
    if cmd == "recover":
        if len(argv) != 3:
            die("recover INSTALL_DIR")
        recover(argv[2])
        return 0
    if cmd == "validate":
        if len(argv) != 4:
            die("validate INSTALL_DIR NAME")
        print(validate_pointer(argv[2], argv[3]))
        return 0
    if cmd == "relpath":
        if len(argv) != 4:
            die("relpath INSTALL_DIR PATH")
        install, path = argv[2], argv[3]
        if not under_releases(install, path):
            die(f"not inside releases/: {path}")
        print(rel_from_install(install, path))
        return 0
    if cmd == "clean-next":
        if len(argv) != 4:
            die("clean-next INSTALL_DIR NAME")
        clean_next(argv[2], argv[3])
        return 0
    die(f"unknown command {cmd}")
    return 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
