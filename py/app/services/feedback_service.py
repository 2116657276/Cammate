from __future__ import annotations

import json
import time

from app.core.database import open_db
from app.models.schemas import FeedbackRequest


def submit(user_id: int, req: FeedbackRequest) -> int:
    scene = req.scene.strip().lower() or "general"
    tip_text = req.tip_text.strip()
    review_text = req.review_text.strip()
    session_meta = json.dumps(req.session_meta or {}, ensure_ascii=False)

    conn = open_db()
    try:
        now = int(time.time())
        cur = conn.execute(
            """
            INSERT INTO feedback(
                user_id, rating, scene, tip_text, photo_uri, is_retouch, review_text, session_meta, created_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                user_id,
                req.rating,
                scene,
                tip_text,
                req.photo_uri,
                1 if req.is_retouch else 0,
                review_text,
                session_meta,
                now,
            ),
        )
        conn.commit()
        return int(cur.lastrowid)
    finally:
        conn.close()
