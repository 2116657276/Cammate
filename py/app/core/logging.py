from __future__ import annotations

import contextvars
import logging
import logging.config
import os
from pathlib import Path

from app.core.config import load_runtime_env

_LOGGING_CONFIGURED = False
request_id_ctx_var: contextvars.ContextVar[str] = contextvars.ContextVar("request_id", default="-")


class RequestIdFilter(logging.Filter):
    def filter(self, record: logging.LogRecord) -> bool:
        record.request_id = request_id_ctx_var.get("-")
        return True


class ConsoleNoiseFilter(logging.Filter):
    def filter(self, record: logging.LogRecord) -> bool:
        if record.levelno >= logging.WARNING:
            return True
        if getattr(record, "log_kind", "") == "startup":
            return True
        if getattr(record, "log_kind", "") == "summary":
            return True
        if getattr(record, "log_kind", "") == "http_request":
            method = getattr(record, "http_method", "")
            status = getattr(record, "http_status", 0)
            elapsed_ms = getattr(record, "http_elapsed_ms", 0)
            path = getattr(record, "http_path", "")
            if status >= 400 or elapsed_ms >= 1000:
                return True
            if method not in {"GET", "HEAD", "OPTIONS"}:
                return True
            if path.startswith("/healthz"):
                return False
            if path.startswith("/community/posts/") and path.endswith("/image"):
                return False
            return False
        return False


def _env_int(name: str, default: int, min_value: int, max_value: int) -> int:
    raw = os.getenv(name)
    if raw is None:
        return default
    try:
        value = int(raw)
    except Exception:
        return default
    return max(min_value, min(max_value, value))


def setup_logging() -> None:
    global _LOGGING_CONFIGURED
    if _LOGGING_CONFIGURED:
        return

    load_runtime_env()
    project_root = Path(__file__).resolve().parents[2]
    log_dir = Path(os.getenv("APP_LOG_DIR", project_root / "logs"))
    log_file = os.getenv("APP_LOG_FILE", "server.log")
    log_level = os.getenv("APP_LOG_LEVEL", "INFO").upper()
    console_level = os.getenv("APP_LOG_CONSOLE_LEVEL", log_level).upper()
    file_level = os.getenv("APP_LOG_FILE_LEVEL", log_level).upper()
    max_bytes = _env_int("APP_LOG_MAX_BYTES", 5 * 1024 * 1024, 256 * 1024, 50 * 1024 * 1024)
    backup_count = _env_int("APP_LOG_BACKUP_COUNT", 5, 1, 30)

    log_dir.mkdir(parents=True, exist_ok=True)
    file_path = str(log_dir / log_file)

    logging.config.dictConfig(
        {
            "version": 1,
            "disable_existing_loggers": False,
            "filters": {
                "request_id": {
                    "()": "app.core.logging.RequestIdFilter",
                },
                "console_noise": {
                    "()": "app.core.logging.ConsoleNoiseFilter",
                }
            },
            "formatters": {
                "default": {
                    "format": "%(asctime)s | %(levelname)s | req=%(request_id)s | %(name)s | %(message)s",
                    "datefmt": "%Y-%m-%d %H:%M:%S",
                }
            },
            "handlers": {
                "console": {
                    "class": "logging.StreamHandler",
                    "level": console_level,
                    "formatter": "default",
                    "filters": ["request_id", "console_noise"],
                },
                "file": {
                    "class": "logging.handlers.RotatingFileHandler",
                    "level": file_level,
                    "formatter": "default",
                    "filename": file_path,
                    "maxBytes": max_bytes,
                    "backupCount": backup_count,
                    "encoding": "utf-8",
                    "filters": ["request_id"],
                },
            },
            "loggers": {
                "": {
                    "handlers": ["console", "file"],
                    "level": log_level,
                },
                "uvicorn.error": {
                    "handlers": ["console", "file"],
                    "level": log_level,
                    "propagate": False,
                },
                "uvicorn.access": {
                    "handlers": ["file"],
                    "level": file_level,
                    "propagate": False,
                },
            },
        }
    )

    _LOGGING_CONFIGURED = True
    logging.getLogger("uvicorn.error").info(
        "logging.ready level=%s console_level=%s file_level=%s file=%s max_bytes=%d backup_count=%d",
        log_level,
        console_level,
        file_level,
        file_path,
        max_bytes,
        backup_count,
        extra={"log_kind": "startup"},
    )
