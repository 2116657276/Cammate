from __future__ import annotations

from fastapi import APIRouter, Depends

from app.api.deps import require_user
from app.models.schemas import AuthUser, RetouchRequest, RetouchResponse
from app.services import retouch_service

router = APIRouter(tags=["retouch"])


@router.post("/retouch", response_model=RetouchResponse)
async def retouch(
    req: RetouchRequest,
    _: AuthUser = Depends(require_user),
) -> RetouchResponse:
    return await retouch_service.retouch(req)
