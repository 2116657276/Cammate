# Cammate 开发文档（当前实现）

本文档只描述当前仓库已落地能力，不写目标蓝图。最终版规划请看 [`DESIGN.md`](./DESIGN.md)。

## 1. 当前版本快照

- 文档更新时间：2026-03-25
- 代码基线：当前工作区（Android + FastAPI）
- 数据库现状：SQLite（`py/app_data.db`）

当前主链路已经可用：

`登录/注册 -> 场景识别 -> AI 构图建议 -> 拍照 -> AI 修图 -> 评分（含文字评价） -> 可选发布社区 -> 社区推荐 -> AI 融合 -> 保存到相册`

## 2. 当前已实现功能

### 2.1 Android 客户端（`kotlin/`）

已实现页面与导航：

- `splash`
- `login` / `register`
- `camera`
- `settings`
- `retouch`
- `feedback`
- `community`

已实现业务能力：

- 登录态持久化与会话恢复（DataStore）
- CameraX 取帧、场景识别联动、AI 分析结果叠加
- 修图模式（模板/自定义）、结果预览与保存
- 评分页新增：
  - 文字评价 `review_text`（0~280）
  - 发布到社区开关
  - 地点标签与风景类型选择
  - 固定提交流程：先提交 `/feedback`，再按开关发布社区
- 社区页三块能力：
  - 社区流（分页加载）
  - 推荐（地点 + 场景类型筛选）
  - AI 融合（选人物图 + 选参考图 + 强度滑杆 + 生成 + 保存）
- 社区图片网络加载（`coil-compose` + Bearer Header）

UI 现状：

- 已统一为深色电影感基调
- 提供玻璃卡片、渐变背景、基础入场动效
- 核心容器组件：`CamMatePage`、`SectionCard`

### 2.2 服务端（`py/`）

已实现路由：

- `GET /healthz`
- `POST /auth/register`
- `POST /auth/login`
- `GET /auth/me`
- `POST /auth/logout`
- `POST /scene/detect`
- `POST /analyze`（NDJSON）
- `POST /retouch`
- `POST /feedback`
- `POST /community/posts`
- `GET /community/feed`
- `GET /community/recommendations`
- `GET /community/posts/{post_id}/image`
- `POST /community/compose`

已实现关键逻辑：

- 全业务接口统一鉴权（除健康检查与登录注册）
- `feedback` 已支持 `review_text`
- 社区发布支持基础自动过滤：
  - 文本敏感词过滤（`COMMUNITY_BLOCKED_WORDS`）
  - 图片 Base64 校验与解码
  - 最大边长限制（`COMMUNITY_UPLOAD_MAX_SIDE`）
- 社区推荐已落地固定公式：
  - `final_score = 0.65*match + 0.25*(rating/5) + 0.10*freshness`
  - 30 天半衰新鲜度
  - 回退：全匹配 -> 单维匹配 -> 全局
- AI 融合已落地，复用同一图像模型接入器，并支持：
  - `COMMUNITY_IMAGE_*` 覆盖
  - 未配置时回退 `ARK_IMAGE_*`

### 2.3 数据库（当前真实结构）

当前为 SQLite 自动建表（`ensure_db`）：

- `users`
- `sessions`
- `feedback`（含 `review_text`）
- `community_posts`

已建索引：

- `idx_community_scene_place_created`
- `idx_community_created`

说明：

- 当前社区图片文件存储在本地目录（默认 `py/uploads/community`）
- `community_posts.image_path` 保存本地路径

## 3. 当前配置与运行方式

### 3.1 后端运行

```bash
mamba run -n Cam python -m pip install -r py/requirements.txt
mamba run -n Cam python -m uvicorn main:app --app-dir py --host 0.0.0.0 --port 8000
```

### 3.2 环境变量加载顺序

1. 进程环境变量（优先）
2. `py/.env.local`
3. `py/.env`

### 3.3 当前配置分层

- 分析链路：`ARK_*`
- 修图链路：`ARK_IMAGE_*`
- 社区链路：`COMMUNITY_*`
- 社区图像可选覆盖：`COMMUNITY_IMAGE_*`（未配置时回退 `ARK_IMAGE_*`）

## 4. 当前已知限制

- 数据库仍是 SQLite，尚未迁移 MySQL
- 社区审核为“自动发布 + 基础过滤”，暂无人工审核后台
- 姿势推荐维度尚未实现（当前仅地点/场景类型）
- AI 融合当前为同步请求，暂未任务化（队列/轮询）

## 5. 日志与排障

- 服务端日志：`py/logs/server.log`
- 客户端日志：应用私有目录 `files/logs/client.log`
- 请求追踪：`x-request-id` / `req=<id>`

常用排障入口：

- 鉴权：`/auth/*` + `app/core/security.py`
- 分析：`external.analyze.*` 日志链路
- 修图/融合：`retouch.provider.*` 日志链路

## 6. 与设计文档边界

- 本文档只写“当前已经做了什么”
- 最终版目标、未来补充、MySQL 完整设计见 [`DESIGN.md`](./DESIGN.md)
