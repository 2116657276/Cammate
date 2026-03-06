from __future__ import annotations

from abc import ABC, abstractmethod
from typing import Any


class VisionProvider(ABC):
    @abstractmethod
    async def analyze(
        self,
        image_base64: str,
        detected_scene: str,
        client_context: dict[str, Any],
    ) -> list[dict[str, Any]]:
        """Return stream events compatible with NDJSON output."""
