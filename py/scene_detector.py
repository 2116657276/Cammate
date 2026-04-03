from __future__ import annotations

from app.vision.models import BoxCandidate
from app.vision.models import SceneResult
from app.vision.subject_fusion import HybridSceneDetector


class SceneDetector:
    def detect(
        self,
        image_bytes: bytes,
        capture_mode: str = "auto",
        scene_hint: str | None = None,
    ) -> SceneResult:
        raise NotImplementedError


class YoloSceneDetector(SceneDetector):
    """
    Backward-compatible detector entry.
    The runtime implementation now uses HybridSceneDetector(YOLO + YuNet fusion).
    """

    def __init__(self) -> None:
        self._detector = HybridSceneDetector()

    def detect(
        self,
        image_bytes: bytes,
        capture_mode: str = "auto",
        scene_hint: str | None = None,
    ) -> SceneResult:
        return self._detector.detect(
            image_bytes=image_bytes,
            capture_mode=capture_mode,
            scene_hint=scene_hint,
        )

    def describe_runtime(self) -> dict[str, object]:
        return self._detector.describe_runtime()


_SCENE_DETECTOR: SceneDetector | None = None


def get_scene_detector() -> SceneDetector:
    global _SCENE_DETECTOR
    if _SCENE_DETECTOR is None:
        _SCENE_DETECTOR = YoloSceneDetector()
    return _SCENE_DETECTOR


def describe_scene_runtime() -> dict[str, object]:
    detector = get_scene_detector()
    describe = getattr(detector, "describe_runtime", None)
    if callable(describe):
        try:
            return describe()
        except Exception:
            return {"ready": False, "model_paths": [], "model_count": 0, "custom_override": False}
    return {"ready": False, "model_paths": [], "model_count": 0, "custom_override": False}


__all__ = [
    "SceneResult",
    "BoxCandidate",
    "SceneDetector",
    "YoloSceneDetector",
    "get_scene_detector",
    "describe_scene_runtime",
]
