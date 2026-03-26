from __future__ import annotations

import base64
import io
import json
import logging
import math
import queue
import time
import uuid
from pathlib import Path
from threading import Event, Lock, Thread
from typing import Any

from fastapi import HTTPException
from PIL import Image, ImageDraw, ImageOps

from app.core.config import SETTINGS
from app.core.database import open_db
from app.models.schemas import (
    CommunityCocreateComposeRequest,
    CommunityCocreateComposeResponse,
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
    CommunityRecommendationView,
    CommunityRecommendationsResponse,
    CommunityRelayParentSummary,
    CommunityRelayPublishRequest,
    CommunityRemakeGuideRequest,
    CommunityRemakeGuideResponse,
)
from providers.image_edit_provider import DoubaoImageEditProvider

_VALID_SCENE_TYPES = {"portrait", "general", "landscape", "food", "night"}
_VALID_POST_TYPES = {"normal", "relay"}
_SEED_LOCK = Lock()
_JOB_LOCK = Lock()
_JOB_QUEUE: queue.Queue[int] = queue.Queue()
_JOB_WORKERS_STARTED = False
_JOB_WORKER_THREADS: list[Thread] = []
_CREATIVE_PROVIDER_TIMEOUT_SEC = 12.0
_CREATIVE_HEARTBEAT_SEC = 2.0
_CREATIVE_JOB_LEASE_SEC = max(18, int(_CREATIVE_PROVIDER_TIMEOUT_SEC) + 8)
_CREATIVE_JOB_HARD_TIMEOUT_SEC = max(20, int(_CREATIVE_PROVIDER_TIMEOUT_SEC) + 10)
_CREATIVE_RECOVERY_INTERVAL_SEC = 10
_CREATIVE_FAILED_RECENT_WINDOW_SEC = 1800
_CREATIVE_RETRY_MAX = max(0, int(SETTINGS.community_creative_max_retries))
_CREATIVE_RETRY_BASE_SEC = max(1, int(SETTINGS.community_creative_retry_base_sec))
_CREATIVE_DEFAULT_PRIORITY = 100
_SEED_NICKNAMES = ["阿琳", "小舟", "Mia", "Kai", "Luna", "River"]
_SEED_PLACE_TAGS = ["外滩", "海边栈道", "城市天台", "松林步道", "老街巷口", "湖边长椅"]
_SEED_SCENES = ["portrait", "general", "landscape", "food", "night"]
_SEED_REVIEWS = [
    "这个机位光线很柔和，傍晚出片率很高。",
    "背景层次不错，人物和环境都能保住细节。",
    "风有点大，建议连拍挑表情。",
    "夜景灯牌很出片，注意曝光别拉太高。",
    "走两步换角度会更有纵深感。",
    "这个地点适合半身和全身都拍一组。",
]
_SEED_TIPS = [
    "镜头稍微下压，留更多环境空间。",
    "主体站在三分线附近更稳定。",
    "背景高光偏多，建议降低曝光。",
    "保持手机水平，画面观感更干净。",
]
logger = logging.getLogger("app.community")


class _CreativeJobCanceledError(Exception):
    pass


# -----------------------------
# Posts
# -----------------------------


def bootstrap_creative_runtime() -> None:
    """Start creative workers and run stale-job recovery once at service startup."""
    _ensure_job_worker_started()


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
                rating, review_text, caption, post_type, source_type,
                relay_parent_post_id, style_template_post_id,
                like_count, comment_count, created_at, moderation_status
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'normal', 'feedback_flow', NULL, NULL, 0, 0, ?, 'published')
            """,
            (
                user_id,
                req.feedback_id,
                str(upload_path),
                scene_type,
                place_tag,
                int(feedback["rating"]),
                review_text,
                "",
                now,
            ),
        )
        post_id = int(cur.lastrowid)
        conn.commit()
    finally:
        conn.close()

    return get_post_or_404(post_id, current_user_id=user_id)


def publish_direct_post(user_id: int, req: CommunityDirectPublishRequest) -> CommunityPostView:
    scene_type = _normalize_scene_type(req.scene_type)
    post_type = _normalize_post_type(req.post_type)
    place_tag = _normalize_place_tag(req.place_tag)
    caption = _normalize_caption(req.caption)
    review_text = _normalize_review(req.review_text)
    rating = int(req.rating) if req.rating is not None else 0
    relay_parent_post_id = int(req.relay_parent_post_id) if req.relay_parent_post_id else None
    style_template_post_id = int(req.style_template_post_id) if req.style_template_post_id else None

    if post_type == "relay" and relay_parent_post_id is None:
        raise HTTPException(status_code=400, detail="relay_parent_post_id required for relay post")

    _ensure_safe_text(place_tag)
    _ensure_safe_text(caption)
    _ensure_safe_text(review_text)

    image_bytes = _prepare_jpeg_bytes(req.image_base64, max_side=SETTINGS.community_upload_max_side)

    conn = open_db()
    try:
        if relay_parent_post_id is not None:
            _ensure_post_exists(conn, relay_parent_post_id)
        if style_template_post_id is not None:
            _ensure_post_exists(conn, style_template_post_id)

        feedback_id: int | None = None
        if _community_posts_feedback_id_required(conn):
            # 兼容历史数据库：如果 feedback_id 仍是 NOT NULL，暂时保留兜底写入。
            # TODO(cammate-v1.2): 清理历史库后移除该兼容分支。
            feedback_id = _create_synthetic_feedback_for_direct(
                conn=conn,
                user_id=user_id,
                scene=scene_type,
                rating=rating,
                review_text=review_text,
                style_template_post_id=style_template_post_id,
            )

        upload_path = _save_upload_image(image_bytes)
        now = int(time.time())
        cur = conn.execute(
            """
            INSERT INTO community_posts(
                user_id, feedback_id, image_path, scene_type, place_tag,
                rating, review_text, caption, post_type, source_type,
                relay_parent_post_id, style_template_post_id,
                like_count, comment_count, created_at, moderation_status
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'direct', ?, ?, 0, 0, ?, 'published')
            """,
            (
                user_id,
                feedback_id,
                str(upload_path),
                scene_type,
                place_tag,
                rating,
                review_text,
                caption,
                post_type,
                relay_parent_post_id,
                style_template_post_id,
                now,
            ),
        )
        post_id = int(cur.lastrowid)
        conn.commit()
    finally:
        conn.close()

    return get_post_or_404(post_id, current_user_id=user_id)


def publish_relay_post(user_id: int, req: CommunityRelayPublishRequest) -> CommunityPostView:
    direct_req = CommunityDirectPublishRequest(
        image_base64=req.image_base64,
        place_tag=req.place_tag,
        scene_type=req.scene_type,
        caption=req.caption,
        review_text=req.review_text,
        rating=req.rating,
        post_type="relay",
        relay_parent_post_id=req.relay_parent_post_id,
        style_template_post_id=req.style_template_post_id,
    )
    return publish_direct_post(user_id=user_id, req=direct_req)


def get_post_or_404(post_id: int, current_user_id: int | None = None) -> CommunityPostView:
    user_id = int(current_user_id or 0)
    conn = open_db()
    try:
        row = conn.execute(
            """
            SELECT
                p.id, p.user_id, u.nickname AS user_nickname, p.feedback_id, p.scene_type, p.place_tag,
                p.rating, p.review_text, p.caption, p.post_type, p.source_type,
                p.relay_parent_post_id, p.style_template_post_id,
                p.like_count, p.comment_count, p.created_at,
                EXISTS(
                    SELECT 1 FROM community_post_likes l
                    WHERE l.post_id = p.id AND l.user_id = ?
                ) AS liked_by_me
            FROM community_posts p
            JOIN users u ON u.id = p.user_id
            WHERE p.id = ? AND p.moderation_status = 'published'
            """,
            (user_id, post_id),
        ).fetchone()
        if row is None:
            raise HTTPException(status_code=404, detail="community post not found")
        return _row_to_post_view(conn, row)
    finally:
        conn.close()


def list_feed(user_id: int, offset: int, limit: int) -> CommunityFeedResponse:
    _ensure_seed_posts_if_needed()
    safe_offset = max(0, offset)
    safe_limit = max(1, min(limit, 50))

    conn = open_db()
    try:
        rows = conn.execute(
            """
            SELECT
                p.id, p.user_id, u.nickname AS user_nickname, p.feedback_id, p.scene_type, p.place_tag,
                p.rating, p.review_text, p.caption, p.post_type, p.source_type,
                p.relay_parent_post_id, p.style_template_post_id,
                p.like_count, p.comment_count, p.created_at,
                EXISTS(
                    SELECT 1 FROM community_post_likes l
                    WHERE l.post_id = p.id AND l.user_id = ?
                ) AS liked_by_me
            FROM community_posts p
            JOIN users u ON u.id = p.user_id
            WHERE p.moderation_status = 'published'
            ORDER BY p.created_at DESC, p.id DESC
            LIMIT ? OFFSET ?
            """,
            (user_id, safe_limit, safe_offset),
        ).fetchall()

        items = [_row_to_post_view(conn, row) for row in rows]
        return CommunityFeedResponse(items=items, next_offset=safe_offset + len(items))
    finally:
        conn.close()


# -----------------------------
# Recommendation
# -----------------------------


def recommendations(
    user_id: int,
    place_tag: str | None,
    scene_type: str | None,
    limit: int | None,
) -> CommunityRecommendationsResponse:
    _ensure_seed_posts_if_needed()
    normalized_place = _normalize_place_tag(place_tag or "") if (place_tag or "").strip() else None
    normalized_scene = _normalize_scene_type(scene_type or "general") if scene_type else None
    safe_limit = limit if limit is not None else SETTINGS.community_recommend_limit_default
    safe_limit = max(1, min(safe_limit, 32))

    # SQLite MVP 阶段先拉取固定窗口再在应用层计算分数，便于快速迭代规则。
    # 后续迁移 MySQL 后可下推到 SQL 做排序与回退，降低应用层开销。
    conn = open_db()
    try:
        rows = conn.execute(
            """
            SELECT
                p.id, p.user_id, u.nickname AS user_nickname, p.feedback_id, p.scene_type, p.place_tag,
                p.rating, p.review_text, p.caption, p.post_type, p.source_type,
                p.relay_parent_post_id, p.style_template_post_id,
                p.like_count, p.comment_count, p.created_at,
                EXISTS(
                    SELECT 1 FROM community_post_likes l
                    WHERE l.post_id = p.id AND l.user_id = ?
                ) AS liked_by_me
            FROM community_posts p
            JOIN users u ON u.id = p.user_id
            WHERE p.moderation_status = 'published'
            ORDER BY p.created_at DESC, p.id DESC
            LIMIT 500
            """,
            (user_id,),
        ).fetchall()

        if not rows:
            return CommunityRecommendationsResponse(items=[])

        scored: list[tuple[Any, float, float]] = []
        now = int(time.time())
        for row in rows:
            match = _match_score(
                post_place=str(row["place_tag"] or ""),
                post_scene=str(row["scene_type"] or ""),
                place_filter=normalized_place,
                scene_filter=normalized_scene,
            )
            freshness = _freshness_score(now=now, created_at=int(row["created_at"]))
            rating_component = max(0, min(5, int(row["rating"] or 0))) / 5.0
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
                    post=_row_to_post_view(conn, row),
                    score=max(0.0, min(1.0, round(score, 4))),
                    reason=reason_template,
                )
            )
        return CommunityRecommendationsResponse(items=items)
    finally:
        conn.close()


# -----------------------------
# Interactions
# -----------------------------


def like_post(user_id: int, post_id: int) -> CommunityLikeResponse:
    conn = open_db()
    try:
        _ensure_post_exists(conn, post_id)
        now = int(time.time())
        conn.execute(
            """
            INSERT OR IGNORE INTO community_post_likes(post_id, user_id, created_at)
            VALUES (?, ?, ?)
            """,
            (post_id, user_id, now),
        )
        like_count = _refresh_post_like_count(conn, post_id)
        conn.commit()
        return CommunityLikeResponse(post_id=post_id, liked=True, like_count=like_count)
    finally:
        conn.close()


def unlike_post(user_id: int, post_id: int) -> CommunityLikeResponse:
    conn = open_db()
    try:
        _ensure_post_exists(conn, post_id)
        conn.execute(
            "DELETE FROM community_post_likes WHERE post_id = ? AND user_id = ?",
            (post_id, user_id),
        )
        like_count = _refresh_post_like_count(conn, post_id)
        conn.commit()
        return CommunityLikeResponse(post_id=post_id, liked=False, like_count=like_count)
    finally:
        conn.close()


def list_comments(user_id: int, post_id: int, offset: int = 0, limit: int = 80) -> CommunityCommentsResponse:
    safe_offset = max(0, int(offset))
    safe_limit = max(1, min(int(limit), 80))
    query_limit = safe_limit + 1

    conn = open_db()
    try:
        _ensure_post_exists(conn, post_id)
        rows = conn.execute(
            """
            SELECT
                c.id, c.post_id, c.user_id, u.nickname AS user_nickname,
                c.text, c.created_at
            FROM community_post_comments c
            JOIN users u ON u.id = c.user_id
            WHERE c.post_id = ?
            ORDER BY c.created_at DESC, c.id DESC
            LIMIT ? OFFSET ?
            """,
            (post_id, query_limit, safe_offset),
        ).fetchall()

        has_more = len(rows) > safe_limit
        visible_rows = rows[:safe_limit]
        next_offset = safe_offset + len(visible_rows)

        return CommunityCommentsResponse(
            items=[
                CommunityCommentView(
                    id=int(row["id"]),
                    post_id=int(row["post_id"]),
                    user_id=int(row["user_id"]),
                    user_nickname=str(row["user_nickname"]),
                    text=str(row["text"] or ""),
                    created_at=int(row["created_at"]),
                    can_delete=int(row["user_id"]) == user_id,
                )
                for row in visible_rows
            ],
            next_offset=next_offset,
            has_more=has_more,
        )
    finally:
        conn.close()


def add_comment(user_id: int, post_id: int, text: str) -> CommunityCommentView:
    safe_text = (text or "").strip()
    if not safe_text:
        raise HTTPException(status_code=400, detail="comment text is empty")
    _ensure_safe_text(safe_text)

    conn = open_db()
    try:
        _ensure_post_exists(conn, post_id)
        now = int(time.time())
        cur = conn.execute(
            """
            INSERT INTO community_post_comments(post_id, user_id, text, created_at)
            VALUES (?, ?, ?, ?)
            """,
            (post_id, user_id, safe_text, now),
        )
        comment_id = int(cur.lastrowid)
        _refresh_post_comment_count(conn, post_id)

        row = conn.execute(
            """
            SELECT
                c.id, c.post_id, c.user_id, u.nickname AS user_nickname,
                c.text, c.created_at
            FROM community_post_comments c
            JOIN users u ON u.id = c.user_id
            WHERE c.id = ?
            """,
            (comment_id,),
        ).fetchone()
        conn.commit()
        if row is None:
            raise HTTPException(status_code=500, detail="comment creation failed")

        return CommunityCommentView(
            id=int(row["id"]),
            post_id=int(row["post_id"]),
            user_id=int(row["user_id"]),
            user_nickname=str(row["user_nickname"]),
            text=str(row["text"] or ""),
            created_at=int(row["created_at"]),
            can_delete=True,
        )
    finally:
        conn.close()


def delete_comment(user_id: int, comment_id: int) -> CommunityDeleteResponse:
    conn = open_db()
    try:
        row = conn.execute(
            "SELECT id, post_id, user_id FROM community_post_comments WHERE id = ?",
            (comment_id,),
        ).fetchone()
        if row is None:
            raise HTTPException(status_code=404, detail="comment not found")
        if int(row["user_id"]) != user_id:
            raise HTTPException(status_code=403, detail="comment does not belong to current user")

        post_id = int(row["post_id"])
        conn.execute("DELETE FROM community_post_comments WHERE id = ?", (comment_id,))
        _refresh_post_comment_count(conn, post_id)
        conn.commit()
        return CommunityDeleteResponse(ok=True)
    finally:
        conn.close()


# -----------------------------
# Creative
# -----------------------------


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
    if base_dir not in image_path.parents:
        raise HTTPException(status_code=400, detail="invalid image path")
    if not image_path.exists():
        raise HTTPException(status_code=404, detail="image file not found")

    data = image_path.read_bytes()
    mime = "image/png" if image_path.suffix.lower() == ".png" else "image/jpeg"
    return data, mime


async def compose_image(user_id: int, req: CommunityComposeRequest) -> CommunityComposeResponse:
    result = await _compose_core(req)
    _record_creative_job(
        user_id=user_id,
        job_type="compose",
        reference_post_id=req.reference_post_id,
        implementation_status=result.implementation_status,
        request_payload={"strength": req.strength},
        result_meta={"provider": result.provider, "model": result.model},
    )
    return result


async def cocreate_compose(user_id: int, req: CommunityCocreateComposeRequest) -> CommunityCocreateComposeResponse:
    result = await _cocreate_core(req)
    _record_creative_job(
        user_id=user_id,
        job_type="cocreate",
        reference_post_id=req.reference_post_id,
        implementation_status=result.implementation_status,
        request_payload={"strength": req.strength},
        result_meta={"provider": result.provider, "model": result.model},
    )
    return result


def create_compose_job(user_id: int, req: CommunityComposeRequest) -> CommunityCreativeJobView:
    payload = {
        "reference_post_id": req.reference_post_id,
        "person_image_base64": req.person_image_base64,
        "strength": req.strength,
    }
    return _create_creative_job(
        user_id=user_id,
        job_type="compose",
        reference_post_id=req.reference_post_id,
        payload=payload,
    )


def create_cocreate_job(user_id: int, req: CommunityCocreateComposeRequest) -> CommunityCreativeJobView:
    payload = {
        "reference_post_id": req.reference_post_id,
        "person_a_image_base64": req.person_a_image_base64,
        "person_b_image_base64": req.person_b_image_base64,
        "strength": req.strength,
    }
    return _create_creative_job(
        user_id=user_id,
        job_type="cocreate",
        reference_post_id=req.reference_post_id,
        payload=payload,
    )


def get_creative_job(user_id: int, job_id: int) -> CommunityCreativeJobView:
    conn = open_db()
    try:
        row = conn.execute(
            """
            SELECT *
            FROM community_creative_jobs
            WHERE id = ? AND user_id = ?
            """,
            (job_id, user_id),
        ).fetchone()
        if row is None:
            raise HTTPException(status_code=404, detail="creative job not found")
        return _row_to_creative_job_view(row)
    finally:
        conn.close()


def retry_creative_job(user_id: int, job_id: int) -> CommunityCreativeJobView:
    conn = open_db()
    try:
        row = conn.execute(
            """
            SELECT *
            FROM community_creative_jobs
            WHERE id = ? AND user_id = ?
            """,
            (job_id, user_id),
        ).fetchone()
        if row is None:
            raise HTTPException(status_code=404, detail="creative job not found")
        status = str(row["status"] or "")
        if status != "failed":
            raise HTTPException(status_code=409, detail="only failed jobs can be retried")

        now = int(time.time())
        conn.execute(
            """
            UPDATE community_creative_jobs
            SET status = 'queued',
                progress = 0,
                priority = ?,
                retry_count = 0,
                max_retries = ?,
                next_retry_at = NULL,
                started_at = NULL,
                heartbeat_at = NULL,
                lease_expires_at = NULL,
                cancel_requested = 0,
                cancel_reason = '',
                implementation_status = 'ready',
                result_meta = '{}',
                result_image_base64 = NULL,
                compare_input_base64 = NULL,
                provider = '',
                model = '',
                error_message = '',
                request_id = '',
                placeholder_notes = '[]',
                updated_at = ?,
                finished_at = NULL
            WHERE id = ?
            """,
            (_CREATIVE_DEFAULT_PRIORITY, _CREATIVE_RETRY_MAX, now, job_id),
        )
        conn.commit()
    finally:
        conn.close()

    _enqueue_creative_job(job_id)
    return get_creative_job(user_id=user_id, job_id=job_id)


def cancel_creative_job(user_id: int, job_id: int) -> CommunityCreativeJobView:
    conn = open_db()
    try:
        row = conn.execute(
            """
            SELECT id, status
            FROM community_creative_jobs
            WHERE id = ? AND user_id = ?
            """,
            (job_id, user_id),
        ).fetchone()
        if row is None:
            raise HTTPException(status_code=404, detail="creative job not found")

        status = str(row["status"] or "")
        if status in {"success", "failed", "canceled"}:
            raise HTTPException(status_code=409, detail="job already finished")
        if status not in {"queued", "running"}:
            raise HTTPException(status_code=409, detail="job status cannot be canceled")

        now = int(time.time())
        reason = "cancelled by user"
        if status == "queued":
            conn.execute(
                """
                UPDATE community_creative_jobs
                SET status = 'canceled',
                    progress = 100,
                    cancel_requested = 1,
                    cancel_reason = ?,
                    next_retry_at = NULL,
                    heartbeat_at = ?,
                    lease_expires_at = NULL,
                    error_message = ?,
                    updated_at = ?,
                    finished_at = ?
                WHERE id = ? AND status = 'queued'
                """,
                (reason, now, reason, now, now, job_id),
            )
        else:
            conn.execute(
                """
                UPDATE community_creative_jobs
                SET cancel_requested = 1,
                    cancel_reason = ?,
                    updated_at = ?
                WHERE id = ? AND status = 'running'
                """,
                (reason, now, job_id),
            )
        conn.commit()
    finally:
        conn.close()
    return get_creative_job(user_id=user_id, job_id=job_id)


def remake_guide(user_id: int, req: CommunityRemakeGuideRequest) -> CommunityRemakeGuideResponse:
    template_post = get_post_or_404(req.template_post_id, current_user_id=user_id)
    shot_script = _build_remake_script(
        scene_type=template_post.scene_type,
        place_tag=template_post.place_tag,
        post_type=template_post.post_type,
    )
    camera_hint = _build_camera_hint(template_post.scene_type, template_post.place_tag)

    _record_creative_job(
        user_id=user_id,
        job_type="remake_guide",
        reference_post_id=req.template_post_id,
        implementation_status="ready",
        request_payload={},
        result_meta={"steps": len(shot_script)},
    )

    return CommunityRemakeGuideResponse(
        template_post=template_post,
        shot_script=shot_script,
        camera_hint=camera_hint,
        implementation_status="ready",
        placeholder_notes=[
            "TODO(cammate-v1.2): 增加人体关键点匹配与实时姿态纠偏。",
        ],
    )


# -----------------------------
# Creative job helpers
# -----------------------------


def _create_creative_job(
    user_id: int,
    job_type: str,
    reference_post_id: int,
    payload: dict[str, Any],
) -> CommunityCreativeJobView:
    safe_job_type = (job_type or "").strip().lower()
    if safe_job_type not in {"compose", "cocreate"}:
        raise HTTPException(status_code=400, detail="unsupported creative job type")

    conn = open_db()
    try:
        _ensure_post_exists(conn, reference_post_id)
        now = int(time.time())
        priority = _priority_for_job_type(safe_job_type)
        cur = conn.execute(
            """
            INSERT INTO community_creative_jobs(
                user_id, job_type, reference_post_id, status, progress, priority,
                retry_count, max_retries, next_retry_at, started_at, heartbeat_at, lease_expires_at,
                cancel_requested, cancel_reason,
                implementation_status, request_payload, result_meta,
                created_at, updated_at
            ) VALUES (?, ?, ?, 'queued', 0, ?, 0, ?, NULL, NULL, NULL, NULL, 0, '', 'ready', ?, '{}', ?, ?)
            """,
            (
                user_id,
                safe_job_type,
                reference_post_id,
                priority,
                _CREATIVE_RETRY_MAX,
                json.dumps(payload, ensure_ascii=False),
                now,
                now,
            ),
        )
        job_id = int(cur.lastrowid)
        conn.commit()
    finally:
        conn.close()

    _enqueue_creative_job(job_id)
    return get_creative_job(user_id=user_id, job_id=job_id)


def _enqueue_creative_job(job_id: int) -> None:
    _ensure_job_worker_started()
    _JOB_QUEUE.put(max(0, int(job_id)))


def _enqueue_creative_job_with_delay(job_id: int, delay_sec: float) -> None:
    safe_delay = max(0.0, float(delay_sec))
    if safe_delay <= 0.01:
        _enqueue_creative_job(job_id)
        return

    def _delayed_enqueue() -> None:
        time.sleep(safe_delay)
        _enqueue_creative_job(job_id)

    Thread(target=_delayed_enqueue, name=f"community-job-delay-{job_id}", daemon=True).start()


def _ensure_job_worker_started() -> None:
    global _JOB_WORKERS_STARTED
    with _JOB_LOCK:
        if _JOB_WORKERS_STARTED:
            return
        worker_count = max(1, int(SETTINGS.community_creative_worker_count))
        for index in range(worker_count):
            worker = Thread(
                target=_job_worker_loop,
                args=(index + 1,),
                name=f"community-creative-worker-{index + 1}",
                daemon=True,
            )
            worker.start()
            _JOB_WORKER_THREADS.append(worker)
        _JOB_WORKERS_STARTED = True
        _recover_stale_running_jobs()
        for _ in range(max(1, worker_count)):
            _JOB_QUEUE.put(0)
        logger.info("community.creative.worker.started worker_count=%d", worker_count)


def _job_worker_loop(worker_index: int) -> None:
    last_recover_at = 0
    while True:
        token: int | None = None
        try:
            try:
                token = _JOB_QUEUE.get(timeout=1.0)
            except queue.Empty:
                token = None

            now = int(time.time())
            if worker_index == 1 and now - last_recover_at >= _CREATIVE_RECOVERY_INTERVAL_SEC:
                _recover_stale_running_jobs()
                last_recover_at = now

            _process_creative_job(trigger_job_id=(token if token and token > 0 else None))
        except Exception as exc:
            logger.exception(
                "community.creative.worker.error worker=%d job_id=%s reason=%r",
                worker_index,
                token,
                exc,
            )
        finally:
            if token is not None:
                _JOB_QUEUE.task_done()


def _process_creative_job(trigger_job_id: int | None = None) -> None:
    claimed = _claim_next_creative_job(trigger_job_id=trigger_job_id)
    if claimed is None:
        return
    job_id, job_type, payload = claimed

    try:
        request_id = f"creative_{uuid.uuid4().hex[:14]}"
        _raise_if_job_cancel_requested(job_id)
        if job_type == "compose":
            req = CommunityComposeRequest.model_validate(payload)
            result = _run_async_with_heartbeat(job_id, _compose_core(req))
            _raise_if_job_cancel_requested(job_id)
            _mark_job_success(
                job_id=job_id,
                request_id=request_id,
                provider=result.provider,
                model=result.model,
                implementation_status=result.implementation_status,
                composed_image_base64=result.composed_image_base64,
                compare_input_base64=result.compare_input_base64,
                placeholder_notes=result.placeholder_notes,
            )
        elif job_type == "cocreate":
            req = CommunityCocreateComposeRequest.model_validate(payload)
            result = _run_async_with_heartbeat(job_id, _cocreate_core(req))
            _raise_if_job_cancel_requested(job_id)
            _mark_job_success(
                job_id=job_id,
                request_id=request_id,
                provider=result.provider,
                model=result.model,
                implementation_status=result.implementation_status,
                composed_image_base64=result.composed_image_base64,
                compare_input_base64=result.compare_input_base64,
                placeholder_notes=result.placeholder_notes,
            )
        else:
            _handle_job_failure(
                job_id=job_id,
                error_message=f"unsupported job type: {job_type}",
                retryable=False,
            )
    except _CreativeJobCanceledError as exc:
        _finalize_running_job_canceled(job_id=job_id, reason=str(exc) or "cancelled by user")
    except TimeoutError as exc:
        _handle_job_failure(
            job_id=job_id,
            error_message=str(exc)[:240] or "creative job timeout",
            retryable=True,
        )
    except HTTPException as exc:
        _handle_job_failure(
            job_id=job_id,
            error_message=str(exc.detail),
            retryable=False,
        )
    except Exception as exc:
        _handle_job_failure(
            job_id=job_id,
            error_message=str(exc)[:240] or "creative job failed",
            retryable=True,
        )


def _claim_next_creative_job(trigger_job_id: int | None = None) -> tuple[int, str, dict[str, Any]] | None:
    _ = trigger_job_id
    now = int(time.time())
    conn = open_db()
    try:
        for _ in range(3):
            row = conn.execute(
                """
                SELECT id, job_type, request_payload
                FROM community_creative_jobs
                WHERE status = 'queued'
                  AND cancel_requested = 0
                  AND (next_retry_at IS NULL OR next_retry_at <= ?)
                ORDER BY priority DESC, created_at ASC, id ASC
                LIMIT 1
                """,
                (now,),
            ).fetchone()
            if row is None:
                return None

            job_id = int(row["id"])
            cur = conn.execute(
                """
                UPDATE community_creative_jobs
                SET status = 'running',
                    progress = 12,
                    next_retry_at = NULL,
                    started_at = COALESCE(started_at, ?),
                    heartbeat_at = ?,
                    lease_expires_at = ?,
                    cancel_reason = '',
                    updated_at = ?
                WHERE id = ? AND status = 'queued' AND cancel_requested = 0
                """,
                (
                    now,
                    now,
                    now + _CREATIVE_JOB_LEASE_SEC,
                    now,
                    job_id,
                ),
            )
            conn.commit()
            if cur.rowcount <= 0:
                continue

            payload_text = str(row["request_payload"] or "{}")
            payload = json.loads(payload_text) if payload_text.strip() else {}
            safe_job_type = str(row["job_type"] or "").strip().lower()
            return job_id, safe_job_type, payload if isinstance(payload, dict) else {}
        return None
    finally:
        conn.close()


def _run_async_with_heartbeat(job_id: int, coro: Any) -> Any:
    outcome: dict[str, Any] = {}
    error: dict[str, Exception] = {}
    done = Event()

    def _runner() -> None:
        try:
            outcome["value"] = _run_async(coro)
        except Exception as exc:  # noqa: BLE001
            error["value"] = exc
        finally:
            done.set()

    worker = Thread(target=_runner, name=f"community-job-runner-{job_id}", daemon=True)
    worker.start()
    start = time.time()

    while True:
        if done.is_set():
            break

        _raise_if_job_cancel_requested(job_id)
        if (time.time() - start) > float(_CREATIVE_JOB_HARD_TIMEOUT_SEC):
            raise TimeoutError("creative job timed out")

        _touch_job_heartbeat(job_id)
        time.sleep(_CREATIVE_HEARTBEAT_SEC)

    worker.join(timeout=0.2)
    _touch_job_heartbeat(job_id)
    if "value" in error:
        raise error["value"]
    return outcome.get("value")


def _mark_job_success(
    job_id: int,
    request_id: str,
    provider: str,
    model: str,
    implementation_status: str,
    composed_image_base64: str,
    compare_input_base64: str | None,
    placeholder_notes: list[str],
) -> bool:
    now = int(time.time())
    safe_notes = [str(item).strip()[:280] for item in placeholder_notes if str(item).strip()]
    safe_status = implementation_status if implementation_status in {"ready", "placeholder"} else "ready"
    conn = open_db()
    try:
        cur = conn.execute(
            """
            UPDATE community_creative_jobs
            SET status = 'success',
                progress = 100,
                implementation_status = ?,
                result_meta = ?,
                result_image_base64 = ?,
                compare_input_base64 = ?,
                provider = ?,
                model = ?,
                error_message = '',
                request_id = ?,
                placeholder_notes = ?,
                heartbeat_at = ?,
                lease_expires_at = NULL,
                cancel_reason = '',
                updated_at = ?,
                finished_at = ?
            WHERE id = ? AND status = 'running' AND cancel_requested = 0
            """,
            (
                safe_status,
                json.dumps(
                    {
                        "provider": provider,
                        "model": model,
                        "implementation_status": safe_status,
                    },
                    ensure_ascii=False,
                ),
                composed_image_base64,
                compare_input_base64,
                provider,
                model,
                request_id,
                json.dumps(safe_notes, ensure_ascii=False),
                now,
                now,
                now,
                job_id,
            ),
        )
        conn.commit()
        return cur.rowcount > 0
    finally:
        conn.close()


def _mark_job_failed(job_id: int, error_message: str) -> bool:
    now = int(time.time())
    conn = open_db()
    try:
        cur = conn.execute(
            """
            UPDATE community_creative_jobs
            SET status = 'failed',
                progress = 100,
                error_message = ?,
                heartbeat_at = ?,
                lease_expires_at = NULL,
                updated_at = ?,
                finished_at = ?
            WHERE id = ? AND status = 'running'
            """,
            ((error_message or "creative job failed")[:280], now, now, now, job_id),
        )
        conn.commit()
        return cur.rowcount > 0
    finally:
        conn.close()


def _finalize_running_job_canceled(job_id: int, reason: str = "cancelled by user") -> bool:
    now = int(time.time())
    safe_reason = (reason or "cancelled by user")[:280]
    conn = open_db()
    try:
        cur = conn.execute(
            """
            UPDATE community_creative_jobs
            SET status = 'canceled',
                progress = 100,
                cancel_requested = 1,
                cancel_reason = ?,
                next_retry_at = NULL,
                heartbeat_at = ?,
                lease_expires_at = NULL,
                error_message = ?,
                updated_at = ?,
                finished_at = ?
            WHERE id = ? AND status = 'running'
            """,
            (safe_reason, now, safe_reason, now, now, job_id),
        )
        conn.commit()
        return cur.rowcount > 0
    finally:
        conn.close()


def _handle_job_failure(job_id: int, error_message: str, retryable: bool) -> None:
    safe_error = (error_message or "creative job failed")[:280]
    now = int(time.time())

    conn = open_db()
    try:
        row = conn.execute(
            """
            SELECT status, retry_count, max_retries, cancel_requested, cancel_reason
            FROM community_creative_jobs
            WHERE id = ?
            """,
            (job_id,),
        ).fetchone()
        if row is None:
            return

        status = str(row["status"] or "")
        if status != "running":
            return

        if _safe_int(row["cancel_requested"], default=0) == 1:
            conn.commit()
            _finalize_running_job_canceled(job_id=job_id, reason=str(row["cancel_reason"] or "cancelled by user"))
            return

        retry_count = max(0, _safe_int(row["retry_count"], default=0))
        max_retries = max(0, _safe_int(row["max_retries"], default=_CREATIVE_RETRY_MAX))
        if retryable and retry_count < max_retries:
            delay_sec = _compute_retry_delay_sec(retry_count)
            next_retry_at = now + delay_sec
            cur = conn.execute(
                """
                UPDATE community_creative_jobs
                SET status = 'queued',
                    progress = 0,
                    retry_count = ?,
                    next_retry_at = ?,
                    error_message = ?,
                    started_at = NULL,
                    heartbeat_at = NULL,
                    lease_expires_at = NULL,
                    cancel_reason = '',
                    updated_at = ?,
                    finished_at = NULL
                WHERE id = ? AND status = 'running' AND cancel_requested = 0
                """,
                (retry_count + 1, next_retry_at, safe_error, now, job_id),
            )
            conn.commit()
            if cur.rowcount > 0:
                _enqueue_creative_job_with_delay(job_id, delay_sec)
            return
    finally:
        conn.close()

    _mark_job_failed(job_id=job_id, error_message=safe_error)


def _touch_job_heartbeat(job_id: int) -> bool:
    now = int(time.time())
    conn = open_db()
    try:
        cur = conn.execute(
            """
            UPDATE community_creative_jobs
            SET heartbeat_at = ?,
                lease_expires_at = ?,
                updated_at = ?
            WHERE id = ? AND status = 'running'
            """,
            (now, now + _CREATIVE_JOB_LEASE_SEC, now, job_id),
        )
        conn.commit()
        return cur.rowcount > 0
    finally:
        conn.close()


def _raise_if_job_cancel_requested(job_id: int) -> None:
    conn = open_db()
    try:
        row = conn.execute(
            """
            SELECT cancel_requested, cancel_reason
            FROM community_creative_jobs
            WHERE id = ?
            """,
            (job_id,),
        ).fetchone()
    finally:
        conn.close()

    if row is None:
        raise _CreativeJobCanceledError("job disappeared")
    if _safe_int(row["cancel_requested"], default=0) == 1:
        raise _CreativeJobCanceledError(str(row["cancel_reason"] or "cancelled by user"))


def _recover_stale_running_jobs() -> None:
    now = int(time.time())
    conn = open_db()
    try:
        rows = conn.execute(
            """
            SELECT id, retry_count, max_retries, cancel_requested, cancel_reason
            FROM community_creative_jobs
            WHERE status = 'running'
              AND lease_expires_at IS NOT NULL
              AND lease_expires_at <= ?
            ORDER BY lease_expires_at ASC, id ASC
            LIMIT 120
            """,
            (now,),
        ).fetchall()
        if not rows:
            return

        for row in rows:
            job_id = int(row["id"])
            if _safe_int(row["cancel_requested"], default=0) == 1:
                _finalize_running_job_canceled(
                    job_id=job_id,
                    reason=str(row["cancel_reason"] or "cancelled by user"),
                )
                continue

            retry_count = max(0, _safe_int(row["retry_count"], default=0))
            max_retries = max(0, _safe_int(row["max_retries"], default=_CREATIVE_RETRY_MAX))
            if retry_count < max_retries:
                delay_sec = _compute_retry_delay_sec(retry_count)
                next_retry_at = now + delay_sec
                cur = conn.execute(
                    """
                    UPDATE community_creative_jobs
                    SET status = 'queued',
                        progress = 0,
                        retry_count = ?,
                        next_retry_at = ?,
                        error_message = 'worker lease expired, auto retry scheduled',
                        started_at = NULL,
                        heartbeat_at = NULL,
                        lease_expires_at = NULL,
                        cancel_reason = '',
                        updated_at = ?,
                        finished_at = NULL
                    WHERE id = ? AND status = 'running'
                    """,
                    (retry_count + 1, next_retry_at, now, job_id),
                )
                conn.commit()
                if cur.rowcount > 0:
                    _enqueue_creative_job_with_delay(job_id, delay_sec)
                continue

            conn.execute(
                """
                UPDATE community_creative_jobs
                SET status = 'failed',
                    progress = 100,
                    error_message = 'worker lease expired',
                    heartbeat_at = ?,
                    lease_expires_at = NULL,
                    updated_at = ?,
                    finished_at = ?
                WHERE id = ? AND status = 'running'
                """,
                (now, now, now, job_id),
            )
            conn.commit()
    finally:
        conn.close()


def _compute_retry_delay_sec(retry_count: int) -> int:
    # 指数退避：base * 2^n，最长 30s，避免持续压测上游服务。
    base = max(1, _CREATIVE_RETRY_BASE_SEC)
    power = max(0, min(retry_count, 4))
    return min(30, base * (2**power))


def creative_queue_snapshot() -> dict[str, int]:
    now = int(time.time())
    failed_after = now - _CREATIVE_FAILED_RECENT_WINDOW_SEC
    conn = open_db()
    try:
        queued = conn.execute(
            "SELECT COUNT(1) AS c FROM community_creative_jobs WHERE status = 'queued'"
        ).fetchone()
        running = conn.execute(
            "SELECT COUNT(1) AS c FROM community_creative_jobs WHERE status = 'running'"
        ).fetchone()
        failed_recent = conn.execute(
            """
            SELECT COUNT(1) AS c
            FROM community_creative_jobs
            WHERE status = 'failed' AND updated_at >= ?
            """,
            (failed_after,),
        ).fetchone()
        return {
            "queued": max(0, _safe_int(queued["c"] if queued is not None else 0, default=0)),
            "running": max(0, _safe_int(running["c"] if running is not None else 0, default=0)),
            "failed_recent": max(0, _safe_int(failed_recent["c"] if failed_recent is not None else 0, default=0)),
            "workers": max(0, len(_JOB_WORKER_THREADS)),
        }
    finally:
        conn.close()

def _row_to_creative_job_view(row: Any) -> CommunityCreativeJobView:
    safe_type = str(row["job_type"] or "compose")
    if safe_type not in {"compose", "cocreate", "remake_guide"}:
        safe_type = "compose"

    safe_status = str(row["status"] or "queued")
    if safe_status not in {"queued", "running", "success", "failed", "canceled"}:
        safe_status = "queued"

    safe_impl = str(row["implementation_status"] or "ready")
    if safe_impl not in {"ready", "placeholder"}:
        safe_impl = "ready"

    placeholder_notes: list[str] = []
    raw_notes = str(row["placeholder_notes"] or "[]")
    try:
        parsed = json.loads(raw_notes)
        if isinstance(parsed, list):
            placeholder_notes = [str(item) for item in parsed if str(item).strip()]
    except Exception:
        placeholder_notes = []

    provider = str(row["provider"] or "")
    model = str(row["model"] or "")
    if not provider or not model:
        meta_raw = str(row["result_meta"] or "{}")
        try:
            meta = json.loads(meta_raw) if meta_raw.strip() else {}
            if isinstance(meta, dict):
                provider = provider or str(meta.get("provider") or "")
                model = model or str(meta.get("model") or "")
        except Exception:
            pass

    return CommunityCreativeJobView(
        job_id=int(row["id"]),
        job_type=safe_type,  # type: ignore[arg-type]
        status=safe_status,  # type: ignore[arg-type]
        progress=max(0, min(100, _safe_int(row["progress"], default=0))),
        priority=max(1, min(999, _safe_int(row["priority"], default=_CREATIVE_DEFAULT_PRIORITY))),
        retry_count=max(0, _safe_int(row["retry_count"], default=0)),
        max_retries=max(0, _safe_int(row["max_retries"], default=0)),
        next_retry_at=_int_or_none(row["next_retry_at"]),
        started_at=_int_or_none(row["started_at"]),
        heartbeat_at=_int_or_none(row["heartbeat_at"]),
        lease_expires_at=_int_or_none(row["lease_expires_at"]),
        cancel_reason=str(row["cancel_reason"] or ""),
        implementation_status=safe_impl,  # type: ignore[arg-type]
        provider=provider,
        model=model,
        composed_image_base64=str(row["result_image_base64"] or "") or None,
        compare_input_base64=str(row["compare_input_base64"] or "") or None,
        placeholder_notes=placeholder_notes,
        error_message=str(row["error_message"] or ""),
        request_id=str(row["request_id"] or ""),
        created_at=_safe_int(row["created_at"], default=0),
        updated_at=_safe_int(row["updated_at"], default=0),
        finished_at=_int_or_none(row["finished_at"]),
    )


def _run_async(coro: Any) -> Any:
    import asyncio

    return asyncio.run(coro)


async def _compose_core(req: CommunityComposeRequest) -> CommunityComposeResponse:
    import asyncio

    reference_path, scene, place = _load_reference_image_meta(req.reference_post_id)
    person_bytes = _prepare_jpeg_bytes(req.person_image_base64, max_side=SETTINGS.community_upload_max_side)
    reference_bytes = reference_path.read_bytes()
    composed_input = _build_compose_input(
        reference_bytes=reference_bytes,
        person_bytes=person_bytes,
        max_side=SETTINGS.community_upload_max_side,
    )
    composed_input_b64 = base64.b64encode(composed_input).decode("utf-8")

    provider = DoubaoImageEditProvider(config_prefix="COMMUNITY_IMAGE", fallback_prefix="ARK_IMAGE")
    if not _provider_is_ready(provider):
        return CommunityComposeResponse(
            composed_image_base64=composed_input_b64,
            provider="local",
            model="compose_mvp_blend",
            implementation_status="placeholder",
            compare_input_base64=composed_input_b64,
            placeholder_notes=[
                "当前未配置 COMMUNITY_IMAGE_API_KEY/ARK_IMAGE_API_KEY，已返回本地融合预览结果。",
                "TODO(cammate-v1.2): 接入遮罩分割与透视约束，提升人物边缘与空间一致性。",
            ],
        )

    try:
        result = await asyncio.wait_for(
            provider.retouch(
                image_base64=composed_input_b64,
                preset=_preset_for_scene(scene),
                strength=req.strength,
                scene_hint=scene,
                custom_prompt=(
                    f"参考地点：{place or '未知地点'}。"
                    "请仅做真实照片级融合优化，保持人物身份、姿态与背景主构图不变。"
                    "优先修复边缘过渡、光影匹配、肤色一致性。"
                ),
            ),
            timeout=_CREATIVE_PROVIDER_TIMEOUT_SEC,
        )
        return CommunityComposeResponse(
            composed_image_base64=result.image_base64,
            provider=result.provider,
            model=result.model,
            implementation_status="ready",
            compare_input_base64=composed_input_b64,
            placeholder_notes=[],
        )
    except Exception as exc:
        logger.warning("community.compose.fallback reason=%r", exc)
        return CommunityComposeResponse(
            composed_image_base64=composed_input_b64,
            provider="local-fallback",
            model="compose_mvp_blend",
            implementation_status="placeholder",
            compare_input_base64=composed_input_b64,
            placeholder_notes=[
                "云端融合调用失败，已回退到本地融合预览结果。",
                "TODO(cammate-v1.2): 增加失败重试队列和边缘质量评分。",
            ],
        )


async def _cocreate_core(req: CommunityCocreateComposeRequest) -> CommunityCocreateComposeResponse:
    import asyncio

    reference_path, scene, place = _load_reference_image_meta(req.reference_post_id)
    person_a_bytes = _prepare_jpeg_bytes(req.person_a_image_base64, max_side=SETTINGS.community_upload_max_side)
    person_b_bytes = _prepare_jpeg_bytes(req.person_b_image_base64, max_side=SETTINGS.community_upload_max_side)
    reference_bytes = reference_path.read_bytes()
    composed_input = _build_cocreate_input(
        reference_bytes=reference_bytes,
        person_a_bytes=person_a_bytes,
        person_b_bytes=person_b_bytes,
        max_side=SETTINGS.community_upload_max_side,
    )
    composed_input_b64 = base64.b64encode(composed_input).decode("utf-8")

    provider = DoubaoImageEditProvider(config_prefix="COMMUNITY_IMAGE", fallback_prefix="ARK_IMAGE")
    if not _provider_is_ready(provider):
        return CommunityCocreateComposeResponse(
            composed_image_base64=composed_input_b64,
            provider="local",
            model="cocreate_mvp_blend",
            implementation_status="placeholder",
            compare_input_base64=composed_input_b64,
            placeholder_notes=[
                "当前未配置 COMMUNITY_IMAGE_API_KEY/ARK_IMAGE_API_KEY，已返回本地双人共创预览结果。",
                "TODO(cammate-v1.2): 增加双人遮罩、肢体关系约束与透视几何一致性。",
            ],
        )

    try:
        result = await asyncio.wait_for(
            provider.retouch(
                image_base64=composed_input_b64,
                preset=_preset_for_scene(scene),
                strength=req.strength,
                scene_hint=scene,
                custom_prompt=(
                    f"参考地点：{place or '未知地点'}。"
                    "请仅做真实照片级双人融合优化，保持两位人物身份与构图位置基本一致。"
                    "优先修复双人边缘粘连、光线方向、皮肤色温与投影一致性。"
                ),
            ),
            timeout=_CREATIVE_PROVIDER_TIMEOUT_SEC,
        )
        return CommunityCocreateComposeResponse(
            composed_image_base64=result.image_base64,
            provider=result.provider,
            model=result.model,
            implementation_status="ready",
            compare_input_base64=composed_input_b64,
            placeholder_notes=[],
        )
    except Exception as exc:
        logger.warning("community.cocreate.fallback reason=%r", exc)
        return CommunityCocreateComposeResponse(
            composed_image_base64=composed_input_b64,
            provider="local-fallback",
            model="cocreate_mvp_blend",
            implementation_status="placeholder",
            compare_input_base64=composed_input_b64,
            placeholder_notes=[
                "云端双人共创调用失败，已回退到本地共创预览结果。",
                "TODO(cammate-v1.2): 增加人物分层对齐与细节修复。",
            ],
        )


def _preset_for_scene(scene_type: str) -> str:
    scene = _normalize_scene_type(scene_type)
    if scene == "portrait":
        return "portrait_beauty"
    if scene in {"food", "night", "landscape"}:
        return "color_grade"
    return "bg_cleanup"


# -----------------------------
# Mapping helpers
# -----------------------------


def _row_to_post_view(conn: Any, row: Any) -> CommunityPostView:
    relay_parent_id = _int_or_none(row["relay_parent_post_id"])
    relay_summary = _load_relay_parent_summary(conn, relay_parent_id) if relay_parent_id else None

    rating = _safe_int(row["rating"], default=0)
    if rating < 0:
        rating = 0
    if rating > 5:
        rating = 5

    return CommunityPostView(
        id=int(row["id"]),
        user_id=int(row["user_id"]),
        user_nickname=str(row["user_nickname"]),
        feedback_id=_int_or_none(row["feedback_id"]),
        image_url=f"/community/posts/{int(row['id'])}/image",
        scene_type=str(row["scene_type"]),
        place_tag=str(row["place_tag"]),
        rating=rating,
        review_text=str(row["review_text"] or ""),
        caption=str(row["caption"] or ""),
        post_type=_normalize_post_type(str(row["post_type"] or "normal")),
        source_type=_normalize_source_type(str(row["source_type"] or "feedback_flow")),
        like_count=max(0, _safe_int(row["like_count"], default=0)),
        comment_count=max(0, _safe_int(row["comment_count"], default=0)),
        liked_by_me=bool(_safe_int(row["liked_by_me"], default=0)),
        style_template_post_id=_int_or_none(row["style_template_post_id"]),
        relay_parent_summary=relay_summary,
        created_at=int(row["created_at"]),
    )


def _load_relay_parent_summary(conn: Any, parent_post_id: int) -> CommunityRelayParentSummary | None:
    row = conn.execute(
        """
        SELECT
            p.id, p.scene_type, p.place_tag,
            u.nickname AS user_nickname
        FROM community_posts p
        JOIN users u ON u.id = p.user_id
        WHERE p.id = ? AND p.moderation_status = 'published'
        """,
        (parent_post_id,),
    ).fetchone()
    if row is None:
        return None
    return CommunityRelayParentSummary(
        id=int(row["id"]),
        user_nickname=str(row["user_nickname"]),
        place_tag=str(row["place_tag"] or ""),
        scene_type=str(row["scene_type"] or "general"),
        image_url=f"/community/posts/{int(row['id'])}/image",
    )


# -----------------------------
# Validation helpers
# -----------------------------


def _normalize_scene_type(scene_type: str) -> str:
    value = (scene_type or "").strip().lower()
    if value not in _VALID_SCENE_TYPES:
        return "general"
    return value


def _normalize_post_type(post_type: str) -> str:
    value = (post_type or "").strip().lower()
    if value not in _VALID_POST_TYPES:
        return "normal"
    return value


def _normalize_source_type(source_type: str) -> str:
    value = (source_type or "").strip().lower()
    if value in {"feedback_flow", "direct"}:
        return value
    return "feedback_flow"


def _normalize_place_tag(text: str) -> str:
    compact = " ".join((text or "").strip().split())
    return compact[:48]


def _normalize_caption(text: str) -> str:
    compact = " ".join((text or "").strip().split())
    return compact[:280]


def _normalize_review(text: str) -> str:
    compact = " ".join((text or "").strip().split())
    return compact[:280]


def _ensure_safe_text(text: str) -> None:
    normalized = (text or "").strip().lower()
    if not normalized:
        return
    for bad in SETTINGS.community_blocked_words:
        if bad and bad in normalized:
            raise HTTPException(status_code=400, detail="text contains blocked words")


def _ensure_post_exists(conn: Any, post_id: int) -> Any:
    row = conn.execute(
        "SELECT id FROM community_posts WHERE id = ? AND moderation_status = 'published'",
        (post_id,),
    ).fetchone()
    if row is None:
        raise HTTPException(status_code=404, detail="community post not found")
    return row


# -----------------------------
# Image helpers
# -----------------------------


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
    # 不使用字符串前缀判断，避免 /a/b 与 /a/bad 这类前缀误判。
    if base_dir not in path.parents:
        raise HTTPException(status_code=500, detail="invalid upload path")
    path.write_bytes(image_bytes)
    return path


def _load_reference_image_meta(reference_post_id: int) -> tuple[Path, str, str]:
    conn = open_db()
    try:
        row = conn.execute(
            """
            SELECT id, scene_type, place_tag, image_path
            FROM community_posts
            WHERE id = ? AND moderation_status = 'published'
            """,
            (reference_post_id,),
        ).fetchone()
    finally:
        conn.close()

    if row is None:
        raise HTTPException(status_code=404, detail="reference post not found")

    reference_path = Path(str(row["image_path"] or "")).resolve()
    base_dir = SETTINGS.community_upload_dir.resolve()
    if base_dir not in reference_path.parents or not reference_path.exists():
        raise HTTPException(status_code=404, detail="reference image not found")

    scene = _normalize_scene_type(str(row["scene_type"] or "general"))
    place = _normalize_place_tag(str(row["place_tag"] or ""))
    return reference_path, scene, place


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


def _build_cocreate_input(
    reference_bytes: bytes,
    person_a_bytes: bytes,
    person_b_bytes: bytes,
    max_side: int,
) -> bytes:
    with Image.open(io.BytesIO(reference_bytes)) as ref_img:
        with Image.open(io.BytesIO(person_a_bytes)) as person_a_img:
            with Image.open(io.BytesIO(person_b_bytes)) as person_b_img:
                bg = ImageOps.exif_transpose(ref_img).convert("RGB")
                fg_a = ImageOps.exif_transpose(person_a_img).convert("RGBA")
                fg_b = ImageOps.exif_transpose(person_b_img).convert("RGBA")

                if max(bg.size) > max_side:
                    bg.thumbnail((max_side, max_side), Image.Resampling.LANCZOS)

                max_fg_w = int(bg.width * 0.32)
                max_fg_h = int(bg.height * 0.56)
                fg_a.thumbnail((max_fg_w, max_fg_h), Image.Resampling.LANCZOS)
                fg_b.thumbnail((max_fg_w, max_fg_h), Image.Resampling.LANCZOS)

                if "A" in fg_a.getbands():
                    alpha_a = fg_a.getchannel("A").point(lambda px: int(px * 0.93))
                    fg_a.putalpha(alpha_a)
                if "A" in fg_b.getbands():
                    alpha_b = fg_b.getchannel("A").point(lambda px: int(px * 0.93))
                    fg_b.putalpha(alpha_b)

                canvas = bg.convert("RGBA")

                left_x = max(0, int(bg.width * 0.18) - (fg_a.width // 2))
                right_x = min(bg.width - fg_b.width, int(bg.width * 0.82) - (fg_b.width // 2))
                baseline_y = bg.height - int(bg.height * 0.06)
                y_a = max(0, min(baseline_y - fg_a.height, bg.height - fg_a.height))
                y_b = max(0, min(baseline_y - fg_b.height, bg.height - fg_b.height))

                canvas.alpha_composite(fg_a, (left_x, y_a))
                canvas.alpha_composite(fg_b, (right_x, y_b))

                merged = canvas.convert("RGB")
                out = io.BytesIO()
                merged.save(out, format="JPEG", quality=92, optimize=True)
                return out.getvalue()


# -----------------------------
# Recommendation helpers
# -----------------------------


def _match_score(post_place: str, post_scene: str, place_filter: str | None, scene_filter: str | None) -> float:
    # V1 推荐维度只包含地点与场景类型，姿势（pose）维度暂未接入。
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


# -----------------------------
# Creative helpers
# -----------------------------


def _provider_is_ready(provider: DoubaoImageEditProvider) -> bool:
    api_key = getattr(provider, "api_key", "")
    return bool(str(api_key).strip())


def _build_remake_script(scene_type: str, place_tag: str, post_type: str) -> list[str]:
    scene = _normalize_scene_type(scene_type)
    base = [
        f"先到 {place_tag or '目标地点'}，找到与参考图同方向的主光源。",
        "将人物放在画面三分线附近，先拍一张中景确认层次。",
        "保持手机水平，先锁定曝光再微调构图。",
    ]
    if scene == "portrait":
        base.append("让人物肩线与镜头略成 30°，下巴轻微收紧，连拍 3 张挑最佳表情。")
    elif scene == "landscape":
        base.append("优先保留前景引导线，主体位于远中近层次交汇位置。")
    elif scene == "night":
        base.append("夜景先降低 0.3~0.7EV，避免高光过曝，再拍一张慢速稳拍版本。")
    elif scene == "food":
        base.append("美食场景保持 45° 俯拍，餐具与背景元素尽量简洁。")
    else:
        base.append("每次横向平移半步拍一张，优先选择背景最干净的一版。")

    if post_type == "relay":
        base.append("这是接力模板，建议保留相同站位但更换手部动作形成接力感。")
    return base


def _build_camera_hint(scene_type: str, place_tag: str) -> str:
    scene = _normalize_scene_type(scene_type)
    if scene == "portrait":
        return f"{place_tag or '当前地点'} 推荐 1.2x~1.6x 焦段，人物与背景保持 2~4 米距离。"
    if scene == "landscape":
        return f"{place_tag or '当前地点'} 推荐 0.8x~1.0x 焦段，地平线放在上 1/3。"
    if scene == "night":
        return f"{place_tag or '当前地点'} 推荐先锁曝光再拍，避免霓虹灯区域过曝。"
    if scene == "food":
        return f"{place_tag or '当前地点'} 推荐固定机位后微调摆盘，保持画面留白。"
    return f"{place_tag or '当前地点'} 推荐先拍中景，再拍近景，方便做同款复刻选择。"


def _record_creative_job(
    user_id: int,
    job_type: str,
    reference_post_id: int,
    implementation_status: str,
    request_payload: dict[str, Any],
    result_meta: dict[str, Any],
) -> None:
    now = int(time.time())
    provider = str(result_meta.get("provider") or "")
    model = str(result_meta.get("model") or "")
    safe_status = implementation_status if implementation_status in {"ready", "placeholder"} else "ready"
    conn = open_db()
    try:
        conn.execute(
            """
            INSERT INTO community_creative_jobs(
                user_id, job_type, reference_post_id, status, progress,
                implementation_status, request_payload, result_meta,
                provider, model, created_at, updated_at, finished_at
            ) VALUES (?, ?, ?, 'success', 100, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                user_id,
                job_type,
                reference_post_id,
                safe_status,
                json.dumps(request_payload, ensure_ascii=False),
                json.dumps(result_meta, ensure_ascii=False),
                provider,
                model,
                now,
                now,
                now,
            ),
        )
        conn.commit()
    finally:
        conn.close()


# -----------------------------
# Persistence helpers
# -----------------------------


def _create_synthetic_feedback_for_direct(
    conn: Any,
    user_id: int,
    scene: str,
    rating: int,
    review_text: str,
    style_template_post_id: int | None,
) -> int:
    now = int(time.time())
    safe_rating_for_feedback = max(1, min(5, rating if rating > 0 else 3))
    tip_text = "社区主动发布"
    if style_template_post_id:
        tip_text = f"社区主动发布（模板 {style_template_post_id}）"

    cur = conn.execute(
        """
        INSERT INTO feedback(
            user_id, rating, scene, tip_text, photo_uri,
            is_retouch, review_text, session_meta, created_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        (
            user_id,
            safe_rating_for_feedback,
            scene,
            tip_text,
            None,
            0,
            review_text,
            "{}",
            now,
        ),
    )
    return int(cur.lastrowid)


def _community_posts_feedback_id_required(conn: Any) -> bool:
    row = conn.execute(
        """
        SELECT "notnull"
        FROM pragma_table_info('community_posts')
        WHERE lower(name) = 'feedback_id'
        LIMIT 1
        """
    ).fetchone()
    if row is None:
        return False
    return int(row["notnull"]) == 1


def _refresh_post_like_count(conn: Any, post_id: int) -> int:
    row = conn.execute(
        "SELECT COUNT(1) AS c FROM community_post_likes WHERE post_id = ?",
        (post_id,),
    ).fetchone()
    count = int(row["c"]) if row is not None else 0
    conn.execute("UPDATE community_posts SET like_count = ? WHERE id = ?", (count, post_id))
    return count


def _refresh_post_comment_count(conn: Any, post_id: int) -> int:
    row = conn.execute(
        "SELECT COUNT(1) AS c FROM community_post_comments WHERE post_id = ?",
        (post_id,),
    ).fetchone()
    count = int(row["c"]) if row is not None else 0
    conn.execute("UPDATE community_posts SET comment_count = ? WHERE id = ?", (count, post_id))
    return count


# -----------------------------
# Seed data
# -----------------------------


def _ensure_seed_posts_if_needed() -> None:
    # 用于早期产品阶段快速填充“朋友圈”社区流，便于联调和视觉验收。
    if not SETTINGS.community_seed_enabled:
        return
    target_count = SETTINGS.community_seed_count
    if target_count <= 0:
        return

    with _SEED_LOCK:
        conn = open_db()
        try:
            row = conn.execute(
                "SELECT COUNT(1) AS c FROM community_posts WHERE moderation_status = 'published'"
            ).fetchone()
            existing_count = int(row["c"]) if row is not None else 0
            if existing_count >= target_count:
                return

            users = _ensure_seed_users(conn, count=max(3, min(8, target_count)))
            if not users:
                return

            upload_dir = SETTINGS.community_upload_dir
            upload_dir.mkdir(parents=True, exist_ok=True)
            base_dir = upload_dir.resolve()
            now = int(time.time())
            missing = target_count - existing_count

            for i in range(missing):
                index = existing_count + i
                user_id, nickname = users[index % len(users)]
                scene = _SEED_SCENES[index % len(_SEED_SCENES)]
                place = _SEED_PLACE_TAGS[index % len(_SEED_PLACE_TAGS)]
                review = _SEED_REVIEWS[index % len(_SEED_REVIEWS)]
                tip_text = _SEED_TIPS[index % len(_SEED_TIPS)]
                rating = 5 - (index % 3)  # 5, 4, 3 循环
                created_at = now - (index * 6 * 3600)

                feedback_cur = conn.execute(
                    """
                    INSERT INTO feedback(
                        user_id, rating, scene, tip_text, photo_uri,
                        is_retouch, review_text, session_meta, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    (
                        user_id,
                        rating,
                        scene,
                        tip_text,
                        None,
                        0,
                        review,
                        "{}",
                        created_at,
                    ),
                )
                feedback_id = int(feedback_cur.lastrowid)

                image_bytes = _build_seed_image(
                    scene_type=scene,
                    place_tag=place,
                    nickname=nickname,
                    index=index,
                )
                file_name = f"seed_post_{index:03d}_{uuid.uuid4().hex[:8]}.jpg"
                image_path = (upload_dir / file_name).resolve()
                if base_dir not in image_path.parents:
                    raise HTTPException(status_code=500, detail="invalid seed image path")
                image_path.write_bytes(image_bytes)

                post_type = "relay" if (index % 6 == 0 and index > 0) else "normal"
                relay_parent_post_id: int | None = None
                if post_type == "relay":
                    parent = conn.execute(
                        """
                        SELECT id FROM community_posts
                        WHERE moderation_status = 'published'
                        ORDER BY created_at DESC, id DESC
                        LIMIT 1
                        """
                    ).fetchone()
                    relay_parent_post_id = int(parent["id"]) if parent is not None else None

                conn.execute(
                    """
                    INSERT INTO community_posts(
                        user_id, feedback_id, image_path, scene_type, place_tag,
                        rating, review_text, caption, post_type, source_type,
                        relay_parent_post_id, style_template_post_id,
                        like_count, comment_count, created_at, moderation_status
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'feedback_flow', ?, NULL, 0, 0, ?, 'published')
                    """,
                    (
                        user_id,
                        feedback_id,
                        str(image_path),
                        scene,
                        place,
                        rating,
                        review,
                        "",
                        post_type,
                        relay_parent_post_id,
                        created_at,
                    ),
                )

            conn.commit()
        finally:
            conn.close()


def _ensure_seed_users(conn: Any, count: int) -> list[tuple[int, str]]:
    users: list[tuple[int, str]] = []
    now = int(time.time())
    for i in range(count):
        email = f"seed_creator_{i + 1}@cammate.local"
        default_nickname = _SEED_NICKNAMES[i % len(_SEED_NICKNAMES)]
        row = conn.execute(
            "SELECT id, nickname FROM users WHERE email = ?",
            (email,),
        ).fetchone()
        if row is None:
            cur = conn.execute(
                """
                INSERT INTO users(email, password_hash, password_salt, nickname, created_at)
                VALUES (?, ?, ?, ?, ?)
                """,
                (
                    email,
                    "seed_password_hash",
                    "seed_password_salt",
                    default_nickname,
                    now - ((i + 1) * 3600),
                ),
            )
            users.append((int(cur.lastrowid), default_nickname))
        else:
            users.append((int(row["id"]), str(row["nickname"] or default_nickname)))
    return users


def _build_seed_image(scene_type: str, place_tag: str, nickname: str, index: int) -> bytes:
    palette = [
        ((33, 58, 84), (19, 30, 44)),
        ((92, 70, 52), (36, 28, 22)),
        ((44, 83, 73), (20, 42, 36)),
        ((78, 66, 102), (31, 24, 46)),
        ((85, 53, 48), (33, 18, 16)),
    ]
    c1, c2 = palette[index % len(palette)]
    width, height = 1080, 1440
    image = Image.new("RGB", (width, height), color=c1)
    draw = ImageDraw.Draw(image)

    # 轻量几何背景，避免纯色占位图太“假”。
    draw.rectangle((0, int(height * 0.62), width, height), fill=c2)
    draw.ellipse(
        (int(width * 0.08), int(height * 0.16), int(width * 0.92), int(height * 0.90)),
        outline=(240, 221, 180),
        width=5,
    )
    draw.rectangle(
        (int(width * 0.12), int(height * 0.72), int(width * 0.88), int(height * 0.84)),
        fill=(18, 18, 18),
    )
    draw.text((74, 84), f"{place_tag} · {scene_type}", fill=(246, 237, 219))
    draw.text((74, 136), f"@{nickname}", fill=(220, 213, 201))
    draw.text((74, int(height * 0.75)), "CamMate Community Seed", fill=(248, 242, 233))

    out = io.BytesIO()
    image.save(out, format="JPEG", quality=90, optimize=True)
    return out.getvalue()


# -----------------------------
# Utility
# -----------------------------


def _priority_for_job_type(job_type: str) -> int:
    normalized = (job_type or "").strip().lower()
    if normalized == "compose":
        return _CREATIVE_DEFAULT_PRIORITY
    if normalized == "cocreate":
        return _CREATIVE_DEFAULT_PRIORITY - 5
    return _CREATIVE_DEFAULT_PRIORITY


def _safe_int(value: Any, default: int = 0) -> int:
    try:
        return int(value)
    except Exception:
        return default


def _int_or_none(value: Any) -> int | None:
    if value is None:
        return None
    try:
        return int(value)
    except Exception:
        return None
