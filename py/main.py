from __future__ import annotations

from app.main import app

__all__ = ["app"]

# mamba run -n cam python -m uvicorn main:app --app-dir py --host 0.0.0.0 --port 8000
