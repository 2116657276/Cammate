from __future__ import annotations

import logging
import time

from fastapi import HTTPException

from app.models.schemas import RetouchRequest, RetouchResponse
from app.services.analyze_service import validate_image_base64
from providers.image_edit_provider import DoubaoImageEditProvider

logger = logging.getLogger("uvicorn.error")


async def retouch(req: RetouchRequest) -> RetouchResponse:
    start_ts = time.perf_counter()
    custom_enabled = bool((req.custom_prompt or "").strip())
    logger.info(
        "retouch.start mode=%s preset=%s scene=%s strength=%.2f custom=%s image_chars=%d",
        "custom" if custom_enabled else "template",
        req.preset,
        req.scene_hint or "-",
        req.strength,
        custom_enabled,
        len(req.image_base64 or ""),
    )

    try:
        validate_image_base64(req.image_base64)
    except HTTPException as exc:
        logger.warning(
            "retouch.fail stage=validate status=%d detail=%s elapsed_ms=%d",
            exc.status_code,
            exc.detail,
            int((time.perf_counter() - start_ts) * 1000),
        )
        raise

    provider = DoubaoImageEditProvider()

    try:
        logger.info(
            "retouch.call provider=doubao model=%s response_format=%s size=%s",
            provider.model,
            provider.response_format,
            provider.size,
        )
        result = await provider.retouch(
            image_base64=req.image_base64,
            preset=req.preset,
            strength=req.strength,
            scene_hint=req.scene_hint,
            custom_prompt=req.custom_prompt,
        )
        logger.info(
            "retouch.success provider=%s model=%s elapsed_ms=%d output_chars=%d",
            result.provider,
            result.model,
            int((time.perf_counter() - start_ts) * 1000),
            len(result.image_base64 or ""),
        )
    except Exception as exc:
        logger.warning(
            "retouch.fail stage=provider_call elapsed_ms=%d reason=%r",
            int((time.perf_counter() - start_ts) * 1000),
            exc,
        )
        raise HTTPException(status_code=502, detail=f"retouch failed: {exc}") from exc

    return RetouchResponse(
        retouched_image_base64=result.image_base64,
        provider=result.provider,
        model=result.model,
    )
