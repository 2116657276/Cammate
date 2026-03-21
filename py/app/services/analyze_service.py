from __future__ import annotations

import asyncio
import base64
import json
import logging
import os
import time
import uuid
from typing import Any

from fastapi import HTTPException

from app.models.schemas import AnalyzeRequest
from providers.factory import get_provider
from scene_detector import get_scene_detector

logger = logging.getLogger("uvicorn.error")


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
        if scene == "landscape":
            scene = "general"
        mode = str(event.get("mode", "auto")).strip().lower()
        if mode not in {"auto", "portrait", "general", "food"}:
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
            event["grid"] = "none"
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
        event["text"] = text[:24] if text else "请微调构图。"
        if event.get("level") not in {"info", "warn"}:
            event["level"] = "info"
    return event


async def event_stream(req: AnalyzeRequest):
    start_ts = time.perf_counter()
    request_id = uuid.uuid4().hex[:8]
    raw_bytes = validate_image_base64(req.image_base64)
    detector = get_scene_detector()
    provider = get_provider()
    provider_timeout_sec = _read_timeout_sec()
    total_budget_sec = _read_total_budget_sec()
    client_ctx = _normalize_client_context(req.client_context.model_dump())
    capture_mode = str(client_ctx.get("capture_mode") or "auto").strip().lower()
    if capture_mode not in {"auto", "portrait", "general", "food"}:
        capture_mode = "auto"

    scene_result = detector.detect(
        raw_bytes,
        capture_mode=capture_mode,
        scene_hint=str(client_ctx.get("scene_hint") or ""),
    )

    # Keep provider prompt in sync with latest fused detector output.
    client_ctx["scene_hint"] = scene_result.scene

    logger.info(
        "analyze.start id=%s scene=%s mode=%s stable=%s score=%.2f recent=%d has_prev=%s has_subject=%s",
        request_id,
        scene_result.scene,
        scene_result.mode,
        bool(client_ctx.get("frame_stable", False)),
        float(client_ctx.get("stability_score", 0.0) or 0.0),
        len(client_ctx.get("recent_tip_texts", [])),
        bool(str(client_ctx.get("previous_tip_text") or "").strip()),
        bool(client_ctx.get("subject_center_norm")),
    )

    scene_event = {
        "type": "scene",
        "scene": scene_result.scene,
        "mode": scene_result.mode,
        "confidence": scene_result.confidence,
    }
    yield json.dumps(normalize_event(scene_event), ensure_ascii=False) + "\n"
    await asyncio.sleep(0)

    elapsed = time.perf_counter() - start_ts
    remaining_budget = max(0.8, total_budget_sec - elapsed)
    hard_timeout = min(provider_timeout_sec, remaining_budget)

    try:
        events = await asyncio.wait_for(
            provider.analyze(
                image_base64=req.image_base64,
                detected_scene=scene_result.scene,
                client_context=client_ctx,
            ),
            timeout=hard_timeout,
        )
        logger.info(
            "analyze.provider_ok id=%s elapsed_ms=%d timeout=%.2f events=%d",
            request_id,
            int((time.perf_counter() - start_ts) * 1000),
            hard_timeout,
            len(events),
        )
    except Exception as exc:
        logger.warning(
            "analyze.provider_failed id=%s elapsed_ms=%d timeout=%.2f reason=%r",
            request_id,
            int((time.perf_counter() - start_ts) * 1000),
            hard_timeout,
            exc,
        )
        reason = str(exc)
        if "ARK_API_KEY missing" in reason:
            text = "云端未配置 ARK_API_KEY，请联系管理员配置后重试"
        elif "401" in reason or "403" in reason:
            text = "云端鉴权失败，请检查 API Key 配置"
        elif "rate limited cooldown active" in reason:
            text = "云端限流冷却中，请稍后再试"
        elif "429" in reason:
            text = "云端限流，请稍后重试"
        elif "cloud repeated tip" in reason:
            text = "云端建议重复，请重试"
        else:
            text = "云端分析失败，请重试"
        events = [
            {
                "type": "ui",
                "text": text,
                "level": "warn",
            },
            {"type": "done"},
        ]

    has_done = False
    for event in events:
        normalized = normalize_event(event)
        if normalized.get("type") == "done":
            has_done = True
        yield json.dumps(normalized, ensure_ascii=False) + "\n"
        await asyncio.sleep(0)

    if not has_done:
        yield json.dumps({"type": "done"}, ensure_ascii=False) + "\n"

    logger.info(
        "analyze.finish id=%s elapsed_ms=%d has_done=%s",
        request_id,
        int((time.perf_counter() - start_ts) * 1000),
        has_done,
    )


def _clamp01(value: Any, default: float) -> float:
    try:
        v = float(value)
    except Exception:
        return default
    return max(0.0, min(1.0, v))


def _read_timeout_sec() -> float:
    raw = os.getenv("ANALYZE_PROVIDER_TIMEOUT_SEC", "13.2").strip()
    try:
        value = float(raw)
    except Exception:
        value = 13.2
    return max(3.0, min(15.0, value))


def _read_total_budget_sec() -> float:
    raw = os.getenv("ANALYZE_TOTAL_BUDGET_SEC", "13.8").strip()
    try:
        value = float(raw)
    except Exception:
        value = 13.8
    return max(4.0, min(15.0, value))


def _normalize_client_context(client_ctx: dict[str, Any]) -> dict[str, Any]:
    normalized = dict(client_ctx)
    recent = normalized.get("recent_tip_texts")
    if not isinstance(recent, list):
        normalized["recent_tip_texts"] = []
    else:
        cleaned: list[str] = []
        for item in recent:
            text = str(item or "").strip()
            if text:
                cleaned.append(text[:80])
        normalized["recent_tip_texts"] = cleaned[-4:]

    prev_tip = str(normalized.get("previous_tip_text") or "").strip()
    normalized["previous_tip_text"] = prev_tip[:80] if prev_tip else ""

    subject_center = normalized.get("subject_center_norm")
    if isinstance(subject_center, list) and len(subject_center) >= 2:
        normalized["subject_center_norm"] = [
            _clamp01(subject_center[0], 0.5),
            _clamp01(subject_center[1], 0.5),
        ]
    else:
        normalized["subject_center_norm"] = None

    subject_bbox = normalized.get("subject_bbox_norm")
    if isinstance(subject_bbox, list) and len(subject_bbox) >= 4:
        x1 = _clamp01(subject_bbox[0], 0.2)
        y1 = _clamp01(subject_bbox[1], 0.2)
        x2 = _clamp01(subject_bbox[2], 0.8)
        y2 = _clamp01(subject_bbox[3], 0.8)
        normalized["subject_bbox_norm"] = [min(x1, x2), min(y1, y2), max(x1, x2), max(y1, y2)]
    else:
        normalized["subject_bbox_norm"] = None
    return normalized
