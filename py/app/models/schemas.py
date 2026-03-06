from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Literal

from pydantic import BaseModel, Field


class ClientContext(BaseModel):
    rotation_degrees: int = 0
    lens_facing: Literal["back", "front"] = "back"
    exposure_compensation: int = 0


class AnalyzeRequest(BaseModel):
    image_base64: str = Field(min_length=16)
    client_context: ClientContext = ClientContext()


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
    preset: Literal["natural", "portrait", "food", "night", "cinematic"] = "natural"
    strength: float = Field(default=0.6, ge=0.0, le=1.0)
    scene_hint: str | None = Field(default=None, max_length=24)


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
