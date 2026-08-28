#!/usr/bin/env python3
"""Accept telemetry log uploads from nonameVPN Android client."""

from __future__ import annotations

import os
import re
from pathlib import Path

from flask import Flask, jsonify, request
from werkzeug.utils import secure_filename

app = Flask(__name__)

LOG_ROOT = Path(os.environ.get("TELEMETRY_LOG_ROOT", "/var/logs/app"))
MAX_CONTENT_LENGTH = 120 * 1024 * 1024  # 120 MB
CLIENT_ID_RE = re.compile(r"^[a-zA-Z0-9._-]{1,128}$")


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

    return jsonify({
        "ok": True,
        "client_id": client_id,
        "path": str(dest_path),
        "bytes": dest_path.stat().st_size,
    })


if __name__ == "__main__":
    listen = os.environ.get("TELEMETRY_LISTEN", "0.0.0.0:9200")
    host, port = listen.rsplit(":", 1)
    app.run(host=host, port=int(port), debug=False)
