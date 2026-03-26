from __future__ import annotations

from fastapi import APIRouter

from app.services.community_service import creative_queue_snapshot

router = APIRouter(tags=["health"])


@router.get("/healthz")
async def healthz() -> dict[str, object]:
    try:
        queue_info = creative_queue_snapshot()
    except Exception:
        queue_info = {
            "queued": 0,
            "running": 0,
            "failed_recent": 0,
            "workers": 0,
        }
    return {"ok": True, "creative_queue": queue_info}
