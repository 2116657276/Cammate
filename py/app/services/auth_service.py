from __future__ import annotations

import sqlite3
import time

from fastapi import HTTPException

from app.core.config import SETTINGS
from app.core.database import open_db
from app.core.security import create_password_pair, issue_token, validate_email, verify_password
from app.models.schemas import AuthResponse, AuthUser, AuthLoginRequest, AuthRegisterRequest, UserView


def register(req: AuthRegisterRequest) -> AuthResponse:
    email = validate_email(req.email)
    nickname = (req.nickname or "").strip() or email.split("@", 1)[0]
    password_hash, password_salt = create_password_pair(req.password)

    conn = open_db()
    try:
        now = int(time.time())
        try:
            cur = conn.execute(
                "INSERT INTO users(email, password_hash, password_salt, nickname, created_at) VALUES (?, ?, ?, ?, ?)",
                (email, password_hash, password_salt, nickname, now),
            )
            user_id = int(cur.lastrowid)
            conn.commit()
        except sqlite3.IntegrityError as exc:
            raise HTTPException(status_code=409, detail="email already registered") from exc

        token, ttl = _create_session(conn, user_id)
        return AuthResponse(
            user=UserView(id=user_id, email=email, nickname=nickname),
            bearer_token=token,
            expires_in_sec=ttl,
        )
    finally:
        conn.close()


def login(req: AuthLoginRequest) -> AuthResponse:
    email = validate_email(req.email)

    conn = open_db()
    try:
        row = conn.execute(
            "SELECT id, email, nickname, password_hash, password_salt FROM users WHERE email = ?",
            (email,),
        ).fetchone()
        if row is None:
            raise HTTPException(status_code=401, detail="invalid credentials")

        if not verify_password(req.password, str(row["password_hash"]), str(row["password_salt"])):
            raise HTTPException(status_code=401, detail="invalid credentials")

        token, ttl = _create_session(conn, int(row["id"]))
        return AuthResponse(
            user=UserView(id=int(row["id"]), email=str(row["email"]), nickname=str(row["nickname"])),
            bearer_token=token,
            expires_in_sec=ttl,
        )
    finally:
        conn.close()


def get_user_by_token(token: str) -> AuthUser:
    now = int(time.time())
    conn = open_db()
    try:
        row = conn.execute(
            """
            SELECT u.id, u.email, u.nickname, s.expires_at
            FROM sessions s
            JOIN users u ON u.id = s.user_id
            WHERE s.token = ?
            """,
            (token,),
        ).fetchone()
        if row is None:
            raise HTTPException(status_code=401, detail="invalid session")

        if int(row["expires_at"]) <= now:
            conn.execute("DELETE FROM sessions WHERE token = ?", (token,))
            conn.commit()
            raise HTTPException(status_code=401, detail="session expired")

        return AuthUser(
            id=int(row["id"]),
            email=str(row["email"]),
            nickname=str(row["nickname"]),
            token=token,
        )
    finally:
        conn.close()


def logout(token: str) -> None:
    conn = open_db()
    try:
        conn.execute("DELETE FROM sessions WHERE token = ?", (token,))
        conn.commit()
    finally:
        conn.close()


def _create_session(conn: sqlite3.Connection, user_id: int) -> tuple[str, int]:
    token = issue_token()
    now = int(time.time())
    expires_at = now + SETTINGS.session_ttl_sec
    conn.execute(
        "INSERT INTO sessions(token, user_id, expires_at, created_at) VALUES (?, ?, ?, ?)",
        (token, user_id, expires_at, now),
    )
    conn.commit()
    return token, SETTINGS.session_ttl_sec
