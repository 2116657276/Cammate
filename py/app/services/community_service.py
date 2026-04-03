from __future__ import annotations

import base64
import io
import json
import logging
import math
import queue
import random
import time
import uuid
from pathlib import Path
from threading import Event, Lock, Thread
from typing import Any

from fastapi import HTTPException
from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageOps, ImageStat

from app.core.config import PROJECT_ROOT
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
    CommunityModerationActionRequest,
    CommunityRecommendationView,
    CommunityRecommendationsResponse,
    CommunityReportCreateRequest,
    CommunityReportView,
    CommunityRelayParentSummary,
    CommunityRelayPublishRequest,
    CommunityRemakeAnalyzeRequest,
    CommunityRemakeAnalyzeResponse,
    CommunityRemakeGuideRequest,
    CommunityRemakeGuideResponse,
)
from app.services.creative_queue import get_redis_creative_queue
from app.services.creative_storage import get_creative_object_storage
from app.services.community_seed import seed_demo_content
from app.vision.remake_analyzer import get_pose_remake_analyzer
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
_CREATIVE_EVENT_RECENT_WINDOW_SEC = 3600
_CREATIVE_RETRY_MAX = max(0, int(SETTINGS.community_creative_max_retries))
_CREATIVE_RETRY_BASE_SEC = max(1, int(SETTINGS.community_creative_retry_base_sec))
_CREATIVE_RETRY_MAX_DELAY_SEC = max(5, int(SETTINGS.community_creative_retry_max_delay_sec))
_CREATIVE_RETRY_JITTER_RATIO = max(0.0, float(SETTINGS.community_creative_retry_jitter_ratio))
_CREATIVE_RESULT_TTL_SEC = max(3600, int(SETTINGS.community_creative_result_ttl_sec))
_CREATIVE_STALE_HEARTBEAT_SEC = max(_CREATIVE_JOB_LEASE_SEC * 2, 30)
_CREATIVE_DEFAULT_PRIORITY = 100
logger = logging.getLogger("app.community")
_REDIS_QUEUE = get_redis_creative_queue()
_OBJECT_STORAGE = get_creative_object_storage()
_REMAKE_ANALYZER = get_pose_remake_analyzer()


class _CreativeJobCanceledError(Exception):
    pass


# -----------------------------
# Posts
# -----------------------------


def bootstrap_creative_runtime() -> None:
    """Initialize creative runtime; worker startup is opt-in for API process."""
    if SETTINGS.creative_embedded_worker:
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
    query_limit = safe_limit + 1

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
            (user_id, query_limit, safe_offset),
        ).fetchall()

        visible_rows = rows[:safe_limit]
        has_more = len(rows) > safe_limit
        items = [_row_to_post_view(conn, row) for row in visible_rows]
        _record_bulk_post_signals(conn, user_id=user_id, post_ids=[item.id for item in items], signal_type="impression")
        conn.commit()
        return CommunityFeedResponse(items=items, next_offset=safe_offset + len(items), has_more=has_more)
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

        post_ids = [int(row["id"]) for row in rows]
        signal_map: dict[int, float] = {}
        if post_ids:
            placeholders = ",".join("?" for _ in post_ids)
            signal_rows = conn.execute(
                f"""
                SELECT
                    post_id,
                    SUM(
                        CASE signal_type
                            WHEN 'impression' THEN 0.20
                            WHEN 'open' THEN 0.45
                            WHEN 'like' THEN 0.90
                            WHEN 'comment' THEN 1.25
                            ELSE 0.10
                        END * value
                    ) AS heat
                FROM community_post_signals
                WHERE post_id IN ({placeholders})
                GROUP BY post_id
                """,
                tuple(post_ids),
            ).fetchall()
            signal_map = {int(row["post_id"]): float(row["heat"] or 0.0) for row in signal_rows}

        preference_rows = conn.execute(
            """
            SELECT p.scene_type, p.place_tag, COUNT(1) AS c
            FROM community_post_signals s
            JOIN community_posts p ON p.id = s.post_id
            WHERE s.user_id = ?
              AND s.signal_type IN ('open', 'like', 'comment')
            GROUP BY p.scene_type, p.place_tag
            ORDER BY c DESC
            LIMIT 60
            """,
            (user_id,),
        ).fetchall()
        preference_scene: dict[str, int] = {}
        preference_place: dict[str, int] = {}
        for pref in preference_rows:
            scene_key = str(pref["scene_type"] or "")
            place_key = str(pref["place_tag"] or "")
            count = max(0, _safe_int(pref["c"], default=0))
            preference_scene[scene_key] = preference_scene.get(scene_key, 0) + count
            preference_place[place_key] = preference_place.get(place_key, 0) + count

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
            heat = math.log1p(max(0.0, float(signal_map.get(int(row["id"]), 0.0)))) / 2.5
            preference = 0.0
            preference += min(1.0, preference_scene.get(str(row["scene_type"] or ""), 0) / 8.0) * 0.6
            preference += min(1.0, preference_place.get(str(row["place_tag"] or ""), 0) / 6.0) * 0.4
            final_score = (
                (0.50 * match)
                + (0.20 * rating_component)
                + (0.10 * freshness)
                + (0.12 * min(1.0, heat))
                + (0.08 * min(1.0, preference))
            )
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
        _record_bulk_post_signals(conn, user_id=user_id, post_ids=[item.post.id for item in items], signal_type="impression")
        conn.commit()
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
        cur = conn.execute(
            """
            INSERT OR IGNORE INTO community_post_likes(post_id, user_id, created_at)
            VALUES (?, ?, ?)
            """,
            (post_id, user_id, now),
        )
        if cur.rowcount > 0:
            _record_post_signal(conn, user_id=user_id, post_id=post_id, signal_type="like")
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
        _record_post_signal(conn, user_id=user_id, post_id=post_id, signal_type="comment")
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


def load_post_image(post_id: int, user_id: int | None = None) -> tuple[bytes, str]:
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

    image_path = _resolve_community_image_path(str(row["image_path"] or ""))
    if not image_path.exists():
        raise HTTPException(status_code=404, detail="image file not found")

    if user_id is not None and user_id > 0:
        conn = open_db()
        try:
            _record_post_signal(conn, user_id=user_id, post_id=post_id, signal_type="open")
            conn.commit()
        finally:
            conn.close()

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
        job_type = str(row["job_type"] or "compose").strip().lower()
        reference_post_id = _safe_int(row["reference_post_id"], default=0)
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
                result_image_path = '',
                result_storage_provider = '',
                result_storage_key = '',
                compare_input_base64 = NULL,
                compare_input_path = '',
                compare_storage_provider = '',
                compare_storage_key = '',
                mime_type = '',
                file_size = 0,
                sha256 = '',
                provider = '',
                model = '',
                error_message = '',
                error_code = '',
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

    _delete_job_artifacts(job_id, row=row)
    _record_job_event(
        job_id=job_id,
        event_type="retry",
        message=f"{job_type} job retried by user",
        payload={"reference_post_id": reference_post_id},
    )
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
                    error_code = 'user_canceled',
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
    _record_job_event(job_id=job_id, event_type="cancel_requested", message=reason, payload={"status": status})
    return get_creative_job(user_id=user_id, job_id=job_id)


def remake_guide(user_id: int, req: CommunityRemakeGuideRequest) -> CommunityRemakeGuideResponse:
    template_post = get_post_or_404(req.template_post_id, current_user_id=user_id)
    shot_script = _build_remake_script(
        scene_type=template_post.scene_type,
        place_tag=template_post.place_tag,
        post_type=template_post.post_type,
    )
    camera_hint = _build_camera_hint(template_post.scene_type, template_post.place_tag)
    pose_hint = _build_pose_hint(
        scene_type=template_post.scene_type,
        place_tag=template_post.place_tag,
        post_type=template_post.post_type,
    )
    framing_hint = _build_framing_hint(
        scene_type=template_post.scene_type,
        place_tag=template_post.place_tag,
        post_type=template_post.post_type,
    )
    timing_hint = _build_timing_hint(template_post.scene_type, template_post.place_tag)
    alignment_checks = _build_alignment_checks(
        scene_type=template_post.scene_type,
        post_type=template_post.post_type,
    )

    _record_creative_job(
        user_id=user_id,
        job_type="remake_guide",
        reference_post_id=req.template_post_id,
        implementation_status="ready",
        request_payload={},
        result_meta={
            "steps": len(shot_script),
            "pose_hint": pose_hint,
            "framing_hint": framing_hint,
            "timing_hint": timing_hint,
            "alignment_checks": alignment_checks,
        },
    )

    return CommunityRemakeGuideResponse(
        template_post=template_post,
        shot_script=shot_script,
        camera_hint=camera_hint,
        pose_hint=pose_hint,
        framing_hint=framing_hint,
        timing_hint=timing_hint,
        alignment_checks=alignment_checks,
        implementation_status="ready",
        placeholder_notes=[
            "当前为规则驱动的复刻指导卡，已补充姿态、构图和时机提示。",
            "下一步会继续接入人体关键点匹配与实时姿态纠偏。",
        ],
    )


def remake_analyze(user_id: int, req: CommunityRemakeAnalyzeRequest) -> CommunityRemakeAnalyzeResponse:
    template_post = get_post_or_404(req.template_post_id, current_user_id=user_id)
    reference_path, scene, _ = _load_reference_image_meta(req.template_post_id)
    analysis = _REMAKE_ANALYZER.analyze(
        template_bytes=reference_path.read_bytes(),
        candidate_base64=req.candidate_image_base64,
        scene_hint=scene or template_post.scene_type,
    )
    _record_creative_job(
        user_id=user_id,
        job_type="remake_guide",
        reference_post_id=req.template_post_id,
        implementation_status=analysis.implementation_status,
        request_payload={"mode": "analyze"},
        result_meta={
            "pose_score": analysis.pose_score,
            "framing_score": analysis.framing_score,
            "alignment_score": analysis.alignment_score,
            "mismatch_hints": analysis.mismatch_hints,
        },
    )
    return CommunityRemakeAnalyzeResponse(
        template_post_id=req.template_post_id,
        pose_score=analysis.pose_score,
        framing_score=analysis.framing_score,
        alignment_score=analysis.alignment_score,
        mismatch_hints=analysis.mismatch_hints,
        implementation_status=analysis.implementation_status,  # type: ignore[arg-type]
        placeholder_notes=analysis.placeholder_notes,
    )


def report_post(user_id: int, post_id: int, req: CommunityReportCreateRequest) -> CommunityReportView:
    safe_reason = " ".join((req.reason or "").strip().split())[:80]
    safe_detail = _normalize_review(req.detail_text)
    if not safe_reason:
        raise HTTPException(status_code=400, detail="report reason is empty")
    conn = open_db()
    try:
        _ensure_post_exists(conn, post_id)
        now = int(time.time())
        cur = conn.execute(
            """
            INSERT INTO community_post_reports(
                post_id, reporter_user_id, reason, detail_text, status,
                moderation_action, resolution_note, created_at, resolved_at, resolved_by
            ) VALUES (?, ?, ?, ?, 'pending', '', '', ?, NULL, NULL)
            """,
            (post_id, user_id, safe_reason, safe_detail, now),
        )
        report_id = int(cur.lastrowid)
        conn.commit()
        row = conn.execute(
            """
            SELECT id, post_id, reporter_user_id, reason, detail_text, status,
                   moderation_action, resolution_note, created_at, resolved_at
            FROM community_post_reports
            WHERE id = ?
            """,
            (report_id,),
        ).fetchone()
        if row is None:
            raise HTTPException(status_code=500, detail="report creation failed")
        return _row_to_report_view(row)
    finally:
        conn.close()


def list_reports(status: str | None = None, limit: int = 50) -> list[CommunityReportView]:
    safe_status = (status or "").strip().lower()
    safe_limit = max(1, min(int(limit), 100))
    conn = open_db()
    try:
        if safe_status:
            rows = conn.execute(
                """
                SELECT id, post_id, reporter_user_id, reason, detail_text, status,
                       moderation_action, resolution_note, created_at, resolved_at
                FROM community_post_reports
                WHERE status = ?
                ORDER BY created_at DESC, id DESC
                LIMIT ?
                """,
                (safe_status, safe_limit),
            ).fetchall()
        else:
            rows = conn.execute(
                """
                SELECT id, post_id, reporter_user_id, reason, detail_text, status,
                       moderation_action, resolution_note, created_at, resolved_at
                FROM community_post_reports
                ORDER BY created_at DESC, id DESC
                LIMIT ?
                """,
                (safe_limit,),
            ).fetchall()
        return [_row_to_report_view(row) for row in rows]
    finally:
        conn.close()


def moderate_report(admin_user_id: int | None, report_id: int, req: CommunityModerationActionRequest) -> CommunityReportView:
    now = int(time.time())
    action = req.action
    note = _normalize_review(req.resolution_note)
    conn = open_db()
    try:
        row = conn.execute(
            """
            SELECT id, post_id
            FROM community_post_reports
            WHERE id = ?
            """,
            (report_id,),
        ).fetchone()
        if row is None:
            raise HTTPException(status_code=404, detail="report not found")
        post_id = int(row["post_id"])
        if action == "hide":
            conn.execute("UPDATE community_posts SET moderation_status = 'hidden' WHERE id = ?", (post_id,))
        elif action == "restore":
            conn.execute("UPDATE community_posts SET moderation_status = 'published' WHERE id = ?", (post_id,))
        elif action == "delete":
            conn.execute("UPDATE community_posts SET moderation_status = 'deleted' WHERE id = ?", (post_id,))

        status = "resolved" if action != "ignore" else "ignored"
        conn.execute(
            """
            UPDATE community_post_reports
            SET status = ?,
                moderation_action = ?,
                resolution_note = ?,
                resolved_at = ?,
                resolved_by = ?
            WHERE id = ?
            """,
            (status, action, note, now, None, report_id),
        )
        conn.commit()
        resolved = conn.execute(
            """
            SELECT id, post_id, reporter_user_id, reason, detail_text, status,
                   moderation_action, resolution_note, created_at, resolved_at
            FROM community_post_reports
            WHERE id = ?
            """,
            (report_id,),
        ).fetchone()
        if resolved is None:
            raise HTTPException(status_code=500, detail="report moderation failed")
        return _row_to_report_view(resolved)
    finally:
        conn.close()


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

    _delete_job_artifacts(job_id)
    _record_job_event(
        job_id=job_id,
        event_type="queued",
        message=f"{safe_job_type} job created",
        payload={"reference_post_id": reference_post_id},
    )
    _enqueue_creative_job(job_id)
    return get_creative_job(user_id=user_id, job_id=job_id)


def _enqueue_creative_job(job_id: int) -> None:
    safe_job_id = max(0, int(job_id))
    if SETTINGS.creative_queue_backend == "redis":
        score = _job_ready_score_for_job(safe_job_id)
        try:
            _REDIS_QUEUE.enqueue_ready(safe_job_id, score)
        except Exception as exc:
            raise HTTPException(status_code=503, detail=f"creative redis queue unavailable: {exc}") from exc
        return
    _ensure_job_worker_started()
    _JOB_QUEUE.put(safe_job_id)


def _enqueue_creative_job_with_delay(job_id: int, delay_sec: float) -> None:
    safe_delay = max(0.0, float(delay_sec))
    safe_job_id = max(0, int(job_id))
    if SETTINGS.creative_queue_backend == "redis":
        score = _job_ready_score_for_job(safe_job_id)
        execute_at = int(time.time() + safe_delay)
        try:
            _REDIS_QUEUE.enqueue_delayed(safe_job_id, score, execute_at)
        except Exception as exc:
            raise HTTPException(status_code=503, detail=f"creative redis queue unavailable: {exc}") from exc
        return
    if safe_delay <= 0.01:
        _enqueue_creative_job(safe_job_id)
        return

    def _delayed_enqueue() -> None:
        time.sleep(safe_delay)
        _enqueue_creative_job(safe_job_id)

    Thread(target=_delayed_enqueue, name=f"community-job-delay-{safe_job_id}", daemon=True).start()


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
        if SETTINGS.creative_queue_backend != "redis":
            for _ in range(max(1, worker_count)):
                _JOB_QUEUE.put(0)
        logger.info("community.creative.worker.started worker_count=%d", worker_count)


def _job_worker_loop(worker_index: int) -> None:
    last_recover_at = 0
    worker_name = f"community-creative-worker-{worker_index}"
    while True:
        token: int | None = None
        try:
            _touch_worker_heartbeat(worker_name)
            if SETTINGS.creative_queue_backend == "redis":
                token = _REDIS_QUEUE.pop_ready(timeout_sec=SETTINGS.creative_worker_poll_sec)
            else:
                try:
                    token = _JOB_QUEUE.get(timeout=1.0)
                except queue.Empty:
                    token = None

            now = int(time.time())
            if worker_index == 1 and now - last_recover_at >= _CREATIVE_RECOVERY_INTERVAL_SEC:
                _recover_stale_running_jobs()
                last_recover_at = now

            _process_creative_job(
                trigger_job_id=(token if token and token > 0 else None),
                worker_name=worker_name,
            )
        except Exception as exc:
            logger.exception(
                "community.creative.worker.error worker=%d job_id=%s reason=%r",
                worker_index,
                token,
                exc,
            )
        finally:
            _touch_worker_heartbeat(worker_name)
            if token is not None and SETTINGS.creative_queue_backend != "redis":
                _JOB_QUEUE.task_done()


def _process_creative_job(trigger_job_id: int | None = None, worker_name: str = "worker") -> None:
    claimed = _claim_next_creative_job(trigger_job_id=trigger_job_id, worker_name=worker_name)
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
                error_code="unsupported_job_type",
            )
    except _CreativeJobCanceledError as exc:
        _finalize_running_job_canceled(job_id=job_id, reason=str(exc) or "cancelled by user")
    except TimeoutError as exc:
        _handle_job_failure(
            job_id=job_id,
            error_message=str(exc)[:240] or "creative job timeout",
            retryable=True,
            error_code="provider_timeout",
        )
    except HTTPException as exc:
        _handle_job_failure(
            job_id=job_id,
            error_message=str(exc.detail),
            retryable=False,
            error_code="request_invalid",
        )
    except Exception as exc:
        _handle_job_failure(
            job_id=job_id,
            error_message=str(exc)[:240] or "creative job failed",
            retryable=True,
            error_code=_classify_job_error(exc),
        )


def _claim_next_creative_job(
    trigger_job_id: int | None = None,
    worker_name: str = "worker",
) -> tuple[int, str, dict[str, Any]] | None:
    if trigger_job_id is not None and trigger_job_id > 0:
        claimed = _claim_specific_creative_job(job_id=trigger_job_id, worker_name=worker_name)
        if claimed is not None:
            return claimed
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
            if SETTINGS.creative_queue_backend == "redis":
                _REDIS_QUEUE.set_lease(job_id, _CREATIVE_JOB_LEASE_SEC, worker_name)
            _record_job_event(
                job_id=job_id,
                event_type="running",
                message=f"{safe_job_type} job claimed by worker",
                payload={"priority": _CREATIVE_DEFAULT_PRIORITY, "worker_name": worker_name},
            )
            return job_id, safe_job_type, payload if isinstance(payload, dict) else {}
        return None
    finally:
        conn.close()


def _claim_specific_creative_job(job_id: int, worker_name: str) -> tuple[int, str, dict[str, Any]] | None:
    now = int(time.time())
    conn = open_db()
    try:
        row = conn.execute(
            """
            SELECT id, job_type, request_payload, next_retry_at
            FROM community_creative_jobs
            WHERE id = ? AND status = 'queued' AND cancel_requested = 0
            """,
            (job_id,),
        ).fetchone()
        if row is None:
            return None
        next_retry_at = _int_or_none(row["next_retry_at"])
        if next_retry_at is not None and next_retry_at > now:
            return None
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
            (now, now, now + _CREATIVE_JOB_LEASE_SEC, now, job_id),
        )
        conn.commit()
        if cur.rowcount <= 0:
            return None
        payload_text = str(row["request_payload"] or "{}")
        payload = json.loads(payload_text) if payload_text.strip() else {}
        safe_job_type = str(row["job_type"] or "").strip().lower()
        if SETTINGS.creative_queue_backend == "redis":
            _REDIS_QUEUE.set_lease(job_id, _CREATIVE_JOB_LEASE_SEC, worker_name)
        _record_job_event(
            job_id=job_id,
            event_type="running",
            message=f"{safe_job_type} job claimed by worker",
            payload={"worker_name": worker_name},
        )
        return job_id, safe_job_type, payload if isinstance(payload, dict) else {}
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
    result_object = _store_creative_image(job_id, "result", composed_image_base64)
    compare_object = _store_creative_image(job_id, "compare", compare_input_base64)
    conn = open_db()
    try:
        cur = conn.execute(
            """
            UPDATE community_creative_jobs
            SET status = 'success',
                progress = 100,
                implementation_status = ?,
                result_meta = ?,
                result_image_base64 = NULL,
                result_image_path = '',
                result_storage_provider = ?,
                result_storage_key = ?,
                compare_input_base64 = NULL,
                compare_input_path = '',
                compare_storage_provider = ?,
                compare_storage_key = ?,
                mime_type = ?,
                file_size = ?,
                sha256 = ?,
                provider = ?,
                model = ?,
                error_message = '',
                error_code = '',
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
                        "storage_mode": "file",
                        "storage_provider": result_object.provider if result_object else "",
                    },
                    ensure_ascii=False,
                ),
                result_object.provider if result_object else "",
                result_object.storage_key if result_object else "",
                compare_object.provider if compare_object else "",
                compare_object.storage_key if compare_object else "",
                result_object.mime_type if result_object else "",
                result_object.file_size if result_object else 0,
                result_object.sha256 if result_object else "",
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
        if cur.rowcount > 0:
            if SETTINGS.creative_queue_backend == "redis":
                _REDIS_QUEUE.clear_lease(job_id)
            _record_job_event(
                job_id=job_id,
                event_type="success",
                message="creative job finished successfully",
                payload={
                    "provider": provider,
                    "model": model,
                    "storage_mode": "file",
                    "storage_provider": result_object.provider if result_object else "",
                },
            )
        return cur.rowcount > 0
    finally:
        conn.close()


def _mark_job_failed(job_id: int, error_message: str, error_code: str = "job_failed") -> bool:
    now = int(time.time())
    conn = open_db()
    try:
        cur = conn.execute(
            """
            UPDATE community_creative_jobs
            SET status = 'failed',
                progress = 100,
                error_message = ?,
                error_code = ?,
                heartbeat_at = ?,
                lease_expires_at = NULL,
                updated_at = ?,
                finished_at = ?
            WHERE id = ? AND status = 'running'
            """,
            ((error_message or "creative job failed")[:280], (error_code or "job_failed")[:64], now, now, now, job_id),
        )
        conn.commit()
        if cur.rowcount > 0:
            if SETTINGS.creative_queue_backend == "redis":
                _REDIS_QUEUE.clear_lease(job_id)
            _record_job_event(
                job_id=job_id,
                event_type="failed",
                message=(error_message or "creative job failed")[:160],
                payload={"error_code": (error_code or "job_failed")[:64]},
            )
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
                error_code = 'user_canceled',
                updated_at = ?,
                finished_at = ?
            WHERE id = ? AND status = 'running'
            """,
            (safe_reason, now, safe_reason, now, now, job_id),
        )
        conn.commit()
        if cur.rowcount > 0:
            if SETTINGS.creative_queue_backend == "redis":
                _REDIS_QUEUE.clear_lease(job_id)
            _record_job_event(
                job_id=job_id,
                event_type="canceled",
                message=safe_reason,
                payload={"error_code": "user_canceled"},
            )
        return cur.rowcount > 0
    finally:
        conn.close()


def _handle_job_failure(job_id: int, error_message: str, retryable: bool, error_code: str = "job_failed") -> None:
    safe_error = (error_message or "creative job failed")[:280]
    safe_code = (error_code or "job_failed")[:64]
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
                    error_code = ?,
                    started_at = NULL,
                    heartbeat_at = NULL,
                    lease_expires_at = NULL,
                    cancel_reason = '',
                    updated_at = ?,
                    finished_at = NULL
                WHERE id = ? AND status = 'running' AND cancel_requested = 0
                """,
                (retry_count + 1, next_retry_at, safe_error, safe_code, now, job_id),
            )
            conn.commit()
            if cur.rowcount > 0:
                if SETTINGS.creative_queue_backend == "redis":
                    _REDIS_QUEUE.clear_lease(job_id)
                _record_job_event(
                    job_id=job_id,
                    event_type="retry_scheduled",
                    message=safe_error[:160],
                    payload={
                        "error_code": safe_code,
                        "next_retry_at": next_retry_at,
                        "retry_count": retry_count + 1,
                        "delay_sec": delay_sec,
                    },
                )
                _enqueue_creative_job_with_delay(job_id, delay_sec)
            return
    finally:
        conn.close()

    _mark_job_failed(job_id=job_id, error_message=safe_error, error_code=safe_code)


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
        if cur.rowcount > 0 and SETTINGS.creative_queue_backend == "redis":
            _REDIS_QUEUE.set_lease(job_id, _CREATIVE_JOB_LEASE_SEC, "heartbeat")
        return cur.rowcount > 0
    finally:
        conn.close()


def _touch_worker_heartbeat(worker_name: str) -> None:
    if SETTINGS.creative_queue_backend != "redis":
        return
    try:
        _REDIS_QUEUE.touch_worker(worker_name, SETTINGS.creative_worker_heartbeat_ttl_sec)
    except Exception:
        return


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
    stale_heartbeat_before = now - _CREATIVE_STALE_HEARTBEAT_SEC
    conn = open_db()
    try:
        rows = conn.execute(
            """
            SELECT id, retry_count, max_retries, cancel_requested, cancel_reason, heartbeat_at, lease_expires_at
            FROM community_creative_jobs
            WHERE status = 'running'
              AND (
                    (lease_expires_at IS NOT NULL AND lease_expires_at <= ?)
                 OR (lease_expires_at IS NULL AND heartbeat_at IS NOT NULL AND heartbeat_at <= ?)
              )
            ORDER BY COALESCE(lease_expires_at, heartbeat_at) ASC, id ASC
            LIMIT 120
            """,
            (now, stale_heartbeat_before),
        ).fetchall()
        if not rows:
            return

        for row in rows:
            job_id = int(row["id"])
            stale_reason = "lease_expired"
            if _int_or_none(row["lease_expires_at"]) is None:
                stale_reason = "heartbeat_stale"
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
                        error_message = ?,
                        error_code = ?,
                        started_at = NULL,
                        heartbeat_at = NULL,
                        lease_expires_at = NULL,
                        cancel_reason = '',
                        updated_at = ?,
                        finished_at = NULL
                    WHERE id = ? AND status = 'running'
                    """,
                    (
                        retry_count + 1,
                        next_retry_at,
                        f"worker {stale_reason.replace('_', ' ')}, auto retry scheduled",
                        stale_reason,
                        now,
                        job_id,
                    ),
                )
                conn.commit()
                if cur.rowcount > 0:
                    if SETTINGS.creative_queue_backend == "redis":
                        _REDIS_QUEUE.clear_lease(job_id)
                    _record_job_event(
                        job_id=job_id,
                        event_type="recovered",
                        message=f"worker {stale_reason.replace('_', ' ')}, auto retry scheduled",
                        payload={"error_code": stale_reason, "next_retry_at": next_retry_at},
                    )
                    _enqueue_creative_job_with_delay(job_id, delay_sec)
                continue

            conn.execute(
                """
                UPDATE community_creative_jobs
                SET status = 'failed',
                    progress = 100,
                    error_message = ?,
                    error_code = ?,
                    heartbeat_at = ?,
                    lease_expires_at = NULL,
                    updated_at = ?,
                    finished_at = ?
                WHERE id = ? AND status = 'running'
                """,
                (f"worker {stale_reason.replace('_', ' ')}", stale_reason, now, now, now, job_id),
            )
            conn.commit()
            if SETTINGS.creative_queue_backend == "redis":
                _REDIS_QUEUE.clear_lease(job_id)
            _record_job_event(
                job_id=job_id,
                event_type="failed",
                message=f"worker {stale_reason.replace('_', ' ')}",
                payload={"error_code": stale_reason},
            )
    finally:
        conn.close()


def _compute_retry_delay_sec(retry_count: int) -> int:
    # 指数退避 + 抖动：避免同批任务在同一秒回放导致重试风暴。
    base = max(1, _CREATIVE_RETRY_BASE_SEC)
    power = max(0, retry_count)
    exponential = min(_CREATIVE_RETRY_MAX_DELAY_SEC, base * (2**power))
    if _CREATIVE_RETRY_JITTER_RATIO <= 0:
        return max(1, int(exponential))
    jitter_span = max(1.0, float(exponential) * _CREATIVE_RETRY_JITTER_RATIO)
    lower = max(1.0, float(exponential) - jitter_span)
    upper = float(exponential) + jitter_span
    with_jitter = random.uniform(lower, upper)
    return max(1, min(_CREATIVE_RETRY_MAX_DELAY_SEC, int(round(with_jitter))))


def _record_job_event(job_id: int, event_type: str, message: str, payload: dict[str, Any]) -> None:
    now = int(time.time())
    safe_type = (event_type or "info").strip().lower()[:48] or "info"
    safe_message = (message or "").strip()[:280]
    conn = open_db()
    try:
        conn.execute(
            """
            INSERT INTO community_creative_job_events(job_id, event_type, message, payload, created_at)
            VALUES (?, ?, ?, ?, ?)
            """,
            (job_id, safe_type, safe_message, json.dumps(payload, ensure_ascii=False), now),
        )
        conn.commit()
    finally:
        conn.close()


def _creative_job_dir(job_id: int) -> Path:
    return SETTINGS.community_creative_result_dir / f"job_{max(0, int(job_id)):06d}"


def _store_creative_image(job_id: int, label: str, image_base64: str | None):
    try:
        return _OBJECT_STORAGE.store_image(job_id=job_id, label=label, image_base64=image_base64)
    except Exception:
        return None


def _delete_job_artifacts(job_id: int, row: Any | None = None) -> None:
    if row is not None:
        for provider_key in (
            ("result_storage_provider", "result_storage_key"),
            ("compare_storage_provider", "compare_storage_key"),
        ):
            provider = str(row[provider_key[0]] or "").strip()
            storage_key = str(row[provider_key[1]] or "").strip()
            if provider and storage_key:
                _OBJECT_STORAGE.delete_object(provider=provider, storage_key=storage_key)

    job_dir = _creative_job_dir(job_id)
    if not job_dir.exists():
        return
    for path in job_dir.glob("*"):
        try:
            if path.is_file():
                path.unlink()
        except Exception:
            continue
    try:
        job_dir.rmdir()
    except Exception:
        pass


def _load_stored_image_base64(provider_or_path: str, storage_key: str | None = None) -> str | None:
    if storage_key is not None:
        return _OBJECT_STORAGE.load_base64(provider=provider_or_path, storage_key=storage_key)
    raw_path = provider_or_path
    path = _resolve_creative_result_path(raw_path)
    if path is None or not path.exists():
        return None
    return base64.b64encode(path.read_bytes()).decode("utf-8")


def _resolve_creative_result_path(raw_path: str) -> Path | None:
    raw = str(raw_path or "").strip()
    if not raw:
        return None
    path = Path(raw)
    resolved = path.resolve() if path.is_absolute() else (PROJECT_ROOT / path).resolve()
    base_dir = SETTINGS.community_creative_result_dir.resolve()
    if base_dir not in resolved.parents:
        return None
    return resolved


def _delete_local_creative_path(raw_path: str) -> bool:
    path = _resolve_creative_result_path(raw_path)
    if path is None:
        return True
    if not path.exists():
        return True
    try:
        path.unlink()
    except Exception:
        return False

    base_dir = SETTINGS.community_creative_result_dir.resolve()
    parent = path.parent
    while parent != base_dir and parent.exists():
        try:
            parent.rmdir()
        except Exception:
            break
        parent = parent.parent
    return True


def _classify_job_error(exc: Exception) -> str:
    message = str(exc).strip().lower()
    if "timeout" in message:
        return "provider_timeout"
    if "permission" in message or "forbidden" in message:
        return "permission_denied"
    if "decode" in message or "image" in message:
        return "image_processing_failed"
    if "network" in message or "connection" in message or "connect" in message:
        return "network_error"
    return "job_failed"


def creative_queue_snapshot() -> dict[str, object]:
    now = int(time.time())
    failed_after = now - _CREATIVE_FAILED_RECENT_WINDOW_SEC
    events_after = now - _CREATIVE_EVENT_RECENT_WINDOW_SEC
    queue_metrics = _REDIS_QUEUE.metrics(SETTINGS.creative_worker_lease_warn_sec) if SETTINGS.creative_queue_backend == "redis" else None
    storage_connected = True
    try:
        _OBJECT_STORAGE.preflight()
    except Exception:
        storage_connected = False
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
        pending_retry = conn.execute(
            """
            SELECT COUNT(1) AS c
            FROM community_creative_jobs
            WHERE status = 'queued' AND next_retry_at IS NOT NULL
            """
        ).fetchone()
        lease_critical = conn.execute(
            """
            SELECT COUNT(1) AS c
            FROM community_creative_jobs
            WHERE status = 'running'
              AND (
                    (lease_expires_at IS NOT NULL AND lease_expires_at <= ?)
                 OR (lease_expires_at IS NULL AND heartbeat_at IS NOT NULL AND heartbeat_at <= ?)
              )
            """,
            (now, now - _CREATIVE_STALE_HEARTBEAT_SEC),
        ).fetchone()
        lease_warning = conn.execute(
            """
            SELECT COUNT(1) AS c
            FROM community_creative_jobs
            WHERE status = 'running'
              AND lease_expires_at IS NOT NULL
              AND lease_expires_at > ?
              AND lease_expires_at <= ?
            """,
            (now, now + max(1, int(SETTINGS.creative_worker_lease_warn_sec))),
        ).fetchone()
        stored_results = conn.execute(
            """
            SELECT COUNT(1) AS c
            FROM community_creative_jobs
            WHERE (
                result_storage_key IS NOT NULL AND trim(result_storage_key) != ''
            ) OR (
                compare_storage_key IS NOT NULL AND trim(compare_storage_key) != ''
            ) OR (
                result_image_path IS NOT NULL AND trim(result_image_path) != ''
            ) OR (
                compare_input_path IS NOT NULL AND trim(compare_input_path) != ''
            )
            """
        ).fetchone()
        recent_events = conn.execute(
            """
            SELECT COUNT(1) AS c
            FROM community_creative_job_events
            WHERE created_at >= ?
            """,
            (events_after,),
        ).fetchone()
        failure_rows = conn.execute(
            """
            SELECT error_code, COUNT(1) AS c
            FROM community_creative_jobs
            WHERE status = 'failed' AND updated_at >= ?
            GROUP BY error_code
            ORDER BY c DESC, error_code ASC
            LIMIT 5
            """,
            (failed_after,),
        ).fetchall()
        workers_local = sum(1 for thread in _JOB_WORKER_THREADS if thread.is_alive())
        workers_active = (
            max(0, queue_metrics.workers_active)
            if queue_metrics is not None
            else max(0, workers_local)
        )
        connected = bool(queue_metrics.connected) if queue_metrics is not None else True
        return {
            "backend": SETTINGS.creative_queue_backend,
            "connected": connected,
            "queue_connectivity": "ok" if connected else "down",
            "storage_connected": storage_connected,
            "queued": max(0, _safe_int(queued["c"] if queued is not None else 0, default=0)),
            "ready": max(0, queue_metrics.ready if queue_metrics is not None else _safe_int(queued["c"] if queued is not None else 0, default=0)),
            "delayed": max(0, queue_metrics.delayed if queue_metrics is not None else 0),
            "running": max(0, _safe_int(running["c"] if running is not None else 0, default=0)),
            "leases_expiring": max(0, queue_metrics.leases_expiring if queue_metrics is not None else 0),
            "lease_risk": {
                "critical": max(0, _safe_int(lease_critical["c"] if lease_critical is not None else 0, default=0)),
                "warning": max(0, _safe_int(lease_warning["c"] if lease_warning is not None else 0, default=0)),
            },
            "failed_recent": max(0, _safe_int(failed_recent["c"] if failed_recent is not None else 0, default=0)),
            "retry_scheduled": max(0, _safe_int(pending_retry["c"] if pending_retry is not None else 0, default=0)),
            "stored_results": max(0, _safe_int(stored_results["c"] if stored_results is not None else 0, default=0)),
            "events_recent": max(0, _safe_int(recent_events["c"] if recent_events is not None else 0, default=0)),
            "failure_codes_recent": {
                str(row["error_code"] or "unknown"): max(0, _safe_int(row["c"], default=0))
                for row in failure_rows
            },
            "workers": workers_active,
            "workers_active": workers_active,
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
    storage_mode = "database"
    if not provider or not model:
        meta_raw = str(row["result_meta"] or "{}")
        try:
            meta = json.loads(meta_raw) if meta_raw.strip() else {}
            if isinstance(meta, dict):
                provider = provider or str(meta.get("provider") or "")
                model = model or str(meta.get("model") or "")
                storage_mode = str(meta.get("storage_mode") or "database")
        except Exception:
            pass

    result_image = str(row["result_image_base64"] or "") or None
    compare_image = str(row["compare_input_base64"] or "") or None
    result_storage_provider = str(row["result_storage_provider"] or "").strip()
    result_storage_key = str(row["result_storage_key"] or "").strip()
    compare_storage_provider = str(row["compare_storage_provider"] or "").strip()
    compare_storage_key = str(row["compare_storage_key"] or "").strip()
    result_path = str(row["result_image_path"] or "").strip()
    compare_path = str(row["compare_input_path"] or "").strip()
    if result_image is None and result_storage_provider and result_storage_key:
        result_image = _load_stored_image_base64(result_storage_provider, result_storage_key)
    if compare_image is None and compare_storage_provider and compare_storage_key:
        compare_image = _load_stored_image_base64(compare_storage_provider, compare_storage_key)
    if result_image is None and result_path:
        result_image = _load_stored_image_base64(result_path)
    if compare_image is None and compare_path:
        compare_image = _load_stored_image_base64(compare_path)
    if result_storage_key or compare_storage_key or result_path or compare_path:
        storage_mode = "file" if not (row["result_image_base64"] or row["compare_input_base64"]) else "hybrid"

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
        composed_image_base64=result_image,
        compare_input_base64=compare_image,
        storage_mode=storage_mode if storage_mode in {"database", "file", "hybrid"} else "database",  # type: ignore[arg-type]
        error_code=str(row["error_code"] or ""),
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
        scene_type=scene,
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
        scene_type=scene,
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


def _resolve_community_image_path(raw_path: str) -> Path:
    raw = str(raw_path or "").strip()
    if not raw:
        raise HTTPException(status_code=400, detail="invalid image path")

    path = Path(raw)
    resolved = path.resolve() if path.is_absolute() else (PROJECT_ROOT / path).resolve()
    allowed_roots = [
        SETTINGS.community_upload_dir.resolve(),
        SETTINGS.community_demo_asset_dir.resolve(),
    ]
    if all(root not in resolved.parents for root in allowed_roots):
        raise HTTPException(status_code=400, detail="invalid image path")
    return resolved


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

    reference_path = _resolve_community_image_path(str(row["image_path"] or ""))
    if not reference_path.exists():
        raise HTTPException(status_code=404, detail="reference image not found")

    scene = _normalize_scene_type(str(row["scene_type"] or "general"))
    place = _normalize_place_tag(str(row["place_tag"] or ""))
    return reference_path, scene, place


def _scene_layout_profile(scene_type: str, subject_count: int) -> tuple[list[float], list[float], float, float]:
    scene = _normalize_scene_type(scene_type)
    if subject_count <= 1:
        if scene == "portrait":
            return [0.5], [0.93], 0.44, 0.68
        if scene == "night":
            return [0.52], [0.94], 0.36, 0.60
        if scene == "landscape":
            return [0.58], [0.94], 0.34, 0.56
        if scene == "food":
            return [0.5], [0.94], 0.38, 0.58
        return [0.5], [0.94], 0.4, 0.62

    if scene == "portrait":
        return [0.34, 0.68], [0.94, 0.94], 0.30, 0.54
    if scene == "night":
        return [0.33, 0.69], [0.95, 0.95], 0.26, 0.5
    if scene == "landscape":
        return [0.31, 0.71], [0.95, 0.95], 0.25, 0.48
    return [0.34, 0.68], [0.95, 0.95], 0.28, 0.5


def _prepare_subject_layer(
    source: Image.Image,
    bg: Image.Image,
    scene_type: str,
    max_fg_w: int,
    max_fg_h: int,
) -> Image.Image:
    fg = ImageOps.exif_transpose(source).convert("RGBA")
    fg.thumbnail((max_fg_w, max_fg_h), Image.Resampling.LANCZOS)

    existing_alpha = fg.getchannel("A") if "A" in fg.getbands() else Image.new("L", fg.size, color=255)
    mask = Image.new("L", fg.size, color=0)
    draw = ImageDraw.Draw(mask)
    w, h = fg.size
    draw.rounded_rectangle(
        (int(w * 0.08), int(h * 0.03), int(w * 0.92), int(h * 0.98)),
        radius=max(12, int(min(w, h) * 0.16)),
        fill=224,
    )
    draw.ellipse(
        (int(w * 0.16), int(h * 0.02), int(w * 0.84), int(h * 0.48)),
        fill=255,
    )
    mask = mask.filter(ImageFilter.GaussianBlur(radius=max(8, int(min(w, h) * 0.055))))
    alpha = ImageChops.multiply(existing_alpha, mask)

    scene = _normalize_scene_type(scene_type)
    sample_box = (
        max(0, int(bg.width * 0.36)),
        max(0, int(bg.height * 0.52)),
        min(bg.width, int(bg.width * 0.64)),
        min(bg.height, int(bg.height * 0.94)),
    )
    tone = tuple(int(value) for value in ImageStat.Stat(bg.crop(sample_box)).mean[:3])
    rgb = fg.convert("RGB")
    tint_strength = 0.14 if scene in {"night", "landscape"} else 0.1
    harmonized = Image.blend(rgb, Image.new("RGB", fg.size, tone), tint_strength)
    if scene == "night":
        harmonized = harmonized.filter(ImageFilter.GaussianBlur(radius=0.2))
    prepared = harmonized.convert("RGBA")
    prepared.putalpha(alpha)
    return prepared


def _add_ground_shadow(
    canvas: Image.Image,
    subject: Image.Image,
    anchor_x: int,
    anchor_y: int,
    blur_radius: int,
) -> None:
    alpha_bbox = subject.getchannel("A").getbbox()
    if alpha_bbox is None:
        return

    subj_w = max(1, alpha_bbox[2] - alpha_bbox[0])
    shadow_w = max(24, int(subj_w * 0.72))
    shadow_h = max(12, int(subj_w * 0.18))
    left = max(0, anchor_x - shadow_w // 2)
    top = max(0, anchor_y - shadow_h // 2)
    right = min(canvas.width, left + shadow_w)
    bottom = min(canvas.height, top + shadow_h)

    shadow = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(shadow)
    draw.ellipse((left, top, right, bottom), fill=(0, 0, 0, 72))
    shadow = shadow.filter(ImageFilter.GaussianBlur(radius=blur_radius))
    canvas.alpha_composite(shadow)


def _build_compose_input(reference_bytes: bytes, person_bytes: bytes, max_side: int, scene_type: str) -> bytes:
    with Image.open(io.BytesIO(reference_bytes)) as ref_img:
        with Image.open(io.BytesIO(person_bytes)) as person_img:
            bg = ImageOps.exif_transpose(ref_img).convert("RGB")
            scene = _normalize_scene_type(scene_type)
            if max(bg.size) > max_side:
                bg.thumbnail((max_side, max_side), Image.Resampling.LANCZOS)

            anchors_x, anchors_y, width_ratio, height_ratio = _scene_layout_profile(scene, 1)
            fg = _prepare_subject_layer(
                source=person_img,
                bg=bg,
                scene_type=scene,
                max_fg_w=max(64, int(bg.width * width_ratio)),
                max_fg_h=max(64, int(bg.height * height_ratio)),
            )
            x = int(bg.width * anchors_x[0]) - fg.width // 2
            baseline_y = int(bg.height * anchors_y[0])
            y = baseline_y - fg.height
            x = max(0, min(x, bg.width - fg.width))
            y = max(0, min(y, bg.height - fg.height))

            canvas = bg.convert("RGBA")
            _add_ground_shadow(
                canvas=canvas,
                subject=fg,
                anchor_x=x + fg.width // 2,
                anchor_y=y + fg.height - max(4, int(bg.height * 0.01)),
                blur_radius=max(8, int(bg.width * 0.012)),
            )
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
    scene_type: str,
) -> bytes:
    with Image.open(io.BytesIO(reference_bytes)) as ref_img:
        with Image.open(io.BytesIO(person_a_bytes)) as person_a_img:
            with Image.open(io.BytesIO(person_b_bytes)) as person_b_img:
                bg = ImageOps.exif_transpose(ref_img).convert("RGB")
                scene = _normalize_scene_type(scene_type)

                if max(bg.size) > max_side:
                    bg.thumbnail((max_side, max_side), Image.Resampling.LANCZOS)

                anchors_x, anchors_y, width_ratio, height_ratio = _scene_layout_profile(scene, 2)
                fg_a = _prepare_subject_layer(
                    source=person_a_img,
                    bg=bg,
                    scene_type=scene,
                    max_fg_w=max(64, int(bg.width * width_ratio)),
                    max_fg_h=max(64, int(bg.height * height_ratio)),
                )
                fg_b = _prepare_subject_layer(
                    source=person_b_img,
                    bg=bg,
                    scene_type=scene,
                    max_fg_w=max(64, int(bg.width * width_ratio)),
                    max_fg_h=max(64, int(bg.height * height_ratio)),
                )

                canvas = bg.convert("RGBA")
                left_x = int(bg.width * anchors_x[0]) - fg_a.width // 2
                right_x = int(bg.width * anchors_x[1]) - fg_b.width // 2
                baseline_y_a = int(bg.height * anchors_y[0])
                baseline_y_b = int(bg.height * anchors_y[1])
                y_a = baseline_y_a - fg_a.height
                y_b = baseline_y_b - fg_b.height
                left_x = max(0, min(left_x, bg.width - fg_a.width))
                right_x = max(0, min(right_x, bg.width - fg_b.width))
                y_a = max(0, min(y_a, bg.height - fg_a.height))
                y_b = max(0, min(y_b, bg.height - fg_b.height))

                _add_ground_shadow(
                    canvas=canvas,
                    subject=fg_a,
                    anchor_x=left_x + fg_a.width // 2,
                    anchor_y=y_a + fg_a.height - max(4, int(bg.height * 0.01)),
                    blur_radius=max(8, int(bg.width * 0.01)),
                )
                _add_ground_shadow(
                    canvas=canvas,
                    subject=fg_b,
                    anchor_x=right_x + fg_b.width // 2,
                    anchor_y=y_b + fg_b.height - max(4, int(bg.height * 0.01)),
                    blur_radius=max(8, int(bg.width * 0.01)),
                )
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


def _build_pose_hint(scene_type: str, place_tag: str, post_type: str) -> str:
    scene = _normalize_scene_type(scene_type)
    if scene == "portrait":
        hint = f"{place_tag or '当前地点'} 建议人物肩线与镜头略成角度，手臂动作尽量打开，别贴紧身体。"
    elif scene == "landscape":
        hint = "风景复刻优先找一个停顿动作，比如回头、抬手或缓慢行走，避免僵直站桩。"
    elif scene == "night":
        hint = "夜景建议动作更稳，停顿 1 秒再按快门，表情和光影都会更完整。"
    elif scene == "food":
        hint = "美食复刻建议只保留手部动作，比如倒奶、夹菜或举杯，避免主体太杂。"
    else:
        hint = "通用场景建议先站稳重心，再用手部和视线补情绪，动作越简单越耐看。"
    if post_type == "relay":
        hint += " 接力图可以保留站位，只换手势或视线方向。"
    return hint


def _build_framing_hint(scene_type: str, place_tag: str, post_type: str) -> str:
    scene = _normalize_scene_type(scene_type)
    if scene == "portrait":
        hint = "人物头顶留白不要过大，脚下保留一点环境，会更像真实现场。"
    elif scene == "landscape":
        hint = f"{place_tag or '这个地点'} 建议保留前景引导线或树枝边框，让远景更有层次。"
    elif scene == "night":
        hint = "夜景尽量把最亮的灯牌留在画面边缘，不要正顶在人物头后。"
    elif scene == "food":
        hint = "食物主体占画面 60% 左右最稳，桌面杂物尽量压到四角。"
    else:
        hint = "先拍一张中景确认主体位置，再决定要不要往前收近一点。"
    if post_type == "relay":
        hint += " 接力图记得保留上一张最有辨识度的结构元素。"
    return hint


def _build_timing_hint(scene_type: str, place_tag: str) -> str:
    scene = _normalize_scene_type(scene_type)
    if scene == "night":
        return f"{place_tag or '当前地点'} 最适合蓝调到入夜前 15 分钟，颜色会更柔和。"
    if scene == "portrait":
        return "人像尽量挑风停的间隙连拍 3 张，表情和发丝更容易稳定。"
    if scene == "landscape":
        return "风景复刻建议多等 3 到 5 分钟观察云层和高光变化，再决定最终机位。"
    if scene == "food":
        return "美食建议在刚上桌、蒸汽还在的时候先拍主图，再补细节。"
    return "先拍一张确认构图，再微调位置和曝光，第二张往往更稳。"


def _build_alignment_checks(scene_type: str, post_type: str) -> list[str]:
    scene = _normalize_scene_type(scene_type)
    checks = [
        "确认手机水平，避免建筑或海平线倾斜。",
        "先看背景边缘是否干净，再让主体进入画面。",
        "曝光先保高光，再微调主体亮度。",
    ]
    if scene == "portrait":
        checks.append("人物头顶不要切太紧，手肘和手掌尽量完整入镜。")
    elif scene == "landscape":
        checks.append("检查前景、主体、远景是否至少形成两层以上关系。")
    elif scene == "night":
        checks.append("亮部灯牌不要爆白，暗部人物不要完全糊掉。")
    elif scene == "food":
        checks.append("桌面边缘和餐具线条尽量整齐，避免无关纸巾抢画面。")
    if post_type == "relay":
        checks.append("接力复刻时保留原图最核心的站位或方向线。")
    return checks


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
        cur = conn.execute(
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
        job_id = int(cur.lastrowid)
        conn.commit()
    finally:
        conn.close()
    _record_job_event(
        job_id=job_id,
        event_type="recorded",
        message=f"{job_type} result recorded",
        payload={"implementation_status": safe_status},
    )


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
    if not SETTINGS.community_seed_enabled:
        return
    if SETTINGS.community_seed_count <= 0:
        return

    with _SEED_LOCK:
        conn = open_db()
        try:
            row = conn.execute(
                "SELECT COUNT(1) AS c FROM community_posts WHERE moderation_status = 'published'"
            ).fetchone()
            existing_count = int(row["c"]) if row is not None else 0
            if existing_count > 0:
                return
            inserted = seed_demo_content(
                conn,
                reset=False,
                max_posts=None,
            )
            if inserted <= 0:
                return
            conn.commit()
        finally:
            conn.close()


# -----------------------------
# Utility
# -----------------------------


def run_creative_worker_forever() -> None:
    _ensure_job_worker_started()
    logger.info(
        "community.creative.worker.process backend=%s redis_url=%s storage_provider=%s embedded=%s",
        SETTINGS.creative_queue_backend,
        SETTINGS.creative_redis_url,
        SETTINGS.creative_storage_provider,
        SETTINGS.creative_embedded_worker,
    )
    while True:
        alive = sum(1 for thread in _JOB_WORKER_THREADS if thread.is_alive())
        if alive <= 0:
            raise RuntimeError("creative workers are not alive")
        time.sleep(10)


def worker_startup_self_check() -> tuple[bool, list[str]]:
    issues: list[str] = []
    conn = None
    try:
        conn = open_db()
        conn.execute("SELECT 1").fetchone()
    except Exception as exc:
        issues.append(f"db_unavailable:{exc}")
    finally:
        if conn is not None:
            conn.close()

    if SETTINGS.creative_queue_backend == "redis" and not _REDIS_QUEUE.ping():
        issues.append("redis_unavailable")

    try:
        _OBJECT_STORAGE.preflight()
    except Exception as exc:
        issues.append(f"storage_unavailable:{exc}")

    return len(issues) == 0, issues


def recover_stuck_creative_jobs(limit: int = 240, dry_run: bool = False) -> dict[str, int]:
    now = int(time.time())
    safe_limit = max(1, min(int(limit), 2000))
    stale_heartbeat_before = now - _CREATIVE_STALE_HEARTBEAT_SEC
    conn = open_db()
    try:
        stale_running_rows = conn.execute(
            """
            SELECT id
            FROM community_creative_jobs
            WHERE status = 'running'
              AND (
                    (lease_expires_at IS NOT NULL AND lease_expires_at <= ?)
                 OR (lease_expires_at IS NULL AND heartbeat_at IS NOT NULL AND heartbeat_at <= ?)
              )
            ORDER BY COALESCE(lease_expires_at, heartbeat_at) ASC, id ASC
            LIMIT ?
            """,
            (now, stale_heartbeat_before, safe_limit),
        ).fetchall()
        due_queued_rows = conn.execute(
            """
            SELECT id
            FROM community_creative_jobs
            WHERE status = 'queued'
              AND cancel_requested = 0
              AND (next_retry_at IS NULL OR next_retry_at <= ?)
            ORDER BY priority DESC, created_at ASC, id ASC
            LIMIT ?
            """,
            (now, safe_limit),
        ).fetchall()
    finally:
        conn.close()

    due_queued_ids = [int(row["id"]) for row in due_queued_rows]
    summary = {
        "stale_running_found": len(stale_running_rows),
        "queued_due_found": len(due_queued_ids),
        "recovered_running": 0,
        "requeued_due": 0,
        "enqueue_failed": 0,
    }
    if dry_run:
        return summary

    before = creative_queue_snapshot()
    _recover_stale_running_jobs()
    after = creative_queue_snapshot()
    recovered_est = max(0, _safe_int(before.get("running", 0), 0) - _safe_int(after.get("running", 0), 0))
    summary["recovered_running"] = min(summary["stale_running_found"], recovered_est)

    for job_id in due_queued_ids:
        try:
            _enqueue_creative_job(job_id)
            summary["requeued_due"] += 1
        except Exception:
            summary["enqueue_failed"] += 1
            _record_job_event(
                job_id=job_id,
                event_type="recover_enqueue_failed",
                message="recover script failed to enqueue due queued job",
                payload={"job_id": job_id},
            )
    return summary


def cleanup_expired_creative_objects(
    ttl_sec: int | None = None,
    batch: int = 120,
    dry_run: bool = False,
) -> dict[str, int]:
    now = int(time.time())
    safe_ttl = max(3600, int(ttl_sec or _CREATIVE_RESULT_TTL_SEC))
    safe_batch = max(1, min(int(batch), 2000))
    cutoff = now - safe_ttl
    conn = open_db()
    try:
        rows = conn.execute(
            """
            SELECT
                id,
                finished_at,
                result_storage_provider,
                result_storage_key,
                compare_storage_provider,
                compare_storage_key,
                result_image_path,
                compare_input_path
            FROM community_creative_jobs
            WHERE status = 'success'
              AND finished_at IS NOT NULL
              AND finished_at <= ?
              AND (
                    (result_storage_key IS NOT NULL AND trim(result_storage_key) != '')
                 OR (compare_storage_key IS NOT NULL AND trim(compare_storage_key) != '')
                 OR (result_image_path IS NOT NULL AND trim(result_image_path) != '')
                 OR (compare_input_path IS NOT NULL AND trim(compare_input_path) != '')
              )
            ORDER BY finished_at ASC, id ASC
            LIMIT ?
            """,
            (cutoff, safe_batch),
        ).fetchall()
    finally:
        conn.close()

    summary = {
        "scanned": len(rows),
        "deleted": 0,
        "failed": 0,
        "skipped": 0,
    }
    if dry_run:
        return summary

    conn = open_db()
    try:
        for row in rows:
            job_id = int(row["id"])
            deleted_all = True
            attempts = 0

            result_provider = str(row["result_storage_provider"] or "").strip()
            result_key = str(row["result_storage_key"] or "").strip()
            if result_provider and result_key:
                attempts += 1
                deleted_all = _OBJECT_STORAGE.delete_object(result_provider, result_key) and deleted_all
            compare_provider = str(row["compare_storage_provider"] or "").strip()
            compare_key = str(row["compare_storage_key"] or "").strip()
            if compare_provider and compare_key:
                attempts += 1
                deleted_all = _OBJECT_STORAGE.delete_object(compare_provider, compare_key) and deleted_all

            result_path = str(row["result_image_path"] or "").strip()
            if result_path:
                attempts += 1
                deleted_all = _delete_local_creative_path(result_path) and deleted_all
            compare_path = str(row["compare_input_path"] or "").strip()
            if compare_path:
                attempts += 1
                deleted_all = _delete_local_creative_path(compare_path) and deleted_all

            if attempts <= 0:
                summary["skipped"] += 1
                continue

            if deleted_all:
                conn.execute(
                    """
                    UPDATE community_creative_jobs
                    SET result_storage_provider = '',
                        result_storage_key = '',
                        compare_storage_provider = '',
                        compare_storage_key = '',
                        result_image_path = '',
                        compare_input_path = '',
                        updated_at = ?
                    WHERE id = ?
                    """,
                    (now, job_id),
                )
                summary["deleted"] += 1
                _record_job_event(
                    job_id=job_id,
                    event_type="cleanup_deleted",
                    message="expired creative objects deleted",
                    payload={"ttl_sec": safe_ttl, "cutoff": cutoff},
                )
            else:
                summary["failed"] += 1
                _record_job_event(
                    job_id=job_id,
                    event_type="cleanup_failed",
                    message="failed to delete at least one expired object",
                    payload={"ttl_sec": safe_ttl, "cutoff": cutoff},
                )
        conn.commit()
    finally:
        conn.close()

    return summary


def _row_to_report_view(row: Any) -> CommunityReportView:
    return CommunityReportView(
        id=int(row["id"]),
        post_id=int(row["post_id"]),
        reporter_user_id=int(row["reporter_user_id"]),
        reason=str(row["reason"] or ""),
        detail_text=str(row["detail_text"] or ""),
        status=str(row["status"] or "pending"),
        moderation_action=str(row["moderation_action"] or ""),
        resolution_note=str(row["resolution_note"] or ""),
        created_at=_safe_int(row["created_at"], default=0),
        resolved_at=_int_or_none(row["resolved_at"]),
    )


def _record_post_signal(
    conn: Any,
    user_id: int,
    post_id: int,
    signal_type: str,
    value: float = 1.0,
    dwell_ms: int = 0,
) -> None:
    if user_id <= 0:
        return
    conn.execute(
        """
        INSERT INTO community_post_signals(post_id, user_id, signal_type, value, dwell_ms, created_at)
        VALUES (?, ?, ?, ?, ?, ?)
        """,
        (post_id, user_id, (signal_type or "event").strip().lower()[:32], float(value), max(0, int(dwell_ms)), int(time.time())),
    )


def _record_bulk_post_signals(conn: Any, user_id: int, post_ids: list[int], signal_type: str) -> None:
    for post_id in post_ids:
        _record_post_signal(conn, user_id=user_id, post_id=int(post_id), signal_type=signal_type)


def _job_ready_score(priority: int, created_at: int, job_id: int) -> float:
    safe_priority = max(1, min(999, int(priority)))
    safe_created = max(0, int(created_at))
    safe_job_id = max(0, int(job_id))
    return float((999 - safe_priority) * 10**12 + safe_created * 10**3 + (safe_job_id % 1000))


def _job_ready_score_for_job(job_id: int) -> float:
    conn = open_db()
    try:
        row = conn.execute(
            "SELECT priority, created_at FROM community_creative_jobs WHERE id = ?",
            (job_id,),
        ).fetchone()
    finally:
        conn.close()
    if row is None:
        return float(int(time.time()))
    return _job_ready_score(
        priority=_safe_int(row["priority"], default=_CREATIVE_DEFAULT_PRIORITY),
        created_at=_safe_int(row["created_at"], default=int(time.time())),
        job_id=job_id,
    )


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
