from __future__ import annotations

import os

from providers.base import VisionProvider
from providers.external_provider import ExternalProvider
from providers.mock_provider import MockProvider


def get_provider() -> VisionProvider:
    provider = os.getenv("AI_PROVIDER", "external").lower().strip()
    if provider in {"mock", "local"}:
        return MockProvider()
    return ExternalProvider()
