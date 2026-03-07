from __future__ import annotations

import base64
import os
from dataclasses import dataclass
from typing import Any

import httpx

from app.core.config import load_runtime_env


@dataclass
class RetouchResult:
    image_base64: str
    provider: str
    model: str


class DoubaoImageEditProvider:
    """
    Best-effort adapter for Doubao/Ark image editing APIs.
    It supports responses that return either `b64_json` or `url`.
    """

    def __init__(self) -> None:
        load_runtime_env()
        self.api_url = os.getenv(
            "ARK_IMAGE_API_URL",
            "https://ark.cn-beijing.volces.com/api/v3/images/generations",
        )
        self.api_key = os.getenv("ARK_API_KEY", "")
        self.model = os.getenv("ARK_IMAGE_MODEL", "doubao-seedream-3-0-t2i-250415")
        self.timeout_sec = float(os.getenv("ARK_IMAGE_TIMEOUT_SEC", "45"))

    async def retouch(
        self,
        image_base64: str,
        preset: str,
        strength: float,
        scene_hint: str | None,
    ) -> RetouchResult:
        if not self.api_key:
            raise RuntimeError("ARK_API_KEY missing")

        prompt = self._build_prompt(
            preset=preset,
            strength=strength,
            scene_hint=scene_hint,
        )

        payload_candidates = [
            {
                "model": self.model,
                "prompt": prompt,
                "image": image_base64,
                "response_format": "b64_json",
            },
            {
                "model": self.model,
                "prompt": prompt,
                "image": f"data:image/jpeg;base64,{image_base64}",
                "response_format": "b64_json",
            },
            {
                "model": self.model,
                "prompt": prompt,
                "input": {
                    "image": image_base64,
                },
                "response_format": "b64_json",
            },
        ]

        headers = {
            "Authorization": f"Bearer {self.api_key}",
            "Content-Type": "application/json",
        }

        async with httpx.AsyncClient(timeout=self.timeout_sec) as client:
            last_error: Exception | None = None
            for payload in payload_candidates:
                try:
                    resp = await client.post(self.api_url, headers=headers, json=payload)
                    resp.raise_for_status()
                    data = resp.json()
                    b64 = await self._extract_image_base64(client, data)
                    if b64:
                        return RetouchResult(
                            image_base64=b64,
                            provider="doubao",
                            model=self.model,
                        )
                except Exception as exc:
                    last_error = exc
                    continue

        raise RuntimeError(f"Doubao retouch failed: {last_error}")

    def _build_prompt(self, preset: str, strength: float, scene_hint: str | None) -> str:
        strength_pct = int(max(0.0, min(1.0, strength)) * 100)
        scene_text = scene_hint or "general"

        preset_map = {
            "natural": "保持自然观感，轻微优化曝光、白平衡和对比度，避免过度磨皮。",
            "portrait": "优化人像肤色与层次，保持五官真实，轻度提亮主体。",
            "food": "增强食物色泽与质感，抑制杂色，突出食材层次与细节。",
            "night": "保留夜景氛围，压高光、提暗部细节，降低噪点。",
            "cinematic": "强化光影层次与色彩对比，保持电影感但不过度锐化。",
        }

        preset_text = preset_map.get(preset, preset_map["natural"])
        return (
            "请对输入图片进行照片级修图，仅输出处理后的图片。"
            f"场景参考：{scene_text}。"
            f"风格预设：{preset}。"
            f"强度：{strength_pct}%。"
            f"要求：{preset_text}"
        )

    async def _extract_image_base64(
        self,
        client: httpx.AsyncClient,
        response_data: dict[str, Any],
    ) -> str | None:
        # OpenAI-like shape: {"data":[{"b64_json":"..."}|{"url":"..."}]}
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

        # Generic fallback: scan nested dict for b64_json/url
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
        resp = await client.get(url)
        resp.raise_for_status()
        return base64.b64encode(resp.content).decode("utf-8")
