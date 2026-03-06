from __future__ import annotations

from fastapi import APIRouter, Depends

from app.api.deps import require_user
from app.models.schemas import AuthLoginRequest, AuthRegisterRequest, AuthResponse, AuthUser, LogoutResponse, UserView
from app.services import auth_service

router = APIRouter(prefix="/auth", tags=["auth"])


@router.post("/register", response_model=AuthResponse)
async def register(req: AuthRegisterRequest) -> AuthResponse:
    return auth_service.register(req)


@router.post("/login", response_model=AuthResponse)
async def login(req: AuthLoginRequest) -> AuthResponse:
    return auth_service.login(req)


@router.get("/me", response_model=UserView)
async def me(current_user: AuthUser = Depends(require_user)) -> UserView:
    return UserView(
        id=current_user.id,
        email=current_user.email,
        nickname=current_user.nickname,
    )


@router.post("/logout", response_model=LogoutResponse)
async def logout(current_user: AuthUser = Depends(require_user)) -> LogoutResponse:
    auth_service.logout(current_user.token)
    return LogoutResponse(ok=True)
