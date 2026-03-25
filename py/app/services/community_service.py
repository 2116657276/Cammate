from __future__ import annotations

import base64
import io
import math
import time
import uuid
from pathlib import Path
from typing import Any

from fastapi import HTTPException
from PIL import Image, ImageOps

from app.core.config import SETTINGS
from app.core.database import open_db
from app.models.schemas import (
    CommunityComposeRequest,
    CommunityComposeResponse,
    CommunityFeedResponse,
    CommunityPostView,
    CommunityPublishRequest,
    CommunityRecommendationView,
    CommunityRecommendationsResponse,
)
from providers.image_edit_provider import DoubaoImageEditProvider

_VALID_SCENE_TYPES = {"portrait", "general", "landscape", "food", "night"}


def publish_post(user_id: int, req: CommunityPublishRequest) -> CommunityPostView:
    place_tag = _normalize_place_tag(req.place_tag)
    scene_type = _normalize_scene_type(req.scene_type)
    image_bytes = _prepare_jpeg_bytes(req.image_base64, max_side=SETTINGS.community_upload_max_side)

    conn = open_db()
    try:
        feedback = conn.execute(
            "SELECT id, user_id, rating, review_text FROM feedback WHERE id = ?",
            (req.feedback_id,),
        ).fetchone()
        if feedback is None:
            raise HTTPException(status_code=404, detail="feedback not found")
        if int(feedback["user_id"]) != user_id:
            raise HTTPException(status_code=403, detail="feedback does not belong to current user")

        review_text = str(feedback["review_text"] or "").strip()
        _ensure_safe_text(place_tag)
        _ensure_safe_text(review_text)

        upload_path = _save_upload_image(image_bytes)
        now = int(time.time())
        cur = conn.execute(
            """
            INSERT INTO community_posts(
                user_id, feedback_id, image_path, scene_type, place_tag,
                rating, review_text, created_at, moderation_status
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'published')
            """,
            (
                user_id,
                req.feedback_id,
                str(upload_path),
                scene_type,
                place_tag,
                int(feedback["rating"]),
                review_text,
                now,
            ),
        )
        post_id = int(cur.lastrowid)
        conn.commit()
    finally:
        conn.close()

    return get_post_or_404(post_id)


def get_post_or_404(post_id: int) -> CommunityPostView:
    conn = open_db()
    try:
        row = conn.execute(
            """
            SELECT
                p.id, p.user_id, u.nickname AS user_nickname, p.feedback_id, p.scene_type, p.place_tag,
                p.rating, p.review_text, p.created_at
            FROM community_posts p
            JOIN users u ON u.id = p.user_id
            WHERE p.id = ? AND p.moderation_status = 'published'
            """,
            (post_id,),
        ).fetchone()
    finally:
        conn.close()

    if row is None:
        raise HTTPException(status_code=404, detail="community post not found")
    return _row_to_post_view(row)


def list_feed(offset: int, limit: int) -> CommunityFeedResponse:
    safe_offset = max(0, offset)
    safe_limit = max(1, min(limit, 50))
    conn = open_db()
    try:
        rows = conn.execute(
            """
            SELECT
                p.id, p.user_id, u.nickname AS user_nickname, p.feedback_id, p.scene_type, p.place_tag,
                p.rating, p.review_text, p.created_at
            FROM community_posts p
            JOIN users u ON u.id = p.user_id
            WHERE p.moderation_status = 'published'
            ORDER BY p.created_at DESC, p.id DESC
            LIMIT ? OFFSET ?
            """,
            (safe_limit, safe_offset),
        ).fetchall()
    finally:
        conn.close()

    items = [_row_to_post_view(row) for row in rows]
    return CommunityFeedResponse(items=items, next_offset=safe_offset + len(items))


def recommendations(
    place_tag: str | None,
    scene_type: str | None,
    limit: int | None,
) -> CommunityRecommendationsResponse:
    normalized_place = _normalize_place_tag(place_tag or "") if (place_tag or "").strip() else None
    normalized_scene = _normalize_scene_type(scene_type or "general") if scene_type else None
    safe_limit = limit if limit is not None else SETTINGS.community_recommend_limit_default
    safe_limit = max(1, min(safe_limit, 32))

    conn = open_db()
    try:
        rows = conn.execute(
            """
            SELECT
                p.id, p.user_id, u.nickname AS user_nickname, p.feedback_id, p.scene_type, p.place_tag,
                p.rating, p.review_text, p.created_at
            FROM community_posts p
            JOIN users u ON u.id = p.user_id
            WHERE p.moderation_status = 'published'
            ORDER BY p.created_at DESC, p.id DESC
            LIMIT 500
            """
        ).fetchall()
    finally:
        conn.close()

    if not rows:
        return CommunityRecommendationsResponse(items=[])

    scored = []
    now = int(time.time())
    for row in rows:
        match = _match_score(
            post_place=str(row["place_tag"] or ""),
            post_scene=str(row["scene_type"] or ""),
            place_filter=normalized_place,
            scene_filter=normalized_scene,
        )
        freshness = _freshness_score(now=now, created_at=int(row["created_at"]))
        rating_component = max(1, min(5, int(row["rating"]))) / 5.0
        final_score = (0.65 * match) + (0.25 * rating_component) + (0.10 * freshness)
        scored.append((row, match, final_score))

    candidates, reason_template = _select_candidates_by_fallback(
        scored=scored,
        place_filter=normalized_place,
        scene_filter=normalized_scene,
    )
    candidates.sort(key=lambda item: (item[2], int(item[0]["created_at"])), reverse=True)
    items = []
    for row, _, score in candidates[:safe_limit]:
        items.append(
            CommunityRecommendationView(
                post=_row_to_post_view(row),
                score=max(0.0, min(1.0, round(score, 4))),
                reason=reason_template,
            )
        )
    return CommunityRecommendationsResponse(items=items)


def load_post_image(post_id: int) -> tuple[bytes, str]:
    conn = open_db()
    try:
        row = conn.execute(
            "SELECT image_path FROM community_posts WHERE id = ? AND moderation_status = 'published'",
            (post_id,),
        ).fetchone()
    finally:
        conn.close()

    if row is None:
        raise HTTPException(status_code=404, detail="community post not found")

    image_path = Path(str(row["image_path"] or "")).resolve()
    base_dir = SETTINGS.community_upload_dir.resolve()
    if not str(image_path).startswith(str(base_dir)):
        raise HTTPException(status_code=400, detail="invalid image path")
    if not image_path.exists():
        raise HTTPException(status_code=404, detail="image file not found")

    data = image_path.read_bytes()
    mime = "image/png" if image_path.suffix.lower() == ".png" else "image/jpeg"
    return data, mime


async def compose_image(req: CommunityComposeRequest) -> CommunityComposeResponse:
    conn = open_db()
    try:
        row = conn.execute(
            """
            SELECT id, scene_type, place_tag, image_path
            FROM community_posts
            WHERE id = ? AND moderation_status = 'published'
            """,
            (req.reference_post_id,),
        ).fetchone()
    finally:
        conn.close()

    if row is None:
        raise HTTPException(status_code=404, detail="reference post not found")

    reference_path = Path(str(row["image_path"] or "")).resolve()
    base_dir = SETTINGS.community_upload_dir.resolve()
    if not str(reference_path).startswith(str(base_dir)) or not reference_path.exists():
        raise HTTPException(status_code=404, detail="reference image not found")

    person_image = _prepare_jpeg_bytes(req.person_image_base64, max_side=SETTINGS.community_upload_max_side)
    reference_image = reference_path.read_bytes()
    merged_input = _build_compose_input(
        reference_bytes=reference_image,
        person_bytes=person_image,
        max_side=SETTINGS.community_upload_max_side,
    )
    merged_b64 = base64.b64encode(merged_input).decode("utf-8")

    provider = DoubaoImageEditProvider(config_prefix="COMMUNITY_IMAGE", fallback_prefix="ARK_IMAGE")
    scene = _normalize_scene_type(str(row["scene_type"] or "general"))
    place = _normalize_place_tag(str(row["place_tag"] or ""))
    compose_prompt = (
        "请将前景人物自然融合进背景照片，保持真实摄影感，不要卡通化。"
        f"场景类型：{scene}，地点标签：{place or 'unknown'}。"
        "允许微调光影、边缘、色温与透视，使人物与环境协调；"
        "禁止新增额外人物，禁止改变人物性别、年龄、种族与核心面部特征。"
    )
    try:
        result = await provider.retouch(
            image_base64=merged_b64,
            preset="portrait_beauty",
            strength=req.strength,
            scene_hint=scene,
            custom_prompt=compose_prompt,
        )
    except Exception as exc:
        raise HTTPException(status_code=502, detail=f"compose failed: {exc}") from exc

    return CommunityComposeResponse(
        composed_image_base64=result.image_base64,
        provider=result.provider,
        model=result.model,
    )


def _row_to_post_view(row: Any) -> CommunityPostView:
    return CommunityPostView(
        id=int(row["id"]),
        user_id=int(row["user_id"]),
        user_nickname=str(row["user_nickname"]),
        feedback_id=int(row["feedback_id"]),
        image_url=f"/community/posts/{int(row['id'])}/image",
        scene_type=str(row["scene_type"]),
        place_tag=str(row["place_tag"]),
        rating=max(1, min(5, int(row["rating"]))),
        review_text=str(row["review_text"] or ""),
        created_at=int(row["created_at"]),
    )


def _normalize_scene_type(scene_type: str) -> str:
    value = (scene_type or "").strip().lower()
    if value not in _VALID_SCENE_TYPES:
        return "general"
    return value


def _normalize_place_tag(text: str) -> str:
    compact = " ".join((text or "").strip().split())
    return compact[:48]


def _ensure_safe_text(text: str) -> None:
    normalized = (text or "").strip().lower()
    if not normalized:
        return
    for bad in SETTINGS.community_blocked_words:
        if bad and bad in normalized:
            raise HTTPException(status_code=400, detail="text contains blocked words")


def _prepare_jpeg_bytes(image_base64: str, max_side: int) -> bytes:
    cleaned = image_base64.strip()
    if cleaned.startswith("data:image") and "," in cleaned:
        _, cleaned = cleaned.split(",", 1)
    try:
        raw = base64.b64decode(cleaned, validate=True)
    except Exception as exc:
        raise HTTPException(status_code=400, detail="invalid image_base64") from exc
    if not raw:
        raise HTTPException(status_code=400, detail="empty image")

    try:
        with Image.open(io.BytesIO(raw)) as image:
            display = ImageOps.exif_transpose(image).convert("RGB")
            if max(display.size) > max_side:
                display.thumbnail((max_side, max_side), Image.Resampling.LANCZOS)
            out = io.BytesIO()
            display.save(out, format="JPEG", quality=92, optimize=True)
            data = out.getvalue()
            if not data:
                raise HTTPException(status_code=400, detail="empty normalized image")
            return data
    except HTTPException:
        raise
    except Exception as exc:
        raise HTTPException(status_code=400, detail="invalid image bytes") from exc


def _save_upload_image(image_bytes: bytes) -> Path:
    upload_dir = SETTINGS.community_upload_dir
    upload_dir.mkdir(parents=True, exist_ok=True)
    file_name = f"post_{int(time.time())}_{uuid.uuid4().hex[:10]}.jpg"
    path = (upload_dir / file_name).resolve()
    base_dir = upload_dir.resolve()
    if not str(path).startswith(str(base_dir)):
        raise HTTPException(status_code=500, detail="invalid upload path")
    path.write_bytes(image_bytes)
    return path


def _match_score(post_place: str, post_scene: str, place_filter: str | None, scene_filter: str | None) -> float:
    place_match = bool(place_filter) and post_place.strip().lower() == place_filter.strip().lower()
    scene_match = bool(scene_filter) and post_scene.strip().lower() == scene_filter.strip().lower()
    if place_filter and scene_filter:
        if place_match and scene_match:
            return 1.0
        if place_match or scene_match:
            return 0.7
        return 0.4
    if place_filter:
        return 1.0 if place_match else 0.4
    if scene_filter:
        return 1.0 if scene_match else 0.4
    return 0.4


def _select_candidates_by_fallback(
    scored: list[tuple[Any, float, float]],
    place_filter: str | None,
    scene_filter: str | None,
) -> tuple[list[tuple[Any, float, float]], str]:
    if place_filter and scene_filter:
        full = [item for item in scored if abs(item[1] - 1.0) < 1e-6]
        if full:
            return full, "地点与类型均匹配"
        single = [item for item in scored if abs(item[1] - 0.7) < 1e-6]
        if single:
            return single, "地点或类型匹配"
        return scored, "为你推荐高评分热门作品"
    if place_filter or scene_filter:
        matched = [item for item in scored if abs(item[1] - 1.0) < 1e-6]
        if matched:
            return matched, "筛选条件匹配"
        return scored, "筛选结果不足，展示热门作品"
    return scored, "综合热门推荐"


def _freshness_score(now: int, created_at: int) -> float:
    age_sec = max(0, now - created_at)
    age_days = age_sec / 86400.0
    return math.exp(-math.log(2) * (age_days / 30.0))


def _build_compose_input(reference_bytes: bytes, person_bytes: bytes, max_side: int) -> bytes:
    with Image.open(io.BytesIO(reference_bytes)) as ref_img:
        with Image.open(io.BytesIO(person_bytes)) as person_img:
            bg = ImageOps.exif_transpose(ref_img).convert("RGB")
            fg = ImageOps.exif_transpose(person_img).convert("RGBA")

            if max(bg.size) > max_side:
                bg.thumbnail((max_side, max_side), Image.Resampling.LANCZOS)

            max_fg_w = int(bg.width * 0.45)
            max_fg_h = int(bg.height * 0.62)
            fg.thumbnail((max_fg_w, max_fg_h), Image.Resampling.LANCZOS)

            if "A" in fg.getbands():
                alpha = fg.getchannel("A").point(lambda px: int(px * 0.95))
                fg.putalpha(alpha)

            x = (bg.width - fg.width) // 2
            y = bg.height - fg.height - int(bg.height * 0.06)
            y = max(0, min(y, bg.height - fg.height))

            canvas = bg.convert("RGBA")
            canvas.alpha_composite(fg, (x, y))
            merged = canvas.convert("RGB")
            out = io.BytesIO()
            merged.save(out, format="JPEG", quality=92, optimize=True)
            return out.getvalue()
