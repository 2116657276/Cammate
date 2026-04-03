from __future__ import annotations

import sqlite3

from app.core.config import SETTINGS


def open_db() -> sqlite3.Connection:
    conn = sqlite3.connect(SETTINGS.db_path, timeout=8.0)
    conn.execute("PRAGMA foreign_keys = ON")
    conn.execute("PRAGMA busy_timeout = 8000")
    conn.execute("PRAGMA journal_mode = WAL")
    conn.execute("PRAGMA synchronous = NORMAL")
    conn.row_factory = sqlite3.Row
    return conn


def ensure_db() -> None:
    conn = open_db()
    try:
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS users (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                email TEXT UNIQUE NOT NULL,
                password_hash TEXT NOT NULL,
                password_salt TEXT NOT NULL,
                nickname TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
            """
        )
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS sessions (
                token TEXT PRIMARY KEY,
                user_id INTEGER NOT NULL,
                expires_at INTEGER NOT NULL,
                created_at INTEGER NOT NULL,
                FOREIGN KEY(user_id) REFERENCES users(id)
            )
            """
        )
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS feedback (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                user_id INTEGER NOT NULL,
                rating INTEGER NOT NULL,
                scene TEXT NOT NULL,
                tip_text TEXT NOT NULL,
                photo_uri TEXT,
                is_retouch INTEGER NOT NULL,
                review_text TEXT NOT NULL DEFAULT '',
                session_meta TEXT,
                created_at INTEGER NOT NULL,
                FOREIGN KEY(user_id) REFERENCES users(id)
            )
            """
        )
        _ensure_feedback_review_text_column(conn)
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS community_posts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                user_id INTEGER NOT NULL,
                feedback_id INTEGER,
                image_path TEXT NOT NULL,
                scene_type TEXT NOT NULL,
                place_tag TEXT NOT NULL,
                rating INTEGER NOT NULL,
                review_text TEXT NOT NULL DEFAULT '',
                caption TEXT NOT NULL DEFAULT '',
                post_type TEXT NOT NULL DEFAULT 'normal',
                source_type TEXT NOT NULL DEFAULT 'feedback_flow',
                relay_parent_post_id INTEGER,
                style_template_post_id INTEGER,
                like_count INTEGER NOT NULL DEFAULT 0,
                comment_count INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                moderation_status TEXT NOT NULL DEFAULT 'published',
                FOREIGN KEY(user_id) REFERENCES users(id),
                FOREIGN KEY(feedback_id) REFERENCES feedback(id)
            )
            """
        )
        _ensure_community_posts_columns(conn)
        _ensure_community_posts_feedback_nullable(conn)
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS community_post_likes (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                post_id INTEGER NOT NULL,
                user_id INTEGER NOT NULL,
                created_at INTEGER NOT NULL,
                UNIQUE(post_id, user_id),
                FOREIGN KEY(post_id) REFERENCES community_posts(id),
                FOREIGN KEY(user_id) REFERENCES users(id)
            )
            """
        )
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS community_post_comments (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                post_id INTEGER NOT NULL,
                user_id INTEGER NOT NULL,
                text TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                FOREIGN KEY(post_id) REFERENCES community_posts(id),
                FOREIGN KEY(user_id) REFERENCES users(id)
            )
            """
        )
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS community_creative_jobs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                user_id INTEGER NOT NULL,
                job_type TEXT NOT NULL,
                reference_post_id INTEGER NOT NULL,
                status TEXT NOT NULL DEFAULT 'queued',
                progress INTEGER NOT NULL DEFAULT 0,
                priority INTEGER NOT NULL DEFAULT 100,
                retry_count INTEGER NOT NULL DEFAULT 0,
                max_retries INTEGER NOT NULL DEFAULT 0,
                next_retry_at INTEGER,
                started_at INTEGER,
                heartbeat_at INTEGER,
                lease_expires_at INTEGER,
                cancel_requested INTEGER NOT NULL DEFAULT 0,
                cancel_reason TEXT NOT NULL DEFAULT '',
                implementation_status TEXT NOT NULL DEFAULT 'ready',
                request_payload TEXT NOT NULL DEFAULT '{}',
                result_meta TEXT NOT NULL DEFAULT '{}',
                result_image_base64 TEXT,
                result_image_path TEXT NOT NULL DEFAULT '',
                result_storage_provider TEXT NOT NULL DEFAULT '',
                result_storage_key TEXT NOT NULL DEFAULT '',
                compare_input_base64 TEXT,
                compare_input_path TEXT NOT NULL DEFAULT '',
                compare_storage_provider TEXT NOT NULL DEFAULT '',
                compare_storage_key TEXT NOT NULL DEFAULT '',
                mime_type TEXT NOT NULL DEFAULT '',
                file_size INTEGER NOT NULL DEFAULT 0,
                sha256 TEXT NOT NULL DEFAULT '',
                provider TEXT NOT NULL DEFAULT '',
                model TEXT NOT NULL DEFAULT '',
                error_message TEXT NOT NULL DEFAULT '',
                error_code TEXT NOT NULL DEFAULT '',
                request_id TEXT NOT NULL DEFAULT '',
                placeholder_notes TEXT NOT NULL DEFAULT '[]',
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                finished_at INTEGER,
                FOREIGN KEY(user_id) REFERENCES users(id),
                FOREIGN KEY(reference_post_id) REFERENCES community_posts(id)
            )
            """
        )
        _ensure_community_creative_jobs_columns(conn)
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS community_creative_job_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                job_id INTEGER NOT NULL,
                event_type TEXT NOT NULL,
                message TEXT NOT NULL DEFAULT '',
                payload TEXT NOT NULL DEFAULT '{}',
                created_at INTEGER NOT NULL,
                FOREIGN KEY(job_id) REFERENCES community_creative_jobs(id) ON DELETE CASCADE
            )
            """
        )
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS community_post_signals (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                post_id INTEGER NOT NULL,
                user_id INTEGER NOT NULL,
                signal_type TEXT NOT NULL,
                value REAL NOT NULL DEFAULT 1.0,
                dwell_ms INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                FOREIGN KEY(post_id) REFERENCES community_posts(id) ON DELETE CASCADE,
                FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
            )
            """
        )
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS community_post_reports (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                post_id INTEGER NOT NULL,
                reporter_user_id INTEGER NOT NULL,
                reason TEXT NOT NULL,
                detail_text TEXT NOT NULL DEFAULT '',
                status TEXT NOT NULL DEFAULT 'pending',
                moderation_action TEXT NOT NULL DEFAULT '',
                resolution_note TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL,
                resolved_at INTEGER,
                resolved_by INTEGER,
                FOREIGN KEY(post_id) REFERENCES community_posts(id) ON DELETE CASCADE,
                FOREIGN KEY(reporter_user_id) REFERENCES users(id) ON DELETE CASCADE,
                FOREIGN KEY(resolved_by) REFERENCES users(id)
            )
            """
        )
        conn.execute(
            "CREATE INDEX IF NOT EXISTS idx_community_scene_place_created "
            "ON community_posts(scene_type, place_tag, created_at DESC)"
        )
        conn.execute(
            "CREATE INDEX IF NOT EXISTS idx_community_created "
            "ON community_posts(created_at DESC)"
        )
        conn.execute(
            "CREATE INDEX IF NOT EXISTS idx_community_post_type_created "
            "ON community_posts(post_type, created_at DESC)"
        )
        conn.execute(
            "CREATE INDEX IF NOT EXISTS idx_community_relay_parent "
            "ON community_posts(relay_parent_post_id, created_at DESC)"
        )
        conn.execute(
            "CREATE INDEX IF NOT EXISTS idx_community_likes_post_created "
            "ON community_post_likes(post_id, created_at DESC)"
        )
        conn.execute(
            "CREATE INDEX IF NOT EXISTS idx_community_likes_user_created "
            "ON community_post_likes(user_id, created_at DESC)"
        )
        conn.execute(
            "CREATE INDEX IF NOT EXISTS idx_community_comments_post_created "
            "ON community_post_comments(post_id, created_at DESC)"
        )
        conn.execute(
            "CREATE INDEX IF NOT EXISTS idx_community_comments_user_created "
            "ON community_post_comments(user_id, created_at DESC)"
        )
        conn.execute(
            "CREATE INDEX IF NOT EXISTS idx_community_jobs_type_created "
            "ON community_creative_jobs(job_type, created_at DESC)"
        )
        conn.execute(
            "CREATE INDEX IF NOT EXISTS idx_community_jobs_status_retry "
            "ON community_creative_jobs(status, next_retry_at)"
        )
        conn.execute(
            "CREATE INDEX IF NOT EXISTS idx_community_jobs_queue_priority "
            "ON community_creative_jobs(status, next_retry_at, priority DESC, created_at ASC)"
        )
        conn.execute(
            "CREATE INDEX IF NOT EXISTS idx_community_jobs_status_lease "
            "ON community_creative_jobs(status, lease_expires_at)"
        )
        conn.execute(
            "CREATE INDEX IF NOT EXISTS idx_community_job_events_job_created "
            "ON community_creative_job_events(job_id, created_at DESC)"
        )
        conn.execute(
            "CREATE INDEX IF NOT EXISTS idx_community_job_events_type_created "
            "ON community_creative_job_events(event_type, created_at DESC)"
        )
        conn.execute(
            "CREATE INDEX IF NOT EXISTS idx_community_post_signals_post_created "
            "ON community_post_signals(post_id, created_at DESC)"
        )
        conn.execute(
            "CREATE INDEX IF NOT EXISTS idx_community_post_signals_user_created "
            "ON community_post_signals(user_id, created_at DESC)"
        )
        conn.execute(
            "CREATE INDEX IF NOT EXISTS idx_community_post_signals_type_created "
            "ON community_post_signals(signal_type, created_at DESC)"
        )
        conn.execute(
            "CREATE INDEX IF NOT EXISTS idx_community_post_reports_status_created "
            "ON community_post_reports(status, created_at DESC)"
        )
        conn.execute(
            "CREATE INDEX IF NOT EXISTS idx_community_post_reports_post_created "
            "ON community_post_reports(post_id, created_at DESC)"
        )
        conn.commit()
    finally:
        conn.close()


def _ensure_feedback_review_text_column(conn: sqlite3.Connection) -> None:
    rows = conn.execute("PRAGMA table_info(feedback)").fetchall()
    cols = {str(row["name"]).strip().lower() for row in rows}
    if "review_text" in cols:
        return
    conn.execute("ALTER TABLE feedback ADD COLUMN review_text TEXT NOT NULL DEFAULT ''")


def _ensure_community_posts_columns(conn: sqlite3.Connection) -> None:
    rows = conn.execute("PRAGMA table_info(community_posts)").fetchall()
    cols = {str(row["name"]).strip().lower() for row in rows}

    if "caption" not in cols:
        conn.execute("ALTER TABLE community_posts ADD COLUMN caption TEXT NOT NULL DEFAULT ''")
    if "post_type" not in cols:
        conn.execute("ALTER TABLE community_posts ADD COLUMN post_type TEXT NOT NULL DEFAULT 'normal'")
    if "source_type" not in cols:
        conn.execute(
            "ALTER TABLE community_posts ADD COLUMN source_type TEXT NOT NULL DEFAULT 'feedback_flow'"
        )
    if "relay_parent_post_id" not in cols:
        conn.execute("ALTER TABLE community_posts ADD COLUMN relay_parent_post_id INTEGER")
    if "style_template_post_id" not in cols:
        conn.execute("ALTER TABLE community_posts ADD COLUMN style_template_post_id INTEGER")
    if "like_count" not in cols:
        conn.execute("ALTER TABLE community_posts ADD COLUMN like_count INTEGER NOT NULL DEFAULT 0")
    if "comment_count" not in cols:
        conn.execute("ALTER TABLE community_posts ADD COLUMN comment_count INTEGER NOT NULL DEFAULT 0")


def _ensure_community_posts_feedback_nullable(conn: sqlite3.Connection) -> None:
    rows = conn.execute("PRAGMA table_info(community_posts)").fetchall()
    feedback_col = None
    for row in rows:
        if str(row["name"]).strip().lower() == "feedback_id":
            feedback_col = row
            break

    if feedback_col is None:
        return

    # 旧版本建表将 feedback_id 设为 NOT NULL。这里做一次轻量迁移，改为可空，
    # 以支持“社区主动发布”与“评分反馈”两条链路解耦。
    if int(feedback_col["notnull"]) == 0:
        return

    conn.commit()
    conn.execute("PRAGMA foreign_keys = OFF")
    try:
        conn.execute("DROP TABLE IF EXISTS community_posts_new")
        conn.execute(
            """
            CREATE TABLE community_posts_new (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                user_id INTEGER NOT NULL,
                feedback_id INTEGER,
                image_path TEXT NOT NULL,
                scene_type TEXT NOT NULL,
                place_tag TEXT NOT NULL,
                rating INTEGER NOT NULL,
                review_text TEXT NOT NULL DEFAULT '',
                caption TEXT NOT NULL DEFAULT '',
                post_type TEXT NOT NULL DEFAULT 'normal',
                source_type TEXT NOT NULL DEFAULT 'feedback_flow',
                relay_parent_post_id INTEGER,
                style_template_post_id INTEGER,
                like_count INTEGER NOT NULL DEFAULT 0,
                comment_count INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                moderation_status TEXT NOT NULL DEFAULT 'published',
                FOREIGN KEY(user_id) REFERENCES users(id),
                FOREIGN KEY(feedback_id) REFERENCES feedback(id)
            )
            """
        )
        conn.execute(
            """
            INSERT INTO community_posts_new(
                id, user_id, feedback_id, image_path, scene_type, place_tag,
                rating, review_text, caption, post_type, source_type,
                relay_parent_post_id, style_template_post_id,
                like_count, comment_count, created_at, moderation_status
            )
            SELECT
                id, user_id, feedback_id, image_path, scene_type, place_tag,
                rating, review_text, caption, post_type, source_type,
                relay_parent_post_id, style_template_post_id,
                like_count, comment_count, created_at, moderation_status
            FROM community_posts
            """
        )
        conn.execute("DROP TABLE community_posts")
        conn.execute("ALTER TABLE community_posts_new RENAME TO community_posts")
        conn.commit()
    finally:
        conn.execute("PRAGMA foreign_keys = ON")


def _ensure_community_creative_jobs_columns(conn: sqlite3.Connection) -> None:
    rows = conn.execute("PRAGMA table_info(community_creative_jobs)").fetchall()
    cols = {str(row["name"]).strip().lower() for row in rows}

    if "progress" not in cols:
        conn.execute("ALTER TABLE community_creative_jobs ADD COLUMN progress INTEGER NOT NULL DEFAULT 0")
    if "priority" not in cols:
        conn.execute("ALTER TABLE community_creative_jobs ADD COLUMN priority INTEGER NOT NULL DEFAULT 100")
    if "retry_count" not in cols:
        conn.execute("ALTER TABLE community_creative_jobs ADD COLUMN retry_count INTEGER NOT NULL DEFAULT 0")
    if "max_retries" not in cols:
        conn.execute("ALTER TABLE community_creative_jobs ADD COLUMN max_retries INTEGER NOT NULL DEFAULT 0")
    if "next_retry_at" not in cols:
        conn.execute("ALTER TABLE community_creative_jobs ADD COLUMN next_retry_at INTEGER")
    if "started_at" not in cols:
        conn.execute("ALTER TABLE community_creative_jobs ADD COLUMN started_at INTEGER")
    if "heartbeat_at" not in cols:
        conn.execute("ALTER TABLE community_creative_jobs ADD COLUMN heartbeat_at INTEGER")
    if "lease_expires_at" not in cols:
        conn.execute("ALTER TABLE community_creative_jobs ADD COLUMN lease_expires_at INTEGER")
    if "cancel_requested" not in cols:
        conn.execute("ALTER TABLE community_creative_jobs ADD COLUMN cancel_requested INTEGER NOT NULL DEFAULT 0")
    if "cancel_reason" not in cols:
        conn.execute("ALTER TABLE community_creative_jobs ADD COLUMN cancel_reason TEXT NOT NULL DEFAULT ''")
    if "result_image_base64" not in cols:
        conn.execute("ALTER TABLE community_creative_jobs ADD COLUMN result_image_base64 TEXT")
    if "result_image_path" not in cols:
        conn.execute("ALTER TABLE community_creative_jobs ADD COLUMN result_image_path TEXT NOT NULL DEFAULT ''")
    if "result_storage_provider" not in cols:
        conn.execute("ALTER TABLE community_creative_jobs ADD COLUMN result_storage_provider TEXT NOT NULL DEFAULT ''")
    if "result_storage_key" not in cols:
        conn.execute("ALTER TABLE community_creative_jobs ADD COLUMN result_storage_key TEXT NOT NULL DEFAULT ''")
    if "compare_input_base64" not in cols:
        conn.execute("ALTER TABLE community_creative_jobs ADD COLUMN compare_input_base64 TEXT")
    if "compare_input_path" not in cols:
        conn.execute("ALTER TABLE community_creative_jobs ADD COLUMN compare_input_path TEXT NOT NULL DEFAULT ''")
    if "compare_storage_provider" not in cols:
        conn.execute("ALTER TABLE community_creative_jobs ADD COLUMN compare_storage_provider TEXT NOT NULL DEFAULT ''")
    if "compare_storage_key" not in cols:
        conn.execute("ALTER TABLE community_creative_jobs ADD COLUMN compare_storage_key TEXT NOT NULL DEFAULT ''")
    if "mime_type" not in cols:
        conn.execute("ALTER TABLE community_creative_jobs ADD COLUMN mime_type TEXT NOT NULL DEFAULT ''")
    if "file_size" not in cols:
        conn.execute("ALTER TABLE community_creative_jobs ADD COLUMN file_size INTEGER NOT NULL DEFAULT 0")
    if "sha256" not in cols:
        conn.execute("ALTER TABLE community_creative_jobs ADD COLUMN sha256 TEXT NOT NULL DEFAULT ''")
    if "provider" not in cols:
        conn.execute("ALTER TABLE community_creative_jobs ADD COLUMN provider TEXT NOT NULL DEFAULT ''")
    if "model" not in cols:
        conn.execute("ALTER TABLE community_creative_jobs ADD COLUMN model TEXT NOT NULL DEFAULT ''")
    if "error_message" not in cols:
        conn.execute("ALTER TABLE community_creative_jobs ADD COLUMN error_message TEXT NOT NULL DEFAULT ''")
    if "error_code" not in cols:
        conn.execute("ALTER TABLE community_creative_jobs ADD COLUMN error_code TEXT NOT NULL DEFAULT ''")
    if "request_id" not in cols:
        conn.execute("ALTER TABLE community_creative_jobs ADD COLUMN request_id TEXT NOT NULL DEFAULT ''")
    if "placeholder_notes" not in cols:
        conn.execute("ALTER TABLE community_creative_jobs ADD COLUMN placeholder_notes TEXT NOT NULL DEFAULT '[]'")
    if "finished_at" not in cols:
        conn.execute("ALTER TABLE community_creative_jobs ADD COLUMN finished_at INTEGER")
