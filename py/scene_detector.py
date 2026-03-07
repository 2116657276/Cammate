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
    bbox_norm: list[float] | None = None
    center_norm: list[float] | None = None


class SceneDetector:
    def detect(self, image_bytes: bytes) -> SceneResult:
        raise NotImplementedError


class YoloSceneDetector(SceneDetector):
    """
    Pure YOLO scene detector.
    No heuristic pixel rules and no local-rule fallback.
    """

    def __init__(self) -> None:
        self._models: list[Any] = []
        self._ready = False
        self._predict_conf = self._env_float("SCENE_YOLO_PREDICT_CONF", 0.20, 0.01, 0.90)
        self._imgsz = self._env_int("SCENE_YOLO_IMGSZ", 640, 320, 1280)
        self._direct_scene_min = self._env_float("SCENE_YOLO_DIRECT_MIN", 0.40, 0.20, 0.95)

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
            if self._ready:
                self._warmup_models()
        except Exception:
            self._ready = False

    def detect(self, image_bytes: bytes) -> SceneResult:
        if not self._ready:
            return SceneResult(scene="general", confidence=0.20, mode="general")

        try:
            image = Image.open(io.BytesIO(image_bytes)).convert("RGB")
            labels_with_conf: list[tuple[str, float]] = []
            best_target: tuple[float, list[float], list[float]] | None = None
            for model in self._models:
                results = model.predict(image, verbose=False, conf=self._predict_conf, imgsz=self._imgsz)
                if not results:
                    continue
                r0 = results[0]
                names = self._normalize_names(getattr(r0, "names", None), getattr(model, "names", None))
                labels_with_conf.extend(self._labels_from_boxes(r0, names))
                labels_with_conf.extend(self._labels_from_probs(r0, names))
                target = self._best_target_from_boxes(r0, names, image.width, image.height)
                if target is not None and (best_target is None or target[0] > best_target[0]):
                    best_target = target
        except Exception:
            return SceneResult(scene="general", confidence=0.20, mode="general")

        if not labels_with_conf:
            return SceneResult(scene="general", confidence=0.25, mode="general", bbox_norm=None, center_norm=None)

        bbox_norm = best_target[1] if best_target is not None else None
        center_norm = best_target[2] if best_target is not None else None

        direct_scene = self._resolve_scene_from_scene_labels(labels_with_conf)
        if direct_scene is not None:
            direct_scene.bbox_norm = bbox_norm
            direct_scene.center_norm = center_norm
            return direct_scene

        semantic_scene = self._resolve_scene_from_object_semantics(labels_with_conf)
        if semantic_scene is not None:
            semantic_scene.bbox_norm = bbox_norm
            semantic_scene.center_norm = center_norm
            return semantic_scene

        max_conf = max((conf for _, conf in labels_with_conf), default=0.25)
        return SceneResult(
            scene="general",
            confidence=min(0.78, 0.30 + max_conf * 0.40),
            mode="general",
            bbox_norm=bbox_norm,
            center_norm=center_norm,
        )

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

    def _warmup_models(self) -> None:
        try:
            warmup_size = self._env_int("SCENE_YOLO_WARMUP_IMGSZ", 320, 160, 640)
            warmup_image = Image.new("RGB", (warmup_size, warmup_size), (16, 16, 16))
            for model in self._models:
                try:
                    model.predict(warmup_image, verbose=False, conf=self._predict_conf, imgsz=self._imgsz)
                except Exception:
                    continue
        except Exception:
            return

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

    def _best_target_from_boxes(
        self,
        result: Any,
        names: dict[int, str],
        image_width: int,
        image_height: int,
    ) -> tuple[float, list[float], list[float]] | None:
        boxes = getattr(result, "boxes", None)
        if boxes is None or len(boxes) == 0:
            return None

        if image_width <= 0 or image_height <= 0:
            return None

        conf_list = boxes.conf.tolist() if hasattr(boxes.conf, "tolist") else list(boxes.conf)
        cls_list = boxes.cls.tolist() if hasattr(boxes.cls, "tolist") else list(boxes.cls)
        xyxy_list = boxes.xyxy.tolist() if hasattr(boxes.xyxy, "tolist") else list(boxes.xyxy)
        if not xyxy_list:
            return None

        allowed_focus_labels = {
            "person",
            "dog",
            "cat",
            "bird",
            "horse",
            "sheep",
            "cow",
            "bear",
            "zebra",
            "giraffe",
            "bottle",
            "cup",
            "bowl",
            "cake",
            "pizza",
            "sandwich",
            "apple",
            "banana",
            "orange",
        }
        best: tuple[float, list[float], list[float]] | None = None
        for idx, xyxy in enumerate(xyxy_list):
            if len(xyxy) < 4:
                continue
            label = names.get(int(cls_list[idx]), "") if idx < len(cls_list) else ""
            conf = float(conf_list[idx]) if idx < len(conf_list) else 0.0
            # Prefer likely subject classes, but allow fallback to highest-confidence box.
            class_boost = 0.08 if label in allowed_focus_labels else 0.0
            score = conf + class_boost

            x1 = max(0.0, min(float(xyxy[0]), float(image_width)))
            y1 = max(0.0, min(float(xyxy[1]), float(image_height)))
            x2 = max(0.0, min(float(xyxy[2]), float(image_width)))
            y2 = max(0.0, min(float(xyxy[3]), float(image_height)))
            if x2 <= x1 or y2 <= y1:
                continue

            bbox_norm = [
                round(x1 / image_width, 4),
                round(y1 / image_height, 4),
                round(x2 / image_width, 4),
                round(y2 / image_height, 4),
            ]
            cx = round(((x1 + x2) * 0.5) / image_width, 4)
            cy = round(((y1 + y2) * 0.5) / image_height, 4)
            center_norm = [cx, cy]
            if best is None or score > best[0]:
                best = (score, bbox_norm, center_norm)
        return best

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

    def _resolve_scene_from_scene_labels(self, labels_with_conf: list[tuple[str, float]]) -> SceneResult | None:
        scene_score: dict[str, float] = defaultdict(float)
        scene_max: dict[str, float] = defaultdict(float)

        for label, conf in labels_with_conf:
            mapped = self._label_to_scene(label)
            if mapped is None:
                continue
            base = conf if conf > 0 else 0.15
            scene_score[mapped] += base
            scene_max[mapped] = max(scene_max[mapped], base)

        if not scene_score:
            return None

        best_scene = max(scene_score, key=lambda x: scene_score[x])
        best_single = scene_max[best_scene]
        best_sum = scene_score[best_scene]
        if best_single < self._direct_scene_min and best_sum < self._direct_scene_min * 1.7:
            return None

        conf = min(0.95, 0.52 + best_single * 0.30 + min(best_sum, 1.0) * 0.12)
        mode = "portrait" if best_scene == "portrait" else "general"
        return SceneResult(scene=best_scene, confidence=conf, mode=mode)

    def _resolve_scene_from_object_semantics(self, labels_with_conf: list[tuple[str, float]]) -> SceneResult | None:
        labels = [x[0].strip().lower() for x in labels_with_conf if x[0].strip()]
        if not labels:
            return None

        person_labels = {"person", "man", "woman", "face", "human"}
        pet_labels = {
            "dog",
            "cat",
            "bird",
            "horse",
            "sheep",
            "cow",
            "bear",
            "zebra",
            "giraffe",
            "hamster",
            "rabbit",
            "parrot",
            "puppy",
            "kitten",
            "pet",
        }
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
        landscape_labels = {
            "mountain",
            "beach",
            "sea",
            "ocean",
            "river",
            "forest",
            "tree",
            "sky",
            "sunset",
            "sunrise",
            "field",
            "park",
            "outdoor",
            "nature",
            "scenery",
            "landscape",
        }
        night_labels = {"night", "moon", "street light", "low light", "dark"}

        person_count = sum(1 for x in labels if x in person_labels)
        pet_count = sum(1 for x in labels if x in pet_labels)
        food_count = sum(1 for x in labels if x in food_labels)
        landscape_count = sum(1 for x in labels if x in landscape_labels)
        night_count = sum(1 for x in labels if x in night_labels)
        total = max(1, len(labels))

        if night_count >= 1 and night_count >= max(person_count, food_count):
            return SceneResult(scene="night", confidence=0.80, mode="general")

        if person_count >= 1 and person_count >= pet_count:
            conf = min(0.95, 0.74 + min(0.18, person_count / total))
            return SceneResult(scene="portrait", confidence=conf, mode="portrait")

        if pet_count > 0 and person_count == 0:
            conf = min(0.90, 0.68 + min(0.16, pet_count / total))
            return SceneResult(scene="general", confidence=conf, mode="general")

        if food_count >= 1:
            conf = min(0.92, 0.70 + min(0.18, food_count / total))
            return SceneResult(scene="food", confidence=conf, mode="general")

        if landscape_count >= 1:
            conf = min(0.90, 0.66 + min(0.16, landscape_count / total))
            return SceneResult(scene="landscape", confidence=conf, mode="general")

        return None

    def _label_to_scene(self, label: str) -> str | None:
        text = label.strip().lower()
        compact = text.replace("_", " ").replace("-", " ")

        portrait_tokens = ("portrait", "selfie", "face", "head", "人像", "人物")
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
    if _SCENE_DETECTOR is None:
        _SCENE_DETECTOR = YoloSceneDetector()
    return _SCENE_DETECTOR
