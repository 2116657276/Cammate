from __future__ import annotations

from fastapi import HTTPException

from app.models.schemas import RetouchRequest, RetouchResponse
from app.services.analyze_service import validate_image_base64
from providers.image_edit_provider import DoubaoImageEditProvider


async def retouch(req: RetouchRequest) -> RetouchResponse:
    validate_image_base64(req.image_base64)
    provider = DoubaoImageEditProvider()

    try:
        result = await provider.retouch(
            image_base64=req.image_base64,
            preset=req.preset,
            strength=req.strength,
            scene_hint=req.scene_hint,
        )
    except Exception as exc:
        raise HTTPException(status_code=502, detail=f"retouch failed: {exc}") from exc

    return RetouchResponse(
        retouched_image_base64=result.image_base64,
        provider=result.provider,
        model=result.model,
    )
