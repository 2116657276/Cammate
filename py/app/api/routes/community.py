from __future__ import annotations

from fastapi import APIRouter, Depends, Query, Response

from app.api.deps import require_user
from app.models.schemas import (
    AuthUser,
    CommunityCocreateComposeRequest,
    CommunityCocreateComposeResponse,
    CommunityCommentCreateRequest,
    CommunityCommentView,
    CommunityCommentsResponse,
    CommunityComposeRequest,
    CommunityComposeResponse,
    CommunityCreativeJobView,
    CommunityDeleteResponse,
    CommunityDirectPublishRequest,
    CommunityFeedResponse,
    CommunityLikeResponse,
    CommunityPostView,
    CommunityPublishRequest,
    CommunityRelayPublishRequest,
    CommunityRemakeGuideRequest,
    CommunityRemakeGuideResponse,
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


@router.post("/posts/direct", response_model=CommunityPostView)
async def publish_direct_post(
    req: CommunityDirectPublishRequest,
    current_user: AuthUser = Depends(require_user),
) -> CommunityPostView:
    return community_service.publish_direct_post(current_user.id, req)


@router.post("/relay/posts", response_model=CommunityPostView)
async def publish_relay_post(
    req: CommunityRelayPublishRequest,
    current_user: AuthUser = Depends(require_user),
) -> CommunityPostView:
    return community_service.publish_relay_post(current_user.id, req)


@router.get("/feed", response_model=CommunityFeedResponse)
async def feed(
    offset: int = Query(default=0, ge=0),
    limit: int = Query(default=12, ge=1, le=50),
    current_user: AuthUser = Depends(require_user),
) -> CommunityFeedResponse:
    return community_service.list_feed(user_id=current_user.id, offset=offset, limit=limit)


@router.get("/recommendations", response_model=CommunityRecommendationsResponse)
async def recommendations(
    place_tag: str | None = Query(default=None, min_length=1, max_length=48),
    scene_type: str | None = Query(default=None, min_length=3, max_length=24),
    limit: int | None = Query(default=None, ge=1, le=32),
    current_user: AuthUser = Depends(require_user),
) -> CommunityRecommendationsResponse:
    return community_service.recommendations(
        user_id=current_user.id,
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


@router.post("/posts/{post_id}/likes", response_model=CommunityLikeResponse)
async def like_post(
    post_id: int,
    current_user: AuthUser = Depends(require_user),
) -> CommunityLikeResponse:
    return community_service.like_post(current_user.id, post_id)


@router.delete("/posts/{post_id}/likes", response_model=CommunityLikeResponse)
async def unlike_post(
    post_id: int,
    current_user: AuthUser = Depends(require_user),
) -> CommunityLikeResponse:
    return community_service.unlike_post(current_user.id, post_id)


@router.get("/posts/{post_id}/comments", response_model=CommunityCommentsResponse)
async def comments(
    post_id: int,
    offset: int = Query(default=0, ge=0),
    limit: int = Query(default=80, ge=1, le=80),
    current_user: AuthUser = Depends(require_user),
) -> CommunityCommentsResponse:
    return community_service.list_comments(
        user_id=current_user.id,
        post_id=post_id,
        offset=offset,
        limit=limit,
    )


@router.post("/posts/{post_id}/comments", response_model=CommunityCommentView)
async def add_comment(
    post_id: int,
    req: CommunityCommentCreateRequest,
    current_user: AuthUser = Depends(require_user),
) -> CommunityCommentView:
    return community_service.add_comment(user_id=current_user.id, post_id=post_id, text=req.text)


@router.delete("/comments/{comment_id}", response_model=CommunityDeleteResponse)
async def delete_comment(
    comment_id: int,
    current_user: AuthUser = Depends(require_user),
) -> CommunityDeleteResponse:
    return community_service.delete_comment(user_id=current_user.id, comment_id=comment_id)


@router.post("/remake/guide", response_model=CommunityRemakeGuideResponse)
async def remake_guide(
    req: CommunityRemakeGuideRequest,
    current_user: AuthUser = Depends(require_user),
) -> CommunityRemakeGuideResponse:
    return community_service.remake_guide(user_id=current_user.id, req=req)


@router.post("/compose", response_model=CommunityComposeResponse)
async def compose(
    req: CommunityComposeRequest,
    current_user: AuthUser = Depends(require_user),
) -> CommunityComposeResponse:
    return await community_service.compose_image(current_user.id, req)


@router.post("/compose/jobs", response_model=CommunityCreativeJobView)
async def compose_job_create(
    req: CommunityComposeRequest,
    current_user: AuthUser = Depends(require_user),
) -> CommunityCreativeJobView:
    return community_service.create_compose_job(current_user.id, req)


@router.post("/cocreate/compose", response_model=CommunityCocreateComposeResponse)
async def cocreate_compose(
    req: CommunityCocreateComposeRequest,
    current_user: AuthUser = Depends(require_user),
) -> CommunityCocreateComposeResponse:
    return await community_service.cocreate_compose(current_user.id, req)


@router.post("/cocreate/jobs", response_model=CommunityCreativeJobView)
async def cocreate_job_create(
    req: CommunityCocreateComposeRequest,
    current_user: AuthUser = Depends(require_user),
) -> CommunityCreativeJobView:
    return community_service.create_cocreate_job(current_user.id, req)


@router.get("/jobs/{job_id}", response_model=CommunityCreativeJobView)
async def get_creative_job(
    job_id: int,
    current_user: AuthUser = Depends(require_user),
) -> CommunityCreativeJobView:
    return community_service.get_creative_job(current_user.id, job_id)


@router.post("/jobs/{job_id}/retry", response_model=CommunityCreativeJobView)
async def retry_creative_job(
    job_id: int,
    current_user: AuthUser = Depends(require_user),
) -> CommunityCreativeJobView:
    return community_service.retry_creative_job(current_user.id, job_id)


@router.post("/jobs/{job_id}/cancel", response_model=CommunityCreativeJobView)
async def cancel_creative_job(
    job_id: int,
    current_user: AuthUser = Depends(require_user),
) -> CommunityCreativeJobView:
    return community_service.cancel_creative_job(current_user.id, job_id)
