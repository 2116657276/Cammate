from __future__ import annotations

import base64
import io
import logging
import os
import textwrap
import time
from collections.abc import Callable
from dataclasses import dataclass
from typing import Any
from urllib.parse import urlparse

import httpx
from PIL import Image, ImageOps

from app.core.config import load_runtime_env

logger = logging.getLogger("uvicorn.error")


@dataclass
class RetouchResult:
    image_base64: str
    provider: str
    model: str


@dataclass(frozen=True)
class SourceImageGeometry:
    size: tuple[int, int]
    orientation: str


class DoubaoImageEditProvider:
    def __init__(self, config_prefix: str = "ARK_IMAGE", fallback_prefix: str | None = None) -> None:
        load_runtime_env()
        self.config_prefix = config_prefix.strip().upper()
        self.fallback_prefix = fallback_prefix.strip().upper() if fallback_prefix else None
        self.api_url = self._cfg_str(
            "API_URL",
            "https://ark.cn-beijing.volces.com/api/v3/images/generations",
        )
        self.api_key = self._cfg_str("API_KEY", "")
        self.model = self._cfg_str("MODEL", "doubao-seedream-5-0-260128")
        self.timeout_sec = self._env_float("TIMEOUT_SEC", 75.0, 10.0, 180.0)
        self.size = self._cfg_str("SIZE", "2K")
        self.response_format = self._env_choice("RESPONSE_FORMAT", "url", {"url", "b64_json"})
        self.sequential = self._env_choice("SEQUENTIAL", "disabled", {"enabled", "disabled"})
        self.watermark = self._env_bool("WATERMARK", True)
        self.stream = self._env_bool("STREAM", False)
        self.max_input_side = self._env_int("MAX_INPUT_SIDE", 2048, 1024, 4096)
        self.jpeg_quality = self._env_int("JPEG_QUALITY", 94, 70, 98)
        self.portrait_strength_floor = self._env_float("PORTRAIT_MIN_STRENGTH", 0.48, 0.20, 0.80)

    async def retouch(
        self,
        image_base64: str,
        preset: str,
        strength: float,
        scene_hint: str | None,
        custom_prompt: str | None = None,
        progress_callback: Callable[[int], None] | None = None,
    ) -> RetouchResult:
        if not self.api_key:
            raise RuntimeError("ARK_IMAGE_API_KEY missing")

        start_ts = time.perf_counter()
        logger.info(
            "retouch.provider.start model=%s preset=%s scene=%s strength=%.2f custom=%s size=%s format=%s",
            self.model,
            preset,
            scene_hint or "-",
            strength,
            bool((custom_prompt or "").strip()),
            self.size,
            self.response_format,
        )
        upload_base64, prep_meta = self._prepare_image_for_upload(image_base64)
        source_geometry = SourceImageGeometry(
            size=prep_meta["source_size"],
            orientation=prep_meta["source_orientation"],
        )
        if prep_meta["resized"]:
            logger.info("retouch.input resized_to_2k max_side=%d", self.max_input_side)
        logger.info(
            "retouch.provider.prepared resized=%s source_size=%s orientation=%s input_chars=%d upload_chars=%d",
            prep_meta["resized"],
            source_geometry.size,
            source_geometry.orientation,
            len(image_base64 or ""),
            len(upload_base64 or ""),
        )
        prompt = self._build_prompt(
            preset=preset,
            strength=strength,
            scene_hint=scene_hint,
            custom_prompt=custom_prompt,
        )
        logger.info(
            "retouch.provider.prompt preview=%s",
            prompt.replace("\n", " ").strip()[:240],
        )
        self._emit_progress(progress_callback, 56)

        base_payload = {
            "model": self.model,
            "prompt": prompt,
            "sequential_image_generation": self.sequential,
            "response_format": self.response_format,
            "size": self.size,
            "stream": self.stream,
            "watermark": self.watermark,
        }
        payload_candidates = [
            {**base_payload, "image": f"data:image/jpeg;base64,{upload_base64}"},
            {**base_payload, "image": upload_base64},
        ]

        headers = {
            "Authorization": f"Bearer {self.api_key}",
            "Content-Type": "application/json",
        }

        async with httpx.AsyncClient(timeout=self.timeout_sec) as client:
            last_error: Exception | None = None
            for index, payload in enumerate(payload_candidates, start=1):
                image_variant = "data_uri" if index == 1 else "plain_base64"
                try:
                    logger.info(
                        "retouch.provider.call attempt=%d variant=%s timeout=%.1fs",
                        index,
                        image_variant,
                        self.timeout_sec,
                    )
                    self._emit_progress(progress_callback, 62)
                    resp = await client.post(self.api_url, headers=headers, json=payload)
                    upstream_req = self._extract_upstream_request_id(resp)
                    logger.info(
                        "retouch.provider.http attempt=%d status=%d upstream_req=%s",
                        index,
                        resp.status_code,
                        upstream_req,
                    )
                    resp.raise_for_status()
                    data = resp.json()
                    self._emit_progress(progress_callback, 78)
                    b64 = await self._extract_image_base64(
                        client,
                        data,
                        progress_callback=progress_callback,
                    )
                    if b64:
                        fixed_b64, geometry_fixed = self._normalize_output_geometry(
                            b64,
                            source_geometry=source_geometry,
                        )
                        self._emit_progress(progress_callback, 96)
                        logger.info(
                            "retouch.provider.success attempt=%d upstream_req=%s elapsed_ms=%d output_chars=%d geometry_fixed=%s",
                            index,
                            upstream_req,
                            int((time.perf_counter() - start_ts) * 1000),
                            len(fixed_b64),
                            geometry_fixed,
                        )
                        return RetouchResult(
                            image_base64=fixed_b64,
                            provider="doubao",
                            model=self.model,
                        )
                    last_error = RuntimeError(f"empty image result upstream_req={upstream_req}")
                    logger.warning(
                        "retouch.provider.empty attempt=%d upstream_req=%s",
                        index,
                        upstream_req,
                    )
                except httpx.HTTPStatusError as exc:
                    upstream_req = self._extract_upstream_request_id(exc.response)
                    detail = exc.response.text.strip().replace("\n", " ")[:260]
                    last_error = RuntimeError(
                        f"upstream_http_{exc.response.status_code} upstream_req={upstream_req} detail={detail}"
                    )
                    logger.warning(
                        "retouch.provider.fail attempt=%d variant=%s stage=http status=%d upstream_req=%s",
                        index,
                        image_variant,
                        exc.response.status_code,
                        upstream_req,
                    )
                except Exception as exc:
                    last_error = exc
                    logger.warning(
                        "retouch.provider.fail attempt=%d variant=%s stage=runtime reason=%r",
                        index,
                        image_variant,
                        exc,
                    )
                    continue

        raise RuntimeError(f"Doubao retouch failed: {last_error}")

    def _prepare_image_for_upload(self, image_base64: str) -> tuple[str, dict[str, Any]]:
        cleaned = self._normalize_base64_image(image_base64)
        try:
            raw = base64.b64decode(cleaned, validate=True)
        except Exception as exc:
            raise RuntimeError("invalid image_base64") from exc

        try:
            with Image.open(io.BytesIO(raw)) as image:
                display_image = ImageOps.exif_transpose(image)
                rgb = display_image.convert("RGB")
                source_size = rgb.size
                source_orientation = self._orientation_by_size(source_size)
                resized = False
                if max(rgb.size) > self.max_input_side:
                    rgb.thumbnail((self.max_input_side, self.max_input_side), Image.Resampling.LANCZOS)
                    resized = True
                out = io.BytesIO()
                rgb.save(out, format="JPEG", quality=self.jpeg_quality, optimize=True)
                return (
                    base64.b64encode(out.getvalue()).decode("utf-8"),
                    {
                        "resized": resized,
                        "source_size": source_size,
                        "source_orientation": source_orientation,
                    },
                )
        except Exception as exc:
            raise RuntimeError("invalid image bytes") from exc

    def _orientation_by_size(self, size: tuple[int, int]) -> str:
        width, height = size
        if width == height:
            return "square"
        return "landscape" if width > height else "portrait"

    def _normalize_output_geometry(
        self,
        image_base64: str,
        source_geometry: SourceImageGeometry,
    ) -> tuple[str, bool]:
        cleaned = self._normalize_base64_image(image_base64)
        try:
            raw = base64.b64decode(cleaned, validate=True)
        except Exception:
            logger.warning("retouch.provider.geometry skip reason=invalid_output_base64")
            return image_base64, False

        try:
            with Image.open(io.BytesIO(raw)) as image:
                output = ImageOps.exif_transpose(image).convert("RGB")
                geometry_fixed = False
                output, orientation_fixed = self._rotate_output_to_match_orientation(
                    output,
                    source_geometry.orientation,
                )
                geometry_fixed = geometry_fixed or orientation_fixed

                output_ratio = output.width / max(1, output.height)
                source_ratio = source_geometry.size[0] / max(1, source_geometry.size[1])
                ratio_delta = abs(output_ratio - source_ratio)
                if output.size != source_geometry.size or ratio_delta > 0.003:
                    if ratio_delta <= 0.01:
                        output = output.resize(source_geometry.size, Image.Resampling.LANCZOS)
                    else:
                        output = ImageOps.fit(
                            output,
                            source_geometry.size,
                            method=Image.Resampling.LANCZOS,
                            centering=(0.5, 0.45),
                        )
                    geometry_fixed = True

                out = io.BytesIO()
                output.save(out, format="JPEG", quality=self.jpeg_quality, optimize=True)
                if geometry_fixed:
                    logger.info(
                        "retouch.provider.geometry fixed source_size=%s source_orientation=%s final_size=%s",
                        source_geometry.size,
                        source_geometry.orientation,
                        output.size,
                    )
                return base64.b64encode(out.getvalue()).decode("utf-8"), geometry_fixed
        except Exception as exc:
            logger.warning("retouch.provider.geometry skip reason=%r", exc)
            return image_base64, False

    def _rotate_output_to_match_orientation(
        self,
        output: Image.Image,
        source_orientation: str,
    ) -> tuple[Image.Image, bool]:
        if source_orientation not in {"landscape", "portrait"}:
            return output, False
        output_orientation = self._orientation_by_size(output.size)
        if output_orientation in {source_orientation, "square"}:
            return output, False

        rotate_cw = output.rotate(90, expand=True)
        if self._orientation_by_size(rotate_cw.size) == source_orientation:
            return rotate_cw, True
        rotate_ccw = output.rotate(-90, expand=True)
        if self._orientation_by_size(rotate_ccw.size) == source_orientation:
            return rotate_ccw, True
        logger.warning(
            "retouch.provider.geometry skip reason=cannot_match source=%s output=%s",
            source_orientation,
            output_orientation,
        )
        return output, False

    def _normalize_base64_image(self, image_base64: str) -> str:
        text = image_base64.strip()
        if text.startswith("data:image") and "," in text:
            _, payload = text.split(",", 1)
            return payload.strip()
        return text

    def _normalize_preset(self, preset: str) -> str:
        value = (preset or "").strip().lower()
        mapping = {
            "bg_cleanup": "bg_cleanup",
            "portrait_beauty": "portrait_beauty",
            "color_grade": "color_grade",
            "natural": "portrait_beauty",
            "portrait": "portrait_beauty",
            "food": "color_grade",
            "night": "color_grade",
            "cinematic": "color_grade",
        }
        return mapping.get(value, "portrait_beauty")

    def _build_prompt(
        self,
        preset: str,
        strength: float,
        scene_hint: str | None,
        custom_prompt: str | None,
    ) -> str:
        strength_norm = max(0.0, min(1.0, strength))
        scene_text = (scene_hint or "general").strip().lower() or "general"
        custom_text = (custom_prompt or "").strip()
        preset_key = self._normalize_preset(preset)
        applied_strength = strength_norm
        if not custom_text and preset_key == "portrait_beauty":
            applied_strength = max(strength_norm, self.portrait_strength_floor)
        strength_pct = int(applied_strength * 100)

        subject_lock = textwrap.dedent(
            """
            This is an image editing task, not a new image generation task.
            Keep the original camera orientation, original canvas aspect ratio, and the overall framing of the input photo.
            Do not rotate the image sideways and do not change it into a different crop ratio.
            Preserve the number of subjects, their identity, visible body coverage, pose intent, and the main scene layout.
            Do not add or remove people, body parts, props, or buildings.
            No face swap, no pasted portrait look, no floating face, no oversized head, no hard cutout edges, and no cartoon rendering.
            """
        ).strip().replace("\n", " ")

        if custom_text:
            return textwrap.dedent(
                f"""
                {subject_lock}
                Scene hint: {scene_text}. Blend intensity: {strength_pct}%.
                Edit brief: {custom_text}
                Rebuild the visible person or people as a natural part of the same photograph.
                Keep the subject scale believable for the scene perspective, horizon, and camera distance.
                Prefer a slightly smaller and more natural person over an oversized person.
                Keep head size natural relative to the whole frame and avoid tight face-dominant framing unless the input already is a close portrait.
                Match perspective, lighting direction, exposure, white balance, depth of field, contact shadows, reflections, and edge transitions.
                Preserve the uploaded identity, clothing category, clothing colors, and visible pose coverage.
                Never invent missing limbs, never fabricate a larger body, and never reveal body areas that are not visible in the input.
                Return only the final edited image.
                """
            ).strip().replace("\n", " ")

        preset_map = {
            "bg_cleanup": (
                "Clean distracting background clutter, repair small artifacts, and keep the subject natural. "
                "Do not change identity, pose, or composition."
            ),
            "portrait_beauty": (
                "Do natural portrait cleanup only: improve uneven skin tone, reduce minor blemishes, "
                "and enhance eyes and facial clarity while keeping realistic skin texture and facial proportions. "
                "No plastic skin and no influencer-face reshaping."
            ),
            "color_grade": (
                "Improve tonal balance, contrast, color temperature, and depth while keeping realistic details. "
                "Do not redesign the scene or alter subject scale."
            ),
        }
        preset_text = preset_map[preset_key]
        if scene_text != "portrait":
            preset_text += " If the input does not already contain a person, do not introduce one."
        return textwrap.dedent(
            f"""
            {subject_lock}
            Scene hint: {scene_text}. Preset: {preset_key}. Strength: {strength_pct}%.
            {preset_text}
            Only perform realistic photo retouching. Do not replace the main subject and do not redraw the image into a different composition.
            Return only the final edited image.
            """
        ).strip().replace("\n", " ")

    async def _extract_image_base64(
        self,
        client: httpx.AsyncClient,
        response_data: dict[str, Any],
        progress_callback: Callable[[int], None] | None = None,
    ) -> str | None:
        direct_b64 = response_data.get("b64_json")
        if isinstance(direct_b64, str) and direct_b64.strip():
            self._emit_progress(progress_callback, 90)
            return direct_b64.strip()
        direct_url = response_data.get("url")
        if isinstance(direct_url, str) and direct_url.strip():
            self._emit_progress(progress_callback, 84)
            return await self._download_as_base64(
                client,
                direct_url.strip(),
                progress_callback=progress_callback,
            )

        data_items = response_data.get("data")
        if isinstance(data_items, list):
            for item in data_items:
                if not isinstance(item, dict):
                    continue
                b64 = item.get("b64_json")
                if isinstance(b64, str) and b64.strip():
                    self._emit_progress(progress_callback, 90)
                    return b64.strip()
                url = item.get("url")
                if isinstance(url, str) and url.strip():
                    self._emit_progress(progress_callback, 84)
                    return await self._download_as_base64(
                        client,
                        url.strip(),
                        progress_callback=progress_callback,
                    )

        for _, value in response_data.items():
            if isinstance(value, dict):
                nested_b64 = value.get("b64_json")
                if isinstance(nested_b64, str) and nested_b64.strip():
                    self._emit_progress(progress_callback, 90)
                    return nested_b64.strip()
                nested_url = value.get("url")
                if isinstance(nested_url, str) and nested_url.strip():
                    self._emit_progress(progress_callback, 84)
                    return await self._download_as_base64(
                        client,
                        nested_url.strip(),
                        progress_callback=progress_callback,
                    )
            if isinstance(value, list):
                for item in value:
                    if not isinstance(item, dict):
                        continue
                    nested_b64 = item.get("b64_json")
                    if isinstance(nested_b64, str) and nested_b64.strip():
                        self._emit_progress(progress_callback, 90)
                        return nested_b64.strip()
                    nested_url = item.get("url")
                    if isinstance(nested_url, str) and nested_url.strip():
                        self._emit_progress(progress_callback, 84)
                        return await self._download_as_base64(
                            client,
                            nested_url.strip(),
                            progress_callback=progress_callback,
                        )

        return None

    async def _download_as_base64(
        self,
        client: httpx.AsyncClient,
        url: str,
        progress_callback: Callable[[int], None] | None = None,
    ) -> str:
        parsed = urlparse(url)
        logger.info(
            "retouch.provider.download.start host=%s path=%s",
            parsed.netloc or "-",
            parsed.path[:120] or "/",
        )
        self._emit_progress(progress_callback, 86)
        resp = await client.get(url)
        upstream_req = self._extract_upstream_request_id(resp)
        logger.info(
            "retouch.provider.download.http status=%d upstream_req=%s",
            resp.status_code,
            upstream_req,
        )
        resp.raise_for_status()
        encoded = base64.b64encode(resp.content).decode("utf-8")
        logger.info(
            "retouch.provider.download.success upstream_req=%s bytes=%d",
            upstream_req,
            len(resp.content),
        )
        self._emit_progress(progress_callback, 92)
        return encoded

    def _emit_progress(self, progress_callback: Callable[[int], None] | None, progress: int) -> None:
        if progress_callback is None:
            return
        try:
            progress_callback(max(0, min(99, int(progress))))
        except Exception:
            logger.debug("retouch.provider.progress_callback failed", exc_info=True)

    def _extract_upstream_request_id(self, response: httpx.Response) -> str:
        for key in ("x-request-id", "x-tt-logid", "x-logid", "x-amzn-requestid", "trace-id"):
            value = response.headers.get(key, "").strip()
            if value:
                return value
        return "-"

    def _cfg_raw(self, suffix: str) -> str | None:
        key = f"{self.config_prefix}_{suffix}"
        raw = os.getenv(key)
        if raw is not None and raw.strip():
            return raw.strip()
        if self.fallback_prefix:
            fallback_key = f"{self.fallback_prefix}_{suffix}"
            fallback_raw = os.getenv(fallback_key)
            if fallback_raw is not None and fallback_raw.strip():
                return fallback_raw.strip()
        return None

    def _cfg_str(self, suffix: str, default: str) -> str:
        raw = self._cfg_raw(suffix)
        if raw is None:
            return default
        return raw.strip() or default

    def _env_choice(self, suffix: str, default: str, allowed: set[str]) -> str:
        raw = self._cfg_raw(suffix)
        if raw is None:
            return default
        value = raw.lower()
        if value in allowed:
            return value
        return default

    def _env_bool(self, suffix: str, default: bool) -> bool:
        raw = self._cfg_raw(suffix)
        if raw is None:
            return default
        return raw.lower() in {"1", "true", "yes", "on"}

    def _env_int(self, suffix: str, default: int, min_value: int, max_value: int) -> int:
        raw = self._cfg_raw(suffix)
        if raw is None:
            return default
        try:
            value = int(raw)
        except Exception:
            return default
        return max(min_value, min(max_value, value))

    def _env_float(self, suffix: str, default: float, min_value: float, max_value: float) -> float:
        raw = self._cfg_raw(suffix)
        if raw is None:
            return default
        try:
            value = float(raw)
        except Exception:
            return default
        return max(min_value, min(max_value, value))
