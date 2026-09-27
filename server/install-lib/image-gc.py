#!/usr/bin/env python3
"""Pick ARDTT image tags that are safe to delete.

Keeps the running image, the previous release (rollback), and any image a
container still uses. Foreign images (telemt and anything else) are never
selected. There is no global image sweep.
"""
from __future__ import annotations

import sys

OWNED_REPOS = {"ardtt/server", "stack-ardtt"}


def _norm_id(value: str) -> str:
    text = (value or "").strip().lower()
    if text.startswith("sha256:"):
        text = text[7:]
    return text


def _same_id(left: str, right: str) -> bool:
    a, b = _norm_id(left), _norm_id(right)
    if not a or not b:
        return False
    return a == b or a.startswith(b) or b.startswith(a)


def _norm_repo(repo: str) -> str:
    text = (repo or "").strip()
    for prefix in ("docker.io/", "index.docker.io/"):
        if text.startswith(prefix):
            text = text[len(prefix) :]
    if text.startswith("library/"):
        text = text[len("library/") :]
    return text


def _norm_ref(ref: str) -> str:
    text = (ref or "").strip()
    if not text or text in {"<none>", "none"}:
        return ""
    if "@" in text:
        text = text.split("@", 1)[0]
    return text


def _owned(repo: str) -> bool:
    name = _norm_repo(repo)
    if not name or name in {"<none>", "none"}:
        return False
    if name in OWNED_REPOS:
        return True
    return name.startswith("ardtt/")


def _parse_images(lines: list[str]) -> list[dict]:
    out = []
    for line in lines:
        line = line.rstrip("\n")
        if not line.strip():
            continue
        parts = line.split("\t")
        if len(parts) < 3:
            continue
        repo, tag, image_id = parts[0].strip(), parts[1].strip(), parts[2].strip()
        ref = ""
        if tag and tag not in {"<none>", "none"}:
            ref = f"{_norm_repo(repo)}:{tag}"
        out.append(
            {
                "repo": _norm_repo(repo),
                "tag": tag,
                "id": image_id,
                "ref": ref,
            }
        )
    return out


def _parse_keep(lines: list[str]) -> tuple[set[str], list[str]]:
    refs: set[str] = set()
    ids: list[str] = []
    for line in lines:
        line = line.rstrip("\n")
        if not line.strip():
            continue
        kind, _, value = line.partition("\t")
        value = value.strip()
        if not value and "\t" not in line:
            # A bare ref/id from a simple fixture.
            value = kind.strip()
            kind = "ref" if ":" in value and not value.startswith("sha256:") else "id"
        if kind == "id":
            if _norm_id(value):
                ids.append(value.strip())
            continue
        ref = _norm_ref(value)
        if ref:
            refs.add(ref)
        elif _norm_id(value):
            ids.append(value.strip())
    return refs, ids


def _kept(image: dict, refs: set[str], ids: list[str]) -> bool:
    if image["ref"] and image["ref"] in refs:
        return True
    return any(_same_id(image["id"], keep) for keep in ids)


def plan(image_lines: list[str], keep_lines: list[str]) -> list[str]:
    images = _parse_images(image_lines)
    refs, ids = _parse_keep(keep_lines)
    kept_ids = {_norm_id(img["id"]) for img in images if _kept(img, refs, ids)}
    # A keep-ref can name an image that is not in the list yet (about to be
    # loaded). Ids already collected above cover running containers.
    remove: list[str] = []
    seen: set[str] = set()
    grouped: dict[str, list[dict]] = {}
    for img in images:
        if not _owned(img["repo"]):
            continue
        if _norm_id(img["id"]) in kept_ids:
            continue
        grouped.setdefault(_norm_id(img["id"]), []).append(img)
    for group in grouped.values():
        tagged = [img for img in group if img["ref"]]
        targets = [img["ref"] for img in tagged] or [group[0]["id"]]
        for target in targets:
            if target and target not in seen:
                seen.add(target)
                remove.append(target)
    return remove


def main(argv: list[str]) -> int:
    if len(argv) != 4 or argv[1] != "plan":
        sys.stderr.write("usage: image-gc.py plan IMAGES_TSV KEEP_TSV\n")
        return 2
    with open(argv[2], encoding="utf-8") as fh:
        images = fh.readlines()
    with open(argv[3], encoding="utf-8") as fh:
        keep = fh.readlines()
    for line in plan(images, keep):
        sys.stdout.write(line + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
