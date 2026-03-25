from __future__ import annotations

import sqlite3

from app.core.config import SETTINGS


def open_db() -> sqlite3.Connection:
    conn = sqlite3.connect(SETTINGS.db_path)
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
                feedback_id INTEGER NOT NULL,
                image_path TEXT NOT NULL,
                scene_type TEXT NOT NULL,
                place_tag TEXT NOT NULL,
                rating INTEGER NOT NULL,
                review_text TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL,
                moderation_status TEXT NOT NULL DEFAULT 'published',
                FOREIGN KEY(user_id) REFERENCES users(id),
                FOREIGN KEY(feedback_id) REFERENCES feedback(id)
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
        conn.commit()
    finally:
        conn.close()


def _ensure_feedback_review_text_column(conn: sqlite3.Connection) -> None:
    rows = conn.execute("PRAGMA table_info(feedback)").fetchall()
    cols = {str(row["name"]).strip().lower() for row in rows}
    if "review_text" in cols:
        return
    conn.execute("ALTER TABLE feedback ADD COLUMN review_text TEXT NOT NULL DEFAULT ''")
