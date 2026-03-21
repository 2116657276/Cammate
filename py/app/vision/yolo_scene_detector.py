from __future__ import annotations

import io
import os
from pathlib import Path
from typing import Any

from PIL import Image

from app.vision.models import BoxCandidate
from app.vision.models import SceneResult


class YoloSceneDetector:
    def __init__(self) -> None:
        self._models: list[Any] = []
        self._ready = False
        self._predict_conf = self._env_float("SCENE_YOLO_PREDICT_CONF", 0.20, 0.01, 0.90)
        self._imgsz = self._env_int("SCENE_YOLO_IMGSZ", 640, 320, 1280)
        self._scene_margin = self._env_float("SCENE_YOLO_SCENE_MARGIN", 0.14, 0.04, 0.45)
        self._portrait_min_box_conf = self._env_float("SCENE_YOLO_PORTRAIT_MIN_CONF", 0.46, 0.20, 0.95)
        self._food_min_area = self._env_float("SCENE_FOOD_MIN_AREA", 0.028, 0.005, 0.30)
        self._food_scene_min_strength = self._env_float("SCENE_FOOD_MIN_STRENGTH", 0.52, 0.30, 0.95)

        project_root = Path(__file__).resolve().parents[2]
        default_model = project_root / "models" / "scene_yolo11n.pt"
        default_model_ref = "./models/scene_yolo11n.pt"
        custom_model = os.getenv("SCENE_YOLO_CUSTOM_MODEL", "").strip()
        generic_model = os.getenv("SCENE_YOLO_MODEL", default_model_ref).strip() or default_model_ref

        candidates: list[Path] = []
        if custom_model:
            resolved = self._resolve_model_path(custom_model, project_root)
            if resolved is not None:
                candidates.append(resolved)
        elif default_model.exists():
            candidates.append(default_model)

        resolved_generic = self._resolve_model_path(generic_model, project_root)
        if resolved_generic is not None and not any(resolved_generic == item for item in candidates):
            candidates.append(resolved_generic)

        if not candidates:
            return

        try:
            from ultralytics import YOLO  # type: ignore

            for model_path in candidates:
                try:
                    self._models.append(YOLO(str(model_path)))
                except Exception:
                    continue
            self._ready = len(self._models) > 0
            if self._ready:
                self._warmup_models()
        except Exception:
            self._ready = False

    def detect_scene_and_boxes(
        self,
        image_bytes: bytes,
        capture_mode: str = "auto",
        scene_hint: str | None = None,
    ) -> tuple[SceneResult, list[BoxCandidate]]:
        if not self._ready:
            return SceneResult(scene="general", confidence=0.20, mode="general"), []

        normalized_mode = self._normalize_capture_mode(capture_mode)
        normalized_hint = self._normalize_scene(scene_hint)
        try:
            image = Image.open(io.BytesIO(image_bytes)).convert("RGB")
        except Exception:
            return SceneResult(scene="general", confidence=0.20, mode="general"), []

        labels_with_conf: list[tuple[str, float]] = []
        boxes: list[BoxCandidate] = []
        for model in self._models:
            try:
                results = model.predict(image, verbose=False, conf=self._predict_conf, imgsz=self._imgsz)
            except Exception:
                continue
            if not results:
                continue
            r0 = results[0]
            names = self._normalize_names(getattr(r0, "names", None), getattr(model, "names", None))
            labels_with_conf.extend(self._labels_from_boxes(r0, names))
            labels_with_conf.extend(self._labels_from_probs(r0, names))
            boxes.extend(self._extract_box_candidates(r0, names, image.width, image.height))

        if not labels_with_conf and not boxes:
            return SceneResult(scene="general", confidence=0.25, mode="general"), []

        scene_result = self._resolve_scene(labels_with_conf, boxes)
        scene_result = self._apply_capture_mode_bias(scene_result, normalized_mode, boxes)
        if normalized_mode == "auto":
            scene_result = self._apply_scene_hint_bias(scene_result, normalized_hint)
        return scene_result, boxes

    def detect(
        self,
        image_bytes: bytes,
        capture_mode: str = "auto",
        scene_hint: str | None = None,
    ) -> SceneResult:
        scene_result, boxes = self.detect_scene_and_boxes(
            image_bytes=image_bytes,
            capture_mode=capture_mode,
            scene_hint=scene_hint,
        )
        target = self.pick_scene_target(scene_result.scene, boxes)
        if target is not None:
            scene_result.bbox_norm = target.bbox_norm
            scene_result.center_norm = target.center_norm
        return scene_result

    def has_person(self, boxes: list[BoxCandidate]) -> bool:
        return any(box.label in PERSON_LABELS for box in boxes)

    def pick_person_target(self, boxes: list[BoxCandidate], prefer_center: bool = True) -> BoxCandidate | None:
        person_boxes = [box for box in boxes if box.label in PERSON_LABELS]
        if not person_boxes:
            return None
        best: tuple[float, BoxCandidate] | None = None
        for box in person_boxes:
            center_bonus = 0.0
            if prefer_center:
                cx, cy = box.center_norm
                center_bonus = (1.0 - min(1.0, abs(cx - 0.5) + abs(cy - 0.5))) * 0.18
            area_target = 0.20
            size_score = 1.0 - min(1.0, abs(box.area_norm - area_target) / max(area_target, 0.05))
            score = box.confidence * 0.70 + size_score * 0.22 + center_bonus
            if best is None or score > best[0]:
                best = (score, box)
        return best[1] if best is not None else None

    def pick_scene_target(self, scene: str, boxes: list[BoxCandidate]) -> BoxCandidate | None:
        if not boxes:
            return None
        weights = self._target_label_weights(scene)
        best: tuple[float, BoxCandidate] | None = None
        for box in boxes:
            label_weight = weights.get(box.label, 0.0)
            if scene == "portrait" and label_weight <= 0.0:
                continue
            cx, cy = box.center_norm
            center_score = 1.0 - min(1.0, abs(cx - 0.5) + abs(cy - 0.5))
            area_target = 0.16 if scene == "portrait" else 0.22
            size_score = 1.0 - min(1.0, abs(box.area_norm - area_target) / max(area_target, 0.05))
            oversize_penalty = 0.20 if box.area_norm > 0.72 else 0.0
            score = (
                box.confidence * 0.62
                + label_weight * 0.36
                + size_score * 0.18
                + center_score * 0.10
                - oversize_penalty
            )
            if best is None or score > best[0]:
                best = (score, box)
        return best[1] if best is not None else None

    def refine_upper_body_box(self, box: BoxCandidate) -> BoxCandidate:
        x1, y1, x2, y2 = box.bbox_norm
        width = x2 - x1
        height = y2 - y1
        if width <= 0.0 or height <= 0.0:
            return box
        nx1 = max(0.0, x1 + width * 0.08)
        nx2 = min(1.0, x2 - width * 0.08)
        ny1 = y1
        ny2 = min(1.0, y1 + height * 0.68)
        return self._box_with_bbox(box, [nx1, ny1, nx2, ny2])

    def refine_head_shoulder_box(self, box: BoxCandidate) -> BoxCandidate:
        x1, y1, x2, y2 = box.bbox_norm
        width = x2 - x1
        height = y2 - y1
        if width <= 0.0 or height <= 0.0:
            return box
        is_tall = height >= width * 1.2
        if is_tall:
            focus_w = width * 0.52
            focus_h = height * 0.38
            center_y = y1 + height * 0.24
        else:
            focus_w = width * 0.62
            focus_h = height * 0.46
            center_y = y1 + height * 0.32
        center_x = (x1 + x2) * 0.5
        nx1 = max(x1, center_x - focus_w * 0.5)
        nx2 = min(x2, center_x + focus_w * 0.5)
        ny1 = max(y1, center_y - focus_h * 0.5)
        ny2 = min(y2, center_y + focus_h * 0.5)
        if nx2 <= nx1 or ny2 <= ny1:
            return self.refine_upper_body_box(box)
        return self._box_with_bbox(box, [nx1, ny1, nx2, ny2])

    def shrink_oversized_box(self, box: BoxCandidate) -> BoxCandidate:
        if box.area_norm <= 0.55:
            return box
        x1, y1, x2, y2 = box.bbox_norm
        cx, cy = box.center_norm
        width = (x2 - x1) * 0.82
        height = (y2 - y1) * 0.82
        nx1 = max(0.0, cx - width * 0.5)
        ny1 = max(0.0, cy - height * 0.5)
        nx2 = min(1.0, cx + width * 0.5)
        ny2 = min(1.0, cy + height * 0.5)
        if nx2 <= nx1 or ny2 <= ny1:
            return box
        return self._box_with_bbox(box, [nx1, ny1, nx2, ny2])

    def _resolve_scene(
        self,
        labels_with_conf: list[tuple[str, float]],
        boxes: list[BoxCandidate],
    ) -> SceneResult:
        if not labels_with_conf and not boxes:
            return SceneResult(scene="general", confidence=0.25, mode="general")

        person_boxes = [box for box in boxes if box.label in PERSON_LABELS]
        food_boxes = [box for box in boxes if self._is_food_candidate(box)]
        person_best = self._best_person_strength(person_boxes)
        closeup_person = self._best_closeup_person_strength(person_boxes)
        food_best = self._best_food_strength(food_boxes)
        pet_best = self._best_pet_strength(boxes)
        general_best = self._best_general_strength(boxes)

        person_label_conf = max((float(conf) for label, conf in labels_with_conf if label in PERSON_LABELS), default=0.0)
        food_label_conf = max((float(conf) for label, conf in labels_with_conf if label in FOOD_LABELS), default=0.0)
        drink_label_conf = max(
            (float(conf) for label, conf in labels_with_conf if label in DRINK_CONTAINER_LABELS),
            default=0.0,
        )
        label_food_conf = max(food_label_conf, drink_label_conf)

        portrait_score = (
            self._normalize_strength(person_best, low=0.32, high=1.16) * 0.42
            + self._normalize_strength(closeup_person, low=0.36, high=1.26) * 0.58
        )
        food_score = self._normalize_strength(food_best, low=0.24, high=0.96)
        general_score = (
            0.30
            + self._normalize_strength(general_best, low=0.22, high=0.98) * 0.30
            + self._normalize_strength(pet_best, low=0.26, high=1.02) * 0.24
        )

        if person_boxes:
            portrait_score += 0.08
        if food_boxes:
            food_score += 0.12
        if label_food_conf >= 0.52:
            food_score += 0.08

        # Mixed scenes are usually ambiguous (for example food + pet/person in one frame),
        # so general gets an ambiguity bonus to reduce scene flapping.
        if person_boxes and food_boxes:
            general_score += 0.06
            if abs(portrait_score - food_score) < 0.12:
                general_score += 0.06

        # Label-only evidence still contributes when boxes are weak.
        portrait_score = max(
            portrait_score,
            self._normalize_strength(person_label_conf, low=0.30, high=0.82) * 0.62,
        )
        food_score = max(
            food_score,
            self._normalize_strength(label_food_conf, low=0.34, high=0.86) * 0.62,
        )

        scores = {
            "portrait": max(0.0, min(1.0, portrait_score)),
            "food": max(0.0, min(1.0, food_score)),
            "general": max(0.0, min(1.0, general_score)),
        }
        ranking = sorted(scores.items(), key=lambda item: item[1], reverse=True)
        top_scene, top_score = ranking[0]
        second_score = ranking[1][1] if len(ranking) > 1 else 0.0
        margin = max(0.0, top_score - second_score)

        # If top2 are too close, fallback to general rather than forcing a wrong specific scene.
        scene_margin_gate = 0.07 if top_scene == "food" else 0.10
        if top_scene != "general" and margin < scene_margin_gate:
            top_scene = "general"
            top_score = max(scores["general"], 0.44 + (scene_margin_gate - margin) * 0.45)
            second_score = max(scores["portrait"], scores["food"])
            margin = max(0.0, top_score - second_score)

        confidence = min(0.95, max(0.50, 0.48 + top_score * 0.34 + margin * 0.28))
        mode = top_scene if top_scene in {"portrait", "food", "general"} else "general"
        return SceneResult(scene=top_scene, confidence=confidence, mode=mode)

    def _apply_capture_mode_bias(
        self,
        scene_result: SceneResult,
        capture_mode: str,
        boxes: list[BoxCandidate],
    ) -> SceneResult:
        if capture_mode == "portrait":
            person_best = self._best_person_strength(boxes)
            if person_best >= max(0.30, self._portrait_min_box_conf - 0.12):
                return SceneResult(
                    scene="portrait",
                    confidence=max(scene_result.confidence, min(0.92, 0.58 + person_best * 0.34)),
                    mode="portrait",
                )
            return SceneResult(scene="general", confidence=max(0.56, scene_result.confidence * 0.80), mode="general")
        if capture_mode == "food":
            food_best = self._best_food_strength(boxes)
            if food_best >= 0.34:
                return SceneResult(
                    scene="food",
                    confidence=max(scene_result.confidence, min(0.90, 0.56 + food_best * 0.30)),
                    mode="food",
                )
            return SceneResult(scene="general", confidence=max(0.56, scene_result.confidence * 0.80), mode="general")
        if capture_mode == "general" and scene_result.scene == "landscape":
            return SceneResult(scene="general", confidence=scene_result.confidence, mode="general")
        return scene_result

    def _apply_scene_hint_bias(self, scene_result: SceneResult, scene_hint: str | None) -> SceneResult:
        if not scene_hint:
            return scene_result
        if scene_hint == "landscape":
            scene_hint = "general"
        if scene_hint == "food" and scene_result.scene == "general" and scene_result.confidence < 0.72:
            return SceneResult(scene="food", confidence=max(0.60, scene_result.confidence), mode="food")
        if scene_hint == scene_result.scene:
            return scene_result
        if scene_result.confidence >= 0.56:
            return scene_result
        if scene_hint == "portrait":
            mode = "portrait"
        elif scene_hint == "food":
            mode = "food"
        else:
            mode = "general"
        return SceneResult(scene=scene_hint, confidence=0.56, mode=mode)

    def _best_person_strength(self, boxes: list[BoxCandidate]) -> float:
        best = 0.0
        for box in boxes:
            if box.label not in PERSON_LABELS:
                continue
            best = max(best, box.confidence + min(0.22, box.area_norm * 0.8))
        return best

    def _best_closeup_person_strength(self, person_boxes: list[BoxCandidate]) -> float:
        best = 0.0
        for box in person_boxes:
            x1, y1, x2, y2 = box.bbox_norm
            width = max(0.0, x2 - x1)
            height = max(0.0, y2 - y1)
            if width <= 0.0 or height <= 0.0:
                continue

            area = box.area_norm
            aspect = height / max(width, 1e-6)
            is_face_like = box.label == "face"
            is_far = area < 0.10
            is_full_body = aspect >= 1.95 and area < 0.38
            if is_far or is_full_body:
                continue

            size_bonus = min(0.25, area * 0.9)
            shape_bonus = 0.08 if aspect <= 1.55 else 0.03
            score = box.confidence + size_bonus + shape_bonus + (0.18 if is_face_like else 0.0)
            best = max(best, score)
        return best

    def _best_food_strength(self, boxes: list[BoxCandidate]) -> float:
        best = 0.0
        for box in boxes:
            if box.label not in FOOD_LABELS:
                continue
            best = max(best, box.confidence + min(0.20, box.area_norm * 0.7))
        return best

    def _best_pet_strength(self, boxes: list[BoxCandidate]) -> float:
        best = 0.0
        for box in boxes:
            if box.label not in PET_LABELS:
                continue
            center_bonus = self._center_bonus(box) * 0.16
            best = max(best, box.confidence + min(0.18, box.area_norm * 0.62) + center_bonus)
        return best

    def _best_general_strength(self, boxes: list[BoxCandidate]) -> float:
        best = 0.0
        for box in boxes:
            if box.label in PERSON_LABELS or box.label in FOOD_LABELS:
                continue
            center_bonus = self._center_bonus(box) * 0.12
            best = max(best, box.confidence + min(0.16, box.area_norm * 0.56) + center_bonus)
        return best

    def _center_bonus(self, box: BoxCandidate) -> float:
        cx, cy = box.center_norm
        return 1.0 - min(1.0, abs(cx - 0.5) + abs(cy - 0.5))

    def _normalize_strength(self, value: float, low: float, high: float) -> float:
        if high <= low:
            return max(0.0, min(1.0, value))
        return max(0.0, min(1.0, (value - low) / (high - low)))

    def _is_food_candidate(self, box: BoxCandidate) -> bool:
        if box.label not in FOOD_LABELS:
            return False
        if box.area_norm >= self._food_min_area:
            return True
        if box.label in DRINK_CONTAINER_LABELS:
            return box.confidence >= 0.55
        return box.confidence >= 0.66

    def _extract_box_candidates(
        self,
        result: Any,
        names: dict[int, str],
        image_width: int,
        image_height: int,
    ) -> list[BoxCandidate]:
        boxes = getattr(result, "boxes", None)
        if boxes is None or len(boxes) == 0:
            return []
        if image_width <= 0 or image_height <= 0:
            return []

        conf_list = boxes.conf.tolist() if hasattr(boxes.conf, "tolist") else list(boxes.conf)
        cls_list = boxes.cls.tolist() if hasattr(boxes.cls, "tolist") else list(boxes.cls)
        xyxy_list = boxes.xyxy.tolist() if hasattr(boxes.xyxy, "tolist") else list(boxes.xyxy)
        out: list[BoxCandidate] = []
        for idx, xyxy in enumerate(xyxy_list):
            if len(xyxy) < 4:
                continue
            label = names.get(int(cls_list[idx]), "").strip().lower() if idx < len(cls_list) else ""
            conf = float(conf_list[idx]) if idx < len(conf_list) else 0.0
            x1 = max(0.0, min(float(xyxy[0]), float(image_width)))
            y1 = max(0.0, min(float(xyxy[1]), float(image_height)))
            x2 = max(0.0, min(float(xyxy[2]), float(image_width)))
            y2 = max(0.0, min(float(xyxy[3]), float(image_height)))
            if x2 <= x1 or y2 <= y1:
                continue
            w = x2 - x1
            h = y2 - y1
            area = (w * h) / float(image_width * image_height)
            bbox = [
                round(x1 / image_width, 4),
                round(y1 / image_height, 4),
                round(x2 / image_width, 4),
                round(y2 / image_height, 4),
            ]
            center = [round((bbox[0] + bbox[2]) * 0.5, 4), round((bbox[1] + bbox[3]) * 0.5, 4)]
            out.append(
                BoxCandidate(
                    label=label,
                    confidence=max(0.0, min(1.0, conf)),
                    bbox_norm=bbox,
                    center_norm=center,
                    area_norm=max(0.0, min(1.0, area)),
                )
            )
        return out

    def _labels_from_boxes(self, result: Any, names: dict[int, str]) -> list[tuple[str, float]]:
        boxes = getattr(result, "boxes", None)
        if boxes is None or len(boxes) == 0:
            return []
        cls_list = boxes.cls.tolist() if hasattr(boxes.cls, "tolist") else list(boxes.cls)
        conf_list = boxes.conf.tolist() if hasattr(boxes.conf, "tolist") else list(boxes.conf)
        out: list[tuple[str, float]] = []
        for idx, cls_id in enumerate(cls_list):
            label = names.get(int(cls_id), "").strip().lower()
            if not label:
                continue
            conf = float(conf_list[idx]) if idx < len(conf_list) else 0.0
            out.append((label, max(0.0, min(1.0, conf))))
        return out

    def _labels_from_probs(self, result: Any, names: dict[int, str]) -> list[tuple[str, float]]:
        probs = getattr(result, "probs", None)
        if probs is None:
            return []
        top_ids = list(getattr(probs, "top5", []) or [])
        conf_raw = getattr(probs, "top5conf", [])
        confs = conf_raw.tolist() if hasattr(conf_raw, "tolist") else list(conf_raw) if conf_raw else []
        if not top_ids and hasattr(probs, "top1"):
            top_ids = [int(getattr(probs, "top1"))]
            top1_conf = getattr(probs, "top1conf", 0.0)
            conf_value = float(top1_conf.item()) if hasattr(top1_conf, "item") else float(top1_conf or 0.0)
            confs = [conf_value]
        out: list[tuple[str, float]] = []
        for idx, cls_id in enumerate(top_ids[:5]):
            label = names.get(int(cls_id), "").strip().lower()
            if not label:
                continue
            conf = float(confs[idx]) if idx < len(confs) else 0.0
            out.append((label, max(0.0, min(1.0, conf))))
        return out

    def _target_label_weights(self, scene: str) -> dict[str, float]:
        if scene == "portrait":
            return {"person": 1.0, "man": 0.95, "woman": 0.95, "human": 0.95, "face": 1.12, "head": 1.04}
        if scene == "food":
            return {
                "bowl": 1.0,
                "cup": 0.94,
                "cake": 1.0,
                "pizza": 1.0,
                "sandwich": 0.96,
                "apple": 0.90,
                "banana": 0.90,
                "orange": 0.90,
                "bottle": 0.76,
                "wine glass": 0.76,
            }
        if scene == "landscape":
            return {"mountain": 1.0, "tree": 0.82, "river": 0.84, "boat": 0.78, "bench": 0.58, "person": 0.56}
        if scene == "night":
            return {"person": 0.82, "car": 0.70, "bus": 0.70, "motorcycle": 0.66, "bicycle": 0.62, "traffic light": 0.62}
        return {
            "person": 0.84,
            "dog": 0.78,
            "cat": 0.78,
            "bird": 0.74,
            "bottle": 0.66,
            "cup": 0.66,
            "bowl": 0.66,
            "cake": 0.70,
            "pizza": 0.70,
            "sandwich": 0.68,
            "apple": 0.64,
            "banana": 0.64,
            "orange": 0.64,
        }

    def _box_with_bbox(self, box: BoxCandidate, bbox_norm: list[float]) -> BoxCandidate:
        x1 = max(0.0, min(1.0, bbox_norm[0]))
        y1 = max(0.0, min(1.0, bbox_norm[1]))
        x2 = max(0.0, min(1.0, bbox_norm[2]))
        y2 = max(0.0, min(1.0, bbox_norm[3]))
        if x2 <= x1 or y2 <= y1:
            return box
        area = (x2 - x1) * (y2 - y1)
        center = [round((x1 + x2) * 0.5, 4), round((y1 + y2) * 0.5, 4)]
        return BoxCandidate(
            label=box.label,
            confidence=box.confidence,
            bbox_norm=[round(x1, 4), round(y1, 4), round(x2, 4), round(y2, 4)],
            center_norm=center,
            area_norm=round(max(0.0, min(1.0, area)), 6),
        )

    def _normalize_names(self, primary: Any, secondary: Any) -> dict[int, str]:
        raw = primary if isinstance(primary, dict) and primary else secondary
        if not isinstance(raw, dict):
            return {}
        out: dict[int, str] = {}
        for k, v in raw.items():
            try:
                out[int(k)] = str(v)
            except Exception:
                continue
        return out

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

    def _normalize_capture_mode(self, capture_mode: str | None) -> str:
        value = str(capture_mode or "auto").strip().lower()
        if value in {"auto", "portrait", "general", "food"}:
            return value
        return "auto"

    def _normalize_scene(self, scene: str | None) -> str | None:
        value = str(scene or "").strip().lower()
        if value in {"portrait", "general", "landscape", "food", "night"}:
            return value
        return None

    def _resolve_model_path(self, model_ref: str, project_root: Path) -> Path | None:
        if not model_ref:
            return None
        p = Path(model_ref).expanduser()
        if p.is_absolute():
            return p if p.exists() else None
        resolved = (project_root / p).resolve()
        if resolved.exists():
            return resolved
        return None

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


PERSON_LABELS = {"person", "man", "woman", "face", "human"}
PET_LABELS = {
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
FOOD_LABELS = {
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
DRINK_CONTAINER_LABELS = {
    "cup",
    "bottle",
    "wine glass",
    "mug",
}
LANDSCAPE_LABELS = {
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
NIGHT_LABELS = {"night", "moon", "street light", "low light", "dark"}
