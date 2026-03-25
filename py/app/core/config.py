from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path

_ENV_LOADED = False


def _parse_env_line(line: str) -> tuple[str, str] | None:
    text = line.strip()
    if not text or text.startswith("#"):
        return None
    if text.startswith("export "):
        text = text[7:].strip()
    if "=" not in text:
        return None
    key, value = text.split("=", 1)
    key = key.strip()
    value = value.strip()
    if not key:
        return None
    if len(value) >= 2 and value[0] == value[-1] and value[0] in {"'", '"'}:
        value = value[1:-1]
    return key, value


def load_runtime_env() -> None:
    global _ENV_LOADED
    if _ENV_LOADED:
        return

    project_root = Path(__file__).resolve().parents[2]
    loaded_files: list[str] = []
    for filename in (".env.local", ".env"):
        env_file = project_root / filename
        if not env_file.exists():
            continue
        try:
            lines = env_file.read_text(encoding="utf-8").splitlines()
        except Exception:
            continue
        for line in lines:
            parsed = _parse_env_line(line)
            if parsed is None:
                continue
            key, value = parsed
            if key not in os.environ and value:
                os.environ[key] = value
        loaded_files.append(str(env_file))

    if loaded_files and "APP_CONFIG_LOADED_ENV_FILES" not in os.environ:
        os.environ["APP_CONFIG_LOADED_ENV_FILES"] = ",".join(loaded_files)
    _ENV_LOADED = True


def _env_int(name: str, default: int) -> int:
    raw = os.getenv(name)
    if raw is None:
        return default
    try:
        return int(raw)
    except Exception:
        return default


def _env_str(name: str, default: str) -> str:
    raw = os.getenv(name)
    if raw is None:
        return default
    value = raw.strip()
    return value if value else default


def _env_csv(name: str, default: str = "") -> list[str]:
    raw = os.getenv(name, default)
    parts = [item.strip().lower() for item in raw.split(",")]
    return [item for item in parts if item]


@dataclass(frozen=True)
class Settings:
    db_path: Path
    session_ttl_sec: int
    pbkdf2_rounds: int
    community_upload_dir: Path
    community_blocked_words: list[str]
    community_upload_max_side: int
    community_recommend_limit_default: int


load_runtime_env()

SETTINGS = Settings(
    db_path=Path(os.getenv("APP_DB_PATH", Path(__file__).resolve().parents[2] / "app_data.db")),
    session_ttl_sec=max(300, _env_int("SESSION_TTL_SEC", 604800)),
    pbkdf2_rounds=max(60000, _env_int("PBKDF2_ROUNDS", 120000)),
    community_upload_dir=Path(
        _env_str(
            "COMMUNITY_UPLOAD_DIR",
            str(Path(__file__).resolve().parents[2] / "uploads" / "community"),
        )
    ),
    community_blocked_words=_env_csv("COMMUNITY_BLOCKED_WORDS", "广告,引流,加微信"),
    community_upload_max_side=max(720, min(_env_int("COMMUNITY_UPLOAD_MAX_SIDE", 1920), 4096)),
    community_recommend_limit_default=max(3, min(_env_int("COMMUNITY_RECOMMEND_LIMIT_DEFAULT", 12), 48)),
)
