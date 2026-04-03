from __future__ import annotations

from app.vision.models import BoxCandidate
from app.vision.models import SceneResult
from app.vision.yolo_scene_detector import YoloSceneDetector
from app.vision.yolo_scene_detector import PERSON_LABELS
from app.vision.yunet_face_detector import YunetFaceDetector


class HybridSceneDetector:
    def __init__(self) -> None:
        self._yolo = YoloSceneDetector()
        self._yunet = YunetFaceDetector()

    def detect(
        self,
        image_bytes: bytes,
        capture_mode: str = "auto",
        scene_hint: str | None = None,
    ) -> SceneResult:
        scene_result, yolo_boxes = self._yolo.detect_scene_and_boxes(
            image_bytes=image_bytes,
            capture_mode=capture_mode,
            scene_hint=scene_hint,
        )
        mode = self._normalize_capture_mode(capture_mode)
        if mode == "portrait":
            scene_result.mode = "portrait"
        elif mode == "food":
            scene_result.mode = "food"
        elif mode == "general":
            scene_result.mode = "general"

        face_boxes: list[BoxCandidate] | None = None
        if mode in {"auto", "general"}:
            face_boxes = self._yunet.detect_faces(image_bytes)
            scene_result = self._apply_face_priority(
                scene_result=scene_result,
                capture_mode=mode,
                face_boxes=face_boxes,
                yolo_boxes=yolo_boxes,
            )

        target = self._select_subject_target(
            image_bytes=image_bytes,
            scene_result=scene_result,
            capture_mode=mode,
            yolo_boxes=yolo_boxes,
            face_boxes=face_boxes,
        )
        if target is not None:
            scene_result.bbox_norm = target.bbox_norm
            scene_result.center_norm = target.center_norm
        else:
            scene_result.bbox_norm = None
            scene_result.center_norm = None
        return scene_result

    def describe_runtime(self) -> dict[str, object]:
        yolo_info = self._yolo.describe_runtime()
        return {
            "ready": bool(yolo_info.get("ready", False)),
            "model_paths": list(yolo_info.get("model_paths", [])),
            "model_count": int(yolo_info.get("model_count", 0)),
            "custom_override": bool(yolo_info.get("custom_override", False)),
            "name_samples": list(yolo_info.get("name_samples", [])),
            "yunet_ready": bool(self._yunet.is_ready()),
        }

    def _select_subject_target(
        self,
        image_bytes: bytes,
        scene_result: SceneResult,
        capture_mode: str,
        yolo_boxes: list[BoxCandidate],
        face_boxes: list[BoxCandidate] | None = None,
    ) -> BoxCandidate | None:
        use_portrait_target = capture_mode == "portrait" or scene_result.scene == "portrait"
        use_food_target = capture_mode == "food" or scene_result.scene == "food"

        if use_portrait_target:
            portrait_faces = face_boxes if face_boxes is not None else self._yunet.detect_faces(image_bytes)
            best_face = self._pick_best_face(portrait_faces)
            if best_face is not None:
                return best_face

            person_box = self._yolo.pick_person_target(yolo_boxes, prefer_center=True)
            if person_box is not None:
                return self._yolo.refine_head_shoulder_box(person_box)
            return self._yolo.pick_scene_target("portrait", yolo_boxes)

        if use_food_target:
            food_box = self._yolo.pick_scene_target("food", yolo_boxes)
            if food_box is not None:
                return self._yolo.shrink_oversized_box(food_box)

        if self._yolo.has_person(yolo_boxes):
            person_box = self._yolo.pick_person_target(yolo_boxes, prefer_center=True)
            if person_box is not None:
                if person_box.area_norm > 0.55:
                    return self._yolo.refine_upper_body_box(person_box)
                return person_box

        scene_target = self._yolo.pick_scene_target(scene_result.scene, yolo_boxes)
        if scene_target is not None:
            if scene_target.label in PERSON_LABELS:
                return self._yolo.refine_upper_body_box(scene_target)
            return self._yolo.shrink_oversized_box(scene_target)
        return None

    def _apply_face_priority(
        self,
        scene_result: SceneResult,
        capture_mode: str,
        face_boxes: list[BoxCandidate],
        yolo_boxes: list[BoxCandidate],
    ) -> SceneResult:
        if capture_mode not in {"auto", "general"}:
            return scene_result
        best_face = self._pick_best_face(face_boxes)
        if best_face is None:
            return scene_result
        has_person_evidence = any(box.label in PERSON_LABELS and box.confidence >= 0.34 for box in yolo_boxes)
        if not has_person_evidence and scene_result.scene != "portrait":
            return scene_result
        # Keep portrait promotion conservative to avoid random false positive face boxes.
        if best_face.confidence < 0.76 or best_face.area_norm < 0.010:
            return scene_result

        boosted_confidence = min(
            0.93,
            max(
                scene_result.confidence,
                0.64 + best_face.confidence * 0.22 + min(0.10, best_face.area_norm * 0.35),
            ),
        )
        return SceneResult(
            scene="portrait",
            confidence=boosted_confidence,
            mode="portrait",
            bbox_norm=scene_result.bbox_norm,
            center_norm=scene_result.center_norm,
        )

    def _pick_best_face(self, faces: list[BoxCandidate]) -> BoxCandidate | None:
        if not faces:
            return None
        best: tuple[float, BoxCandidate] | None = None
        for face in faces:
            cx, cy = face.center_norm
            center_score = 1.0 - min(1.0, abs(cx - 0.5) + abs(cy - 0.5))
            size_target = 0.10
            size_score = 1.0 - min(1.0, abs(face.area_norm - size_target) / max(size_target, 0.03))
            score = face.confidence * 0.72 + center_score * 0.16 + size_score * 0.12
            if best is None or score > best[0]:
                best = (score, face)
        return best[1] if best is not None else None

    def _normalize_capture_mode(self, capture_mode: str | None) -> str:
        value = str(capture_mode or "auto").strip().lower()
        if value in {"auto", "portrait", "general", "food"}:
            return value
        return "auto"
