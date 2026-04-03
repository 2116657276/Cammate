from __future__ import annotations

from fastapi import Header
from fastapi import HTTPException

from app.core.config import SETTINGS
from app.core.security import parse_bearer_token
from app.models.schemas import AuthUser
from app.services import auth_service


def require_user(authorization: str | None = Header(default=None)) -> AuthUser:
    token = parse_bearer_token(authorization)
    return auth_service.get_user_by_token(token)


def require_admin(x_admin_token: str | None = Header(default=None, alias="x-admin-token")) -> int:
    expected = SETTINGS.community_admin_token.strip()
    if not expected:
        raise HTTPException(status_code=503, detail="admin moderation token not configured")
    if (x_admin_token or "").strip() != expected:
        raise HTTPException(status_code=403, detail="invalid admin token")
    return 1
