from __future__ import annotations

import base64
import io

from fastapi import HTTPException
from PIL import Image

MAX_IMAGE_BYTES = 10 * 1024 * 1024
MAX_IMAGE_PIXELS = 24_000_000


def decode_image_base64(image_base64: str) -> bytes:
    # Bound the encoded input before allocating its decoded copy.
    if len(image_base64) > 4 * ((MAX_IMAGE_BYTES + 2) // 3) + 256:
        raise HTTPException(status_code=413, detail="image too large (maximum 10 MiB)")
    cleaned = image_base64.strip()
    if cleaned.startswith("data:image") and "," in cleaned:
        _, cleaned = cleaned.split(",", 1)
    if len(cleaned) > 4 * ((MAX_IMAGE_BYTES + 2) // 3):
        raise HTTPException(status_code=413, detail="image too large (maximum 10 MiB)")
    try:
        raw = base64.b64decode(cleaned, validate=True)
    except Exception as exc:
        raise HTTPException(status_code=400, detail="invalid image_base64") from exc
    if not raw:
        raise HTTPException(status_code=400, detail="empty image")
    if len(raw) > MAX_IMAGE_BYTES:
        raise HTTPException(status_code=413, detail="image too large (maximum 10 MiB)")
    return raw


def open_validated_image(raw: bytes) -> Image.Image:
    image = None
    try:
        image = Image.open(io.BytesIO(raw))
        if image.width * image.height > MAX_IMAGE_PIXELS:
            raise HTTPException(status_code=413, detail="image exceeds 24 million pixels")
        image.load()
        return image
    except Exception as exc:
        if image is not None:
            image.close()
        if isinstance(exc, HTTPException):
            raise
        if isinstance(exc, (Image.DecompressionBombError, Image.DecompressionBombWarning)):
            raise HTTPException(status_code=413, detail="image exceeds pixel limit") from exc
        raise HTTPException(status_code=400, detail="invalid image bytes") from exc


def validate_image_base64(image_base64: str) -> bytes:
    raw = decode_image_base64(image_base64)
    with open_validated_image(raw):
        pass
    return raw
