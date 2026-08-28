#!/usr/bin/env python3
"""Accept telemetry log uploads from nonameVPN Android client."""

from __future__ import annotations

import hmac
import json
import os
import re
from datetime import datetime, timezone
from pathlib import Path

from flask import Flask, jsonify, request
from werkzeug.utils import secure_filename

app = Flask(__name__)

LOG_ROOT = Path(os.environ.get("TELEMETRY_LOG_ROOT", "/var/logs/app"))
MAX_CONTENT_LENGTH = 120 * 1024 * 1024  # 120 MB
app.config["MAX_CONTENT_LENGTH"] = MAX_CONTENT_LENGTH
CLIENT_ID_RE = re.compile(r"^[a-zA-Z0-9._-]{1,128}$")
REVIEW_TOKEN = os.environ.get("TELEMETRY_REVIEW_TOKEN", "").strip()


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def read_marker(log_path: Path) -> Path:
    return log_path.with_name(f".{log_path.name}.read.json")


def review_authorized() -> bool:
    if REVIEW_TOKEN:
        header = request.headers.get("Authorization", "")
        supplied = header.removeprefix("Bearer ").strip()
        return hmac.compare_digest(supplied, REVIEW_TOKEN)
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
    path = LOG_ROOT / client_id / safe_name
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

    dest_dir = LOG_ROOT / client_id
    dest_dir.mkdir(parents=True, exist_ok=True)
    dest_path = dest_dir / filename
    uploaded.save(dest_path)
    # A replacement upload is a new review revision.
    read_marker(dest_path).unlink(missing_ok=True)

    return jsonify({
        "ok": True,
        "client_id": client_id,
        "path": str(dest_path),
        "bytes": dest_path.stat().st_size,
        "comment": embedded_user_comment(dest_path),
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

    items = []
    if LOG_ROOT.is_dir():
        for path in sorted(LOG_ROOT.glob("*/*.json"), key=lambda item: item.stat().st_mtime, reverse=True):
            if path.name.startswith("."):
                continue
            marker = read_marker(path)
            is_read = marker.is_file()
            if status == "read" and not is_read:
                continue
            if status == "unread" and is_read:
                continue
            review = None
            if is_read:
                try:
                    review = json.loads(marker.read_text(encoding="utf-8"))
                except (OSError, ValueError):
                    review = {"read": True}
            items.append({
                "client_id": path.parent.name,
                "filename": path.name,
                "path": str(path),
                "bytes": path.stat().st_size,
                "uploaded_at": datetime.fromtimestamp(path.stat().st_mtime, timezone.utc).isoformat(),
                "comment": embedded_user_comment(path),
                "read": is_read,
                "review": review,
            })
    return jsonify({"ok": True, "count": len(items), "logs": items})


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
    note = str(body.get("note") or "").strip()[:2048]
    marker = read_marker(path)
    payload = {
        "read": True,
        "processed_at": utc_now(),
        "processed_by": processed_by or "cursor-agent",
        "note": note,
        "log": str(path),
    }
    tmp = marker.with_suffix(marker.suffix + ".tmp")
    tmp.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    tmp.replace(marker)
    return jsonify({"ok": True, **payload})


if __name__ == "__main__":
    listen = os.environ.get("TELEMETRY_LISTEN", "0.0.0.0:9200")
    host, port = listen.rsplit(":", 1)
    app.run(host=host, port=int(port), debug=False)
