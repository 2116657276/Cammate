from __future__ import annotations

import base64
import json
import logging
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from app.core.config import PROJECT_ROOT
from app.core.config import SETTINGS

logger = logging.getLogger("app.community.seed")

_SEED_USERS = [
    {"email": "seed_demo_1@cammate.local", "nickname": "青禾"},
    {"email": "seed_demo_2@cammate.local", "nickname": "阿澈"},
    {"email": "seed_demo_3@cammate.local", "nickname": "Luna"},
    {"email": "seed_demo_4@cammate.local", "nickname": "小满"},
    {"email": "seed_demo_5@cammate.local", "nickname": "Nico"},
    {"email": "seed_demo_6@cammate.local", "nickname": "迟夏"},
    {"email": "seed_demo_7@cammate.local", "nickname": "Momo"},
    {"email": "seed_demo_8@cammate.local", "nickname": "木子"},
]

_CATEGORY_COMMENT_TEMPLATES = {
    "landscape": [
        "{place} 这个机位真的很会选，前景一压层次就出来了。",
        "天空和水面都留够了，画面看起来特别舒服。",
        "这种天气真的值得多等一会儿，颜色会越往后越耐看。",
        "风景片最怕拥挤，这张留白和主体比例刚刚好。",
    ],
    "travel": [
        "这种带一点旅行纪念感的小物件，比纯风景更有记忆点。",
        "{place} 这类视角很适合做同款路线收藏。",
        "海风和颜色都很轻，随手一拍就有假日感。",
        "这种小场景比大远景更容易让人记住地点。",
    ],
    "night": [
        "这个时间点拍得真准，颜色还没完全掉下去。",
        "夜色一上来之后反而更有氛围，云层也保住了。",
        "{place} 这种天色太适合慢一点等光线了。",
        "亮部压得住，暗部也没糊，夜景出片率很高。",
    ],
    "portrait": [
        "张开手臂这个动作很自然，海边风感一下子就有了。",
        "人像和背景的距离刚好，画面看着很松弛。",
        "{place} 这种场景真的很适合蓝调时刻拍人像。",
        "动作不复杂但很有情绪，特别适合做封面图。",
    ],
    "food": [
        "这锅颜色太有食欲了，热气一上来就想马上开吃。",
        "甜品和正餐都拍得很干净，摆盘信息一眼能看懂。",
        "{place} 的灯光居然这么适合拍吃的。",
        "这种近距离构图很稳，食物主体一下就立住了。",
    ],
    "pet": [
        "这个眼神真的太会营业了，完全就是主角。",
        "宠物图最怕抓拍糊掉，这张状态特别好。",
        "{place} 这只小家伙看起来就很亲人。",
        "互动感很强，看完会忍不住多停几秒。",
    ],
    "flower": [
        "花瓣层次拍得很干净，颜色也特别温柔。",
        "这种季节感内容一发出来就很像朋友圈日常。",
        "{place} 的春天是真的会让人想停下来拍两张。",
        "近景和留白都拿捏得很好，氛围很轻。",
    ],
    "indoor": [
        "室内这组层次好丰富，文字和花都没有丢细节。",
        "{place} 这种角落太适合做生活感分享了。",
        "暖光把颜色托得很舒服，看起来特别安静。",
        "不是标准打卡照，但很有停下来看的感觉。",
    ],
}

_RELAY_BLUEPRINTS = [
    {
        "source_name": "IMG_4516.jpeg",
        "parent_source_name": "IMG_4539.jpeg",
        "caption": "同一个海边，从风景接到人像，蓝调时刻真的很稳。",
        "review_text": "先拍环境，再补人像，整组内容会更完整。",
        "rating": 5,
    },
    {
        "source_name": "IMG_8573.jpeg",
        "parent_source_name": "IMG_7459.jpeg",
        "caption": "从糖水接力到生日蛋糕，今晚的甜口局圆满收尾。",
        "review_text": "桌面杂物尽量压到边缘，主体会显得更干净。",
        "rating": 4,
    },
    {
        "source_name": "IMG_7012.jpeg",
        "parent_source_name": "IMG_6254.jpeg",
        "caption": "从楼下猫猫接力到街角小狗，治愈值翻倍。",
        "review_text": "宠物图最重要的是互动瞬间，这种抬手就很有代入感。",
        "rating": 5,
    },
    {
        "source_name": "IMG_1203.jpeg",
        "parent_source_name": "IMG_1200.jpeg",
        "caption": "同一天换到水边机位，视野一下子就打开了。",
        "review_text": "风景接力很适合保留同地点但不同层次的视角。",
        "rating": 4,
    },
    {
        "source_name": "IMG_9009.jpeg",
        "parent_source_name": "IMG_9008.jpeg",
        "caption": "海边的云真的每五分钟都在换表情，必须接一张。",
        "review_text": "冬天的海边只要等到天色发粉，就很容易出片。",
        "rating": 5,
    },
    {
        "source_name": "IMG_9985.jpeg",
        "parent_source_name": "IMG_9981.jpeg",
        "caption": "樱花也来接力，同一棵树换个仰角就是另一种心情。",
        "review_text": "花枝和天空的比例一换，画面会立刻轻起来。",
        "rating": 4,
    },
]

_STYLE_TEMPLATE_LINKS = {
    "IMG_1203.jpeg": "IMG_1200.jpeg",
    "IMG_4516.jpeg": "IMG_4539.jpeg",
    "IMG_9009.jpeg": "IMG_9008.jpeg",
    "IMG_9983.jpeg": "IMG_9981.jpeg",
}

_JOB_BLUEPRINTS = [
    {"job_type": "compose", "status": "success", "implementation_status": "ready"},
    {"job_type": "compose", "status": "failed", "implementation_status": "placeholder"},
    {"job_type": "cocreate", "status": "queued", "implementation_status": "placeholder"},
]


def load_seed_manifest() -> list[dict[str, Any]]:
    manifest_path = SETTINGS.community_seed_manifest_path
    if not manifest_path.exists():
        return []

    try:
        payload = json.loads(manifest_path.read_text(encoding="utf-8"))
    except Exception as exc:
        logger.warning("community.seed.manifest_load_failed path=%s reason=%r", manifest_path, exc)
        return []

    items = payload.get("items") if isinstance(payload, dict) else payload
    if not isinstance(items, list):
        return []

    normalized: list[dict[str, Any]] = []
    for raw in items:
        if not isinstance(raw, dict):
            continue
        source_name = str(raw.get("source_name") or "").strip()
        asset_name = str(raw.get("asset_name") or "").strip()
        image_path = _resolve_manifest_image_path(asset_name)
        if not source_name or not asset_name or not image_path.exists():
            continue
        normalized.append(
            {
                "source_name": source_name,
                "asset_name": asset_name,
                "captured_at": str(raw.get("captured_at") or ""),
                "primary_category": str(raw.get("primary_category") or "landscape").strip().lower(),
                "scene_type": str(raw.get("scene_type") or "general").strip().lower(),
                "subject": str(raw.get("subject") or "").strip(),
                "place_tag": str(raw.get("place_tag") or "").strip(),
                "caption": str(raw.get("caption") or "").strip(),
                "review_text": str(raw.get("review_text") or "").strip(),
                "rating": int(raw.get("rating") or 4),
                "tags": [str(item).strip() for item in raw.get("tags", []) if str(item).strip()],
                "allow_as_post": bool(raw.get("allow_as_post", True)),
                "image_rel_path": str(Path("demo_assets") / "community_seed" / asset_name),
            }
        )
    normalized.sort(key=lambda item: (item["captured_at"], item["asset_name"]))
    return normalized


def seed_demo_content(conn: Any, reset: bool = False, max_posts: int | None = None) -> int:
    manifest = load_seed_manifest()
    if not manifest:
        return 0

    if reset:
        _clear_existing_seed_data(conn)

    selected = [item for item in manifest if item.get("allow_as_post", True)]
    if max_posts is not None:
        selected = selected[: max(0, max_posts)]
    if not selected:
        return 0

    seed_users = _ensure_seed_users(conn)
    main_posts = _insert_main_posts(conn, selected, seed_users)
    relay_posts = _insert_relay_posts(conn, selected, main_posts, seed_users)
    _apply_style_templates(conn, main_posts)
    _insert_likes_and_comments(conn, [*main_posts.values(), *relay_posts], seed_users)
    _refresh_counts(conn)
    _insert_demo_jobs(conn, main_posts, seed_users)
    return len(main_posts) + len(relay_posts)


def _resolve_manifest_image_path(asset_name: str) -> Path:
    return (SETTINGS.community_demo_asset_dir / asset_name).resolve()


def _clear_existing_seed_data(conn: Any) -> None:
    rows = conn.execute(
        "SELECT id FROM users WHERE email LIKE 'seed_demo_%@cammate.local' ORDER BY id"
    ).fetchall()
    user_ids = [int(row["id"]) for row in rows]
    if not user_ids:
        return

    placeholders = ",".join("?" for _ in user_ids)
    post_rows = conn.execute(
        f"SELECT id FROM community_posts WHERE user_id IN ({placeholders})",
        tuple(user_ids),
    ).fetchall()
    post_ids = [int(row["id"]) for row in post_rows]

    if post_ids:
        post_placeholders = ",".join("?" for _ in post_ids)
        conn.execute(
            f"DELETE FROM community_post_likes WHERE post_id IN ({post_placeholders})",
            tuple(post_ids),
        )
        conn.execute(
            f"DELETE FROM community_post_comments WHERE post_id IN ({post_placeholders})",
            tuple(post_ids),
        )
        conn.execute(
            f"DELETE FROM community_creative_jobs WHERE reference_post_id IN ({post_placeholders})",
            tuple(post_ids),
        )
        conn.execute(
            f"DELETE FROM community_posts WHERE id IN ({post_placeholders})",
            tuple(post_ids),
        )

    conn.execute(
        f"DELETE FROM community_post_likes WHERE user_id IN ({placeholders})",
        tuple(user_ids),
    )
    conn.execute(
        f"DELETE FROM community_post_comments WHERE user_id IN ({placeholders})",
        tuple(user_ids),
    )
    conn.execute(
        f"DELETE FROM community_creative_jobs WHERE user_id IN ({placeholders})",
        tuple(user_ids),
    )
    conn.execute(f"DELETE FROM feedback WHERE user_id IN ({placeholders})", tuple(user_ids))
    conn.execute(f"DELETE FROM sessions WHERE user_id IN ({placeholders})", tuple(user_ids))
    conn.execute(f"DELETE FROM users WHERE id IN ({placeholders})", tuple(user_ids))


def _ensure_seed_users(conn: Any) -> list[dict[str, Any]]:
    users: list[dict[str, Any]] = []
    base_ts = int(datetime(2024, 5, 1, tzinfo=timezone.utc).timestamp())
    for index, spec in enumerate(_SEED_USERS):
        row = conn.execute(
            "SELECT id, nickname FROM users WHERE email = ?",
            (spec["email"],),
        ).fetchone()
        if row is None:
            cur = conn.execute(
                """
                INSERT INTO users(email, password_hash, password_salt, nickname, created_at)
                VALUES (?, ?, ?, ?, ?)
                """,
                (
                    spec["email"],
                    "seed_password_hash",
                    "seed_password_salt",
                    spec["nickname"],
                    base_ts + index * 600,
                ),
            )
            user_id = int(cur.lastrowid)
            nickname = spec["nickname"]
        else:
            user_id = int(row["id"])
            nickname = str(row["nickname"] or spec["nickname"])
        users.append({"id": user_id, "nickname": nickname, "email": spec["email"]})
    return users


def _insert_main_posts(
    conn: Any,
    manifest: list[dict[str, Any]],
    seed_users: list[dict[str, Any]],
) -> dict[str, dict[str, Any]]:
    inserted: dict[str, dict[str, Any]] = {}
    for index, item in enumerate(manifest):
        user = seed_users[index % len(seed_users)]
        created_at = _parse_manifest_timestamp(item["captured_at"]) + (index % 3) * 1800
        feedback_cur = conn.execute(
            """
            INSERT INTO feedback(
                user_id, rating, scene, tip_text, photo_uri,
                is_retouch, review_text, session_meta, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                user["id"],
                max(1, min(int(item["rating"]), 5)),
                item["scene_type"],
                _tip_text_for_category(item["primary_category"]),
                item["image_rel_path"],
                0,
                item["review_text"],
                json.dumps({"seed": "demo", "source_name": item["source_name"]}, ensure_ascii=False),
                created_at,
            ),
        )
        feedback_id = int(feedback_cur.lastrowid)
        post_cur = conn.execute(
            """
            INSERT INTO community_posts(
                user_id, feedback_id, image_path, scene_type, place_tag,
                rating, review_text, caption, post_type, source_type,
                relay_parent_post_id, style_template_post_id,
                like_count, comment_count, created_at, moderation_status
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'normal', 'feedback_flow', NULL, NULL, 0, 0, ?, 'published')
            """,
            (
                user["id"],
                feedback_id,
                item["image_rel_path"],
                item["scene_type"],
                item["place_tag"],
                max(1, min(int(item["rating"]), 5)),
                item["review_text"],
                item["caption"],
                created_at,
            ),
        )
        inserted[item["source_name"]] = {
            "post_id": int(post_cur.lastrowid),
            "user_id": user["id"],
            "nickname": user["nickname"],
            "created_at": created_at,
            "primary_category": item["primary_category"],
            "place_tag": item["place_tag"],
            "scene_type": item["scene_type"],
            "asset_name": item["asset_name"],
            "image_rel_path": item["image_rel_path"],
        }
    return inserted


def _insert_relay_posts(
    conn: Any,
    manifest: list[dict[str, Any]],
    main_posts: dict[str, dict[str, Any]],
    seed_users: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    manifest_by_source = {item["source_name"]: item for item in manifest}
    inserted: list[dict[str, Any]] = []
    for index, blueprint in enumerate(_RELAY_BLUEPRINTS):
        child = manifest_by_source.get(blueprint["source_name"])
        parent = main_posts.get(blueprint["parent_source_name"])
        if child is None or parent is None:
            continue
        user = seed_users[(index + 3) % len(seed_users)]
        created_at = max(parent["created_at"] + 5400, _parse_manifest_timestamp(child["captured_at"]) + 3600)
        cur = conn.execute(
            """
            INSERT INTO community_posts(
                user_id, feedback_id, image_path, scene_type, place_tag,
                rating, review_text, caption, post_type, source_type,
                relay_parent_post_id, style_template_post_id,
                like_count, comment_count, created_at, moderation_status
            ) VALUES (?, NULL, ?, ?, ?, ?, ?, ?, 'relay', 'direct', ?, NULL, 0, 0, ?, 'published')
            """,
            (
                user["id"],
                child["image_rel_path"],
                child["scene_type"],
                child["place_tag"],
                max(1, min(int(blueprint["rating"]), 5)),
                blueprint["review_text"],
                blueprint["caption"],
                parent["post_id"],
                created_at,
            ),
        )
        inserted.append(
            {
                "post_id": int(cur.lastrowid),
                "user_id": user["id"],
                "nickname": user["nickname"],
                "created_at": created_at,
                "primary_category": child["primary_category"],
                "place_tag": child["place_tag"],
                "scene_type": child["scene_type"],
                "asset_name": child["asset_name"],
                "image_rel_path": child["image_rel_path"],
            }
        )
    return inserted


def _apply_style_templates(conn: Any, main_posts: dict[str, dict[str, Any]]) -> None:
    for source_name, template_source in _STYLE_TEMPLATE_LINKS.items():
        post = main_posts.get(source_name)
        template = main_posts.get(template_source)
        if post is None or template is None:
            continue
        conn.execute(
            "UPDATE community_posts SET style_template_post_id = ? WHERE id = ?",
            (template["post_id"], post["post_id"]),
        )


def _insert_likes_and_comments(
    conn: Any,
    posts: list[dict[str, Any]],
    seed_users: list[dict[str, Any]],
) -> None:
    for index, post in enumerate(posts):
        like_count = 2 + (index % 5)
        comment_count = 1 + (index % 4)

        like_candidates = [user for user in seed_users if user["id"] != post["user_id"]]
        for offset in range(like_count):
            liker = like_candidates[(index + offset) % len(like_candidates)]
            conn.execute(
                """
                INSERT OR IGNORE INTO community_post_likes(post_id, user_id, created_at)
                VALUES (?, ?, ?)
                """,
                (post["post_id"], liker["id"], post["created_at"] + 900 + offset * 300),
            )

        comments = _comment_templates_for_category(post["primary_category"])
        for offset in range(comment_count):
            commenter = like_candidates[(index + offset + 1) % len(like_candidates)]
            text_template = comments[(index + offset) % len(comments)]
            text = text_template.format(place=post["place_tag"], nickname=post["nickname"])
            conn.execute(
                """
                INSERT INTO community_post_comments(post_id, user_id, text, created_at)
                VALUES (?, ?, ?, ?)
                """,
                (post["post_id"], commenter["id"], text, post["created_at"] + 1800 + offset * 420),
            )


def _refresh_counts(conn: Any) -> None:
    conn.execute(
        """
        UPDATE community_posts
        SET like_count = (
            SELECT COUNT(1) FROM community_post_likes l WHERE l.post_id = community_posts.id
        )
        """
    )
    conn.execute(
        """
        UPDATE community_posts
        SET comment_count = (
            SELECT COUNT(1) FROM community_post_comments c WHERE c.post_id = community_posts.id
        )
        """
    )


def _insert_demo_jobs(
    conn: Any,
    main_posts: dict[str, dict[str, Any]],
    seed_users: list[dict[str, Any]],
) -> None:
    asset_sources = [
        "IMG_4516.jpeg",
        "IMG_9983.jpeg",
        "IMG_9008.jpeg",
    ]
    sample_posts = [main_posts.get(source_name) for source_name in asset_sources]
    sample_posts = [item for item in sample_posts if item is not None]
    if not sample_posts:
        return

    for index, blueprint in enumerate(_JOB_BLUEPRINTS):
        reference = sample_posts[index % len(sample_posts)]
        image_path = (PROJECT_ROOT / reference["image_rel_path"]).resolve()
        image_b64 = base64.b64encode(image_path.read_bytes()).decode("utf-8") if image_path.exists() else None
        created_at = reference["created_at"] + 7200 + index * 1800
        status = blueprint["status"]
        progress = 100 if status == "success" else (72 if status == "failed" else 12)
        finished_at = created_at + 180 if status in {"success", "failed"} else None
        updated_at = finished_at or created_at + 60
        error_message = "示例任务：云端合成超时，建议稍后重试。" if status == "failed" else ""
        placeholder_notes = []
        if blueprint["implementation_status"] == "placeholder":
            placeholder_notes = [
                "示例数据：当前结果用于展示任务状态与回退信息。",
                "后续会接入更稳定的队列与对象存储。",
            ]
        conn.execute(
            """
            INSERT INTO community_creative_jobs(
                user_id, job_type, reference_post_id, status, progress, priority,
                retry_count, max_retries, next_retry_at, started_at, heartbeat_at,
                lease_expires_at, cancel_requested, cancel_reason, implementation_status,
                request_payload, result_meta, result_image_base64, compare_input_base64,
                provider, model, error_message, request_id, placeholder_notes,
                created_at, updated_at, finished_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                seed_users[index % len(seed_users)]["id"],
                blueprint["job_type"],
                reference["post_id"],
                status,
                progress,
                100 - index * 5,
                1 if status == "failed" else 0,
                2,
                created_at + 600 if status == "failed" else None,
                created_at + 15 if status != "queued" else None,
                created_at + 45 if status != "queued" else None,
                created_at + 240 if status == "running" else None,
                "",
                blueprint["implementation_status"],
                json.dumps({"strength": 0.45 + index * 0.05}, ensure_ascii=False),
                json.dumps({"provider": "demo", "model": f"{blueprint['job_type']}_demo"}, ensure_ascii=False),
                image_b64 if status == "success" else None,
                image_b64 if status in {"success", "failed"} else None,
                "demo",
                f"{blueprint['job_type']}_demo",
                error_message,
                f"demo-job-{index + 1:02d}",
                json.dumps(placeholder_notes, ensure_ascii=False),
                created_at,
                updated_at,
                finished_at,
            ),
        )


def _parse_manifest_timestamp(value: str) -> int:
    if not value:
        return 0
    text = value.strip()
    if text.endswith("Z"):
        text = text[:-1] + "+00:00"
    return int(datetime.fromisoformat(text).timestamp())


def _tip_text_for_category(category: str) -> str:
    tips = {
        "landscape": "保持地平线稳定，优先保留前景和远景层次。",
        "travel": "抓一个能代表地点的小元素，画面会更有记忆点。",
        "night": "先压住高光，再等颜色最舒服的一分钟。",
        "portrait": "让人物先放松动作，再慢慢补表情和视线。",
        "food": "餐桌边缘尽量收干净，食物主体会更突出。",
        "pet": "多留一点反应时间，互动瞬间最容易出片。",
        "flower": "稍微靠近一点拍花瓣层次，背景自然就柔下来了。",
        "indoor": "室内暖光要保留环境细节，别把亮部拉得太死。",
    }
    return tips.get(category, "保持画面干净，先确认主体再调整构图。")


def _comment_templates_for_category(category: str) -> list[str]:
    if category in _CATEGORY_COMMENT_TEMPLATES:
        return _CATEGORY_COMMENT_TEMPLATES[category]
    return _CATEGORY_COMMENT_TEMPLATES["landscape"]
