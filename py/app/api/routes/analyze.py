from __future__ import annotations

from fastapi import APIRouter, Depends
from fastapi.responses import StreamingResponse

from app.api.deps import require_user
from app.models.schemas import AnalyzeRequest, AuthUser
from app.services.analyze_service import event_stream

router = APIRouter(tags=["analyze"])


@router.post("/analyze")
async def analyze(
    req: AnalyzeRequest,
    _: AuthUser = Depends(require_user),
) -> StreamingResponse:
    return StreamingResponse(event_stream(req), media_type="application/x-ndjson")
