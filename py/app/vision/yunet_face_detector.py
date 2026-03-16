from __future__ import annotations

import os
import threading
from pathlib import Path

import cv2
import numpy as np

from app.vision.models import BoxCandidate


class YunetFaceDetector:
    def __init__(self) -> None:
        self._ready = False
        self._lock = threading.Lock()

        project_root = Path(__file__).resolve().parents[2]
        model_ref = os.getenv("SCENE_YUNET_MODEL", "./models/face_detection_yunet_2023mar.onnx").strip()
        model_path = self._resolve_model_path(model_ref, project_root)
        if model_path is None:
            return

        self._score_threshold = self._env_float("SCENE_YUNET_SCORE_THRESHOLD", 0.70, 0.05, 0.99)
        self._nms_threshold = self._env_float("SCENE_YUNET_NMS_THRESHOLD", 0.30, 0.05, 0.95)
        self._top_k = self._env_int("SCENE_YUNET_TOP_K", 256, 1, 5000)

        try:
            self._detector = cv2.FaceDetectorYN.create(
                str(model_path),
                "",
                (320, 320),
                self._score_threshold,
                self._nms_threshold,
                self._top_k,
            )
            self._ready = self._detector is not None
        except Exception:
            self._ready = False

    def detect_faces(self, image_bytes: bytes) -> list[BoxCandidate]:
        if not self._ready:
            return []

        frame = cv2.imdecode(np.frombuffer(image_bytes, dtype=np.uint8), cv2.IMREAD_COLOR)
        if frame is None:
            return []
        image_height, image_width = frame.shape[:2]
        if image_width <= 0 or image_height <= 0:
            return []

        with self._lock:
            try:
                self._detector.setInputSize((image_width, image_height))
                _, faces = self._detector.detect(frame)
            except Exception:
                return []

        if faces is None:
            return []

        out: list[BoxCandidate] = []
        for face in faces:
            if len(face) < 5:
                continue
            x, y, w, h, score = float(face[0]), float(face[1]), float(face[2]), float(face[3]), float(face[4])
            if score < self._score_threshold:
                continue
            x1 = max(0.0, min(x, float(image_width)))
            y1 = max(0.0, min(y, float(image_height)))
            x2 = max(0.0, min(x + w, float(image_width)))
            y2 = max(0.0, min(y + h, float(image_height)))
            if x2 <= x1 or y2 <= y1:
                continue

            bbox = [
                round(x1 / image_width, 4),
                round(y1 / image_height, 4),
                round(x2 / image_width, 4),
                round(y2 / image_height, 4),
            ]
            center = [round((bbox[0] + bbox[2]) * 0.5, 4), round((bbox[1] + bbox[3]) * 0.5, 4)]
            area = (bbox[2] - bbox[0]) * (bbox[3] - bbox[1])
            out.append(
                BoxCandidate(
                    label="face",
                    confidence=max(0.0, min(1.0, score)),
                    bbox_norm=bbox,
                    center_norm=center,
                    area_norm=max(0.0, min(1.0, area)),
                )
            )
        return out

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

