#!/usr/bin/env python3
"""Delete only a canonical path inside an allowed ARDTT subtree."""
from __future__ import annotations

import os
import shutil
import sys

FORBIDDEN_EXACT = {"/", "/opt", "/opt/ardtt"}


def die(msg: str) -> None:
    print(msg, file=sys.stderr)
    raise SystemExit(1)


def real(p: str) -> str:
    return os.path.realpath(p)


def allowed_roots(install: str) -> list[str]:
    inst = real(install)
    return [
        os.path.join(inst, "releases"),
        os.path.join(inst, "incoming"),
        os.path.join(inst, "staging"),
        os.path.join(inst, "cache"),
        os.path.join(inst, "logs"),
        os.path.join(inst, "state", "rollback"),
    ]


def under(root: str, path: str) -> bool:
    try:
        return os.path.commonpath([real(root), real(path)]) == real(root) and real(path) != real(root)
    except ValueError:
        return False


def main(argv: list[str]) -> int:
    if len(argv) < 3:
        die("safe-rm.py INSTALL_DIR PATH [PATH...]")
    install = argv[1]
    if not install:
        die("empty install dir")
    inst = real(install)
    home = real(os.path.expanduser("~")) if os.environ.get("HOME") else ""
    roots = [real(r) for r in allowed_roots(install)]
    for raw in argv[2:]:
        if not raw or not raw.strip():
            die("empty path")
        if os.path.islink(raw) and not os.path.isdir(raw):
            # file symlink: delete the link, not the target, only if the link itself is under a root
            parent = real(os.path.dirname(raw) or ".")
            if not any(under(r, os.path.join(parent, os.path.basename(raw))) or real(os.path.dirname(raw)) == r or under(r, parent) for r in roots):
                # check the link path via abspath without following
                abs_link = os.path.abspath(raw)
                if not any(abs_link.startswith(r + os.sep) for r in roots):
                    die(f"symlink not under allowed root: {raw}")
            os.unlink(raw)
            continue
        # Refuse to follow a pointer whose immediate path is a symlink to outside.
        if os.path.islink(raw):
            abs_link = os.path.abspath(raw)
            if not any(abs_link.startswith(r + os.sep) for r in roots):
                die(f"refusing symlink path: {raw}")
            # If the symlink itself lives under releases/ but points outside, still refuse recursive delete of target.
            die(f"refusing to rm -rf a symlink: {raw}")
        target = real(raw)
        if target in FORBIDDEN_EXACT or target == inst or (home and target == home):
            die(f"refusing to delete {target}")
        if not any(under(r, target) or target == r for r in roots):
            # allow deleting a child of an allowed root; not the install root
            die(f"path not inside ARDTT-owned subtree: {raw} -> {target}")
        if target == inst:
            die("refusing to delete install dir")
        if os.path.isdir(target) and not os.path.islink(raw):
            shutil.rmtree(target)
        elif os.path.lexists(target):
            os.unlink(target)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
