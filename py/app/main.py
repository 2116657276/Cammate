from __future__ import annotations

from fastapi import FastAPI

from app.api.routes.analyze import router as analyze_router
from app.api.routes.auth import router as auth_router
from app.api.routes.feedback import router as feedback_router
from app.api.routes.health import router as health_router
from app.api.routes.retouch import router as retouch_router
from app.core.database import ensure_db


def create_app() -> FastAPI:
    ensure_db()

    app = FastAPI(title="LiveAICapture MVP API", version="0.4.1")
    app.include_router(health_router)
    app.include_router(auth_router)
    app.include_router(analyze_router)
    app.include_router(retouch_router)
    app.include_router(feedback_router)
    return app


app = create_app()
