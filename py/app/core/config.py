from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path


def _env_int(name: str, default: int) -> int:
    raw = os.getenv(name)
    if raw is None:
        return default
    try:
        return int(raw)
    except Exception:
        return default


@dataclass(frozen=True)
class Settings:
    db_path: Path
    session_ttl_sec: int
    pbkdf2_rounds: int


SETTINGS = Settings(
    db_path=Path(os.getenv("APP_DB_PATH", Path(__file__).resolve().parents[2] / "app_data.db")),
    session_ttl_sec=max(300, _env_int("SESSION_TTL_SEC", 604800)),
    pbkdf2_rounds=max(60000, _env_int("PBKDF2_ROUNDS", 120000)),
)
