#!/usr/bin/env python3
"""Accept telemetry log uploads from the ARDTT Android client."""

from __future__ import annotations

import fcntl
import hmac
import json
import os
import re
from datetime import datetime, timezone
from pathlib import Path

from flask import Flask, jsonify, request
from werkzeug.utils import secure_filename

app = Flask(__name__)

MAX_CONTENT_LENGTH = 120 * 1024 * 1024  # 120 MB
app.config["MAX_CONTENT_LENGTH"] = MAX_CONTENT_LENGTH
CLIENT_ID_RE = re.compile(r"^[a-zA-Z0-9._-]{1,128}$")


def log_root() -> Path:
    return Path(os.environ.get("TELEMETRY_LOG_ROOT", "/var/logs/app"))


def review_token() -> str:
    return os.environ.get("TELEMETRY_REVIEW_TOKEN", "").strip()


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def read_marker(log_path: Path) -> Path:
    return log_path.with_name(f".{log_path.name}.read.json")


def ticket_marker(log_path: Path) -> Path:
    return log_path.with_name(f".{log_path.name}.ticket.json")


def review_authorized() -> bool:
    token = review_token()
    if token:
        header = request.headers.get("Authorization", "")
        supplied = header.removeprefix("Bearer ").strip()
        return hmac.compare_digest(supplied, token)
    # Without an explicit token, review operations stay local to the VPS.
    return request.remote_addr in {"127.0.0.1", "::1"}


def require_review_auth():
    if review_authorized():
        return None
    return jsonify({"error": "review authorization required"}), 401


def validated_log_path(client_id: str, filename: str) -> Path | None:
    if not CLIENT_ID_RE.match(client_id):
        return None
    safe_name = secure_filename(filename)
    if safe_name != filename or not safe_name.endswith(".json"):
        return None
    path = log_root() / client_id / safe_name
    if not path.is_file():
        return None
    return path


def embedded_user_comment(path: Path) -> str | None:
    """Find the latest embedded user_comment without loading a 100 MB log."""
    try:
        with path.open("rb") as handle:
            size = path.stat().st_size
            handle.seek(max(0, size - 64 * 1024))
            tail = handle.read().decode("utf-8", errors="replace")
        for line in reversed(tail.splitlines()):
            event = json.loads(line)
            if event.get("event_type") == "user_comment":
                value = (event.get("data") or {}).get("comment")
                if isinstance(value, str) and value.strip():
                    return value.strip()
    except (OSError, ValueError, TypeError):
        return None
    return None


def read_ticket(path: Path) -> int:
    marker = ticket_marker(path)
    try:
        data = json.loads(marker.read_text(encoding="utf-8"))
        number = int(data.get("ticket") or 0)
        return number if number > 0 else 0
    except (OSError, ValueError, TypeError):
        return 0


def max_ticket_in_sidecars(root: Path) -> int:
    highest = 0
    if not root.is_dir():
        return 0
    for marker in root.glob("*/*.ticket.json"):
        if not marker.name.startswith("."):
            continue
        try:
            data = json.loads(marker.read_text(encoding="utf-8"))
            highest = max(highest, int(data.get("ticket") or 0))
        except (OSError, ValueError, TypeError):
            continue
    return highest


def next_ticket_number() -> int:
    """Atomically allocate the next global appeal number."""
    root = log_root()
    root.mkdir(parents=True, exist_ok=True)
    seq_path = root / ".ticket_seq"
    with seq_path.open("a+") as handle:
        fcntl.flock(handle, fcntl.LOCK_EX)
        try:
            handle.seek(0)
            raw = handle.read().strip()
            current = int(raw) if raw.isdigit() else 0
            if current <= 0:
                current = max_ticket_in_sidecars(root)
            nxt = current + 1
            handle.seek(0)
            handle.truncate()
            handle.write(str(nxt))
            handle.flush()
            os.fsync(handle.fileno())
            return nxt
        finally:
            fcntl.flock(handle, fcntl.LOCK_UN)


def write_json(path: Path, payload: dict) -> None:
    tmp = path.with_name(path.name + ".tmp")
    tmp.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    tmp.replace(path)


def assign_ticket(client_id: str, dest_path: Path) -> int:
    existing = read_ticket(dest_path)
    if existing > 0:
        return existing
    number = next_ticket_number()
    write_json(
        ticket_marker(dest_path),
        {
            "ticket": number,
            "client_id": client_id,
            "filename": dest_path.name,
            "assigned_at": utc_now(),
        },
    )
    return number


def review_payload(path: Path) -> dict | None:
    marker = read_marker(path)
    if not marker.is_file():
        return None
    try:
        data = json.loads(marker.read_text(encoding="utf-8"))
        if isinstance(data, dict):
            return data
    except (OSError, ValueError):
        pass
    return {"read": True}


def author_reply_text(review: dict | None) -> str:
    if not review:
        return ""
    for key in ("reply", "note"):
        value = review.get(key)
        if isinstance(value, str) and value.strip():
            return value.strip()
    return ""


def log_path_from_ticket_marker(marker: Path) -> Path | None:
    name = marker.name
    if not (name.startswith(".") and name.endswith(".ticket.json")):
        return None
    filename = name[1 : -len(".ticket.json")]
    candidate = marker.parent / filename
    return candidate if candidate.is_file() else None


def find_by_ticket(number: int) -> tuple[str, Path] | None:
    if number <= 0:
        return None
    root = log_root()
    if not root.is_dir():
        return None
    for marker in root.glob("*/*.ticket.json"):
        if not marker.name.startswith("."):
            continue
        try:
            data = json.loads(marker.read_text(encoding="utf-8"))
            if int(data.get("ticket") or 0) != number:
                continue
        except (OSError, ValueError, TypeError):
            continue
        filename = str(data.get("filename") or "").strip()
        path = marker.parent / filename if filename else None
        if path is None or not path.is_file():
            path = log_path_from_ticket_marker(marker)
        if path is None:
            continue
        return marker.parent.name, path
    return None


def upsert_review(
    path: Path,
    *,
    processed_by: str,
    reply: str | None = None,
    set_reply: bool = False,
) -> dict:
    existing = review_payload(path) or {}
    now = utc_now()
    already_read = bool(existing.get("read"))
    current_reply = author_reply_text(existing)
    if set_reply:
        current_reply = (reply or "").strip()
    payload = {
        "read": True,
        "processed_at": existing.get("processed_at") if already_read else now,
        "processed_by": processed_by or existing.get("processed_by") or "cursor-agent",
        "note": current_reply,
        "reply": current_reply,
        "log": str(path),
        "ticket": read_ticket(path) or existing.get("ticket"),
    }
    if not payload["processed_at"]:
        payload["processed_at"] = now
    if set_reply:
        payload["replied_at"] = now
    elif existing.get("replied_at"):
        payload["replied_at"] = existing["replied_at"]
    write_json(read_marker(path), payload)
    return payload


def log_public_fields(path: Path, client_id: str) -> dict:
    review = review_payload(path)
    is_read = review is not None
    reply = author_reply_text(review)
    return {
        "client_id": client_id,
        "filename": path.name,
        "bytes": path.stat().st_size,
        "uploaded_at": datetime.fromtimestamp(path.stat().st_mtime, timezone.utc).isoformat(),
        "comment": embedded_user_comment(path),
        "ticket": read_ticket(path) or None,
        "read": is_read,
        "reply": reply or None,
        "replied_at": (review or {}).get("replied_at"),
        "review": review,
    }


def iter_client_logs(client_id: str):
    folder = log_root() / client_id
    if not folder.is_dir():
        return
    paths = [path for path in folder.glob("*.json") if path.is_file() and not path.name.startswith(".")]
    for path in sorted(paths, key=lambda item: item.stat().st_mtime, reverse=True):
        yield path


@app.route("/health", methods=["GET"])
def health():
    return jsonify({"ok": True})


@app.route("/api/upload-log", methods=["POST"])
def upload_log():
    client_id = (request.form.get("client_id") or "").strip()
    if not client_id or not CLIENT_ID_RE.match(client_id):
        return jsonify({"error": "invalid client_id"}), 400

    uploaded = request.files.get("file")
    if uploaded is None or uploaded.filename == "":
        return jsonify({"error": "file is required"}), 400

    filename = secure_filename(uploaded.filename)
    if not filename.endswith(".json"):
        return jsonify({"error": "only .json log files are accepted"}), 400

    dest_dir = log_root() / client_id
    dest_dir.mkdir(parents=True, exist_ok=True)
    dest_path = dest_dir / filename
    uploaded.save(dest_path)
    # A replacement upload is a new review revision, but keeps the ticket number.
    read_marker(dest_path).unlink(missing_ok=True)
    ticket = assign_ticket(client_id, dest_path)

    return jsonify({
        "ok": True,
        "client_id": client_id,
        "filename": filename,
        "path": str(dest_path),
        "bytes": dest_path.stat().st_size,
        "comment": embedded_user_comment(dest_path),
        "ticket": ticket,
        "read": False,
    })


@app.route("/api/logs", methods=["GET"])
def list_logs():
    denied = require_review_auth()
    if denied:
        return denied
    status = (request.args.get("status") or "all").strip().lower()
    if status not in {"all", "read", "unread"}:
        return jsonify({"error": "status must be all, read or unread"}), 400
    ticket_raw = (request.args.get("ticket") or "").strip()
    ticket_filter = int(ticket_raw) if ticket_raw.isdigit() else 0

    items = []
    root = log_root()
    if root.is_dir():
        for path in sorted(root.glob("*/*.json"), key=lambda item: item.stat().st_mtime, reverse=True):
            if path.name.startswith("."):
                continue
            fields = log_public_fields(path, path.parent.name)
            if ticket_filter and fields.get("ticket") != ticket_filter:
                continue
            if status == "read" and not fields["read"]:
                continue
            if status == "unread" and fields["read"]:
                continue
            items.append({
                **fields,
                "path": str(path),
            })
    return jsonify({"ok": True, "count": len(items), "logs": items})


@app.route("/api/logs/ticket/<int:ticket>", methods=["GET"])
def log_by_ticket(ticket: int):
    denied = require_review_auth()
    if denied:
        return denied
    found = find_by_ticket(ticket)
    if found is None:
        return jsonify({"error": "log not found"}), 404
    client_id, path = found
    return jsonify({"ok": True, **log_public_fields(path, client_id), "path": str(path)})


@app.route("/api/logs/<client_id>/status", methods=["GET"])
def client_inbox(client_id: str):
    """Public-to-the-device inbox: ticket numbers and author-read flags."""
    if not CLIENT_ID_RE.match(client_id):
        return jsonify({"error": "invalid client_id"}), 400
    items = [log_public_fields(path, client_id) for path in iter_client_logs(client_id)]
    return jsonify({
        "ok": True,
        "client_id": client_id,
        "count": len(items),
        "logs": items,
    })


@app.route("/api/logs/<client_id>/<filename>/status", methods=["GET"])
def log_status(client_id: str, filename: str):
    path = validated_log_path(client_id, filename)
    if path is None:
        return jsonify({"error": "log not found"}), 404
    return jsonify({"ok": True, **log_public_fields(path, client_id)})


@app.route("/api/logs/<client_id>/<filename>/read", methods=["POST"])
def mark_log_read(client_id: str, filename: str):
    denied = require_review_auth()
    if denied:
        return denied
    path = validated_log_path(client_id, filename)
    if path is None:
        return jsonify({"error": "log not found"}), 404

    body = request.get_json(silent=True) or {}
    processed_by = str(body.get("processed_by") or "cursor-agent").strip()[:128]
    reply = None
    set_reply = False
    if "reply" in body:
        reply = str(body.get("reply") or "").strip()[:2048]
        set_reply = True
    elif "note" in body:
        note = str(body.get("note") or "").strip()[:2048]
        if note:
            reply = note
            set_reply = True
    payload = upsert_review(
        path,
        processed_by=processed_by or "cursor-agent",
        reply=reply,
        set_reply=set_reply,
    )
    return jsonify({"ok": True, **payload})


@app.route("/api/logs/<client_id>/<filename>/comment", methods=["POST"])
def mark_log_comment(client_id: str, filename: str):
    denied = require_review_auth()
    if denied:
        return denied
    path = validated_log_path(client_id, filename)
    if path is None:
        return jsonify({"error": "log not found"}), 404

    body = request.get_json(silent=True) or {}
    processed_by = str(body.get("processed_by") or "cursor-agent").strip()[:128]
    reply = str(body.get("reply") or body.get("note") or "").strip()[:2048]
    if not reply:
        return jsonify({"error": "reply is required"}), 400
    payload = upsert_review(
        path,
        processed_by=processed_by or "cursor-agent",
        reply=reply,
        set_reply=True,
    )
    return jsonify({"ok": True, **payload})


if __name__ == "__main__":
    listen = os.environ.get("TELEMETRY_LISTEN", "0.0.0.0:9200")
    host, port = listen.rsplit(":", 1)
    app.run(host=host, port=int(port), debug=False)
