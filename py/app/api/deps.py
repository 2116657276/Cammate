from __future__ import annotations

from fastapi import Header

from app.core.security import parse_bearer_token
from app.models.schemas import AuthUser
from app.services import auth_service


def require_user(authorization: str | None = Header(default=None)) -> AuthUser:
    token = parse_bearer_token(authorization)
    return auth_service.get_user_by_token(token)
