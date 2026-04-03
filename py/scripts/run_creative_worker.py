from __future__ import annotations

import logging
import os
import sys
from pathlib import Path


SCRIPT_DIR = Path(__file__).resolve().parent
PROJECT_PY_ROOT = SCRIPT_DIR.parent
if str(PROJECT_PY_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_PY_ROOT))

from app.core.database import ensure_db
from app.core.logging import setup_logging
from app.services.community_service import worker_startup_self_check
from app.services.community_service import run_creative_worker_forever


def main() -> None:
    os.environ.setdefault("CREATIVE_EMBEDDED_WORKER", "true")
    setup_logging()
    ensure_db()
    logger = logging.getLogger("app.worker")
    ok, issues = worker_startup_self_check()
    if not ok:
        for issue in issues:
            logger.error("creative.worker.preflight.failed issue=%s", issue)
        raise SystemExit(2)

    logger.info("creative.worker.starting")
    try:
        run_creative_worker_forever()
    except KeyboardInterrupt:
        logger.info("creative.worker.stopped.by_signal")
        raise SystemExit(0)
    except Exception as exc:
        logger.exception("creative.worker.crashed reason=%r", exc)
        raise SystemExit(3)
    raise SystemExit(4)


if __name__ == "__main__":
    sys.exit(main())
