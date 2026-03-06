# LiveAICapture_MVP

一个可运行的 Android + FastAPI 项目，现已支持完整拍摄闭环：登录/注册 -> 场景识别 -> AI分析建议 -> 拍照 -> AI修图 -> 评分反馈。

## 目录

```text
.
  kotlin/
  py/
    app/
      api/
      core/
      models/
      services/
  README.md
```

## 功能概览

- 账号体系：邮箱注册、登录、免登校验、退出登录
- 相机主流程：CameraX 预览 + 拍照保存到系统相册（MediaStore）
- 实时场景识别：相机打开即自动识别场景并显示标签
- AI分析建议：用户点击 `AI分析` 按钮后触发，返回 Overlay + 文本 + TTS
- AI修图：调用豆包云端修图接口，成功后可生成并保存修图结果
- 评分反馈：1-5 星评分，上传场景/建议/图片 URI/会话上下文
- 场景识别引擎：YOLO11 混合识别（不可用时自动回退启发式）

---

## 1) 后端启动

```bash
cd py
pip install -r requirements.txt
uvicorn main:app --host 0.0.0.0 --port 8000
```

健康检查：

```bash
curl http://127.0.0.1:8000/healthz
# {"ok": true}
```

---

## 2) Android 运行

1. Android Studio 打开 `kotlin/`
2. 等待 Gradle Sync
3. 运行到模拟器或真机

默认后端地址：
- 模拟器：`http://10.0.2.2:8000`
- 真机：`http://<你的电脑局域网IP>:8000`

可在 App 的 `Settings` 页面修改：
- `Server URL`
- `分析间隔(ms)`
- `语音提示`
- `Debug 原始响应`
- `拍摄模式`
- `引导来源`

---

## 3) 环境变量

### 场景识别

```bash
# 默认：YOLO11 混合（不可用自动回退）
export SCENE_DETECTOR=yolo_hybrid
export SCENE_YOLO_MODEL=yolo11n.pt
```

### 拍前分析 Provider

```bash
# 默认（无 Key 可运行）
export AI_PROVIDER=mock

# 外部 API（Volcengine Ark Responses）
export AI_PROVIDER=external
export ARK_API_KEY="<your_key>"
export ARK_API_URL="https://ark.cn-beijing.volces.com/api/v3/responses"
export ARK_MODEL="doubao-seed-2-0-mini-260215"
export EXTERNAL_TIMEOUT_SEC=20
```

### 豆包修图

```bash
export ARK_API_KEY="<your_key>"
export ARK_IMAGE_API_URL="https://ark.cn-beijing.volces.com/api/v3/images/generations"
export ARK_IMAGE_MODEL="doubao-seedream-3-0-t2i-250415"
export ARK_IMAGE_TIMEOUT_SEC=45
```

---

## 4) API 说明

### GET /healthz

响应：

```json
{"ok": true}
```

### POST /auth/register

请求：

```json
{
  "email": "demo@example.com",
  "password": "12345678",
  "nickname": "zhou"
}
```

响应：

```json
{
  "user": {"id": 1, "email": "demo@example.com", "nickname": "zhou"},
  "bearer_token": "...",
  "expires_in_sec": 604800
}
```

### POST /auth/login

请求：

```json
{
  "email": "demo@example.com",
  "password": "12345678"
}
```

响应同 `/auth/register`。

### GET /auth/me

请求头：`Authorization: Bearer <token>`

响应：

```json
{"id": 1, "email": "demo@example.com", "nickname": "zhou"}
```

### POST /auth/logout

请求头：`Authorization: Bearer <token>`

响应：

```json
{"ok": true}
```

### POST /analyze

请求头：`Authorization: Bearer <token>`

请求：

```json
{
  "image_base64": "...jpeg base64...",
  "client_context": {
    "rotation_degrees": 0,
    "lens_facing": "back",
    "exposure_compensation": 0
  }
}
```

响应：`application/x-ndjson`，每行一个 JSON 事件：

1. `{"type":"scene","scene":"portrait|general|landscape|food|night","mode":"auto|portrait|general","confidence":0.0~1.0}`
2. `{"type":"strategy","grid":"thirds|center","target_point_norm":[0.66,0.5]}`
3. `{"type":"target","bbox_norm":[...],"center_norm":[...]}`（可选）
4. `{"type":"ui","text":"...","level":"info"}`
5. `{"type":"param","exposure_compensation":-1}`（可选）
6. `{"type":"done"}`

### POST /retouch

请求头：`Authorization: Bearer <token>`

请求：

```json
{
  "image_base64": "...",
  "preset": "natural",
  "strength": 0.6,
  "scene_hint": "portrait"
}
```

响应：

```json
{
  "retouched_image_base64": "...",
  "provider": "doubao",
  "model": "doubao-seedream-3-0-t2i-250415"
}
```

### POST /feedback

请求头：`Authorization: Bearer <token>`

请求：

```json
{
  "rating": 5,
  "scene": "portrait",
  "tip_text": "人物眼睛靠近上三分线",
  "photo_uri": "content://...",
  "is_retouch": true,
  "session_meta": {
    "frame_count": 120,
    "tip_count": 8,
    "photo_count": 2,
    "capture_mode": "auto",
    "guide_provider": "cloud",
    "retouch_preset": "natural"
  }
}
```

响应：

```json
{"feedback_id": 1}
```

---

## 5) 常见问题

1. 真机访问后端失败
- 真机不能用 `10.0.2.2`
- 改成电脑局域网 IP（如 `http://192.168.1.100:8000`）
- 手机和电脑必须在同一局域网，并放行 8000 端口

2. 修图失败
- 检查 `ARK_API_KEY`、`ARK_IMAGE_API_URL`、`ARK_IMAGE_MODEL`
- 当前策略是失败提示重试并保留原图，不会自动本地修图

3. YOLO11 未生效
- 先安装 `ultralytics`：`pip install ultralytics`
- 检查 `SCENE_DETECTOR=yolo_hybrid` 和模型路径 `SCENE_YOLO_MODEL`

4. 登录后仍回到登录页
- 检查设备时间是否异常
- 检查后端 `SESSION_TTL_SEC` 配置是否过短
