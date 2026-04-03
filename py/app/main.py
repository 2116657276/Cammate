from __future__ import annotations

import logging
import os
import time
import uuid

from fastapi import FastAPI
from fastapi import Request
from fastapi import Response

from app.api.routes.analyze import router as analyze_router
from app.api.routes.auth import router as auth_router
from app.api.routes.community import router as community_router
from app.api.routes.feedback import router as feedback_router
from app.api.routes.health import router as health_router
from app.api.routes.retouch import router as retouch_router
from app.api.routes.scene import router as scene_router
from app.core.database import ensure_db
from app.core.logging import request_id_ctx_var
from app.core.logging import setup_logging
from app.services import community_service
from providers.factory import close_provider
from scene_detector import describe_scene_runtime
setup_logging()
logger = logging.getLogger("app.api")


def create_app() -> FastAPI:
    ensure_db()

    app = FastAPI(title="LiveAICapture MVP API", version="0.4.1")
    app.include_router(health_router)
    app.include_router(auth_router)
    app.include_router(scene_router)
    app.include_router(analyze_router)
    app.include_router(retouch_router)
    app.include_router(feedback_router)
    app.include_router(community_router)

    @app.middleware("http")
    async def request_log_middleware(request: Request, call_next) -> Response:
        request_id = request.headers.get("x-request-id") or uuid.uuid4().hex[:8]
        token = request_id_ctx_var.set(request_id)
        start = time.perf_counter()
        status_code = 500
        try:
            response = await call_next(request)
            status_code = response.status_code
            response.headers["x-request-id"] = request_id
            return response
        finally:
            elapsed_ms = int((time.perf_counter() - start) * 1000)
            logger.info(
                "http.request method=%s path=%s status=%d elapsed_ms=%d client=%s",
                request.method,
                request.url.path,
                status_code,
                elapsed_ms,
                request.client.host if request.client else "-",
            )
            request_id_ctx_var.reset(token)

    @app.on_event("shutdown")
    async def close_runtime_resources() -> None:
        await close_provider()

    @app.on_event("startup")
    async def startup_runtime_resources() -> None:
        community_service.bootstrap_creative_runtime()

    has_ark_key = bool(os.getenv("ARK_API_KEY", "").strip() or os.getenv("EXTERNAL_VISION_API_KEY", "").strip())
    logger.info(
        "startup.config AI_PROVIDER=%s ARK_API_KEY_present=%s ARK_MODEL=%s ARK_API_URL=%s ARK_CHAT_API_URL=%s ARK_REASONING_EFFORT=%s ARK_TRUST_ENV=%s ARK_MAX_OUTPUT_TOKENS=%s EXTERNAL_TIMEOUT_SEC=%s APP_LOG_LEVEL=%s APP_LOG_DIR=%s ENV_FILES=%s",
        os.getenv("AI_PROVIDER", "external"),
        has_ark_key,
        os.getenv("ARK_MODEL", "doubao-seed-2-0-lite-260215"),
        os.getenv("ARK_API_URL", "<unset>"),
        os.getenv("ARK_CHAT_API_URL", "<auto_from_ARK_API_URL>"),
        os.getenv("ARK_REASONING_EFFORT", "minimal"),
        os.getenv("ARK_TRUST_ENV", "false"),
        os.getenv("ARK_MAX_OUTPUT_TOKENS", "480"),
        os.getenv("EXTERNAL_TIMEOUT_SEC", "13.2"),
        os.getenv("APP_LOG_LEVEL", "INFO"),
        os.getenv("APP_LOG_DIR", "<project>/logs"),
        os.getenv("APP_CONFIG_LOADED_ENV_FILES", "<none>"),
    )
    logger.info(
        "startup.community APP_DB_PATH=%s COMMUNITY_UPLOAD_DIR=%s COMMUNITY_DEMO_ASSET_DIR=%s COMMUNITY_SEED_MANIFEST_PATH=%s COMMUNITY_CREATIVE_RESULT_DIR=%s COMMUNITY_UPLOAD_MAX_SIDE=%s COMMUNITY_RECOMMEND_LIMIT_DEFAULT=%s COMMUNITY_BLOCKED_WORDS=%s COMMUNITY_IMAGE_API_URL=%s COMMUNITY_IMAGE_MODEL=%s COMMUNITY_SEED_ENABLED=%s COMMUNITY_SEED_COUNT=%s COMMUNITY_CREATIVE_WORKER_COUNT=%s COMMUNITY_CREATIVE_MAX_RETRIES=%s COMMUNITY_CREATIVE_RETRY_BASE_SEC=%s CREATIVE_QUEUE_BACKEND=%s CREATIVE_REDIS_URL=%s CREATIVE_STORAGE_PROVIDER=%s CREATIVE_STORAGE_BUCKET=%s CREATIVE_EMBEDDED_WORKER=%s COMMUNITY_POSE_MODEL=%s",
        os.getenv("APP_DB_PATH", "<project>/app_data.db"),
        os.getenv("COMMUNITY_UPLOAD_DIR", "<project>/uploads/community"),
        os.getenv("COMMUNITY_DEMO_ASSET_DIR", "<project>/demo_assets/community_seed"),
        os.getenv("COMMUNITY_SEED_MANIFEST_PATH", "<project>/demo_assets/community_seed/manifest.json"),
        os.getenv("COMMUNITY_CREATIVE_RESULT_DIR", "<project>/creative_results"),
        os.getenv("COMMUNITY_UPLOAD_MAX_SIDE", "1920"),
        os.getenv("COMMUNITY_RECOMMEND_LIMIT_DEFAULT", "12"),
        os.getenv("COMMUNITY_BLOCKED_WORDS", "广告,引流,加微信"),
        os.getenv("COMMUNITY_IMAGE_API_URL", "<fallback:ARK_IMAGE_API_URL>"),
        os.getenv("COMMUNITY_IMAGE_MODEL", "<fallback:ARK_IMAGE_MODEL>"),
        os.getenv("COMMUNITY_SEED_ENABLED", "true"),
        os.getenv("COMMUNITY_SEED_COUNT", "28"),
        os.getenv("COMMUNITY_CREATIVE_WORKER_COUNT", "2"),
        os.getenv("COMMUNITY_CREATIVE_MAX_RETRIES", "2"),
        os.getenv("COMMUNITY_CREATIVE_RETRY_BASE_SEC", "2"),
        os.getenv("CREATIVE_QUEUE_BACKEND", "redis"),
        os.getenv("CREATIVE_REDIS_URL", "redis://127.0.0.1:6379/0"),
        os.getenv("CREATIVE_STORAGE_PROVIDER", "local"),
        os.getenv("CREATIVE_STORAGE_BUCKET", "cammate-creative"),
        os.getenv("CREATIVE_EMBEDDED_WORKER", "false"),
        os.getenv("COMMUNITY_POSE_MODEL", "yolo11n-pose.pt"),
    )
    scene_info = describe_scene_runtime()
    logger.info(
        "startup.scene_model ready=%s model_count=%s custom_override=%s model_paths=%s name_samples=%s",
        scene_info.get("ready", False),
        scene_info.get("model_count", 0),
        scene_info.get("custom_override", False),
        scene_info.get("model_paths", []),
        scene_info.get("name_samples", []),
    )
    return app


app = create_app()
