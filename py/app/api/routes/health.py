from __future__ import annotations

from fastapi import APIRouter

from app.services.community_service import creative_queue_snapshot
from scene_detector import describe_scene_runtime

router = APIRouter(tags=["health"])


@router.get("/healthz")
async def healthz() -> dict[str, object]:
    try:
        queue_info = creative_queue_snapshot()
    except Exception:
        queue_info = {
            "backend": "redis",
            "connected": False,
            "queue_connectivity": "down",
            "storage_connected": False,
            "queued": 0,
            "ready": 0,
            "delayed": 0,
            "running": 0,
            "leases_expiring": 0,
            "lease_risk": {"critical": 0, "warning": 0},
            "failed_recent": 0,
            "retry_scheduled": 0,
            "stored_results": 0,
            "events_recent": 0,
            "failure_codes_recent": {},
            "workers": 0,
            "workers_active": 0,
        }
    try:
        scene_info = describe_scene_runtime()
    except Exception:
        scene_info = {
            "ready": False,
            "model_paths": [],
            "model_count": 0,
            "custom_override": False,
        }
    return {"ok": True, "creative_queue": queue_info, "scene_model": scene_info}
