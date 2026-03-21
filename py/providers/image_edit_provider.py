from __future__ import annotations

import base64
import io
import logging
import os
import time
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


class DoubaoImageEditProvider:
    def __init__(self) -> None:
        load_runtime_env()
        self.api_url = os.getenv(
            "ARK_IMAGE_API_URL",
            "https://ark.cn-beijing.volces.com/api/v3/images/generations",
        )
        self.api_key = os.getenv("ARK_IMAGE_API_KEY", "").strip()
        self.model = os.getenv("ARK_IMAGE_MODEL", "doubao-seedream-5-0-260128").strip()
        self.timeout_sec = self._env_float("ARK_IMAGE_TIMEOUT_SEC", 75.0, 10.0, 180.0)
        self.size = os.getenv("ARK_IMAGE_SIZE", "2K").strip() or "2K"
        self.response_format = self._env_choice("ARK_IMAGE_RESPONSE_FORMAT", "url", {"url", "b64_json"})
        self.sequential = self._env_choice("ARK_IMAGE_SEQUENTIAL", "disabled", {"enabled", "disabled"})
        self.watermark = self._env_bool("ARK_IMAGE_WATERMARK", True)
        self.stream = self._env_bool("ARK_IMAGE_STREAM", False)
        self.max_input_side = self._env_int("ARK_IMAGE_MAX_INPUT_SIDE", 2048, 1024, 4096)
        self.jpeg_quality = self._env_int("ARK_IMAGE_JPEG_QUALITY", 94, 70, 98)

    async def retouch(
        self,
        image_base64: str,
        preset: str,
        strength: float,
        scene_hint: str | None,
        custom_prompt: str | None = None,
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
        upload_base64, resized, source_orientation = self._prepare_image_for_upload(image_base64)
        if resized:
            logger.info("retouch.input resized_to_2k max_side=%d", self.max_input_side)
        logger.info(
            "retouch.provider.prepared resized=%s orientation=%s input_chars=%d upload_chars=%d",
            resized,
            source_orientation,
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
                    b64 = await self._extract_image_base64(client, data)
                    if b64:
                        fixed_b64, orientation_fixed = self._align_output_orientation(
                            b64,
                            source_orientation=source_orientation,
                        )
                        logger.info(
                            "retouch.provider.success attempt=%d upstream_req=%s elapsed_ms=%d output_chars=%d orientation_fixed=%s",
                            index,
                            upstream_req,
                            int((time.perf_counter() - start_ts) * 1000),
                            len(fixed_b64),
                            orientation_fixed,
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

    def _prepare_image_for_upload(self, image_base64: str) -> tuple[str, bool, str]:
        cleaned = self._normalize_base64_image(image_base64)
        try:
            raw = base64.b64decode(cleaned, validate=True)
        except Exception as exc:
            raise RuntimeError("invalid image_base64") from exc

        try:
            with Image.open(io.BytesIO(raw)) as image:
                display_image = ImageOps.exif_transpose(image)
                source_orientation = self._orientation_by_size(display_image.size)
                rgb = display_image.convert("RGB")
                if max(rgb.size) <= self.max_input_side:
                    return cleaned, False, source_orientation
                rgb.thumbnail((self.max_input_side, self.max_input_side), Image.Resampling.LANCZOS)
                out = io.BytesIO()
                rgb.save(out, format="JPEG", quality=self.jpeg_quality, optimize=True)
                return base64.b64encode(out.getvalue()).decode("utf-8"), True, source_orientation
        except Exception as exc:
            raise RuntimeError("invalid image bytes") from exc

    def _orientation_by_size(self, size: tuple[int, int]) -> str:
        width, height = size
        if width == height:
            return "square"
        return "landscape" if width > height else "portrait"

    def _align_output_orientation(self, image_base64: str, source_orientation: str) -> tuple[str, bool]:
        if source_orientation not in {"landscape", "portrait"}:
            return image_base64, False

        cleaned = self._normalize_base64_image(image_base64)
        try:
            raw = base64.b64decode(cleaned, validate=True)
        except Exception:
            logger.warning("retouch.provider.orientation skip reason=invalid_output_base64")
            return image_base64, False

        try:
            with Image.open(io.BytesIO(raw)) as image:
                output = ImageOps.exif_transpose(image).convert("RGB")
                output_orientation = self._orientation_by_size(output.size)
                if output_orientation == source_orientation:
                    return cleaned, False
                if output_orientation == "square":
                    return cleaned, False

                rotate_cw = output.rotate(90, expand=True)
                fixed = rotate_cw
                if self._orientation_by_size(rotate_cw.size) != source_orientation:
                    rotate_ccw = output.rotate(-90, expand=True)
                    if self._orientation_by_size(rotate_ccw.size) == source_orientation:
                        fixed = rotate_ccw
                    else:
                        logger.warning(
                            "retouch.provider.orientation skip reason=cannot_match source=%s output=%s",
                            source_orientation,
                            output_orientation,
                        )
                        return cleaned, False

                out = io.BytesIO()
                fixed.save(out, format="JPEG", quality=self.jpeg_quality, optimize=True)
                logger.info(
                    "retouch.provider.orientation fixed source=%s output=%s",
                    source_orientation,
                    output_orientation,
                )
                return base64.b64encode(out.getvalue()).decode("utf-8"), True
        except Exception as exc:
            logger.warning("retouch.provider.orientation skip reason=%r", exc)
            return image_base64, False

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
            # Backward compatibility for old client presets.
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
        strength_pct = int(max(0.0, min(1.0, strength)) * 100)
        scene_text = (scene_hint or "general").strip().lower() or "general"
        custom_text = (custom_prompt or "").strip()
        subject_lock = (
            "这是图像修图任务，不是文生图。"
            "必须严格基于输入原图做后期优化，保持原图主体种类、数量、位置、姿态与构图。"
            "禁止新增、删除、替换主体；禁止凭空生成人物、动物、建筑或道具。"
            "若原图无人像，严禁新增人物；若原图有人像，仅允许轻度美化，不改变身份与五官特征。"
            "禁止换脸、二次创作、夸张重绘、卡通化。"
        )

        if custom_text:
            return (
                f"{subject_lock}"
                "你是手机摄影修图助手，仅执行照片级后期优化。"
                f"场景参考：{scene_text}。强度：{strength_pct}%。"
                f"用户要求：{custom_text}。"
                "严格限制：仅允许去除杂乱背景、调色、曝光/白平衡优化、人像皮肤和细节优化；"
                "若用户要求与上述限制冲突，以上述限制为准。"
                "输出仅为处理后的图片。"
            )

        preset_key = self._normalize_preset(preset)
        preset_map = {
            "bg_cleanup": "清理和弱化杂乱背景元素，主体保持清晰自然，不改变主体身份与姿态。",
            "portrait_beauty": "优化人像肤色与层次，保持五官真实，适度提亮主体并保留皮肤质感。",
            "color_grade": "进行电影感调色与层次增强，优化对比与色温，保持真实细节。",
        }
        preset_text = preset_map[preset_key]
        if scene_text != "portrait":
            preset_text += "当前场景不是人像场景，禁止生成或引入人物。"
        return (
            f"{subject_lock}"
            "请执行照片级修图，仅输出处理后的图片。"
            f"场景参考：{scene_text}。"
            f"模板：{preset_key}。"
            f"强度：{strength_pct}%。"
            f"要求：{preset_text}"
            "限制：仅做去杂乱背景、调色和轻度人像优化，不做主体替换或夸张重绘。"
        )

    async def _extract_image_base64(
        self,
        client: httpx.AsyncClient,
        response_data: dict[str, Any],
    ) -> str | None:
        direct_b64 = response_data.get("b64_json")
        if isinstance(direct_b64, str) and direct_b64.strip():
            return direct_b64.strip()
        direct_url = response_data.get("url")
        if isinstance(direct_url, str) and direct_url.strip():
            return await self._download_as_base64(client, direct_url.strip())

        data_items = response_data.get("data")
        if isinstance(data_items, list):
            for item in data_items:
                if not isinstance(item, dict):
                    continue
                b64 = item.get("b64_json")
                if isinstance(b64, str) and b64.strip():
                    return b64.strip()
                url = item.get("url")
                if isinstance(url, str) and url.strip():
                    return await self._download_as_base64(client, url.strip())

        for _, value in response_data.items():
            if isinstance(value, dict):
                nested_b64 = value.get("b64_json")
                if isinstance(nested_b64, str) and nested_b64.strip():
                    return nested_b64.strip()
                nested_url = value.get("url")
                if isinstance(nested_url, str) and nested_url.strip():
                    return await self._download_as_base64(client, nested_url.strip())
            if isinstance(value, list):
                for item in value:
                    if not isinstance(item, dict):
                        continue
                    nested_b64 = item.get("b64_json")
                    if isinstance(nested_b64, str) and nested_b64.strip():
                        return nested_b64.strip()
                    nested_url = item.get("url")
                    if isinstance(nested_url, str) and nested_url.strip():
                        return await self._download_as_base64(client, nested_url.strip())

        return None

    async def _download_as_base64(self, client: httpx.AsyncClient, url: str) -> str:
        parsed = urlparse(url)
        logger.info(
            "retouch.provider.download.start host=%s path=%s",
            parsed.netloc or "-",
            parsed.path[:120] or "/",
        )
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
        return encoded

    def _extract_upstream_request_id(self, response: httpx.Response) -> str:
        for key in ("x-request-id", "x-tt-logid", "x-logid", "x-amzn-requestid", "trace-id"):
            value = response.headers.get(key, "").strip()
            if value:
                return value
        return "-"

    def _env_choice(self, key: str, default: str, allowed: set[str]) -> str:
        raw = os.getenv(key)
        if raw is None:
            return default
        value = raw.strip().lower()
        if value in allowed:
            return value
        return default

    def _env_bool(self, key: str, default: bool) -> bool:
        raw = os.getenv(key)
        if raw is None:
            return default
        return raw.strip().lower() in {"1", "true", "yes", "on"}

    def _env_int(self, key: str, default: int, min_value: int, max_value: int) -> int:
        raw = os.getenv(key)
        if raw is None:
            return default
        try:
            value = int(raw)
        except Exception:
            return default
        return max(min_value, min(max_value, value))

    def _env_float(self, key: str, default: float, min_value: float, max_value: float) -> float:
        raw = os.getenv(key)
        if raw is None:
            return default
        try:
            value = float(raw)
        except Exception:
            return default
        return max(min_value, min(max_value, value))
