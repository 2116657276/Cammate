from __future__ import annotations

import asyncio
import base64
import io
import json
import logging
import os
import re
import time
from difflib import SequenceMatcher
from typing import Any

import httpx
from PIL import Image

from app.core.config import load_runtime_env
from providers.base import VisionProvider
from providers.mock_provider import MockProvider

logger = logging.getLogger("uvicorn.error")


class ExternalProvider(VisionProvider):
    """
    External provider for Volcengine Ark vision models.
    Falls back to MockProvider on any failure to keep MVP always available.
    """

    def __init__(self) -> None:
        load_runtime_env()
        raw_api_url = os.getenv(
            "ARK_API_URL",
            os.getenv("EXTERNAL_VISION_API_URL", "https://ark.cn-beijing.volces.com/api/v3/responses"),
        )
        self.api_url = os.getenv(
            "ARK_CHAT_API_URL",
            raw_api_url.replace("/responses", "/chat/completions"),
        )
        self.api_key = os.getenv("ARK_API_KEY", os.getenv("EXTERNAL_VISION_API_KEY", ""))
        self.model = os.getenv("ARK_MODEL", os.getenv("EXTERNAL_VISION_MODEL", "doubao-seed-2-0-lite-260215"))
        self.timeout_sec = self._env_float("EXTERNAL_TIMEOUT_SEC", 13.2, 2.0, 20.0)
        self.max_output_tokens = self._env_int("ARK_MAX_OUTPUT_TOKENS", 1200, 128, 3072)
        # Slightly higher temperature for better variation while keeping guidance stable.
        self.temperature = self._env_float("ARK_TEMPERATURE", 0.33, 0.0, 1.0)
        self.reasoning_effort = self._env_choice(
            "ARK_REASONING_EFFORT",
            "minimal",
            {"minimal", "low", "medium", "high"},
        )
        # Default bypasses local HTTP(S) proxy to avoid unnecessary detours for domestic Ark endpoint.
        self.trust_env_proxy = self._env_bool("ARK_TRUST_ENV", False)
        # Default to strict cloud mode: do not silently fall back to local mock suggestions.
        self.strict_cloud = self._env_bool("ARK_STRICT_CLOUD", True)
        self.rate_limit_cooldown_sec = self._env_float("ARK_RATE_LIMIT_COOLDOWN_SEC", 12.0, 1.0, 120.0)
        self._rate_limited_until = 0.0
        self._consecutive_429 = 0
        self._fallback = MockProvider()

    async def analyze(
        self,
        image_base64: str,
        detected_scene: str,
        client_context: dict[str, Any],
    ) -> list[dict[str, Any]]:
        if not self.api_key:
            if self.strict_cloud:
                raise RuntimeError("ARK_API_KEY missing")
            return await self._fallback.analyze(image_base64, detected_scene, client_context)
        now = time.monotonic()
        if now < self._rate_limited_until:
            remaining = self._rate_limited_until - now
            raise RuntimeError(f"cloud rate limited cooldown active ({remaining:.1f}s)")

        cloud_image_base64 = self._prepare_cloud_image_base64(image_base64)
        payload = self._build_chat_payload(
            image_base64=cloud_image_base64,
            prompt=self._build_prompt(
                detected_scene=detected_scene,
                client_context=client_context,
            ),
            max_tokens=self.max_output_tokens,
            temperature=self.temperature,
        )

        headers = {
            "Authorization": f"Bearer {self.api_key}",
            "Content-Type": "application/json",
        }
        recent_tips = self._history_tips(client_context)

        try:
            payloads = [
                payload,
                self._build_retry_payload(cloud_image_base64, detected_scene, client_context, retry_index=1),
                self._build_retry_payload(cloud_image_base64, detected_scene, client_context, retry_index=2),
                self._build_retry_payload(cloud_image_base64, detected_scene, client_context, retry_index=3),
            ]
            for attempt, current_payload in enumerate(payloads, start=1):
                try:
                    async with httpx.AsyncClient(timeout=self.timeout_sec, trust_env=self.trust_env_proxy) as client:
                        resp = await client.post(self.api_url, headers=headers, json=current_payload)
                        resp.raise_for_status()
                        result = resp.json()
                except httpx.HTTPStatusError as exc:
                    status = exc.response.status_code if exc.response is not None else -1
                    if status == 429 and attempt < len(payloads):
                        self._consecutive_429 += 1
                        cooldown = self.rate_limit_cooldown_sec * min(3, max(1, self._consecutive_429))
                        self._rate_limited_until = max(self._rate_limited_until, time.monotonic() + cooldown)
                        backoff = 0.35 * attempt
                        logger.warning(
                            "external.analyze rate_limited attempt=%d backoff=%.2fs cooldown=%.1fs",
                            attempt,
                            backoff,
                            cooldown,
                        )
                        await asyncio.sleep(backoff)
                        continue
                    raise

                raw_text = self._extract_text(result)
                events = self._events_from_raw_text(raw_text, detected_scene, client_context)
                tip = self._extract_ui_tip(events) if events is not None else ""
                is_duplicate = self._is_duplicate_tip_against_recent(tip, recent_tips) if tip else True
                if not is_duplicate and events is not None and self._should_reject_redundant_move(events, client_context):
                    is_duplicate = True
                logger.info(
                    "external.analyze attempt=%d scene=%s status=%s incomplete=%s text_len=%d duplicate=%s",
                    attempt,
                    detected_scene,
                    str(result.get("status", "")),
                    result.get("incomplete_details"),
                    len(raw_text),
                    is_duplicate,
                )
                if events is not None and not is_duplicate:
                    self._consecutive_429 = 0
                    self._rate_limited_until = 0.0
                    logger.info(
                        "external.analyze accepted attempt=%d grid=%s tip=%s",
                        attempt,
                        self._extract_strategy_grid(events),
                        tip,
                    )
                    return events

            raise RuntimeError("cloud repeated tip")
        except Exception as exc:
            logger.warning("external.analyze failed: %s", exc)
            if self.strict_cloud:
                raise
            return await self._fallback.analyze(image_base64, detected_scene, client_context)

    def _prepare_cloud_image_base64(self, image_base64: str) -> str:
        # Downscale before cloud inference to reduce latency and incomplete(length) responses.
        try:
            raw = base64.b64decode(image_base64, validate=True)
            with Image.open(io.BytesIO(raw)) as image:
                rgb = image.convert("RGB")
                max_side = self._env_int("ARK_IMAGE_MAX_SIDE", 960, 640, 1536)
                quality = self._env_int("ARK_IMAGE_JPEG_QUALITY", 82, 60, 95)
                if max(rgb.size) > max_side:
                    rgb.thumbnail((max_side, max_side), Image.Resampling.LANCZOS)
                buf = io.BytesIO()
                rgb.save(buf, format="JPEG", quality=quality, optimize=True)
                return base64.b64encode(buf.getvalue()).decode("utf-8")
        except Exception:
            return image_base64

    def _build_retry_payload(
        self,
        image_base64: str,
        detected_scene: str,
        client_context: dict[str, Any],
        retry_index: int = 1,
    ) -> dict[str, Any]:
        retry_tokens = max(220, min(720, self.max_output_tokens))
        return self._build_chat_payload(
            image_base64=image_base64,
            prompt=self._build_retry_prompt(detected_scene, client_context, retry_index=retry_index),
            max_tokens=retry_tokens,
            temperature=min(0.55, max(0.2, self.temperature + 0.04 * retry_index)),
        )

    def _build_chat_payload(
        self,
        image_base64: str,
        prompt: str,
        max_tokens: int,
        temperature: float,
    ) -> dict[str, Any]:
        return {
            "model": self.model,
            "reasoning_effort": self.reasoning_effort,
            "max_tokens": max_tokens,
            "temperature": temperature,
            "messages": [
                {
                    "role": "user",
                    "content": [
                        {
                            "type": "image_url",
                            "image_url": {
                                "url": f"data:image/jpeg;base64,{image_base64}",
                            },
                        },
                        {
                            "type": "text",
                            "text": prompt,
                        },
                    ],
                }
            ],
        }

    def _build_retry_prompt(self, detected_scene: str, client_context: dict[str, Any], retry_index: int) -> str:
        recent_tips = self._history_tips(client_context)
        scene_hint = str(client_context.get("scene_hint") or detected_scene).strip().lower()
        subject_center = client_context.get("subject_center_norm")
        subject_bbox = client_context.get("subject_bbox_norm")
        return (
            "只输出一条中文拍摄建议，不超过22字，不要解释，不要列表，不要思考过程。\n"
            "本次建议必须不同于 recent_tip_texts 中任意一条，尤其不要重复三分线措辞。\n"
            "如果主体已接近目标位置，禁止重复给位置建议，改给曝光/光线/背景建议。\n"
            f"scene_hint={scene_hint}, retry_index={retry_index}, "
            f"subject_center_norm={subject_center if subject_center else ['无']}, "
            f"subject_bbox_norm={subject_bbox if subject_bbox else ['无']}, "
            f"recent_tip_texts={recent_tips if recent_tips else ['无']}"
        )

    def _build_prompt(self, detected_scene: str, client_context: dict[str, Any]) -> str:
        capture_mode = str(client_context.get("capture_mode") or "auto").strip().lower()
        scene_hint = str(client_context.get("scene_hint") or detected_scene).strip().lower()
        frame_stable = bool(client_context.get("frame_stable", False))
        stability_score = float(client_context.get("stability_score", 0.0) or 0.0)
        recent_tips = self._history_tips(client_context)
        subject_center = client_context.get("subject_center_norm")
        subject_bbox = client_context.get("subject_bbox_norm")

        return (
            "你是摄影指导助手。仅给最终结果，不要思考过程。\n"
            "目标: 给出一条当前最能提升成片质量的建议。\n"
            "输出优先为JSON，不要markdown，不要解释:\n"
            '{"strategy":{"grid":"thirds|center|none","target_point_norm":[0~1,0~1]},'
            '"ui":{"text":"<=24字中文建议","level":"info|warn"},'
            '"param":{"exposure_compensation":-2..2}}\n'
            "若无法给JSON，退化为一条<=22字中文建议（纯文本）。\n"
            "约束:\n"
            "1) 只给一条建议；\n"
            "2) 可在 thirds/center/none 中自由选择，不要固定三分线；\n"
            "3) 若 recent_tip_texts 非空，本次必须与其中任意一条动作不同；\n"
            "4) 若 frame_stable=false 或 stability_score<0.78，优先提示先稳住画面；\n"
            "5) 若 subject_center_norm 已接近 target_point_norm(<=0.06)，不要再提放到三分线或居中；\n"
            "6) 若输出JSON，坐标保留2位小数。\n"
            f"模式策略: {self._mode_prompt(capture_mode)}\n"
            f"scene_hint={scene_hint}, detector_scene={detected_scene}, "
            f"frame_stable={frame_stable}, stability_score={stability_score:.2f}, "
            f"subject_center_norm={subject_center if subject_center else ['无']}, "
            f"subject_bbox_norm={subject_bbox if subject_bbox else ['无']}, "
            f"recent_tip_texts={recent_tips if recent_tips else ['无']}"
        )

    def _mode_prompt(self, capture_mode: str) -> str:
        if capture_mode == "portrait":
            return "人像模式：优先人物表情和眼神，建议姿态与留白。"
        if capture_mode == "general":
            return "通用模式：优先提升主体突出度和画面平衡。"
        return "自动模式：根据 scene_hint 选择一条当前最优建议。"

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
        # Only consume assistant message text, never reasoning summary.
        outputs = response_data.get("output")
        if isinstance(outputs, list):
            text_parts = []
            for out in outputs:
                if not isinstance(out, dict):
                    continue
                if str(out.get("type", "")).strip().lower() != "message":
                    continue
                content = out.get("content")
                if not isinstance(content, list):
                    continue
                for part in content:
                    if not isinstance(part, dict):
                        continue
                    if isinstance(part.get("json"), dict):
                        return json.dumps(part["json"], ensure_ascii=False)
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
        if isinstance(content, list):
            text_parts: list[str] = []
            for part in content:
                if isinstance(part, dict):
                    txt = part.get("text")
                    if isinstance(txt, str) and txt.strip():
                        text_parts.append(txt.strip())
                elif isinstance(part, str) and part.strip():
                    text_parts.append(part.strip())
            if text_parts:
                return "\n".join(text_parts).strip()

        return ""

    def _parse_strict_json_output(self, raw_text: str) -> dict[str, Any] | None:
        text = raw_text.strip()
        if not text:
            return None

        try:
            obj = json.loads(text)
            if not isinstance(obj, dict):
                return None
            # strict schema gate: must include strategy/ui objects
            strategy = obj.get("strategy")
            ui = obj.get("ui")
            if not isinstance(strategy, dict) or not isinstance(ui, dict):
                return None
            return obj
        except Exception:
            return None

    def _events_from_raw_text(
        self,
        raw_text: str,
        detected_scene: str,
        client_context: dict[str, Any],
    ) -> list[dict[str, Any]] | None:
        parsed = self._parse_strict_json_output(raw_text)
        if parsed is not None:
            events = self._normalize_events(parsed, detected_scene, client_context)
            tip = self._extract_ui_tip(events)
            if self._looks_like_internal_reasoning(tip):
                return None
            return events
        return self._events_from_plain_text(raw_text, detected_scene, client_context)

    def _normalize_events(
        self,
        data: dict[str, Any],
        detected_scene: str,
        client_context: dict[str, Any],
    ) -> list[dict[str, Any]]:
        events: list[dict[str, Any]] = []
        strategy = data.get("strategy", {})
        ui = data.get("ui", {})
        target = data.get("target")
        param = data.get("param")
        capture_mode = str(client_context.get("capture_mode") or "auto").strip().lower()
        raw_grid = str(strategy.get("grid", "")).strip().lower()
        grid = raw_grid if raw_grid in {"thirds", "center", "none"} else self._default_grid(detected_scene, capture_mode)
        target_point = strategy.get("target_point_norm", self._default_target_point(detected_scene, grid))

        events.append(
            {
                "type": "strategy",
                "grid": grid,
                "target_point_norm": target_point,
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

        ui_text = str(ui.get("text", "")).strip()
        if not ui_text:
            ui_text = "云端未返回建议，请重试"
        events.append({"type": "ui", "text": ui_text, "level": ui.get("level", "info")})

        if isinstance(param, dict) and "exposure_compensation" in param:
            try:
                ev = int(round(float(param["exposure_compensation"])))
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

    def _events_from_plain_text(
        self,
        raw_text: str,
        detected_scene: str,
        client_context: dict[str, Any],
    ) -> list[dict[str, Any]] | None:
        text = (raw_text or "").strip()
        if not text:
            return None
        if self._looks_like_internal_reasoning(text):
            return None

        lines = [x.strip() for x in text.splitlines() if x.strip()]
        if not lines:
            return None

        # remove markdown heading/bullet noise and keep first actionable sentence
        cleaned: list[str] = []
        for line in lines:
            line = re.sub(r"^[#>*\\-\\d\\.\\s]+", "", line).strip()
            if line:
                cleaned.append(line)
        if not cleaned:
            return None

        banned_tokens = (
            "json",
            "schema",
            "strategy",
            "target_point_norm",
            "grid",
            "exposure_compensation",
            "输出",
            "字段",
            "约束",
            "我现在需要",
            "生成符合要求",
            "思考",
        )
        filtered = [x for x in cleaned if not any(tok in x.lower() for tok in banned_tokens)]
        if not filtered:
            return None
        cleaned = filtered

        tip = ""
        for i, line in enumerate(cleaned):
            if "构图建议" in line and i + 1 < len(cleaned):
                tip = cleaned[i + 1]
                break
        for line in cleaned:
            if any(k in line for k in ("建议", "对齐", "放在", "靠近", "留白", "构图", "水平", "稳住", "曝光")):
                tip = line
                break
        if not tip:
            tip = cleaned[0]
        if self._looks_like_internal_reasoning(tip):
            return None
        tip = tip[:48]

        capture_mode = str(client_context.get("capture_mode") or "auto").strip().lower()
        grid = self._default_grid(detected_scene, capture_mode)
        target = self._default_target_point(detected_scene, grid)

        return [
            {
                "type": "strategy",
                "grid": grid,
                "target_point_norm": [round(float(target[0]), 2), round(float(target[1]), 2)],
            },
            {
                "type": "ui",
                "text": tip,
                "level": "info",
            },
            {
                "type": "param",
                "exposure_compensation": 0,
            },
            {"type": "done"},
        ]

    def _looks_like_internal_reasoning(self, text: str) -> bool:
        lowered = text.strip().lower()
        if not lowered:
            return True
        markers = (
            "我现在",
            "我现在需要",
            "首先",
            "接下来",
            "然后",
            "不对",
            "等下",
            "再调整",
            "schema",
            "json",
            "target_point_norm",
            "exposure_compensation",
            "输出",
            "字段",
            "思考",
        )
        return any(marker in lowered for marker in markers)

    def _default_grid(self, detected_scene: str, capture_mode: str) -> str:
        if capture_mode == "portrait" or detected_scene == "portrait":
            return "center"
        if detected_scene in {"landscape", "food"}:
            return "thirds"
        if capture_mode == "general":
            return "thirds"
        if detected_scene == "night":
            return "none"
        return "none"

    def _default_target_point(self, detected_scene: str, grid: str) -> list[float]:
        if grid == "center":
            return [0.5, 0.52]
        if detected_scene == "landscape":
            return [0.5, 0.35]
        if detected_scene == "food":
            return [0.5, 0.62]
        if detected_scene == "portrait":
            return [0.55, 0.45]
        if grid == "thirds":
            return [0.67, 0.5]
        return [0.5, 0.5]

    def _history_tips(self, client_context: dict[str, Any]) -> list[str]:
        tips: list[str] = []
        recent = client_context.get("recent_tip_texts")
        if isinstance(recent, list):
            for item in recent:
                text = str(item or "").strip()
                if text:
                    tips.append(text[:80])
        previous_tip = str(client_context.get("previous_tip_text") or "").strip()
        if previous_tip:
            tips.append(previous_tip[:80])
        deduped: list[str] = []
        for tip in tips:
            if tip not in deduped:
                deduped.append(tip)
        return deduped[-4:]

    def _is_duplicate_tip_against_recent(self, tip: str, recent_tips: list[str]) -> bool:
        text = str(tip or "").strip()
        if not text:
            return True
        for old_tip in recent_tips:
            if self._tip_too_similar(text, old_tip):
                return True
        return False

    def _tip_too_similar(self, current_tip: str, previous_tip: str) -> bool:
        current = self._normalize_tip_text(current_tip)
        previous = self._normalize_tip_text(previous_tip)
        if not current or not previous:
            return False
        if current == previous or current in previous or previous in current:
            return True

        # Catch paraphrase variants with different wording but same intent.
        ratio = SequenceMatcher(None, current, previous).ratio()
        if ratio >= 0.66:
            return True

        current_topics = self._tip_topics(current)
        previous_topics = self._tip_topics(previous)
        if current_topics and previous_topics:
            if current_topics == previous_topics:
                return True
            if len(current_topics.intersection(previous_topics)) >= 2:
                return True
            if "thirds" in current_topics and "thirds" in previous_topics:
                return True
            if "center" in current_topics and "center" in previous_topics:
                return True
            if "exposure" in current_topics and "exposure" in previous_topics and ratio >= 0.42:
                return True

        keywords = ("三分", "九宫格", "构图", "曝光", "居中", "留白", "背景", "角度", "机位", "姿态", "光线", "水平")
        overlap = sum(1 for k in keywords if k in current and k in previous)
        return overlap >= 2

    def _normalize_tip_text(self, text: str) -> str:
        return (
            text.strip()
            .replace("，", "")
            .replace("。", "")
            .replace("、", "")
            .replace(" ", "")
            .lower()
        )

    def _tip_topics(self, text: str) -> set[str]:
        topics: set[str] = set()
        groups: list[tuple[str, tuple[str, ...]]] = [
            ("thirds", ("三分", "九宫格", "交点", "构图")),
            ("center", ("居中", "中央", "中心")),
            ("exposure", ("曝光", "高光", "阴影", "亮度")),
            ("lighting", ("光线", "侧光", "逆光", "补光")),
            ("background", ("背景", "干扰", "杂物", "留白")),
            ("angle", ("机位", "角度", "下蹲", "俯拍", "仰拍", "平移")),
            ("horizon", ("水平", "地平线", "倾斜")),
            ("subject", ("主体", "分离", "突出")),
            ("pose", ("姿态", "站姿", "抬头", "眼神")),
            ("stability", ("稳住", "防抖", "稳定")),
            ("move", ("左移", "右移", "上移", "下移", "靠近", "后退", "平移")),
        ]
        for name, words in groups:
            if any(w in text for w in words):
                topics.add(name)
        return topics

    def _should_reject_redundant_move(self, events: list[dict[str, Any]], client_context: dict[str, Any]) -> bool:
        subject = client_context.get("subject_center_norm")
        if not isinstance(subject, list) or len(subject) < 2:
            return False
        try:
            sx = float(subject[0])
            sy = float(subject[1])
        except Exception:
            return False

        target: list[float] | None = None
        tip_text = ""
        for event in events:
            if event.get("type") == "strategy":
                point = event.get("target_point_norm")
                if isinstance(point, list) and len(point) >= 2:
                    target = point
            if event.get("type") == "ui":
                tip_text = str(event.get("text", ""))
        if target is None:
            return False
        try:
            tx = float(target[0])
            ty = float(target[1])
        except Exception:
            return False

        dx = tx - sx
        dy = ty - sy
        dist = (dx * dx + dy * dy) ** 0.5
        if dist > 0.06:
            return False
        move_tokens = ("放在", "移到", "移至", "靠近", "居中", "三分", "交点", "左移", "右移", "上移", "下移")
        return any(token in tip_text for token in move_tokens)

    def _response_schema(self) -> dict[str, Any]:
        return {
            "type": "object",
            "properties": {
                "strategy": {
                    "type": "object",
                    "properties": {
                        "grid": {"type": "string", "enum": ["thirds", "center", "none"]},
                        "target_point_norm": {
                            "type": "array",
                            "items": {"type": "number"},
                            "minItems": 2,
                            "maxItems": 2,
                        },
                    },
                    "required": ["grid", "target_point_norm"],
                    "additionalProperties": False,
                },
                "ui": {
                    "type": "object",
                    "properties": {
                        "text": {"type": "string", "maxLength": 28},
                        "level": {"type": "string", "enum": ["info", "warn"]},
                    },
                    "required": ["text", "level"],
                    "additionalProperties": False,
                },
                "param": {
                    "type": "object",
                    "properties": {
                        "exposure_compensation": {"type": "integer", "minimum": -2, "maximum": 2},
                    },
                    "required": ["exposure_compensation"],
                    "additionalProperties": False,
                },
            },
            "required": ["strategy", "ui", "param"],
            "additionalProperties": False,
        }

    def _extract_strategy_grid(self, events: list[dict[str, Any]]) -> str:
        for event in events:
            if event.get("type") == "strategy":
                return str(event.get("grid", "none"))
        return "none"

    def _extract_ui_tip(self, events: list[dict[str, Any]]) -> str:
        for event in events:
            if event.get("type") == "ui":
                return str(event.get("text", ""))
        return ""

    def _env_bool(self, key: str, default: bool) -> bool:
        raw = os.getenv(key)
        if raw is None:
            return default
        return raw.strip().lower() in {"1", "true", "yes", "on"}

    def _env_choice(self, key: str, default: str, allowed: set[str]) -> str:
        raw = os.getenv(key)
        if raw is None:
            return default
        value = raw.strip().lower()
        if value in allowed:
            return value
        return default

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
