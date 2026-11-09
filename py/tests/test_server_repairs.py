from __future__ import annotations

import asyncio
import base64
import importlib.util
import io
import json
import sys
import tempfile
import time
import unittest
from dataclasses import replace
from pathlib import Path
from types import ModuleType, SimpleNamespace
from unittest.mock import AsyncMock, Mock, patch

from fastapi import FastAPI, HTTPException
from fastapi.testclient import TestClient
from PIL import Image

from app.core import database
from app.core.config import SETTINGS
from app.models.schemas import (
    AnalyzeRequest,
    CommunityCocreateComposeRequest,
    CommunityComposeRequest,
    CommunityDirectPublishRequest,
    CommunityModerationActionRequest,
    CommunityRemakeAnalyzeRequest,
    RetouchRequest,
    SceneDetectRequest,
)
from app.services import creative_storage, image_validation

# Load a private service instance so model substitutes cannot leak to other tests.
vision_boundary = ModuleType("app.vision.remake_analyzer")
vision_boundary.get_pose_remake_analyzer = Mock()
service_spec = importlib.util.spec_from_file_location(
    "_cammate_repair_test_service", Path(__file__).parents[1] / "app/services/community_service.py"
)
service = importlib.util.module_from_spec(service_spec)
previous_vision_boundary = sys.modules.get(vision_boundary.__name__)
sys.modules[vision_boundary.__name__] = vision_boundary
try:
    with patch("app.vision.subject_fusion.HybridSceneDetector"):
        service_spec.loader.exec_module(service)
finally:
    if previous_vision_boundary is None:
        sys.modules.pop(vision_boundary.__name__)
    else:
        sys.modules[vision_boundary.__name__] = previous_vision_boundary
from app.api.routes import analyze as analyze_route
from app.api.deps import require_user
from app.services import analyze_service
from app.api.routes import scene as scene_route
from app.services import retouch_service
from providers.image_edit_provider import DoubaoImageEditProvider


def image_base64(size=(8, 8)):
    out = io.BytesIO()
    Image.new("RGB", size, "red").save(out, "PNG")
    return base64.b64encode(out.getvalue()).decode("ascii")


class ServerRepairTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        settings = replace(
            SETTINGS,
            db_path=self.root / "test.db",
            community_upload_dir=self.root / "uploads",
            community_creative_result_dir=self.root / "results",
            creative_queue_backend="redis",
            creative_storage_provider="local",
        )
        for module in (database, service, creative_storage):
            replacement = patch.object(module, "SETTINGS", settings)
            replacement.start()
            self.addCleanup(replacement.stop)
        self.redis = Mock()
        replacement = patch.object(service, "_REDIS_QUEUE", self.redis)
        replacement.start()
        self.addCleanup(replacement.stop)
        self.storage = creative_storage.CreativeObjectStorage()
        replacement = patch.object(service, "_OBJECT_STORAGE", self.storage)
        replacement.start()
        self.addCleanup(replacement.stop)
        database.ensure_db()
        with database.open_db() as conn:
            for user_id in (1, 2):
                conn.execute(
                    "INSERT INTO users(id, email, password_hash, password_salt, nickname, created_at) VALUES (?, ?, '', '', ?, ?)",
                    (user_id, f"{user_id}@test.invalid", f"user{user_id}", int(time.time())),
                )
            conn.execute(
                """INSERT INTO community_posts(id, user_id, image_path, image_blob, scene_type, place_tag, rating, created_at)
                   VALUES (1, 1, '', ?, 'portrait', '', 5, ?)""",
                (base64.b64decode(image_base64()), int(time.time())),
            )
        self.image = image_base64()
        self.request = CommunityComposeRequest(reference_post_id=1, person_image_base64=self.image)

    def update_job(self, job_id, **values):
        with database.open_db() as conn:
            conn.execute(
                "UPDATE community_creative_jobs SET " + ", ".join(f"{key} = ?" for key in values) + " WHERE id = ?",
                (*values.values(), job_id),
            )

    def row(self, job_id):
        with database.open_db() as conn:
            return conn.execute("SELECT * FROM community_creative_jobs WHERE id = ?", (job_id,)).fetchone()

    def job(self, owner=2):
        return service.create_compose_job(owner, self.request).job_id

    def success(self, job_id):
        return service._mark_job_success(job_id, "request", "test", "test", "ready", self.image, self.image, [])

    def test_delete_preserves_other_owner_jobs_results_and_events(self):
        own_job, other_job = self.job(1), self.job(2)
        self.update_job(other_job, status="running")
        self.assertTrue(self.success(other_job))
        result = service.get_creative_job(2, other_job).composed_image_base64
        service.delete_post(1, 1)
        self.assertEqual(service.get_creative_job(2, other_job).composed_image_base64, result)
        self.assertIsNotNone(self.row(own_job))
        with database.open_db() as conn:
            post = conn.execute("SELECT * FROM community_posts WHERE id = 1").fetchone()
            self.assertEqual(post["moderation_status"], "deleted")
            self.assertIsNone(post["image_blob"])
            self.assertEqual(post["image_path"], "")
            self.assertGreater(conn.execute("SELECT count(*) FROM community_creative_job_events WHERE job_id = ?", (other_job,)).fetchone()[0], 0)
            self.assertEqual(conn.execute("PRAGMA foreign_key_check").fetchall(), [])
        with self.assertRaises(HTTPException) as error:
            service.load_post_image(1, 2)
        self.assertEqual(error.exception.status_code, 404)

    def test_delete_checks_owner_and_deleted_reference_fails_clearly(self):
        with self.assertRaises(HTTPException) as error:
            service.delete_post(2, 1)
        self.assertEqual(error.exception.status_code, 403)
        job_id = self.job()
        service.delete_post(1, 1)
        service._process_creative_job(job_id)
        self.assertEqual(self.row(job_id)["status"], "failed")
        self.assertIn("deleted", self.row(job_id)["error_message"])

    def test_moderation_cannot_restore_or_hide_deleted_tombstone(self):
        with database.open_db() as conn:
            conn.execute("INSERT INTO community_post_reports(post_id, reporter_user_id, reason, created_at) VALUES (1, 2, 'test', 1)")
        service.delete_post(1, 1)
        for action in ("restore", "hide"):
            with self.subTest(action=action), self.assertRaises(HTTPException) as error:
                service.moderate_report(1, 1, CommunityModerationActionRequest(action=action))
            self.assertEqual(error.exception.status_code, 409)

    def test_create_and_manual_retry_return_same_failed_job_when_queue_unavailable(self):
        self.redis.enqueue_ready.side_effect = ConnectionError("offline")
        job_id = self.job()
        self.assertEqual(self.row(job_id)["error_code"], "queue_unavailable")
        view = service.retry_creative_job(2, job_id)
        self.assertEqual((view.job_id, view.status, view.error_code), (job_id, "failed", "queue_unavailable"))
        self.assertIn("person_image_base64", json.loads(self.row(job_id)["request_payload"]))

    def test_enqueue_failure_does_not_clobber_claimed_job(self):
        job_id = self.job()
        def claimed_then_failed(*args):
            self.update_job(job_id, status="running")
            raise ConnectionError("lost acknowledgement")
        self.redis.enqueue_ready.side_effect = claimed_then_failed
        self.assertFalse(service._enqueue_creative_job(job_id))
        self.assertEqual(self.row(job_id)["status"], "running")

    def test_auto_retry_and_recovery_share_enqueue_failure_state(self):
        job_id = self.job()
        self.update_job(job_id, status="running", max_retries=1)
        self.redis.enqueue_delayed.side_effect = ConnectionError("offline")
        service._handle_job_failure(job_id, "timeout", True)
        self.assertEqual((self.row(job_id)["status"], self.row(job_id)["error_code"]), ("failed", "queue_unavailable"))
        recovered = self.job()
        self.update_job(recovered, status="running", lease_expires_at=1, max_retries=1)
        service._recover_stale_running_jobs()
        self.assertEqual(self.row(recovered)["error_code"], "queue_unavailable")

    def test_queued_recovery_reports_enqueue_failure(self):
        job_id = self.job()
        self.redis.enqueue_ready.side_effect = ConnectionError("offline")
        result = service.recover_stuck_creative_jobs()
        self.assertEqual(result["enqueue_failed"], 1)
        self.assertEqual(self.row(job_id)["error_code"], "queue_unavailable")

    def test_success_and_both_cancellation_paths_strip_input(self):
        for terminal in ("success", "queued_cancel", "running_cancel"):
            with self.subTest(terminal=terminal):
                job_id = self.job()
                if terminal == "success":
                    self.update_job(job_id, status="running")
                    self.success(job_id)
                elif terminal == "queued_cancel":
                    service.cancel_creative_job(2, job_id)
                else:
                    self.update_job(job_id, status="running")
                    service.cancel_creative_job(2, job_id)
                    service._finalize_running_job_canceled(job_id)
                self.assertEqual(json.loads(self.row(job_id)["request_payload"]), {})

    def test_cleanup_preserves_retry_window_and_unexpired_success(self):
        recent_failed, old_failed, recent_success, old_success, canceled = [self.job() for _ in range(5)]
        now = int(time.time())
        old = now - service._CREATIVE_RESULT_TTL_SEC - 1
        self.update_job(recent_failed, status="failed", finished_at=now)
        self.update_job(old_failed, status="failed", finished_at=old)
        for job_id, finished in ((recent_success, now), (old_success, old)):
            self.update_job(job_id, status="success", finished_at=finished, result_image_base64=self.image)
        self.update_job(canceled, status="canceled", finished_at=now)
        before = self.row(old_failed)["request_payload"]
        service.cleanup_expired_creative_objects(dry_run=True)
        self.assertEqual(self.row(old_failed)["request_payload"], before)
        service.cleanup_expired_creative_objects()
        self.assertNotEqual(self.row(recent_failed)["request_payload"], "{}")
        self.assertEqual(self.row(old_failed)["request_payload"], "{}")
        self.assertEqual(self.row(canceled)["request_payload"], "{}")
        self.assertEqual(self.row(recent_success)["status"], "success")
        self.assertEqual(self.row(recent_success)["result_image_base64"], self.image)
        self.assertEqual(self.row(recent_success)["request_payload"], "{}")
        self.assertEqual(self.row(old_success)["error_code"], "result_expired")
        self.assertIsNone(self.row(old_success)["result_image_base64"])
        for job_id in (old_failed, old_success):
            with self.assertRaises(HTTPException) as error:
                service.retry_creative_job(2, job_id)
            self.assertEqual(error.exception.status_code, 409)
            self.assertIn("重新选择图片", error.exception.detail)
        self.assertEqual(service.retry_creative_job(2, recent_failed).status, "queued")

    def test_expired_retry_rejected_even_before_cleanup(self):
        job_id = self.job()
        self.update_job(job_id, status="failed", finished_at=int(time.time()) - service._CREATIVE_RESULT_TTL_SEC)
        with self.assertRaises(HTTPException) as error:
            service.retry_creative_job(2, job_id)
        self.assertIn("重新选择图片", error.exception.detail)

    def test_cancel_and_timeout_await_generation_cleanup(self):
        for action in ("cancel", "timeout"):
            with self.subTest(action=action):
                job_id = self.job()
                self.update_job(job_id, max_retries=0)
                cleaned = []
                async def generate(*args, **kwargs):
                    try:
                        if action == "cancel":
                            service.cancel_creative_job(2, job_id)
                        await asyncio.Event().wait()
                    finally:
                        await asyncio.sleep(0)
                        cleaned.append(self.row(job_id)["status"])
                with patch.object(service, "_compose_core", generate), patch.object(service, "_CREATIVE_HEARTBEAT_SEC", 0.005), patch.object(service, "_CREATIVE_JOB_HARD_TIMEOUT_SEC", 0.02):
                    service._process_creative_job(job_id)
                self.assertEqual(cleaned, ["running"])
                self.assertEqual(self.row(job_id)["status"], "canceled" if action == "cancel" else "failed")

    def test_storage_failure_cannot_report_success_and_cleans_first_object(self):
        job_id = self.job()
        self.update_job(job_id, status="running")
        store = self.storage.store_image
        def fail_compare(*args, **kwargs):
            if str(args[1]).startswith("compare"):
                raise OSError("disk full")
            return store(*args, **kwargs)
        with patch.object(self.storage, "store_image", side_effect=fail_compare), self.assertRaises(OSError):
            self.success(job_id)
        self.assertEqual(self.row(job_id)["status"], "running")
        self.assertEqual(list((self.root / "results").rglob("*.png")), [])
        self.assertNotEqual(self.row(job_id)["request_payload"], "{}")

    def test_database_failure_cleans_generated_objects(self):
        job_id = self.job()
        self.update_job(job_id, status="running")
        with patch.object(service, "open_db", side_effect=OSError("database unavailable")), self.assertRaises(OSError):
            self.success(job_id)
        self.assertEqual(self.row(job_id)["status"], "running")
        self.assertEqual(list((self.root / "results").rglob("*.png")), [])

    def test_partial_local_storage_write_is_removed(self):
        write_bytes = Path.write_bytes
        def partial_write(path, data):
            write_bytes(path, data[:8])
            raise OSError("disk full")
        with patch.object(Path, "write_bytes", partial_write), self.assertRaises(OSError):
            self.storage.store_image(1, "result", self.image)
        self.assertEqual(list((self.root / "results").rglob("*.png")), [])

    def test_cancel_racing_success_removes_uncommitted_result_objects(self):
        job_id = self.job()
        self.update_job(job_id, status="running")
        store = self.storage.store_image
        def cancel_during_store(*args, **kwargs):
            result = store(*args, **kwargs)
            service.cancel_creative_job(2, job_id)
            return result
        with patch.object(self.storage, "store_image", side_effect=cancel_during_store), self.assertRaises(service._CreativeJobCanceledError):
            self.success(job_id)
        self.assertNotEqual(self.row(job_id)["status"], "success")
        self.assertEqual(list((self.root / "results").rglob("*.png")), [])

    def test_invalid_images_rejected_before_jobs_or_posts_persist(self):
        bad = base64.b64encode(b"not an image at all").decode("ascii")
        requests = (
            lambda: service.create_compose_job(2, CommunityComposeRequest(reference_post_id=1, person_image_base64=bad)),
            lambda: service.create_cocreate_job(2, CommunityCocreateComposeRequest(reference_post_id=1, person_a_image_base64=self.image, person_b_image_base64=bad)),
            lambda: service.publish_direct_post(2, CommunityDirectPublishRequest(image_base64=bad)),
            lambda: service.remake_analyze(2, CommunityRemakeAnalyzeRequest(template_post_id=1, candidate_image_base64=bad)),
        )
        for request in requests:
            with self.assertRaises(HTTPException) as error:
                request()
            self.assertEqual(error.exception.status_code, 400)
        with database.open_db() as conn:
            self.assertEqual(conn.execute("SELECT count(*) FROM community_creative_jobs").fetchone()[0], 0)
            self.assertEqual(conn.execute("SELECT count(*) FROM community_posts").fetchone()[0], 1)


class ImageAndStreamTests(unittest.TestCase):
    def test_valid_data_uri_and_malformed_image_status(self):
        encoded = image_base64()
        self.assertEqual(image_validation.validate_image_base64("data:image/png;base64," + encoded), base64.b64decode(encoded))
        for bad in ("!" * 24, base64.b64encode(b"invalid image bytes").decode("ascii")):
            with self.assertRaises(HTTPException) as error:
                image_validation.validate_image_base64(bad)
            self.assertEqual(error.exception.status_code, 400)

    def test_size_precheck_does_not_decode_oversize_input(self):
        oversized = "A" * (4 * ((image_validation.MAX_IMAGE_BYTES + 2) // 3) + 257)
        with self.assertRaises(HTTPException) as error:
            image_validation.validate_image_base64(oversized)
        self.assertEqual(error.exception.status_code, 413)

    def test_pixel_bound_precedes_full_image_load(self):
        with patch.object(image_validation, "MAX_IMAGE_PIXELS", 63), self.assertRaises(HTTPException) as error:
            image_validation.validate_image_base64(image_base64((8, 8)))
        self.assertEqual(error.exception.status_code, 413)

    def test_analyze_rejects_invalid_image_before_stream_response(self):
        app = FastAPI()
        app.include_router(analyze_route.router)
        app.dependency_overrides[require_user] = lambda: None
        response = TestClient(app).post("/analyze", json={"image_base64": base64.b64encode(b"not image bytes").decode("ascii")})
        self.assertEqual(response.status_code, 400)
        self.assertEqual(response.json(), {"detail": "invalid image bytes"})

    def test_scene_and_retouch_share_invalid_image_status(self):
        invalid = base64.b64encode(b"not image bytes").decode("ascii")
        with self.assertRaises(HTTPException) as error:
            asyncio.run(scene_route.detect_scene(SceneDetectRequest(image_base64=invalid), None))
        self.assertEqual(error.exception.status_code, 400)
        provider = DoubaoImageEditProvider()
        provider.api_key = ""
        with patch.object(retouch_service, "DoubaoImageEditProvider", return_value=provider), self.assertRaises(HTTPException) as error:
            asyncio.run(retouch_service.retouch(RetouchRequest(image_base64=invalid)))
        self.assertEqual(error.exception.status_code, 400)
        with patch.object(retouch_service, "DoubaoImageEditProvider", return_value=provider), patch.object(image_validation, "MAX_IMAGE_PIXELS", 63), self.assertRaises(HTTPException) as error:
            asyncio.run(retouch_service.retouch(RetouchRequest(image_base64=image_base64())))
        self.assertEqual(error.exception.status_code, 413)

    def test_stream_failure_and_normal_warning_have_distinct_events(self):
        async def collect(provider_events=None, detector_error=None):
            detector = Mock()
            detector.detect.side_effect = detector_error
            detector.detect.return_value = SimpleNamespace(scene="general", mode="auto", confidence=0.8)
            provider = Mock()
            provider.analyze = AsyncMock(return_value=provider_events)
            with patch.object(analyze_service, "get_scene_detector", return_value=detector), patch.object(analyze_service, "get_provider", return_value=provider):
                return [json.loads(line) async for line in analyze_service.event_stream(AnalyzeRequest(image_base64=image_base64()), b"already validated")]
        failed = asyncio.run(collect(detector_error=RuntimeError("detector failure")))
        self.assertEqual([event["type"] for event in failed], ["error", "done"])
        warning = asyncio.run(collect([{"type": "ui", "level": "warn", "text": "请保持稳定"}, {"type": "done"}]))
        self.assertEqual([event["type"] for event in warning], ["scene", "ui", "done"])
        async def provider_failure():
            with patch.object(analyze_service, "get_scene_detector", return_value=SimpleNamespace(detect=lambda *a, **k: SimpleNamespace(scene="general", mode="auto", confidence=0.8))), patch.object(analyze_service, "get_provider", return_value=SimpleNamespace(analyze=AsyncMock(side_effect=RuntimeError("upstream failed")))):
                return [json.loads(line) async for line in analyze_service.event_stream(AnalyzeRequest(image_base64=image_base64()), b"already validated")]
        failed = asyncio.run(provider_failure())
        self.assertEqual([event["type"] for event in failed], ["scene", "error", "done"])


if __name__ == "__main__":
    unittest.main()
