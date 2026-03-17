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
    session_meta: dict[str, Any] | None = None


class FeedbackResponse(BaseModel):
    feedback_id: int


@dataclass
class AuthUser:
    id: int
    email: str
    nickname: str
    token: str
