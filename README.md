# Cammate

Android + FastAPI 的 AI 拍摄应用，支持完整流程：
`登录/注册 -> 场景识别 -> AI分析建议 -> 拍照 -> AI修图 -> 评分反馈`

## 项目结构

```text
.
├── kotlin/                # Android 客户端（Jetpack Compose + CameraX）
├── py/                    # FastAPI 服务端
│   ├── app/               # 路由、服务、模型、核心配置
│   ├── app/vision/        # 视觉检测模块（YOLO 场景 + YuNet 人脸 + 融合）
│   ├── models/            # 模型目录（scene_yolo11n.pt / face_detection_yunet_2023mar.onnx）
│   ├── main.py            # 服务入口（导出 app）
│   ├── scene_detector.py  # 兼容入口（内部转发到 app/vision）
│   └── requirements.txt
└── README.md
```

## 快速启动

### 1) Python 服务端（mamba: cam）

```bash
cd py
mamba activate cam
python -m pip install -r requirements.txt
uvicorn main:app --host 0.0.0.0 --port 8000
```

可选：把密钥写到 `py/.env.local`（推荐），启动时会自动加载，无需每次 `export`：

```bash
cd py
cat > .env.local <<'EOF'
ARK_API_KEY=你的key
ARK_API_URL=https://ark.cn-beijing.volces.com/api/v3/responses
ARK_CHAT_API_URL=https://ark.cn-beijing.volces.com/api/v3/chat/completions
ARK_MODEL=doubao-seed-2-0-lite-260215
ARK_REASONING_EFFORT=minimal
AI_PROVIDER=external
EOF
```

健康检查：

```bash
curl http://127.0.0.1:8000/healthz
# {"ok":true}
```

### 2) Android 客户端

1. Android Studio 打开 `kotlin/`
2. 等待 Gradle Sync
3. 运行到模拟器/真机
4. 在 `Settings` 确认 `Server URL`

默认地址：
- 模拟器：`http://10.0.2.2:8000`
- 真机：`http://<你的电脑局域网IP>:8000`

## 视觉模型说明（YOLO + YuNet）

当前默认使用以下模型（均在 `py/models/`）：
- `scene_yolo11n.pt`：YOLO 场景识别与人体/物体候选框
- `face_detection_yunet_2023mar.onnx`：YuNet 人脸框（人像优先）

识别策略：
1. YOLO 先识别场景与候选框
2. 人像模式优先用 YuNet 出脸框；无脸时回退 YOLO 的头肩/上半身框
3. 非人像模式若有人，优先 YOLO 人体/上半身框；否则用场景目标框

推荐环境变量：

```bash
export SCENE_YOLO_MODEL=./models/scene_yolo11n.pt
export SCENE_YUNET_MODEL=./models/face_detection_yunet_2023mar.onnx
export SCENE_YUNET_SCORE_THRESHOLD=0.70
export SCENE_YUNET_NMS_THRESHOLD=0.30
export SCENE_YUNET_TOP_K=256
# 可选：你自己的模型
# export SCENE_YOLO_CUSTOM_MODEL=./models/scene_yolo11_best.pt
```

## AI Provider 环境变量

```bash
# 拍前分析（构图建议，默认 external）
export AI_PROVIDER=external
# export ARK_API_KEY="<your_key>"
# export ARK_API_URL="https://ark.cn-beijing.volces.com/api/v3/responses"
# export ARK_CHAT_API_URL="https://ark.cn-beijing.volces.com/api/v3/chat/completions"
# export ARK_MODEL="doubao-seed-2-0-lite-260215"
# export ARK_REASONING_EFFORT="minimal"  # minimal/low/medium/high
# export ARK_TEMPERATURE="0.33"
# export ARK_TRUST_ENV="false"           # 默认 false，尽量不走本机代理
# export ARK_STRICT_CLOUD="true"         # 默认 true，禁用本地建议兜底
# export ARK_RATE_LIMIT_COOLDOWN_SEC="12"

# 修图（与构图建议 API 完全隔离）
# export ARK_IMAGE_API_URL="https://ark.cn-beijing.volces.com/api/v3/images/generations"
# export ARK_IMAGE_API_KEY="<retouch_key>"   # 必填，不会回退到 ARK_API_KEY
# export ARK_IMAGE_MODEL="doubao-seedream-5-0-260128"
# export ARK_IMAGE_SIZE="2K"
# export ARK_IMAGE_RESPONSE_FORMAT="url"     # url / b64_json
# export ARK_IMAGE_SEQUENTIAL="disabled"     # enabled / disabled
# export ARK_IMAGE_WATERMARK="true"
# export ARK_IMAGE_STREAM="false"
# export ARK_IMAGE_MAX_INPUT_SIDE="2048"     # 输入图长边超过该值会先缩放
# export ARK_IMAGE_JPEG_QUALITY="94"
```

说明：服务端会按顺序读取 `py/.env.local`、`py/.env`，仅在进程环境缺失时填充变量。

## 核心接口

- `GET /healthz`
- `POST /auth/register`
- `POST /auth/login`
- `GET /auth/me`
- `POST /auth/logout`
- `POST /analyze`（NDJSON）
- `POST /retouch`
- `POST /feedback`

## 常见问题

1. 真机连不上后端
- 不要用 `10.0.2.2`
- 改成电脑局域网 IP，并确保同一网络

2. YOLO 没生效
- 确认运行在 `cam` 环境
- 确认 `py/models/scene_yolo11n.pt` 存在
- 确认 `ultralytics` 依赖已安装成功（`pip show ultralytics`）

3. 修图失败
- 确认 `ARK_IMAGE_API_KEY` 已配置（不会回退到 `ARK_API_KEY`）
- 确认 `ARK_IMAGE_API_URL`、`ARK_IMAGE_MODEL` 与账号权限匹配

4. 日志查看
- 服务端日志默认输出到控制台 + `py/logs/server.log`（自动轮转）
- 每条日志带 `req=<request_id>`，便于串联同一次请求
- 可配置：
  - `APP_LOG_LEVEL`（默认 `INFO`）
  - `APP_LOG_DIR`（默认 `py/logs`）
  - `APP_LOG_FILE`（默认 `server.log`）
  - `APP_LOG_MAX_BYTES`（默认 `5242880`）
  - `APP_LOG_BACKUP_COUNT`（默认 `5`）

5. 云端限流（429）
- 已内置 429 退避 + 冷却窗口（`ARK_RATE_LIMIT_COOLDOWN_SEC`，默认 12 秒）
- 冷却期间会快速返回“云端限流冷却中，请稍后再试”

6. 画面移动指引
- `scene/detect` 现在会返回融合后的主体框与中心点（人像优先 YuNet）
- `analyze` 返回目标点后，客户端会计算“移动方向/距离/角度”并在相机页显示移动建议与箭头提示

8. 自动场景切换规则
- 每次进入相机页，只允许一次自动场景切换
- 若用户在相机页手动切换模式，本次拍摄会话不再自动切换
- 离开相机页后再次进入，会重置为可自动切换一次

7. Android 客户端日志
- 客户端关键日志会写入应用私有目录：`files/logs/client.log`（自动轮转）
- 适合在没有 adb 的情况下回溯登录、分析、反馈等错误
- 所有网络请求会附带 `x-request-id`，服务端会原样回传，便于两端日志对齐排查
