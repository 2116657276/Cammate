from __future__ import annotations

import os
from threading import Lock

from providers.base import VisionProvider
from providers.external_provider import ExternalProvider
from providers.mock_provider import MockProvider

_provider_lock = Lock()
_provider_name = ""
_provider_instance: VisionProvider | None = None


def get_provider() -> VisionProvider:
    global _provider_name
    global _provider_instance

    provider = os.getenv("AI_PROVIDER", "external").lower().strip()
    with _provider_lock:
        if _provider_instance is not None and _provider_name == provider:
            return _provider_instance
        if provider in {"mock", "local"}:
            _provider_instance = MockProvider()
        else:
            _provider_instance = ExternalProvider()
        _provider_name = provider
        return _provider_instance


async def close_provider() -> None:
    global _provider_name
    global _provider_instance
    with _provider_lock:
        provider = _provider_instance
        _provider_instance = None
        _provider_name = ""
    if provider is None:
        return
    close_fn = getattr(provider, "aclose", None)
    if callable(close_fn):
        await close_fn()
