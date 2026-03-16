from __future__ import annotations

import base64
import hashlib
import hmac
import os
import re
import secrets

from fastapi import HTTPException

from app.core.config import SETTINGS


def normalize_email(email: str) -> str:
    value = email.strip().lower()
    # Normalize common full-width/Chinese punctuation from mobile keyboards.
    replacements = {
        "\uFF20": "@",  # full-width @
        "\u3002": ".",  # ideographic full stop
        "\uFF0E": ".",  # full-width dot
        "\uFE52": ".",  # small full stop
        "\uFF61": ".",  # half-width ideographic full stop
        "\u200B": "",   # zero-width space
        "\u00A0": "",   # non-breaking space
    }
    for old, new in replacements.items():
        value = value.replace(old, new)
    return value


def validate_email(email: str) -> str:
    value = normalize_email(email)
    if not re.fullmatch(r"[^@\s]+@[^@\s]+\.[^@\s]+", value):
        raise HTTPException(status_code=400, detail="invalid email")
    return value


def hash_password(password: str, salt: bytes) -> str:
    digest = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, SETTINGS.pbkdf2_rounds)
    return base64.b64encode(digest).decode("utf-8")


def create_password_pair(password: str) -> tuple[str, str]:
    salt = os.urandom(16)
    return hash_password(password, salt), base64.b64encode(salt).decode("utf-8")


def verify_password(password: str, expected_hash: str, salt_b64: str) -> bool:
    try:
        salt = base64.b64decode(salt_b64)
    except Exception:
        return False
    calc = hash_password(password, salt)
    return hmac.compare_digest(calc, expected_hash)


def issue_token() -> str:
    return secrets.token_urlsafe(32)


def parse_bearer_token(authorization: str | None) -> str:
    if not authorization:
        raise HTTPException(status_code=401, detail="missing authorization")
    parts = authorization.strip().split(" ", 1)
    if len(parts) != 2 or parts[0].lower() != "bearer" or not parts[1].strip():
        raise HTTPException(status_code=401, detail="invalid authorization")
    return parts[1].strip()
