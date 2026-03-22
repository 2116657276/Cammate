# Cammate

Android + FastAPI 的 AI 拍摄应用，主流程为：
`登录/注册 -> 场景识别 -> AI 构图建议 -> 拍照 -> AI 修图 -> 评分反馈`

## 文档索引

- 开发文档：[`DEVELOPMENT.md`](./DEVELOPMENT.md)
- 测试文档：[`TESTING.md`](./TESTING.md)

## 快速启动

### 1) Python 服务端

在项目根目录启动（推荐）：

```bash
mamba run -n Cam python -m pip install -r py/requirements.txt
mamba run -n Cam python -m uvicorn main:app --app-dir py --host 0.0.0.0 --port 8000
```

或进入 `py/` 启动：

```bash
cd py
mamba run -n Cam python -m uvicorn main:app --host 0.0.0.0 --port 8000
```

健康检查：

```bash
curl http://127.0.0.1:8000/healthz
```

### 2) Android 客户端

1. Android Studio 打开 `kotlin/`
2. 等待 Gradle Sync
3. 运行到模拟器/真机
4. 在 `Settings` 中确认 `Server URL`

默认地址：

- 模拟器：`http://10.0.2.2:8000`
- 真机：`http://<你的电脑局域网IP>:8000`

## 核心接口

- `GET /healthz`
- `POST /auth/register`
- `POST /auth/login`
- `GET /auth/me`
- `POST /auth/logout`
- `POST /scene/detect`
- `POST /analyze`（NDJSON）
- `POST /retouch`
- `POST /feedback`

## 环境变量（现状）

服务端会按顺序读取 `py/.env.local`、`py/.env`，仅在进程环境缺失时填充变量。

### 分析链路（构图建议）

```bash
AI_PROVIDER=external
ARK_API_KEY=<analyze_key>
ARK_API_URL=https://ark.cn-beijing.volces.com/api/v3/responses
ARK_MODEL=doubao-seed-2-0-lite-260215
ARK_REASONING_EFFORT=minimal
ARK_TEMPERATURE=0.33
ARK_TRUST_ENV=false
ARK_STRICT_CLOUD=true
EXTERNAL_TIMEOUT_SEC=13.2
ARK_MAX_OUTPUT_TOKENS=480
ARK_RETRY_MAX_OUTPUT_TOKENS=360
ARK_RATE_LIMIT_COOLDOWN_SEC=12
```

分析性能相关（可选）：

```bash
ARK_IMAGE_MAX_SIDE=960
ARK_IMAGE_MIN_SIDE=640
ARK_IMAGE_JPEG_QUALITY=82
ARK_DYNAMIC_IMAGE_SIDE=true
ARK_SLOW_REQUEST_MS=9800
ARK_FAST_REQUEST_MS=4300
ARK_DYNAMIC_SIDE_STEP_DOWN=128
ARK_DYNAMIC_SIDE_STEP_UP=64
```

### 修图链路（与分析链路独立）

```bash
ARK_IMAGE_API_URL=https://ark.cn-beijing.volces.com/api/v3/images/generations
ARK_IMAGE_API_KEY=<retouch_key>
ARK_IMAGE_MODEL=doubao-seedream-5-0-260128
ARK_IMAGE_SIZE=2K
ARK_IMAGE_RESPONSE_FORMAT=url
ARK_IMAGE_SEQUENTIAL=disabled
ARK_IMAGE_WATERMARK=true
ARK_IMAGE_STREAM=false
ARK_IMAGE_MAX_INPUT_SIDE=2048
ARK_IMAGE_JPEG_QUALITY=94
ARK_IMAGE_PORTRAIT_MIN_STRENGTH=0.48
```

## 日志

- 服务端日志：`py/logs/server.log`
- 每次请求包含 `req=<request_id>`，用于客户端与服务端链路对齐排障

客户端更详细维护说明、架构说明和排障建议见 [`DEVELOPMENT.md`](./DEVELOPMENT.md)。
