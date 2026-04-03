from __future__ import annotations

import base64
import io
from dataclasses import dataclass
from typing import Any

import numpy as np
from PIL import Image
from PIL import ImageOps

from app.core.config import PROJECT_ROOT
from app.core.config import SETTINGS
from app.vision.subject_fusion import HybridSceneDetector

try:
    from ultralytics import YOLO  # type: ignore
except Exception:  # pragma: no cover - optional until dependency install
    YOLO = None  # type: ignore[assignment]


@dataclass(frozen=True)
class RemakeAnalysis:
    pose_score: float
    framing_score: float
    alignment_score: float
    mismatch_hints: list[str]
    implementation_status: str
    placeholder_notes: list[str]


class PoseRemakeAnalyzer:
    def __init__(self) -> None:
        self._ready = False
        self._model = None
        self._scene_detector = HybridSceneDetector()
        if YOLO is None:
            return
        try:
            model_ref = SETTINGS.community_pose_model
            if model_ref.startswith("./"):
                model_ref = str((PROJECT_ROOT / model_ref).resolve())
            self._model = YOLO(model_ref)
            self._ready = self._model is not None
        except Exception:
            self._ready = False

    def analyze(self, template_bytes: bytes, candidate_base64: str, scene_hint: str = "general") -> RemakeAnalysis:
        candidate_bytes = self._decode_candidate(candidate_base64)
        template_img = self._load_image(template_bytes)
        candidate_img = self._load_image(candidate_bytes)

        template_scene = self._scene_detector.detect(template_bytes, capture_mode="auto", scene_hint=scene_hint)
        candidate_scene = self._scene_detector.detect(candidate_bytes, capture_mode="auto", scene_hint=scene_hint)

        framing_score = self._framing_score(template_scene.bbox_norm, candidate_scene.bbox_norm)
        pose_score = 0.52
        mismatch_hints: list[str] = []
        placeholder_notes: list[str] = []
        implementation_status = "ready"

        template_pose = self._detect_pose(template_img)
        candidate_pose = self._detect_pose(candidate_img)
        if template_pose is not None and candidate_pose is not None:
            pose_score = self._pose_similarity(template_pose, candidate_pose)
            mismatch_hints.extend(self._pose_hints(template_pose, candidate_pose))
        else:
            implementation_status = "placeholder"
            placeholder_notes.append("姿态评分当前回退到主体框与站位启发式，待 pose 权重就绪后会自动升级。")
            mismatch_hints.extend(self._framing_hints(template_scene.bbox_norm, candidate_scene.bbox_norm))

        alignment_score = max(0.0, min(1.0, round((pose_score * 0.56) + (framing_score * 0.44), 4)))
        if candidate_scene.scene != template_scene.scene and candidate_scene.confidence < 0.66:
            mismatch_hints.append("当前场景识别和模板不够接近，建议先确保主体和背景结构一致。")
            alignment_score = max(0.0, round(alignment_score - 0.08, 4))

        if not mismatch_hints:
            mismatch_hints.append("当前站位和构图已经接近模板，可以先连拍 3 张再微调动作。")

        return RemakeAnalysis(
            pose_score=max(0.0, min(1.0, round(pose_score, 4))),
            framing_score=max(0.0, min(1.0, round(framing_score, 4))),
            alignment_score=alignment_score,
            mismatch_hints=mismatch_hints[:6],
            implementation_status=implementation_status,
            placeholder_notes=placeholder_notes,
        )

    def _decode_candidate(self, image_base64: str) -> bytes:
        cleaned = image_base64.strip()
        if cleaned.startswith("data:image") and "," in cleaned:
            _, cleaned = cleaned.split(",", 1)
        return base64.b64decode(cleaned, validate=True)

    def _load_image(self, raw: bytes) -> Image.Image:
        with Image.open(io.BytesIO(raw)) as image:
            return ImageOps.exif_transpose(image).convert("RGB")

    def _detect_pose(self, image: Image.Image) -> np.ndarray | None:
        if not self._ready or self._model is None:
            return None
        try:
            results = self._model.predict(image, verbose=False, conf=0.20, imgsz=640)
        except Exception:
            return None
        if not results:
            return None
        result = results[0]
        keypoints = getattr(result, "keypoints", None)
        boxes = getattr(result, "boxes", None)
        if keypoints is None or getattr(keypoints, "xy", None) is None:
            return None
        xy = keypoints.xy.cpu().numpy() if hasattr(keypoints.xy, "cpu") else np.asarray(keypoints.xy)
        conf = keypoints.conf.cpu().numpy() if getattr(keypoints, "conf", None) is not None and hasattr(keypoints.conf, "cpu") else None
        if xy is None or len(xy) == 0:
            return None
        best_idx = 0
        best_area = 0.0
        if boxes is not None and getattr(boxes, "xyxy", None) is not None:
            xyxy = boxes.xyxy.cpu().numpy() if hasattr(boxes.xyxy, "cpu") else np.asarray(boxes.xyxy)
            for idx, box in enumerate(xyxy):
                area = max(0.0, float(box[2] - box[0])) * max(0.0, float(box[3] - box[1]))
                if area > best_area:
                    best_area = area
                    best_idx = idx
        selected = np.asarray(xy[best_idx], dtype=float)
        if conf is not None and len(conf) > best_idx:
            selected_conf = np.asarray(conf[best_idx], dtype=float).reshape(-1, 1)
            selected = np.concatenate([selected, selected_conf], axis=1)
        return selected

    def _pose_similarity(self, template_pose: np.ndarray, candidate_pose: np.ndarray) -> float:
        common = []
        for idx in (5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16):
            if idx >= len(template_pose) or idx >= len(candidate_pose):
                continue
            t = template_pose[idx]
            c = candidate_pose[idx]
            if (len(t) >= 3 and t[2] < 0.15) or (len(c) >= 3 and c[2] < 0.15):
                continue
            common.append((idx, t[:2], c[:2]))
        if len(common) < 4:
            return 0.52

        template_center = self._torso_center(template_pose)
        candidate_center = self._torso_center(candidate_pose)
        template_scale = self._torso_scale(template_pose)
        candidate_scale = self._torso_scale(candidate_pose)
        base_scale = max(8.0, (template_scale + candidate_scale) * 0.5)

        errors = []
        for _, t_xy, c_xy in common:
            t_norm = (t_xy - template_center) / base_scale
            c_norm = (c_xy - candidate_center) / base_scale
            errors.append(float(np.linalg.norm(t_norm - c_norm)))
        if not errors:
            return 0.52
        avg_error = float(np.mean(errors))
        return max(0.0, min(1.0, 1.0 - min(1.0, avg_error / 1.35)))

    def _pose_hints(self, template_pose: np.ndarray, candidate_pose: np.ndarray) -> list[str]:
        hints: list[str] = []
        t_center = self._torso_center(template_pose)
        c_center = self._torso_center(candidate_pose)
        dx = float(c_center[0] - t_center[0])
        dy = float(c_center[1] - t_center[1])
        if dx > 18:
            hints.append("主体整体偏右，往左挪半步会更贴近模板。")
        elif dx < -18:
            hints.append("主体整体偏左，往右挪半步会更贴近模板。")
        if dy > 20:
            hints.append("当前站位略低，建议抬一点机位或让人物站直。")
        elif dy < -20:
            hints.append("当前机位略高，建议压低一点拍摄高度。")

        shoulder_delta = self._shoulder_angle(candidate_pose) - self._shoulder_angle(template_pose)
        if shoulder_delta > 10:
            hints.append("肩线打开得比模板更大，收一点上半身会更像。")
        elif shoulder_delta < -10:
            hints.append("肩线略平，可以把一侧肩膀再抬开一点。")
        return hints[:4]

    def _framing_score(self, template_bbox: list[float] | None, candidate_bbox: list[float] | None) -> float:
        if not template_bbox or not candidate_bbox:
            return 0.48
        t_cx = (template_bbox[0] + template_bbox[2]) * 0.5
        t_cy = (template_bbox[1] + template_bbox[3]) * 0.5
        c_cx = (candidate_bbox[0] + candidate_bbox[2]) * 0.5
        c_cy = (candidate_bbox[1] + candidate_bbox[3]) * 0.5
        center_error = abs(t_cx - c_cx) + abs(t_cy - c_cy)
        t_area = max(0.01, (template_bbox[2] - template_bbox[0]) * (template_bbox[3] - template_bbox[1]))
        c_area = max(0.01, (candidate_bbox[2] - candidate_bbox[0]) * (candidate_bbox[3] - candidate_bbox[1]))
        area_error = abs(t_area - c_area) / max(t_area, 0.01)
        score = 1.0 - min(1.0, center_error * 1.8 + area_error * 0.55)
        return max(0.0, min(1.0, score))

    def _framing_hints(self, template_bbox: list[float] | None, candidate_bbox: list[float] | None) -> list[str]:
        if not template_bbox or not candidate_bbox:
            return ["当前还没有稳定识别到主体，建议让人物更靠近画面中心后再试。"]
        hints: list[str] = []
        t_cx = (template_bbox[0] + template_bbox[2]) * 0.5
        c_cx = (candidate_bbox[0] + candidate_bbox[2]) * 0.5
        t_area = (template_bbox[2] - template_bbox[0]) * (template_bbox[3] - template_bbox[1])
        c_area = (candidate_bbox[2] - candidate_bbox[0]) * (candidate_bbox[3] - candidate_bbox[1])
        if c_cx - t_cx > 0.06:
            hints.append("人物当前偏右，向左挪一点更接近模板。")
        elif c_cx - t_cx < -0.06:
            hints.append("人物当前偏左，向右挪一点更接近模板。")
        if c_area > t_area * 1.15:
            hints.append("主体占比偏大，建议退后半步或拉远一点。")
        elif c_area < t_area * 0.85:
            hints.append("主体占比偏小，建议再靠近镜头一点。")
        return hints[:4]

    def _torso_center(self, pose: np.ndarray) -> np.ndarray:
        points = []
        for idx in (5, 6, 11, 12):
            if idx >= len(pose):
                continue
            point = pose[idx]
            if len(point) >= 3 and point[2] < 0.12:
                continue
            points.append(point[:2])
        if not points:
            return np.array([0.0, 0.0], dtype=float)
        return np.mean(np.asarray(points, dtype=float), axis=0)

    def _torso_scale(self, pose: np.ndarray) -> float:
        pairs = []
        for left, right in ((5, 6), (11, 12)):
            if left >= len(pose) or right >= len(pose):
                continue
            l_point = pose[left]
            r_point = pose[right]
            if (len(l_point) >= 3 and l_point[2] < 0.12) or (len(r_point) >= 3 and r_point[2] < 0.12):
                continue
            pairs.append(float(np.linalg.norm(l_point[:2] - r_point[:2])))
        return max(pairs) if pairs else 48.0

    def _shoulder_angle(self, pose: np.ndarray) -> float:
        if len(pose) <= 6:
            return 0.0
        left = pose[5]
        right = pose[6]
        if (len(left) >= 3 and left[2] < 0.12) or (len(right) >= 3 and right[2] < 0.12):
            return 0.0
        dx = float(right[0] - left[0])
        dy = float(right[1] - left[1])
        if abs(dx) < 1e-6:
            return 0.0
        return float(np.degrees(np.arctan2(dy, dx)))


_POSE_ANALYZER = PoseRemakeAnalyzer()


def get_pose_remake_analyzer() -> PoseRemakeAnalyzer:
    return _POSE_ANALYZER
