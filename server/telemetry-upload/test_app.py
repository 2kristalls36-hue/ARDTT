#!/usr/bin/env python3
"""Tests for telemetry upload ticket numbers and client inbox status."""

from __future__ import annotations

import io
import json
import os
import tempfile
import unittest
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

os.environ.setdefault("TELEMETRY_LOG_ROOT", tempfile.mkdtemp(prefix="ardtt-telemetry-"))

from app import assign_ticket, app, log_root, read_ticket, ticket_marker  # noqa: E402


def _log_file(name: str, comment: str) -> tuple[str, io.BytesIO]:
    event = {
        "timestamp": 1,
        "event_type": "user_comment",
        "session_id": "user-comment",
        "data": {"comment": comment, "source": "testing_screen"},
    }
    payload = (json.dumps(event, ensure_ascii=False) + "\n").encode("utf-8")
    return name, io.BytesIO(payload)


class TelemetryUploadTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="ardtt-telemetry-")
        os.environ["TELEMETRY_LOG_ROOT"] = self.tmp.name
        os.environ.pop("TELEMETRY_REVIEW_TOKEN", None)
        self.client = app.test_client()

    def tearDown(self):
        self.tmp.cleanup()

    def upload(self, client_id: str, filename: str, comment: str = "не поднимается обход"):
        name, body = _log_file(filename, comment)
        return self.client.post(
            "/api/upload-log",
            data={"client_id": client_id, "file": (body, name)},
            content_type="multipart/form-data",
        )

    def test_upload_assigns_sequential_tickets(self):
        first = self.upload("client_aaa", "a.json").get_json()
        second = self.upload("client_bbb", "b.json").get_json()
        self.assertTrue(first["ok"])
        self.assertEqual(first["ticket"], 1)
        self.assertFalse(first["read"])
        self.assertEqual(second["ticket"], 2)
        self.assertEqual(log_root(), Path(self.tmp.name))

    def test_reupload_same_file_keeps_ticket_and_resets_read(self):
        client_id = "client_aaa"
        filename = "same.json"
        first = self.upload(client_id, filename).get_json()
        mark = self.client.post(
            f"/api/logs/{client_id}/{filename}/read",
            json={"processed_by": "author", "note": "разобрано"},
        )
        self.assertEqual(mark.status_code, 200)
        again = self.upload(client_id, filename, comment="повтор").get_json()
        self.assertEqual(again["ticket"], first["ticket"])
        self.assertFalse(again["read"])
        inbox = self.client.get(f"/api/logs/{client_id}/status").get_json()
        self.assertEqual(inbox["count"], 1)
        self.assertEqual(inbox["logs"][0]["ticket"], first["ticket"])
        self.assertFalse(inbox["logs"][0]["read"])

    def test_client_inbox_does_not_require_review_auth(self):
        self.upload("client_aaa", "a.json", comment="после Wi‑Fi")
        os.environ["TELEMETRY_REVIEW_TOKEN"] = "secret"
        inbox = self.client.get("/api/logs/client_aaa/status")
        self.assertEqual(inbox.status_code, 200)
        payload = inbox.get_json()
        self.assertEqual(payload["logs"][0]["comment"], "после Wi‑Fi")
        denied = self.client.get("/api/logs")
        self.assertEqual(denied.status_code, 401)
        allowed = self.client.get(
            "/api/logs",
            headers={"Authorization": "Bearer secret"},
        )
        self.assertEqual(allowed.status_code, 200)
        self.assertEqual(allowed.get_json()["logs"][0]["ticket"], 1)

    def test_one_file_status_and_unknown_client(self):
        self.upload("client_aaa", "a.json")
        found = self.client.get("/api/logs/client_aaa/a.json/status")
        self.assertEqual(found.status_code, 200)
        self.assertEqual(found.get_json()["ticket"], 1)
        missing = self.client.get("/api/logs/client_missing/status")
        self.assertEqual(missing.status_code, 200)
        self.assertEqual(missing.get_json()["count"], 0)
        missing_file = self.client.get("/api/logs/client_aaa/nope.json/status")
        self.assertEqual(missing_file.status_code, 404)

    def test_mark_read_then_comment_is_visible_in_inbox(self):
        self.upload("client_aaa", "a.json")
        marked = self.client.post(
            "/api/logs/client_aaa/a.json/read",
            json={"processed_by": "cursor-agent"},
        )
        self.assertEqual(marked.status_code, 200)
        inbox = self.client.get("/api/logs/client_aaa/status").get_json()
        item = inbox["logs"][0]
        self.assertTrue(item["read"])
        self.assertIsNone(item["reply"])
        again = self.client.post(
            "/api/logs/client_aaa/a.json/read",
            json={"processed_by": "cursor-agent"},
        )
        self.assertEqual(again.status_code, 200)
        commented = self.client.post(
            "/api/logs/client_aaa/a.json/comment",
            json={"processed_by": "cursor-agent", "reply": "обход падает на смене Wi‑Fi"},
        )
        self.assertEqual(commented.status_code, 200)
        self.assertEqual(commented.get_json()["reply"], "обход падает на смене Wi‑Fi")
        inbox = self.client.get("/api/logs/client_aaa/status").get_json()
        item = inbox["logs"][0]
        self.assertTrue(item["read"])
        self.assertEqual(item["reply"], "обход падает на смене Wi‑Fi")
        blank = self.client.post(
            "/api/logs/client_aaa/a.json/comment",
            json={"processed_by": "cursor-agent", "reply": "  "},
        )
        self.assertEqual(blank.status_code, 400)

    def test_lookup_by_ticket_number(self):
        self.upload("client_aaa", "a.json")
        self.upload("client_bbb", "b.json")
        found = self.client.get("/api/logs/ticket/2")
        self.assertEqual(found.status_code, 200)
        self.assertEqual(found.get_json()["client_id"], "client_bbb")
        listed = self.client.get("/api/logs?ticket=1")
        self.assertEqual(listed.status_code, 200)
        payload = listed.get_json()
        self.assertEqual(payload["count"], 1)
        self.assertEqual(payload["logs"][0]["ticket"], 1)
        missing = self.client.get("/api/logs/ticket/99")
        self.assertEqual(missing.status_code, 404)

    def test_read_without_note_does_not_wipe_reply(self):
        self.upload("client_aaa", "a.json")
        self.client.post(
            "/api/logs/client_aaa/a.json/comment",
            json={"reply": "уже ответили"},
        )
        self.client.post("/api/logs/client_aaa/a.json/read", json={"processed_by": "cursor-agent"})
        inbox = self.client.get("/api/logs/client_aaa/status").get_json()
        self.assertEqual(inbox["logs"][0]["reply"], "уже ответили")
        self.assertTrue(inbox["logs"][0]["read"])
        self.client.post(
            "/api/logs/client_aaa/a.json/read",
            json={"processed_by": "cursor-agent", "note": "   "},
        )
        inbox = self.client.get("/api/logs/client_aaa/status").get_json()
        self.assertEqual(inbox["logs"][0]["reply"], "уже ответили")

    def test_concurrent_assign_same_file_keeps_one_ticket(self):
        self.upload("client_aaa", "race.json")
        path = log_root() / "client_aaa" / "race.json"
        ticket_marker(path).unlink()
        with ThreadPoolExecutor(max_workers=8) as pool:
            numbers = list(pool.map(lambda _: assign_ticket("client_aaa", path), range(8)))
        self.assertEqual(len(set(numbers)), 1)
        self.assertEqual(read_ticket(path), numbers[0])


if __name__ == "__main__":
    unittest.main()
