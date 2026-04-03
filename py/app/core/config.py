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


def _env_bool(name: str, default: bool) -> bool:
    raw = os.getenv(name)
    if raw is None:
        return default
    value = raw.strip().lower()
    if value in {"1", "true", "yes", "on"}:
        return True
    if value in {"0", "false", "no", "off"}:
        return False
    return default


def _env_float(name: str, default: float) -> float:
    raw = os.getenv(name)
    if raw is None:
        return default
    try:
        return float(raw)
    except Exception:
        return default


def _default_community_seed_enabled() -> bool:
    app_env = os.getenv("APP_ENV", "development").strip().lower()
    return app_env not in {"prod", "production"}


PROJECT_ROOT = Path(__file__).resolve().parents[2]


@dataclass(frozen=True)
class Settings:
    db_path: Path
    demo_db_path: Path
    session_ttl_sec: int
    pbkdf2_rounds: int
    community_upload_dir: Path
    community_demo_asset_dir: Path
    community_seed_manifest_path: Path
    community_creative_result_dir: Path
    community_blocked_words: list[str]
    community_upload_max_side: int
    community_recommend_limit_default: int
    community_seed_enabled: bool
    community_seed_count: int
    community_creative_worker_count: int
    community_creative_max_retries: int
    community_creative_retry_base_sec: int
    community_creative_retry_max_delay_sec: int
    community_creative_retry_jitter_ratio: float
    community_creative_result_ttl_sec: int
    creative_queue_backend: str
    creative_redis_url: str
    creative_redis_ready_key: str
    creative_redis_delayed_key: str
    creative_redis_lease_prefix: str
    creative_redis_worker_prefix: str
    creative_worker_poll_sec: float
    creative_worker_lease_warn_sec: int
    creative_worker_heartbeat_ttl_sec: int
    creative_embedded_worker: bool
    creative_storage_provider: str
    creative_storage_endpoint: str
    creative_storage_bucket: str
    creative_storage_access_key: str
    creative_storage_secret_key: str
    creative_storage_region: str
    creative_storage_prefix: str
    community_pose_model: str
    community_admin_token: str


load_runtime_env()

SETTINGS = Settings(
    db_path=Path(os.getenv("APP_DB_PATH", PROJECT_ROOT / "demo_app_data.db")),
    demo_db_path=Path(_env_str("APP_DEMO_DB_PATH", str(PROJECT_ROOT / "demo_app_data.db"))),
    session_ttl_sec=max(300, _env_int("SESSION_TTL_SEC", 604800)),
    pbkdf2_rounds=max(60000, _env_int("PBKDF2_ROUNDS", 120000)),
    community_upload_dir=Path(
        _env_str(
            "COMMUNITY_UPLOAD_DIR",
            str(PROJECT_ROOT / "uploads" / "community"),
        )
    ),
    community_demo_asset_dir=Path(
        _env_str(
            "COMMUNITY_DEMO_ASSET_DIR",
            str(PROJECT_ROOT / "demo_assets" / "community_seed"),
        )
    ),
    community_seed_manifest_path=Path(
        _env_str(
            "COMMUNITY_SEED_MANIFEST_PATH",
            str(PROJECT_ROOT / "demo_assets" / "community_seed" / "manifest.json"),
        )
    ),
    community_creative_result_dir=Path(
        _env_str(
            "COMMUNITY_CREATIVE_RESULT_DIR",
            str(PROJECT_ROOT / "creative_results"),
        )
    ),
    community_blocked_words=_env_csv("COMMUNITY_BLOCKED_WORDS", "广告,引流,加微信"),
    community_upload_max_side=max(720, min(_env_int("COMMUNITY_UPLOAD_MAX_SIDE", 1920), 4096)),
    community_recommend_limit_default=max(3, min(_env_int("COMMUNITY_RECOMMEND_LIMIT_DEFAULT", 12), 48)),
    # 开发阶段默认启用社区假数据；若 APP_ENV=production 默认关闭，可显式覆盖。
    community_seed_enabled=_env_bool("COMMUNITY_SEED_ENABLED", _default_community_seed_enabled()),
    community_seed_count=max(0, min(_env_int("COMMUNITY_SEED_COUNT", 28), 60)),
    community_creative_worker_count=max(1, min(_env_int("COMMUNITY_CREATIVE_WORKER_COUNT", 2), 8)),
    community_creative_max_retries=max(0, min(_env_int("COMMUNITY_CREATIVE_MAX_RETRIES", 2), 5)),
    community_creative_retry_base_sec=max(1, min(_env_int("COMMUNITY_CREATIVE_RETRY_BASE_SEC", 2), 20)),
    community_creative_retry_max_delay_sec=max(5, min(_env_int("COMMUNITY_CREATIVE_RETRY_MAX_DELAY_SEC", 120), 1800)),
    community_creative_retry_jitter_ratio=max(0.0, min(_env_float("COMMUNITY_CREATIVE_RETRY_JITTER_RATIO", 0.35), 1.0)),
    community_creative_result_ttl_sec=max(3600, min(_env_int("COMMUNITY_CREATIVE_RESULT_TTL_SEC", 604800), 7776000)),
    creative_queue_backend=_env_str("CREATIVE_QUEUE_BACKEND", "redis").strip().lower(),
    creative_redis_url=_env_str("CREATIVE_REDIS_URL", "redis://127.0.0.1:6379/0"),
    creative_redis_ready_key=_env_str("CREATIVE_REDIS_READY_KEY", "cammate:creative:ready"),
    creative_redis_delayed_key=_env_str("CREATIVE_REDIS_DELAYED_KEY", "cammate:creative:delayed"),
    creative_redis_lease_prefix=_env_str("CREATIVE_REDIS_LEASE_PREFIX", "cammate:creative:lease:"),
    creative_redis_worker_prefix=_env_str("CREATIVE_REDIS_WORKER_PREFIX", "cammate:creative:worker:"),
    creative_worker_poll_sec=max(0.1, min(_env_float("CREATIVE_WORKER_POLL_SEC", 1.0), 10.0)),
    creative_worker_lease_warn_sec=max(1, min(_env_int("CREATIVE_WORKER_LEASE_WARN_SEC", 6), 60)),
    creative_worker_heartbeat_ttl_sec=max(3, min(_env_int("CREATIVE_WORKER_HEARTBEAT_TTL_SEC", 15), 120)),
    creative_embedded_worker=_env_bool("CREATIVE_EMBEDDED_WORKER", False),
    creative_storage_provider=_env_str("CREATIVE_STORAGE_PROVIDER", "local").strip().lower(),
    creative_storage_endpoint=_env_str("CREATIVE_STORAGE_ENDPOINT", "http://127.0.0.1:9000"),
    creative_storage_bucket=_env_str("CREATIVE_STORAGE_BUCKET", "cammate-creative"),
    creative_storage_access_key=_env_str("CREATIVE_STORAGE_ACCESS_KEY", "minioadmin"),
    creative_storage_secret_key=_env_str("CREATIVE_STORAGE_SECRET_KEY", "minioadmin"),
    creative_storage_region=_env_str("CREATIVE_STORAGE_REGION", "us-east-1"),
    creative_storage_prefix=_env_str("CREATIVE_STORAGE_PREFIX", "creative-results"),
    community_pose_model=_env_str("COMMUNITY_POSE_MODEL", "./models/yolo11n-pose.pt"),
    community_admin_token=_env_str("COMMUNITY_ADMIN_TOKEN", ""),
)
