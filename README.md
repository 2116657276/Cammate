# Cammate

Android + FastAPI 的 AI 拍摄应用，支持完整流程：
`登录/注册 -> 场景识别 -> AI分析建议 -> 拍照 -> AI修图 -> 评分反馈`

## 项目结构

```text
.
├── kotlin/                # Android 客户端（Jetpack Compose + CameraX）
├── py/                    # FastAPI 服务端
│   ├── app/               # 路由、服务、模型、核心配置
│   ├── models/            # YOLO 模型目录
│   ├── main.py            # 服务入口（导出 app）
│   ├── scene_detector.py  # YOLO + 启发式混合识别
│   └── requirements.txt
└── README.md
```

## 快速启动

### 1) Python 服务端（mamba: Cam）

```bash
cd py
mamba activate Cam
python -m pip install -r requirements.txt
uvicorn main:app --host 0.0.0.0 --port 8000
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

## YOLO 说明（已部署预训练）

当前默认使用 **Ultralytics YOLO11 预训练模型**：
- 本地路径：`py/models/scene_yolo11n.pt`
- 来源：`https://github.com/ultralytics/assets/releases/download/v8.4.0/yolo11n.pt`
- SHA256：`0ebbc80d4a7680d14987a577cd21342b65ecfd94632bd9a8da63ae6417644ee1`

识别策略：
1. `SCENE_YOLO_CUSTOM_MODEL`（若配置）
2. `SCENE_YOLO_MODEL`（默认 `./models/scene_yolo11n.pt`）
3. 启发式规则回退

推荐环境变量：

```bash
export SCENE_DETECTOR=yolo_hybrid
export SCENE_YOLO_MODEL=./models/scene_yolo11n.pt
# 可选：你自己的模型
# export SCENE_YOLO_CUSTOM_MODEL=./models/scene_yolo11_best.pt
```

## AI Provider 环境变量

```bash
# 拍前分析（默认 mock）
export AI_PROVIDER=mock
# export AI_PROVIDER=external
# export ARK_API_KEY="<your_key>"
# export ARK_API_URL="https://ark.cn-beijing.volces.com/api/v3/responses"
# export ARK_MODEL="doubao-seed-2-0-mini-260215"

# 修图（豆包）
# export ARK_API_KEY="<your_key>"
# export ARK_IMAGE_API_URL="https://ark.cn-beijing.volces.com/api/v3/images/generations"
# export ARK_IMAGE_MODEL="doubao-seedream-3-0-t2i-250415"
```

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
- 确认运行在 `Cam` 环境
- 确认 `py/models/scene_yolo11n.pt` 存在
- 确认 `SCENE_DETECTOR=yolo_hybrid`

3. 修图失败
- 检查 `ARK_API_KEY` 和图像接口相关环境变量
- 当前策略是失败提示重试并保留原图
