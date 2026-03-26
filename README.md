# Cammate

Android + FastAPI 的 AI 拍摄应用。当前代码已经覆盖：
`登录/注册 -> 场景识别 -> AI 构图建议 -> 拍照 -> AI 修图 -> 评分反馈 -> 可选发布社区 -> 社区主动发布 -> 社区互动 -> 社区推荐 -> AI 融合/双人共创`

发布链路说明（当前实现）：
- 评分后发布：帖子关联 `feedback_id`
- 社区主动发布：不强依赖 `feedback_id`（可为空）

## 文档分层（主文档三类）

- `README`（本文件）：项目入口、快速启动、文档导航
- 开发文档（当前已完成）：[`DEVELOPMENT.md`](./DEVELOPMENT.md)
- 设计文档（最终版目标 + 未来补充）：[`DESIGN.md`](./DESIGN.md)

## 快速启动

### 1) 启动 Python 服务端

在项目根目录执行：

```bash
mamba run -n Cam python -m pip install -r py/requirements.txt
mamba run -n Cam python -m uvicorn main:app --app-dir py --host 0.0.0.0 --port 8000
```

健康检查：

```bash
curl http://127.0.0.1:8000/healthz
```

### 2) 运行 Android 客户端

1. Android Studio 打开 `kotlin/`
2. Gradle Sync 完成后运行 `app`
3. 在 `Settings` 设置服务地址

常用地址：

- 模拟器：`http://10.0.2.2:8000`
- 真机：`http://<你的电脑局域网IP>:8000`

## 当前后端接口（已实现）

- `GET /healthz`
- `POST /auth/register`
- `POST /auth/login`
- `GET /auth/me`
- `POST /auth/logout`
- `POST /scene/detect`
- `POST /analyze`（NDJSON）
- `POST /retouch`
- `POST /feedback`（支持 `review_text`）
- `POST /community/posts`
- `POST /community/posts/direct`
- `POST /community/relay/posts`
- `GET /community/feed`
- `GET /community/recommendations`
- `GET /community/posts/{post_id}/image`
- `POST /community/posts/{post_id}/likes`
- `DELETE /community/posts/{post_id}/likes`
- `GET /community/posts/{post_id}/comments?offset&limit`
- `POST /community/posts/{post_id}/comments`
- `DELETE /community/comments/{comment_id}`
- `POST /community/remake/guide`
- `POST /community/compose`
- `POST /community/compose/jobs`
- `POST /community/cocreate/compose`
- `POST /community/cocreate/jobs`
- `GET /community/jobs/{job_id}`
- `POST /community/jobs/{job_id}/retry`
- `POST /community/jobs/{job_id}/cancel`

稳定性增强（V1.2）：

- 社区流仅展示服务端真实数据（客户端不再在失败时注入本地 mock 帖子）
- 创意任务新增轻量调度字段：`priority/started_at/heartbeat_at/lease_expires_at/cancel_reason`
- `GET /healthz` 返回 `creative_queue` 指标（`queued/running/failed_recent/workers`）

## 配置说明（简版）

服务端按顺序加载 `py/.env.local`、`py/.env`（进程环境变量优先）。

- 构图分析：`ARK_*`
- 修图：`ARK_IMAGE_*`
- 社区：`COMMUNITY_*`
- 社区图像模型可选覆盖：`COMMUNITY_IMAGE_*`（未配置时回退到 `ARK_IMAGE_*`）
- 社区创意任务并发：`COMMUNITY_CREATIVE_WORKER_COUNT`
- 社区创意任务重试：`COMMUNITY_CREATIVE_MAX_RETRIES` / `COMMUNITY_CREATIVE_RETRY_BASE_SEC`
- 社区假数据：开发默认开启；`APP_ENV=production` 默认关闭，可用 `COMMUNITY_SEED_ENABLED` 覆盖

详细配置、当前实现细节见 [`DEVELOPMENT.md`](./DEVELOPMENT.md)；最终目标配置规范见 [`DESIGN.md`](./DESIGN.md)。
