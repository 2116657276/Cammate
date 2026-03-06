from __future__ import annotations

import asyncio
import base64
import json
from typing import Any

from fastapi import HTTPException

from app.models.schemas import AnalyzeRequest
from providers.factory import get_provider
from scene_detector import get_scene_detector


def validate_image_base64(image_base64: str) -> bytes:
    try:
        raw = base64.b64decode(image_base64, validate=True)
    except Exception as exc:
        raise HTTPException(status_code=400, detail="invalid image_base64") from exc
    if len(raw) == 0:
        raise HTTPException(status_code=400, detail="empty image")
    if len(raw) > 10 * 1024 * 1024:
        raise HTTPException(status_code=413, detail="image too large")
    return raw


def normalize_event(event: dict[str, Any]) -> dict[str, Any]:
    etype = event.get("type")
    if etype == "scene":
        scene = str(event.get("scene", "general")).strip().lower()
        if scene not in {"portrait", "general", "landscape", "food", "night"}:
            scene = "general"
        mode = str(event.get("mode", "auto")).strip().lower()
        if mode not in {"auto", "portrait", "general"}:
            mode = "auto"
        event["scene"] = scene
        event["mode"] = mode
        event["confidence"] = _clamp01(event.get("confidence", 0.5), 0.5)
    elif etype == "strategy":
        target = event.get("target_point_norm", [0.5, 0.5])
        event["target_point_norm"] = [
            _clamp01(target[0] if len(target) > 0 else 0.5, 0.5),
            _clamp01(target[1] if len(target) > 1 else 0.5, 0.5),
        ]
        if event.get("grid") not in {"thirds", "center", "none"}:
            event["grid"] = "thirds"
    elif etype == "target":
        bbox = event.get("bbox_norm", [0.2, 0.2, 0.8, 0.8])
        if len(bbox) != 4:
            bbox = [0.2, 0.2, 0.8, 0.8]
        x1 = _clamp01(bbox[0], 0.2)
        y1 = _clamp01(bbox[1], 0.2)
        x2 = _clamp01(bbox[2], 0.8)
        y2 = _clamp01(bbox[3], 0.8)
        event["bbox_norm"] = [min(x1, x2), min(y1, y2), max(x1, x2), max(y1, y2)]
        center = event.get("center_norm", [0.5, 0.5])
        event["center_norm"] = [
            _clamp01(center[0] if len(center) > 0 else 0.5, 0.5),
            _clamp01(center[1] if len(center) > 1 else 0.5, 0.5),
        ]
    elif etype == "param":
        try:
            event["exposure_compensation"] = max(-2, min(2, int(event.get("exposure_compensation", 0))))
        except Exception:
            event["exposure_compensation"] = 0
    elif etype == "ui":
        text = str(event.get("text", "请微调构图。")).strip()
        event["text"] = text[:64] if text else "请微调构图。"
        if event.get("level") not in {"info", "warn"}:
            event["level"] = "info"
    return event


async def event_stream(req: AnalyzeRequest):
    raw_bytes = validate_image_base64(req.image_base64)
    detector = get_scene_detector()
    provider = get_provider()
    scene_result = detector.detect(raw_bytes)

    scene_event = {
        "type": "scene",
        "scene": scene_result.scene,
        "mode": scene_result.mode,
        "confidence": scene_result.confidence,
    }
    yield json.dumps(normalize_event(scene_event), ensure_ascii=False) + "\n"
    await asyncio.sleep(0)

    events = await provider.analyze(
        image_base64=req.image_base64,
        detected_scene=scene_result.scene,
        client_context=req.client_context.model_dump(),
    )
    has_done = False
    for event in events:
        normalized = normalize_event(event)
        if normalized.get("type") == "done":
            has_done = True
        yield json.dumps(normalized, ensure_ascii=False) + "\n"
        await asyncio.sleep(0)

    if not has_done:
        yield json.dumps({"type": "done"}, ensure_ascii=False) + "\n"


def _clamp01(value: Any, default: float) -> float:
    try:
        v = float(value)
    except Exception:
        return default
    return max(0.0, min(1.0, v))
