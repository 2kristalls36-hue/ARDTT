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
from unittest import mock

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
        os.environ["TELEMETRY_UPLOAD_TOKEN"] = "upload-secret"
        os.environ["TELEMETRY_REVIEW_TOKEN"] = "review-secret"
        os.environ["TELEMETRY_MAX_UPLOAD_MB"] = "20"
        os.environ["TELEMETRY_QUOTA_MB"] = "50"
        self.client = app.test_client()

    def tearDown(self):
        self.tmp.cleanup()
        os.environ.pop("TELEMETRY_UPLOAD_TOKEN", None)
        os.environ.pop("TELEMETRY_REVIEW_TOKEN", None)

    def upload(self, client_id: str, filename: str, comment: str = "не поднимается обход"):
        name, body = _log_file(filename, comment)
        return self.client.post(
            "/api/upload-log",
            data={"client_id": client_id, "file": (body, name)},
            content_type="multipart/form-data",
            headers={"Authorization": "Bearer upload-secret"},
        )

    def review_headers(self):
        return {"Authorization": "Bearer review-secret"}

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
            headers=self.review_headers(),
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
        inbox = self.client.get("/api/logs/client_aaa/status")
        self.assertEqual(inbox.status_code, 200)
        payload = inbox.get_json()
        self.assertEqual(payload["logs"][0]["comment"], "после Wi‑Fi")
        denied = self.client.get("/api/logs")
        self.assertEqual(denied.status_code, 401)
        loopback_denied = self.client.get("/api/logs", environ_base={"REMOTE_ADDR": "127.0.0.1"})
        self.assertEqual(loopback_denied.status_code, 401)
        allowed = self.client.get(
            "/api/logs",
            headers=self.review_headers(),
        )
        self.assertEqual(allowed.status_code, 200)
        self.assertEqual(allowed.get_json()["logs"][0]["ticket"], 1)

    def test_upload_requires_bearer(self):
        name, body = _log_file("x.json", "нет токена")
        denied = self.client.post(
            "/api/upload-log",
            data={"client_id": "client_aaa", "file": (body, name)},
            content_type="multipart/form-data",
        )
        self.assertEqual(denied.status_code, 401)

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
            headers=self.review_headers(),
        )
        self.assertEqual(marked.status_code, 200)
        inbox = self.client.get("/api/logs/client_aaa/status").get_json()
        item = inbox["logs"][0]
        self.assertTrue(item["read"])
        self.assertIsNone(item["reply"])
        again = self.client.post(
            "/api/logs/client_aaa/a.json/read",
            json={"processed_by": "cursor-agent"},
            headers=self.review_headers(),
        )
        self.assertEqual(again.status_code, 200)
        commented = self.client.post(
            "/api/logs/client_aaa/a.json/comment",
            json={"processed_by": "cursor-agent", "reply": "обход падает на смене Wi‑Fi"},
            headers=self.review_headers(),
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
            headers=self.review_headers(),
        )
        self.assertEqual(blank.status_code, 400)

    def test_lookup_by_ticket_number(self):
        self.upload("client_aaa", "a.json")
        self.upload("client_bbb", "b.json")
        found = self.client.get("/api/logs/ticket/2", headers=self.review_headers())
        self.assertEqual(found.status_code, 200)
        self.assertEqual(found.get_json()["client_id"], "client_bbb")
        listed = self.client.get("/api/logs?ticket=1", headers=self.review_headers())
        self.assertEqual(listed.status_code, 200)
        payload = listed.get_json()
        self.assertEqual(payload["count"], 1)
        self.assertEqual(payload["logs"][0]["ticket"], 1)
        missing = self.client.get("/api/logs/ticket/99", headers=self.review_headers())
        self.assertEqual(missing.status_code, 404)

    def test_read_without_note_does_not_wipe_reply(self):
        self.upload("client_aaa", "a.json")
        self.client.post(
            "/api/logs/client_aaa/a.json/comment",
            json={"reply": "уже ответили"},
            headers=self.review_headers(),
        )
        self.client.post(
            "/api/logs/client_aaa/a.json/read",
            json={"processed_by": "cursor-agent"},
            headers=self.review_headers(),
        )
        inbox = self.client.get("/api/logs/client_aaa/status").get_json()
        self.assertEqual(inbox["logs"][0]["reply"], "уже ответили")
        self.assertTrue(inbox["logs"][0]["read"])
        self.client.post(
            "/api/logs/client_aaa/a.json/read",
            json={"processed_by": "cursor-agent", "note": "   "},
            headers=self.review_headers(),
        )
        inbox = self.client.get("/api/logs/client_aaa/status").get_json()
        self.assertEqual(inbox["logs"][0]["reply"], "уже ответили")

    def test_quota_per_client(self):
        os.environ["TELEMETRY_QUOTA_MB"] = "1"
        huge = "x" * (600 * 1024)
        name, body = _log_file("big.json", huge)
        first = self.client.post(
            "/api/upload-log",
            data={"client_id": "client_quota", "file": (body, name)},
            content_type="multipart/form-data",
            headers={"Authorization": "Bearer upload-secret"},
        )
        self.assertEqual(first.status_code, 200)
        name2, body2 = _log_file("big2.json", huge)
        second = self.client.post(
            "/api/upload-log",
            data={"client_id": "client_quota", "file": (body2, name2)},
            content_type="multipart/form-data",
            headers={"Authorization": "Bearer upload-secret"},
        )
        self.assertEqual(second.status_code, 429)

    def test_total_quota_caps_all_clients(self):
        huge = "x" * (600 * 1024)
        with mock.patch.dict(os.environ, {"TELEMETRY_TOTAL_QUOTA_MB": "1"}):
            first = self.upload("client_aaa", "big.json", comment=huge)
            second = self.upload("client_bbb", "big.json", comment=huge)
            self.assertEqual(first.status_code, 200)
            self.assertEqual(second.status_code, 507)
            self.assertEqual(second.get_json()["error"], "server storage quota exceeded")
            # Replacing an already stored file only counts the difference.
            again = self.upload("client_aaa", "big.json", comment=huge)
            self.assertEqual(again.status_code, 200)

    def test_total_quota_is_off_by_default(self):
        huge = "x" * (600 * 1024)
        with mock.patch.dict(os.environ):
            os.environ.pop("TELEMETRY_TOTAL_QUOTA_MB", None)
            for index in range(3):
                resp = self.upload(f"client_{index}", "big.json", comment=huge)
                self.assertEqual(resp.status_code, 200)

    def test_concurrent_assign_same_file_keeps_one_ticket(self):
        self.upload("client_aaa", "race.json")
        path = log_root() / "client_aaa" / "race.json"
        ticket_marker(path).unlink()
        with ThreadPoolExecutor(max_workers=8) as pool:
            numbers = list(pool.map(lambda _: assign_ticket("client_aaa", path), range(8)))
        self.assertEqual(len(set(numbers)), 1)
        self.assertEqual(read_ticket(path), numbers[0])


class PublicUploadTest(unittest.TestCase):
    """Central log server: TELEMETRY_UPLOAD_PUBLIC=1 and no upload token at all."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="ardtt-telemetry-public-")
        patcher = mock.patch.dict(
            os.environ,
            {
                "TELEMETRY_LOG_ROOT": self.tmp.name,
                "ARDTT_DATA": self.tmp.name,  # no users.json: no device tokens
                "TELEMETRY_REVIEW_TOKEN": "review-secret",
                "TELEMETRY_UPLOAD_PUBLIC": "1",
                "TELEMETRY_MAX_UPLOAD_MB": "20",
                "TELEMETRY_QUOTA_MB": "50",
            },
        )
        patcher.start()
        os.environ.pop("TELEMETRY_UPLOAD_TOKEN", None)
        os.environ.pop("TELEMETRY_TOTAL_QUOTA_MB", None)
        self.addCleanup(patcher.stop)
        self.addCleanup(self.tmp.cleanup)
        self.client = app.test_client()

    def post_upload(
        self,
        client_id: str = "client_pub",
        filename: str = "a.json",
        comment: str = "без токена",
        headers: dict | None = None,
    ):
        name, body = _log_file(filename, comment)
        return self.client.post(
            "/api/upload-log",
            data={"client_id": client_id, "file": (body, name)},
            content_type="multipart/form-data",
            headers=headers or {},
        )

    def test_upload_without_token_is_accepted(self):
        resp = self.post_upload()
        self.assertEqual(resp.status_code, 200)
        payload = resp.get_json()
        self.assertTrue(payload["ok"])
        self.assertEqual(payload["ticket"], 1)
        self.assertFalse(payload["read"])
        self.assertEqual(payload["comment"], "без токена")
        self.assertTrue((log_root() / "client_pub" / "a.json").is_file())
        inbox = self.client.get("/api/logs/client_pub/status").get_json()
        self.assertEqual(inbox["logs"][0]["ticket"], 1)

    def test_any_bearer_is_ignored(self):
        resp = self.post_upload(headers={"Authorization": "Bearer not-a-real-token"})
        self.assertEqual(resp.status_code, 200)

    def test_flag_values(self):
        for index, value in enumerate(("1", "true", "TRUE", "yes", " on ")):
            with self.subTest(value=value), mock.patch.dict(os.environ, {"TELEMETRY_UPLOAD_PUBLIC": value}):
                self.assertEqual(self.post_upload(filename=f"ok_{index}.json").status_code, 200)
        for value in ("0", "false", "no", "off", "", "2"):
            with self.subTest(value=value), mock.patch.dict(os.environ, {"TELEMETRY_UPLOAD_PUBLIC": value}):
                self.assertEqual(self.post_upload(filename="denied.json").status_code, 401)
        with mock.patch.dict(os.environ):
            os.environ.pop("TELEMETRY_UPLOAD_PUBLIC", None)
            self.assertEqual(self.post_upload(filename="unset.json").status_code, 401)

    def test_still_validates_client_id_and_file(self):
        self.assertEqual(self.post_upload(client_id="../etc").status_code, 400)
        self.assertEqual(self.post_upload(client_id="with space").status_code, 400)
        no_file = self.client.post(
            "/api/upload-log",
            data={"client_id": "client_pub"},
            content_type="multipart/form-data",
        )
        self.assertEqual(no_file.status_code, 400)
        self.assertEqual(self.post_upload(filename="notes.txt").status_code, 400)
        self.assertEqual(list(log_root().glob("*/*")), [])

    def test_still_enforces_size_and_quota(self):
        huge = "x" * (600 * 1024)
        with mock.patch.dict(os.environ, {"TELEMETRY_QUOTA_MB": "1"}):
            self.assertEqual(self.post_upload(filename="one.json", comment=huge).status_code, 200)
            self.assertEqual(self.post_upload(filename="two.json", comment=huge).status_code, 429)
            # Another device has its own per-client quota.
            self.assertEqual(
                self.post_upload(client_id="client_other", filename="one.json", comment=huge).status_code,
                200,
            )
        with mock.patch.dict(os.environ, {"TELEMETRY_MAX_UPLOAD_MB": "1"}):
            too_big = self.post_upload(client_id="client_big", comment="x" * (2 * 1024 * 1024))
            self.assertEqual(too_big.status_code, 413)

    def test_total_quota_protects_the_disk(self):
        huge = "x" * (600 * 1024)
        with mock.patch.dict(os.environ, {"TELEMETRY_TOTAL_QUOTA_MB": "1"}):
            self.assertEqual(self.post_upload(client_id="client_a", comment=huge).status_code, 200)
            denied = self.post_upload(client_id="client_b", comment=huge)
            self.assertEqual(denied.status_code, 507)
            self.assertFalse((log_root() / "client_b").exists())

    def test_review_api_stays_closed(self):
        self.post_upload()
        self.assertEqual(self.client.get("/api/logs").status_code, 401)
        self.assertEqual(self.client.get("/api/logs/ticket/1").status_code, 401)
        self.assertEqual(
            self.client.post("/api/logs/client_pub/a.json/read", json={}).status_code,
            401,
        )
        self.assertEqual(
            self.client.post("/api/logs/client_pub/a.json/comment", json={"reply": "x"}).status_code,
            401,
        )
        allowed = self.client.get("/api/logs?status=unread", headers={"Authorization": "Bearer review-secret"})
        self.assertEqual(allowed.status_code, 200)
        self.assertEqual(allowed.get_json()["count"], 1)

    def test_review_is_closed_without_review_token(self):
        self.post_upload()
        with mock.patch.dict(os.environ):
            os.environ.pop("TELEMETRY_REVIEW_TOKEN", None)
            self.assertEqual(
                self.client.get("/api/logs", headers={"Authorization": "Bearer "}).status_code,
                401,
            )


if __name__ == "__main__":
    unittest.main()
