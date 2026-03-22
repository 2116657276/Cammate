# Cammate 开发文档

本文档描述当前仓库的真实实现状态（`main` 分支），用于开发、排障与协作。

## 1. 系统总览

Cammate 由两部分组成：

- Android 客户端（`kotlin/`，Jetpack Compose + CameraX）
- FastAPI 服务端（`py/`，场景识别 + 云端分析 + 云端修图）

职责边界：

- 客户端负责采帧、交互、拍照、请求编排、结果展示与反馈上报
- 服务端负责鉴权、YOLO/YuNet 场景与主体检测、云端构图建议与修图代理

## 2. 关键流程与数据流

### 2.1 登录与会话

1. 客户端调用 `/auth/register` 或 `/auth/login`
2. 服务端返回 `bearer_token`
3. 客户端持久化 token（DataStore）
4. 后续接口通过 `Authorization: Bearer <token>` 访问

### 2.2 场景识别与构图建议

1. 客户端摄像头帧编码为 JPEG/Base64
2. 调用 `/scene/detect`，服务端融合 YOLO + YuNet 得到 `scene/bbox/center`
3. 客户端携带 `subject_center_norm/subject_bbox_norm` 调用 `/analyze`（NDJSON）
4. 服务端调用外部模型返回 `scene/strategy/target/ui/param/done`
5. 客户端叠加构图线、目标点、主体框与移动箭头

### 2.3 拍照后修图与反馈

1. 拍照后用户可选择继续原图或进入修图
2. 修图调用 `/retouch`，支持模板或自定义提示词
3. 服务端调用豆包图片 API，返回 `retouched_image_base64`
4. 客户端可继续修图或完成，最终进入反馈页调用 `/feedback`

## 3. 模块映射（核心）

### 3.1 Android 端

- `MainViewModel`：核心状态机与请求调度（场景、分析、修图、反馈）
- `ui/*Screen.kt`：页面层（登录/相机/设置/修图/反馈）
- `network/*ApiClient.kt`：接口封装（auth/scene/analyze/retouch/feedback）
- `camera/FrameEncoder.kt`：图像帧编码
- `data/*Repository.kt`：本地设置与会话存储

说明：当前实现中 `GuideProvider` 配置仍保留，但行为统一走云端分析链路。

### 3.2 服务端

- `app/main.py`：应用装配、日志中间件、路由注册
- `app/api/routes/*.py`：HTTP 路由
- `app/services/*.py`：业务服务（analyze/retouch/auth/feedback）
- `app/vision/*`：YOLO + YuNet + 融合策略
- `providers/external_provider.py`：构图建议云端调用
- `providers/image_edit_provider.py`：修图云端调用

## 4. 配置与运行

### 4.1 后端启动

在项目根目录：

```bash
mamba run -n Cam python -m pip install -r py/requirements.txt
mamba run -n Cam python -m uvicorn main:app --app-dir py --host 0.0.0.0 --port 8000
```

健康检查：

```bash
curl http://127.0.0.1:8000/healthz
```

### 4.2 环境变量分层

加载顺序：

1. 进程环境变量（已有值优先）
2. `py/.env.local`
3. `py/.env`

关键配置分两套（严格隔离）：

- 分析：`ARK_*`（`ARK_API_KEY` 等）
- 修图：`ARK_IMAGE_*`（`ARK_IMAGE_API_KEY` 等，不回退到 `ARK_API_KEY`）

### 4.3 客户端运行

1. Android Studio 打开 `kotlin/`
2. 运行到模拟器/真机
3. `Settings` 中配置 `Server URL`

## 5. 日志与排障

- 服务端日志：`py/logs/server.log`（滚动文件）
- 客户端日志：应用私有目录 `files/logs/client.log`
- 链路追踪：客户端请求会带 `x-request-id`，服务端日志统一输出 `req=<id>`

常用排障入口：

- 登录问题：`/auth/*` + token 解析（`app/core/security.py`）
- 分析超时：`external.analyze.*` 日志（prepare/request/parse/total）
- 修图失败：`retouch.provider.*` 日志（upstream status、download、elapsed）

## 6. 维护建议

- 分支建议：`main` 作为稳定分支，`preview` 作为集成/验收分支；常规采用 `main -> preview` 合并，避免重写历史
- 提交建议：功能与文档分开提交，便于回溯
- 稳定性优先：优先保留已有接口与请求结构，渐进优化阈值参数
- 安全建议：不要把真实 Key 提交到仓库；本地仅放在 `.env.local`
