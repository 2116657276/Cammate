from __future__ import annotations

from fastapi import APIRouter, Depends, Query, Response

from app.api.deps import require_user
from app.models.schemas import (
    AuthUser,
    CommunityComposeRequest,
    CommunityComposeResponse,
    CommunityFeedResponse,
    CommunityPostView,
    CommunityPublishRequest,
    CommunityRecommendationsResponse,
)
from app.services import community_service

router = APIRouter(prefix="/community", tags=["community"])


@router.post("/posts", response_model=CommunityPostView)
async def publish_post(
    req: CommunityPublishRequest,
    current_user: AuthUser = Depends(require_user),
) -> CommunityPostView:
    return community_service.publish_post(current_user.id, req)


@router.get("/feed", response_model=CommunityFeedResponse)
async def feed(
    offset: int = Query(default=0, ge=0),
    limit: int = Query(default=12, ge=1, le=50),
    _: AuthUser = Depends(require_user),
) -> CommunityFeedResponse:
    return community_service.list_feed(offset=offset, limit=limit)


@router.get("/recommendations", response_model=CommunityRecommendationsResponse)
async def recommendations(
    place_tag: str | None = Query(default=None, min_length=1, max_length=48),
    scene_type: str | None = Query(default=None, min_length=3, max_length=24),
    limit: int | None = Query(default=None, ge=1, le=32),
    _: AuthUser = Depends(require_user),
) -> CommunityRecommendationsResponse:
    return community_service.recommendations(
        place_tag=place_tag,
        scene_type=scene_type,
        limit=limit,
    )


@router.get("/posts/{post_id}/image")
async def post_image(
    post_id: int,
    _: AuthUser = Depends(require_user),
) -> Response:
    data, mime = community_service.load_post_image(post_id)
    return Response(content=data, media_type=mime)


@router.post("/compose", response_model=CommunityComposeResponse)
async def compose(
    req: CommunityComposeRequest,
    _: AuthUser = Depends(require_user),
) -> CommunityComposeResponse:
    return await community_service.compose_image(req)
