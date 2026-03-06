from __future__ import annotations

import io
import os
from dataclasses import dataclass

from PIL import Image


@dataclass
class SceneResult:
    scene: str
    confidence: float
    mode: str


class SceneDetector:
    def detect(self, image_bytes: bytes) -> SceneResult:
        raise NotImplementedError


class HeuristicSceneDetector(SceneDetector):
    def detect(self, image_bytes: bytes) -> SceneResult:
        try:
            image = Image.open(io.BytesIO(image_bytes)).convert("RGB")
        except Exception:
            return SceneResult(scene="general", confidence=0.35, mode="auto")

        w, h = image.size
        w = max(1, w)
        h = max(1, h)
        step = max(1, min(w, h) // 72)

        total = 0
        luma_sum = 0.0
        sat_sum = 0.0
        warm = 0
        green_blue = 0
        center_skin = 0
        center_total = 0

        x1, x2 = int(w * 0.25), int(w * 0.75)
        y1, y2 = int(h * 0.2), int(h * 0.8)

        px = image.load()
        for y in range(0, h, step):
            for x in range(0, w, step):
                r, g, b = px[x, y]
                mx = max(r, g, b)
                mn = min(r, g, b)
                sat = 0.0 if mx <= 1 else float(mx - mn) / float(mx)
                luma = 0.299 * r + 0.587 * g + 0.114 * b

                total += 1
                luma_sum += luma
                sat_sum += sat

                if r > g + 12 and r > b + 12:
                    warm += 1
                if g > r and b > r:
                    green_blue += 1

                if x1 <= x <= x2 and y1 <= y <= y2:
                    center_total += 1
                    if self._is_skin_like(r, g, b):
                        center_skin += 1

        if total == 0:
            return SceneResult(scene="general", confidence=0.35, mode="auto")

        mean_luma = luma_sum / float(total)
        mean_sat = sat_sum / float(total)
        warm_ratio = warm / float(total)
        gb_ratio = green_blue / float(total)
        center_skin_ratio = 0.0 if center_total == 0 else center_skin / float(center_total)

        if mean_luma < 56.0:
            return SceneResult(scene="night", confidence=self._conf((56.0 - mean_luma) / 48.0), mode="general")

        if center_skin_ratio > 0.10 and 60.0 <= mean_luma <= 205.0:
            score = (center_skin_ratio - 0.10) * 2.0 + (0.23 - abs(mean_sat - 0.23))
            return SceneResult(scene="portrait", confidence=self._conf(score / 0.6), mode="portrait")

        if warm_ratio > 0.30 and mean_sat > 0.25:
            score = (warm_ratio - 0.30) + (mean_sat - 0.25)
            return SceneResult(scene="food", confidence=self._conf(score / 0.5), mode="general")

        if gb_ratio > 0.36 and mean_luma > 80.0:
            score = (gb_ratio - 0.36) + ((mean_luma - 80.0) / 120.0)
            return SceneResult(scene="landscape", confidence=self._conf(score / 0.6), mode="general")

        return SceneResult(scene="general", confidence=0.52, mode="general")

    def _is_skin_like(self, r: int, g: int, b: int) -> bool:
        mx = max(r, g, b)
        mn = min(r, g, b)
        return r > 95 and g > 40 and b > 20 and (mx - mn) > 15 and abs(r - g) > 15 and r > g and r > b

    def _conf(self, normalized: float) -> float:
        v = max(0.0, min(1.0, normalized))
        return max(0.50, min(0.95, 0.5 + v * 0.45))


class YoloHybridSceneDetector(SceneDetector):
    """
    Hybrid detector: YOLO11 object semantics + heuristic priors.
    If YOLO is unavailable, it falls back to heuristic only.
    """

    def __init__(self) -> None:
        self._fallback = HeuristicSceneDetector()
        self._model = None
        self._ready = False

        try:
            from ultralytics import YOLO  # type: ignore

            model_name = os.getenv("SCENE_YOLO_MODEL", "yolo11n.pt")
            self._model = YOLO(model_name)
            self._ready = True
        except Exception:
            self._ready = False

    def detect(self, image_bytes: bytes) -> SceneResult:
        heuristic = self._fallback.detect(image_bytes)

        if not self._ready or self._model is None:
            return heuristic

        try:
            image = Image.open(io.BytesIO(image_bytes)).convert("RGB")
            results = self._model.predict(image, verbose=False, conf=0.2, imgsz=640)
            if not results:
                return heuristic

            r0 = results[0]
            boxes = getattr(r0, "boxes", None)
            if boxes is None or len(boxes) == 0:
                return heuristic

            names = getattr(self._model, "names", {})
            labels: list[str] = []
            for cls_id in boxes.cls.tolist():
                name = names.get(int(cls_id), "") if isinstance(names, dict) else ""
                if name:
                    labels.append(str(name))

            if not labels:
                return heuristic

            person_count = sum(1 for x in labels if x == "person")
            food_labels = {
                "bowl",
                "cup",
                "fork",
                "knife",
                "spoon",
                "cake",
                "donut",
                "pizza",
                "sandwich",
                "hot dog",
                "apple",
                "banana",
                "orange",
                "broccoli",
                "carrot",
                "bottle",
                "wine glass",
            }
            food_count = sum(1 for x in labels if x in food_labels)
            total = max(1, len(labels))

            if heuristic.scene == "night":
                return heuristic

            if person_count / total >= 0.34 or person_count >= 2:
                conf = max(0.75, heuristic.confidence)
                return SceneResult(scene="portrait", confidence=min(conf + 0.1, 0.95), mode="portrait")

            if food_count / total >= 0.22 or food_count >= 2:
                conf = max(0.72, heuristic.confidence)
                return SceneResult(scene="food", confidence=min(conf + 0.08, 0.92), mode="general")

            if heuristic.scene == "landscape" and person_count == 0 and food_count == 0:
                return SceneResult(scene="landscape", confidence=max(heuristic.confidence, 0.7), mode="general")

            return heuristic
        except Exception:
            return heuristic


_SCENE_DETECTOR: SceneDetector | None = None


def get_scene_detector() -> SceneDetector:
    global _SCENE_DETECTOR
    if _SCENE_DETECTOR is not None:
        return _SCENE_DETECTOR

    detector = os.getenv("SCENE_DETECTOR", "yolo_hybrid").strip().lower()
    if detector in {"yolo", "yolo_hybrid", "hybrid", "yolo11"}:
        _SCENE_DETECTOR = YoloHybridSceneDetector()
    else:
        _SCENE_DETECTOR = HeuristicSceneDetector()
    return _SCENE_DETECTOR
