from __future__ import annotations

from app.main import app

__all__ = ["app"]

# 后端启动说明：
# 1) 安装依赖：mamba run -n Cam python -m pip install -r py/requirements.txt
# 2) 启动方式 A（在项目根目录）：mamba run -n Cam python -m uvicorn main:app --app-dir py --host 0.0.0.0 --port 8000
# 3) 启动方式 B（先进入 py 目录）：cd py && mamba run -n Cam python -m uvicorn main:app --host 0.0.0.0 --port 8000
# 4) 健康检查：curl http://127.0.0.1:8000/healthz