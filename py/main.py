from __future__ import annotations

from app.main import app

__all__ = ["app"]

# 后端启动说明：
# 1) 安装依赖：mamba run -n Cam python -m pip install -r py/requirements.txt
# 2) 推荐启动（在项目根目录）：mamba run -n Cam python py/scripts/run_api_server.py
# 3) 该脚本默认使用 py/demo_app_data.db，并在 8010 已被旧服务占用时自动重启旧进程
