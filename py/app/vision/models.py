from __future__ import annotations

from dataclasses import dataclass


@dataclass
class SceneResult:
    scene: str
    confidence: float
    mode: str
    bbox_norm: list[float] | None = None
    center_norm: list[float] | None = None


@dataclass
class BoxCandidate:
    label: str
    confidence: float
    bbox_norm: list[float]
    center_norm: list[float]
    area_norm: float

