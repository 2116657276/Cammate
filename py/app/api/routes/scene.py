from __future__ import annotations

import logging

from fastapi import APIRouter, Depends

from app.api.deps import require_user
from app.models.schemas import AuthUser, SceneDetectRequest, SceneDetectResponse
from app.services.analyze_service import validate_image_base64
from scene_detector import get_scene_detector

router = APIRouter(prefix="/scene", tags=["scene"])
logger = logging.getLogger("app.scene")


@router.post("/detect", response_model=SceneDetectResponse)
async def detect_scene(
    req: SceneDetectRequest,
    _: AuthUser = Depends(require_user),
) -> SceneDetectResponse:
    raw = validate_image_base64(req.image_base64)
    result = get_scene_detector().detect(raw)
    logger.info(
        "scene.detect scene=%s conf=%.2f mode=%s has_bbox=%s",
        result.scene,
        result.confidence,
        result.mode,
        bool(result.bbox_norm),
    )
    return SceneDetectResponse(
        scene=result.scene,
        confidence=result.confidence,
        mode=result.mode,
        bbox_norm=result.bbox_norm,
        center_norm=result.center_norm,
    )
