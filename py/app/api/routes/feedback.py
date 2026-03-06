from __future__ import annotations

from fastapi import APIRouter, Depends

from app.api.deps import require_user
from app.models.schemas import AuthUser, FeedbackRequest, FeedbackResponse
from app.services import feedback_service

router = APIRouter(tags=["feedback"])


@router.post("/feedback", response_model=FeedbackResponse)
async def submit_feedback(
    req: FeedbackRequest,
    current_user: AuthUser = Depends(require_user),
) -> FeedbackResponse:
    feedback_id = feedback_service.submit(current_user.id, req)
    return FeedbackResponse(feedback_id=feedback_id)
