from __future__ import annotations

import json
import os
from typing import Any

import httpx

from providers.base import VisionProvider
from providers.mock_provider import MockProvider


class ExternalProvider(VisionProvider):
    """
    External provider for Volcengine Ark Responses API.
    Falls back to MockProvider on any failure to keep MVP always available.
    """

    def __init__(self) -> None:
        self.api_url = os.getenv(
            "ARK_API_URL",
            os.getenv("EXTERNAL_VISION_API_URL", "https://ark.cn-beijing.volces.com/api/v3/responses"),
        )
        self.api_key = os.getenv("ARK_API_KEY", os.getenv("EXTERNAL_VISION_API_KEY", ""))
        self.model = os.getenv("ARK_MODEL", os.getenv("EXTERNAL_VISION_MODEL", "doubao-seed-2-0-mini-260215"))
        self.timeout_sec = float(os.getenv("EXTERNAL_TIMEOUT_SEC", "20"))
        self._fallback = MockProvider()

    async def analyze(
        self,
        image_base64: str,
        detected_scene: str,
        client_context: dict[str, Any],
    ) -> list[dict[str, Any]]:
        if not self.api_key:
            return await self._fallback.analyze(image_base64, detected_scene, client_context)

        payload = {
            "model": self.model,
            "input": [
                {
                    "role": "user",
                    "content": [
                        {
                            "type": "input_image",
                            "image_url": f"data:image/jpeg;base64,{image_base64}",
                        },
                        {
                            "type": "input_text",
                            "text": self._build_prompt(
                                detected_scene=detected_scene,
                                client_context=client_context,
                            ),
                        },
                    ],
                }
            ],
        }

        headers = {
            "Authorization": f"Bearer {self.api_key}",
            "Content-Type": "application/json",
        }

        try:
            async with httpx.AsyncClient(timeout=self.timeout_sec) as client:
                resp = await client.post(self.api_url, headers=headers, json=payload)
                resp.raise_for_status()
                result = resp.json()

            raw_text = self._extract_text(result)
            parsed = self._extract_json_object(raw_text)
            if parsed is None:
                return await self._fallback.analyze(image_base64, detected_scene, client_context)
            return self._normalize_events(parsed)
        except Exception:
            return await self._fallback.analyze(image_base64, detected_scene, client_context)

    def _build_prompt(self, detected_scene: str, client_context: dict[str, Any]) -> str:
        return (
            "请分析这张拍前取景图。只输出一个 JSON 对象，不要 markdown，不要解释。\n"
            "JSON 字段严格如下：\n"
            "{\n"
            '  "strategy": {"grid": "thirds|center|none", "target_point_norm": [0-1,0-1]},\n'
            '  "target": {"bbox_norm": [0-1,0-1,0-1,0-1], "center_norm": [0-1,0-1]},\n'
            '  "ui": {"text": "一句中文建议，<=30字", "level": "info|warn"},\n'
            '  "param": {"exposure_compensation": -2..2}\n'
            "}\n"
            "约束：\n"
            "1) 坐标必须是 0~1 小数；\n"
            "2) 没有目标可省略 target；\n"
            "3) 参考场景识别结果："
            f"{detected_scene}；\n"
            "4) client_context="
            f"{json.dumps(client_context, ensure_ascii=False)}\n"
            "5) 输出必须是合法 JSON 对象。"
        )

    def _extract_text(self, response_data: dict[str, Any]) -> str:
        # 1) Some APIs provide output_text directly.
        output_text = response_data.get("output_text")
        if isinstance(output_text, str) and output_text.strip():
            return output_text.strip()
        if isinstance(output_text, list):
            text_parts = []
            for item in output_text:
                if isinstance(item, str):
                    text_parts.append(item)
                elif isinstance(item, dict):
                    txt = item.get("text") or item.get("output_text")
                    if isinstance(txt, str):
                        text_parts.append(txt)
            if text_parts:
                return "\n".join(text_parts).strip()

        # 2) Ark/OpenAI-like response.output[].content[].text
        outputs = response_data.get("output")
        if isinstance(outputs, list):
            text_parts = []
            for out in outputs:
                if not isinstance(out, dict):
                    continue
                content = out.get("content")
                if not isinstance(content, list):
                    continue
                for part in content:
                    if not isinstance(part, dict):
                        continue
                    txt = part.get("text") or part.get("output_text")
                    if isinstance(txt, str) and txt.strip():
                        text_parts.append(txt.strip())
            if text_parts:
                return "\n".join(text_parts).strip()

        # 3) Backward compatibility for chat-completions-like shape.
        content = (
            response_data.get("choices", [{}])[0]
            .get("message", {})
            .get("content")
        )
        if isinstance(content, str) and content.strip():
            return content.strip()

        return ""

    def _extract_json_object(self, raw_text: str) -> dict[str, Any] | None:
        text = raw_text.strip()
        if not text:
            return None

        try:
            obj = json.loads(text)
            if isinstance(obj, dict):
                return obj
        except Exception:
            pass

        start = text.find("{")
        if start < 0:
            return None

        depth = 0
        in_string = False
        escaped = False
        for idx in range(start, len(text)):
            ch = text[idx]
            if in_string:
                if escaped:
                    escaped = False
                elif ch == "\\":
                    escaped = True
                elif ch == '"':
                    in_string = False
                continue

            if ch == '"':
                in_string = True
            elif ch == "{":
                depth += 1
            elif ch == "}":
                depth -= 1
                if depth == 0:
                    candidate = text[start : idx + 1]
                    try:
                        obj = json.loads(candidate)
                        if isinstance(obj, dict):
                            return obj
                    except Exception:
                        return None

        return None

    def _normalize_events(self, data: dict[str, Any]) -> list[dict[str, Any]]:
        events: list[dict[str, Any]] = []
        strategy = data.get("strategy", {})
        ui = data.get("ui", {})
        target = data.get("target")
        param = data.get("param")

        events.append(
            {
                "type": "strategy",
                "grid": strategy.get("grid", "thirds"),
                "target_point_norm": strategy.get("target_point_norm", [0.5, 0.5]),
            }
        )

        if isinstance(target, dict):
            events.append(
                {
                    "type": "target",
                    "bbox_norm": target.get("bbox_norm", [0.2, 0.2, 0.8, 0.8]),
                    "center_norm": target.get("center_norm", [0.5, 0.5]),
                }
            )

        events.append(
            {
                "type": "ui",
                "text": ui.get("text", "请微调构图，让主体靠近三分点。"),
                "level": ui.get("level", "info"),
            }
        )

        if isinstance(param, dict) and "exposure_compensation" in param:
            try:
                ev = int(param["exposure_compensation"])
            except Exception:
                ev = 0
            events.append(
                {
                    "type": "param",
                    "exposure_compensation": ev,
                }
            )

        events.append({"type": "done"})
        return events
