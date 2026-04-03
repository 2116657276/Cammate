from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Literal

from pydantic import BaseModel, Field


class ClientContext(BaseModel):
    rotation_degrees: int = 0
    lens_facing: Literal["back", "front"] = "back"
    exposure_compensation: int = 0
    capture_mode: Literal["auto", "portrait", "general", "food"] = "auto"
    scene_hint: str | None = Field(default=None, max_length=24)
    previous_tip_text: str | None = Field(default=None, max_length=120)
    recent_tip_texts: list[str] = Field(default_factory=list)
    subject_center_norm: list[float] | None = None
    subject_bbox_norm: list[float] | None = None
    frame_stable: bool = False
    stability_score: float = Field(default=0.0, ge=0.0, le=1.0)


class AnalyzeRequest(BaseModel):
    image_base64: str = Field(min_length=16)
    client_context: ClientContext = ClientContext()


class SceneDetectRequest(BaseModel):
    image_base64: str = Field(min_length=16)
    client_context: ClientContext = ClientContext()


class SceneDetectResponse(BaseModel):
    scene: Literal["portrait", "general", "landscape", "food", "night"] = "general"
    confidence: float = Field(default=0.5, ge=0.0, le=1.0)
    mode: Literal["auto", "portrait", "general", "food"] = "auto"
    bbox_norm: list[float] | None = None
    center_norm: list[float] | None = None


class AuthRegisterRequest(BaseModel):
    email: str
    password: str = Field(min_length=6, max_length=128)
    nickname: str | None = Field(default=None, max_length=32)


class AuthLoginRequest(BaseModel):
    email: str
    password: str = Field(min_length=6, max_length=128)


class UserView(BaseModel):
    id: int
    email: str
    nickname: str


class AuthResponse(BaseModel):
    user: UserView
    bearer_token: str
    expires_in_sec: int


class LogoutResponse(BaseModel):
    ok: bool


class RetouchRequest(BaseModel):
    image_base64: str = Field(min_length=16)
    preset: Literal[
        "bg_cleanup",
        "portrait_beauty",
        "color_grade",
        "natural",
        "portrait",
        "food",
        "night",
        "cinematic",
    ] = "portrait_beauty"
    strength: float = Field(default=0.35, ge=0.0, le=1.0)
    scene_hint: str | None = Field(default=None, max_length=24)
    custom_prompt: str | None = Field(default=None, max_length=240)


class RetouchResponse(BaseModel):
    retouched_image_base64: str
    provider: str
    model: str


class FeedbackRequest(BaseModel):
    rating: int = Field(ge=1, le=5)
    scene: str = Field(default="general", max_length=24)
    tip_text: str = Field(default="", max_length=120)
    photo_uri: str | None = Field(default=None, max_length=500)
    is_retouch: bool = False
    review_text: str = Field(default="", max_length=280)
    session_meta: dict[str, Any] | None = None


class FeedbackResponse(BaseModel):
    feedback_id: int


class CommunityPublishRequest(BaseModel):
    feedback_id: int = Field(ge=1)
    image_base64: str = Field(min_length=16)
    place_tag: str = Field(min_length=1, max_length=48)
    scene_type: Literal["portrait", "general", "landscape", "food", "night"] = "general"


class CommunityDirectPublishRequest(BaseModel):
    image_base64: str = Field(min_length=16)
    place_tag: str = Field(min_length=1, max_length=48)
    scene_type: Literal["portrait", "general", "landscape", "food", "night"] = "general"
    caption: str = Field(default="", max_length=280)
    review_text: str = Field(default="", max_length=280)
    rating: int | None = Field(default=None, ge=1, le=5)
    post_type: Literal["normal", "relay"] = "normal"
    relay_parent_post_id: int | None = Field(default=None, ge=1)
    style_template_post_id: int | None = Field(default=None, ge=1)


class CommunityRelayPublishRequest(BaseModel):
    image_base64: str = Field(min_length=16)
    place_tag: str = Field(min_length=1, max_length=48)
    scene_type: Literal["portrait", "general", "landscape", "food", "night"] = "general"
    caption: str = Field(default="", max_length=280)
    review_text: str = Field(default="", max_length=280)
    rating: int | None = Field(default=None, ge=1, le=5)
    relay_parent_post_id: int = Field(ge=1)
    style_template_post_id: int | None = Field(default=None, ge=1)


class CommunityRelayParentSummary(BaseModel):
    id: int
    user_nickname: str
    place_tag: str
    scene_type: str
    image_url: str


class CommunityPostView(BaseModel):
    id: int
    user_id: int
    user_nickname: str
    feedback_id: int | None = None
    image_url: str
    scene_type: str
    place_tag: str
    rating: int = Field(default=0, ge=0, le=5)
    review_text: str
    caption: str = ""
    post_type: Literal["normal", "relay"] = "normal"
    source_type: Literal["feedback_flow", "direct"] = "feedback_flow"
    like_count: int = Field(default=0, ge=0)
    comment_count: int = Field(default=0, ge=0)
    liked_by_me: bool = False
    style_template_post_id: int | None = None
    relay_parent_summary: CommunityRelayParentSummary | None = None
    created_at: int


class CommunityFeedResponse(BaseModel):
    items: list[CommunityPostView]
    next_offset: int
    has_more: bool = False


class CommunityRecommendationView(BaseModel):
    post: CommunityPostView
    score: float = Field(ge=0.0, le=1.0)
    reason: str


class CommunityRecommendationsResponse(BaseModel):
    items: list[CommunityRecommendationView]


class CommunityRemakeAnalyzeRequest(BaseModel):
    template_post_id: int = Field(ge=1)
    candidate_image_base64: str = Field(min_length=16)


class CommunityRemakeAnalyzeResponse(BaseModel):
    template_post_id: int
    pose_score: float = Field(ge=0.0, le=1.0)
    framing_score: float = Field(ge=0.0, le=1.0)
    alignment_score: float = Field(ge=0.0, le=1.0)
    mismatch_hints: list[str] = Field(default_factory=list)
    implementation_status: Literal["ready", "placeholder"] = "ready"
    placeholder_notes: list[str] = Field(default_factory=list)


class CommunityComposeRequest(BaseModel):
    reference_post_id: int = Field(ge=1)
    person_image_base64: str = Field(min_length=16)
    strength: float = Field(default=0.45, ge=0.0, le=1.0)


class CommunityComposeResponse(BaseModel):
    composed_image_base64: str
    provider: str
    model: str
    implementation_status: Literal["ready", "placeholder"] = "ready"
    compare_input_base64: str | None = None
    placeholder_notes: list[str] = Field(default_factory=list)


class CommunityCocreateComposeRequest(BaseModel):
    reference_post_id: int = Field(ge=1)
    person_a_image_base64: str = Field(min_length=16)
    person_b_image_base64: str = Field(min_length=16)
    strength: float = Field(default=0.45, ge=0.0, le=1.0)


class CommunityCocreateComposeResponse(BaseModel):
    composed_image_base64: str
    provider: str
    model: str
    implementation_status: Literal["ready", "placeholder"] = "ready"
    compare_input_base64: str | None = None
    placeholder_notes: list[str] = Field(default_factory=list)


class CommunityCreativeJobView(BaseModel):
    job_id: int
    job_type: Literal["compose", "cocreate", "remake_guide"]
    status: Literal["queued", "running", "success", "failed", "canceled"]
    progress: int = Field(ge=0, le=100)
    priority: int = Field(default=100, ge=1, le=999)
    retry_count: int = Field(default=0, ge=0)
    max_retries: int = Field(default=0, ge=0)
    next_retry_at: int | None = None
    started_at: int | None = None
    heartbeat_at: int | None = None
    lease_expires_at: int | None = None
    cancel_reason: str = ""
    implementation_status: Literal["ready", "placeholder"] = "ready"
    provider: str = ""
    model: str = ""
    composed_image_base64: str | None = None
    compare_input_base64: str | None = None
    storage_mode: Literal["database", "file", "hybrid"] = "database"
    error_code: str = ""
    placeholder_notes: list[str] = Field(default_factory=list)
    error_message: str = ""
    request_id: str = ""
    created_at: int
    updated_at: int
    finished_at: int | None = None


class CommunityLikeResponse(BaseModel):
    post_id: int
    liked: bool
    like_count: int = Field(ge=0)


class CommunityCommentCreateRequest(BaseModel):
    text: str = Field(min_length=1, max_length=280)


class CommunityCommentView(BaseModel):
    id: int
    post_id: int
    user_id: int
    user_nickname: str
    text: str
    created_at: int
    can_delete: bool = False


class CommunityCommentsResponse(BaseModel):
    items: list[CommunityCommentView]
    next_offset: int = 0
    has_more: bool = False


class CommunityDeleteResponse(BaseModel):
    ok: bool


class CommunityReportCreateRequest(BaseModel):
    reason: str = Field(min_length=1, max_length=80)
    detail_text: str = Field(default="", max_length=280)


class CommunityReportView(BaseModel):
    id: int
    post_id: int
    reporter_user_id: int
    reason: str
    detail_text: str = ""
    status: str
    moderation_action: str = ""
    resolution_note: str = ""
    created_at: int
    resolved_at: int | None = None


class CommunityModerationActionRequest(BaseModel):
    action: Literal["hide", "restore", "delete", "ignore"]
    resolution_note: str = Field(default="", max_length=280)


class CommunityRemakeGuideRequest(BaseModel):
    template_post_id: int = Field(ge=1)


class CommunityRemakeGuideResponse(BaseModel):
    template_post: CommunityPostView
    shot_script: list[str]
    camera_hint: str
    pose_hint: str = ""
    framing_hint: str = ""
    timing_hint: str = ""
    alignment_checks: list[str] = Field(default_factory=list)
    implementation_status: Literal["ready", "placeholder"] = "ready"
    placeholder_notes: list[str] = Field(default_factory=list)


@dataclass
class AuthUser:
    id: int
    email: str
    nickname: str
    token: str
