from __future__ import annotations

import io
import os
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path
from typing import Any

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
    Strategy:
    1) Prefer project custom YOLO model when provided.
    2) Fall back to generic YOLO11 model.
    3) If YOLO result is weak or unavailable, fall back to heuristic detector.
    """

    def __init__(self) -> None:
        self._fallback = HeuristicSceneDetector()
        self._models: list[Any] = []
        self._ready = False
        self._predict_conf = self._env_float("SCENE_YOLO_PREDICT_CONF", 0.20, 0.01, 0.90)
        self._imgsz = self._env_int("SCENE_YOLO_IMGSZ", 640, 320, 1280)
        self._direct_scene_min = self._env_float("SCENE_YOLO_DIRECT_MIN", 0.42, 0.20, 0.95)

        try:
            from ultralytics import YOLO  # type: ignore

            model_candidates: list[str] = []
            project_default_model = Path(__file__).resolve().parent / "models" / "scene_yolo11n.pt"
            default_model_ref = str(project_default_model) if project_default_model.exists() else "yolo11n.pt"

            custom_model = os.getenv("SCENE_YOLO_CUSTOM_MODEL", "").strip()
            if not custom_model and project_default_model.exists():
                custom_model = str(project_default_model)
            generic_model = os.getenv("SCENE_YOLO_MODEL", default_model_ref).strip() or default_model_ref

            for candidate in (custom_model, generic_model):
                if candidate and not any(self._same_model_ref(candidate, x) for x in model_candidates):
                    model_candidates.append(candidate)

            for model_ref in model_candidates:
                model = self._try_load_model(YOLO, model_ref)
                if model is not None:
                    self._models.append(model)

            self._ready = len(self._models) > 0
        except Exception:
            self._ready = False

    def detect(self, image_bytes: bytes) -> SceneResult:
        heuristic = self._fallback.detect(image_bytes)

        if not self._ready:
            return heuristic

        try:
            image = Image.open(io.BytesIO(image_bytes)).convert("RGB")
            labels_with_conf: list[tuple[str, float]] = []
            for model in self._models:
                labels_with_conf.extend(self._predict_labels(model, image))

            if not labels_with_conf:
                return heuristic

            direct_scene = self._resolve_scene_from_scene_labels(labels_with_conf, heuristic)
            if direct_scene is not None:
                return direct_scene

            semantic_scene = self._resolve_scene_from_object_semantics(labels_with_conf, heuristic)
            if semantic_scene is not None:
                return semantic_scene

            return heuristic
        except Exception:
            return heuristic

    def _try_load_model(self, yolo_cls: Any, model_ref: str):
        try:
            return yolo_cls(model_ref)
        except Exception:
            p = Path(model_ref)
            if not p.is_absolute():
                project_local = Path(__file__).resolve().parent / model_ref
                if project_local.exists():
                    try:
                        return yolo_cls(str(project_local))
                    except Exception:
                        return None
            return None

    def _same_model_ref(self, a: str, b: str) -> bool:
        if a == b:
            return True
        try:
            return Path(a).expanduser().resolve() == Path(b).expanduser().resolve()
        except Exception:
            return False

    def _predict_labels(self, model: Any, image: Image.Image) -> list[tuple[str, float]]:
        labels: list[tuple[str, float]] = []
        results = model.predict(image, verbose=False, conf=self._predict_conf, imgsz=self._imgsz)
        if not results:
            return labels

        r0 = results[0]
        names = self._normalize_names(getattr(r0, "names", None), getattr(model, "names", None))
        labels.extend(self._labels_from_boxes(r0, names))
        labels.extend(self._labels_from_probs(r0, names))
        return labels

    def _labels_from_boxes(self, result: Any, names: dict[int, str]) -> list[tuple[str, float]]:
        boxes = getattr(result, "boxes", None)
        if boxes is None or len(boxes) == 0:
            return []
        cls_list = boxes.cls.tolist() if hasattr(boxes.cls, "tolist") else list(boxes.cls)
        conf_list = boxes.conf.tolist() if hasattr(boxes.conf, "tolist") else list(boxes.conf)
        out: list[tuple[str, float]] = []
        for i, cls_id in enumerate(cls_list):
            label = names.get(int(cls_id), "")
            if not label:
                continue
            conf = float(conf_list[i]) if i < len(conf_list) else 0.0
            out.append((label, max(0.0, min(1.0, conf))))
        return out

    def _labels_from_probs(self, result: Any, names: dict[int, str]) -> list[tuple[str, float]]:
        probs = getattr(result, "probs", None)
        if probs is None:
            return []

        top_ids = list(getattr(probs, "top5", []) or [])
        conf_raw = getattr(probs, "top5conf", [])
        if hasattr(conf_raw, "tolist"):
            confs = conf_raw.tolist()
        else:
            confs = list(conf_raw) if conf_raw else []

        if not top_ids and hasattr(probs, "top1"):
            top_ids = [int(getattr(probs, "top1"))]
            top1_conf = getattr(probs, "top1conf", 0.0)
            conf_value = float(top1_conf.item()) if hasattr(top1_conf, "item") else float(top1_conf or 0.0)
            confs = [conf_value]

        out: list[tuple[str, float]] = []
        for i, cls_id in enumerate(top_ids[:5]):
            label = names.get(int(cls_id), "")
            if not label:
                continue
            conf = float(confs[i]) if i < len(confs) else 0.0
            out.append((label, max(0.0, min(1.0, conf))))
        return out

    def _resolve_scene_from_scene_labels(
        self,
        labels_with_conf: list[tuple[str, float]],
        heuristic: SceneResult,
    ) -> SceneResult | None:
        scene_score: dict[str, float] = defaultdict(float)
        scene_max: dict[str, float] = defaultdict(float)

        for label, conf in labels_with_conf:
            mapped = self._label_to_scene(label)
            if mapped is None:
                continue
            base = conf if conf > 0 else 0.20
            scene_score[mapped] += base
            scene_max[mapped] = max(scene_max[mapped], base)

        if not scene_score:
            return None

        best_scene = max(scene_score, key=lambda x: scene_score[x])
        best_single = scene_max[best_scene]
        best_sum = scene_score[best_scene]
        if best_single < self._direct_scene_min and best_sum < self._direct_scene_min * 1.7:
            return None

        conf = max(heuristic.confidence, min(0.95, 0.55 + best_single * 0.30 + min(best_sum, 1.0) * 0.10))
        mode = "portrait" if best_scene == "portrait" else "general"
        return SceneResult(scene=best_scene, confidence=conf, mode=mode)

    def _resolve_scene_from_object_semantics(
        self,
        labels_with_conf: list[tuple[str, float]],
        heuristic: SceneResult,
    ) -> SceneResult | None:
        labels = [x[0].strip().lower() for x in labels_with_conf if x[0].strip()]
        if not labels:
            return None

        person_count = sum(1 for x in labels if x in {"person", "man", "woman", "face", "human"})
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
            return SceneResult(scene="portrait", confidence=min(conf + 0.10, 0.95), mode="portrait")

        if food_count / total >= 0.22 or food_count >= 2:
            conf = max(0.72, heuristic.confidence)
            return SceneResult(scene="food", confidence=min(conf + 0.08, 0.92), mode="general")

        if heuristic.scene == "landscape" and person_count == 0 and food_count == 0:
            return SceneResult(scene="landscape", confidence=max(heuristic.confidence, 0.70), mode="general")

        return None

    def _label_to_scene(self, label: str) -> str | None:
        text = label.strip().lower()
        compact = text.replace("_", " ").replace("-", " ")

        portrait_tokens = ("portrait", "person", "people", "human", "selfie", "face", "人像", "人物")
        food_tokens = ("food", "meal", "dish", "dessert", "fruit", "drink", "美食", "食物", "餐")
        landscape_tokens = (
            "landscape",
            "mountain",
            "beach",
            "forest",
            "nature",
            "scenery",
            "outdoor",
            "风景",
            "自然",
        )
        night_tokens = ("night", "dark", "low light", "moon", "夜景", "暗光")
        general_tokens = ("general", "others", "other", "common", "通用", "其他")

        if any(tok in compact for tok in portrait_tokens):
            return "portrait"
        if any(tok in compact for tok in food_tokens):
            return "food"
        if any(tok in compact for tok in landscape_tokens):
            return "landscape"
        if any(tok in compact for tok in night_tokens):
            return "night"
        if any(tok in compact for tok in general_tokens):
            return "general"
        return None

    def _normalize_names(self, primary: Any, secondary: Any) -> dict[int, str]:
        raw = primary if isinstance(primary, dict) and primary else secondary
        if isinstance(raw, dict):
            out: dict[int, str] = {}
            for k, v in raw.items():
                try:
                    out[int(k)] = str(v)
                except Exception:
                    continue
            return out
        return {}

    def _env_float(self, key: str, default: float, min_value: float, max_value: float) -> float:
        raw = os.getenv(key)
        if raw is None:
            return default
        try:
            value = float(raw)
        except Exception:
            return default
        return max(min_value, min(max_value, value))

    def _env_int(self, key: str, default: int, min_value: int, max_value: int) -> int:
        raw = os.getenv(key)
        if raw is None:
            return default
        try:
            value = int(raw)
        except Exception:
            return default
        return max(min_value, min(max_value, value))


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
