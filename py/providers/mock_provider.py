from __future__ import annotations

from typing import Any

from providers.base import VisionProvider


class MockProvider(VisionProvider):
    def __init__(self) -> None:
        self._tips: dict[str, list[str]] = {
            "portrait": [
                "人像建议：眼睛靠近上三分线，头顶留一点空间。",
                "人像建议：人物离背景稍远，主体层次会更清晰。",
                "人像建议：让肩线微微倾斜，画面会更自然。",
            ],
            "food": [
                "美食建议：主体放在下三分线，尽量减少杂物。",
                "美食建议：先拍整体，再贴近拍细节纹理。",
                "美食建议：稍提亮度，食物层次更干净。",
            ],
            "night": [
                "夜景建议：优先保护高光，避免霓虹过曝。",
                "夜景建议：按快门前先稳住机身，减少拖影。",
                "夜景建议：把主体靠近主光区，画面更通透。",
            ],
            "landscape": [
                "风景建议：地平线放在三分线，不要居中。",
                "风景建议：加入前景元素，增强纵深层次。",
                "风景建议：先找主视觉，再简化边缘干扰。",
            ],
            "general": [
                "通用建议：主体靠近三分点，保持画面水平。",
                "通用建议：降低机位一点，画面层次更明显。",
                "通用建议：先确定主光方向，再微调主体位置。",
            ],
        }

    async def analyze(
        self,
        image_base64: str,
        detected_scene: str,
        client_context: dict[str, Any],
    ) -> list[dict[str, Any]]:
        exposure = int(client_context.get("exposure_compensation", 0))
        scene = detected_scene if detected_scene in self._tips else "general"

        tip_pool = self._tips[scene]
        seed = len(image_base64) + exposure * 7 + len(scene) * 13
        tip = tip_pool[seed % len(tip_pool)]

        if scene == "portrait":
            recommend_ev = 0 if exposure <= 1 else -1
            return [
                {
                    "type": "strategy",
                    "grid": "center",
                    "target_point_norm": [0.5, 0.4],
                },
                {
                    "type": "target",
                    "bbox_norm": [0.3, 0.2, 0.7, 0.85],
                    "center_norm": [0.5, 0.525],
                },
                {
                    "type": "ui",
                    "text": tip,
                    "level": "info",
                },
                {
                    "type": "param",
                    "exposure_compensation": recommend_ev,
                },
                {"type": "done"},
            ]

        if scene == "food":
            recommend_ev = 1
            return [
                {
                    "type": "strategy",
                    "grid": "thirds",
                    "target_point_norm": [0.5, 0.62],
                },
                {
                    "type": "target",
                    "bbox_norm": [0.18, 0.34, 0.82, 0.90],
                    "center_norm": [0.5, 0.62],
                },
                {
                    "type": "ui",
                    "text": tip,
                    "level": "info",
                },
                {
                    "type": "param",
                    "exposure_compensation": recommend_ev,
                },
                {"type": "done"},
            ]

        if scene == "night":
            recommend_ev = -1
            return [
                {
                    "type": "strategy",
                    "grid": "thirds",
                    "target_point_norm": [0.5, 0.45],
                },
                {
                    "type": "ui",
                    "text": tip,
                    "level": "info",
                },
                {
                    "type": "param",
                    "exposure_compensation": recommend_ev,
                },
                {"type": "done"},
            ]

        if scene == "landscape":
            recommend_ev = 0
            return [
                {
                    "type": "strategy",
                    "grid": "thirds",
                    "target_point_norm": [0.5, 0.38],
                },
                {
                    "type": "ui",
                    "text": tip,
                    "level": "info",
                },
                {
                    "type": "param",
                    "exposure_compensation": recommend_ev,
                },
                {"type": "done"},
            ]

        recommend_ev = -1 if exposure > 1 else 0
        return [
            {
                "type": "strategy",
                "grid": "thirds",
                "target_point_norm": [0.66, 0.5],
            },
            {
                "type": "ui",
                "text": tip,
                "level": "info",
            },
            {
                "type": "param",
                "exposure_compensation": recommend_ev,
            },
            {"type": "done"},
        ]
