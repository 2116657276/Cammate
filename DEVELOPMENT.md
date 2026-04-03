# Cammate 开发进度

本文档是根目录唯一的开发文档，统一记录：

- 当前已实现能力
- 阶段进度与后续计划
- 本地运行方式
- 创意任务告警 / 恢复要点
- MySQL、Redis、MinIO 的取舍结论

## 1. 项目状态

- 文档更新时间：2026-04-03
- 当前代码基线：Android 客户端 + FastAPI 服务端
- 当前默认数据库：SQLite（默认 `py/app_data.db`，演示库可切到 `py/demo_app_data.db`）

当前主链路已可运行：

`登录/注册 -> 场景识别 -> AI 构图建议 -> 拍照 -> AI 修图 -> 评分（含文字评价） -> 可选发布社区 -> 社区主动发布 -> 社区互动 -> AI 融合/双人共创 -> 保存到相册`

当前演示导航结构：

- 拍摄分栏：辅助拍摄、姿势推荐、AI 融合
- 社区分栏：默认直接进入朋友圈动态
- 设置分栏：仅保留账号信息与退出登录

## 2. 目录与职责

- [`kotlin`](/Users/zhoujianlin/AndroidStudioProjects/Camera/kotlin)：Android 客户端、页面、状态管理、社区 UI
- [`py/app`](/Users/zhoujianlin/AndroidStudioProjects/Camera/py/app)：FastAPI 路由、服务层、数据库、视觉能力
- [`py/scripts`](/Users/zhoujianlin/AndroidStudioProjects/Camera/py/scripts)：构建演示数据、集成验证、告警、恢复、清理脚本
- [`py/demo_assets/community_seed`](/Users/zhoujianlin/AndroidStudioProjects/Camera/py/demo_assets/community_seed)：社区演示图与 `manifest.json`
- [`py/creative_results`](/Users/zhoujianlin/AndroidStudioProjects/Camera/py/creative_results)：创意任务结果落盘目录
- [`Pictures`](/Users/zhoujianlin/AndroidStudioProjects/Camera/Pictures)：演示素材来源目录

## 3. 当前已实现能力

### 3.1 Android 客户端

已实现页面与导航：

- `splash`
- `login` / `register`
- `home`
- `camera`
- `pose_recommend`
- `ai_compose`
- `settings`
- `retouch`
- `feedback`
- `community`
- `community_publish`
- `community_tools`

已实现业务能力：

- 登录态持久化与会话恢复
- CameraX 取帧、场景识别联动、AI 分析结果叠加
- 修图模式、结果预览与保存
- 评分页提交 `review_text` 并可选择是否发布到社区
- 社区主动发布、评分后发布、接力发布
- 社区流分页、点赞、评论、举报
- 同款复刻指南与分析
- AI 融合 / 双人共创异步任务
- 融合任务轮询、失败重试、前后对比滑杆
- 网络图片鉴权加载
- 社区“发布动态”和“玩法入口”已拆成独立页面
- 姿势推荐与 AI 融合已从拍摄分栏直达独立页面，不再先跳到社区首页

UI 现状：

- 暖色浅色系玻璃风
- 渐变背景 + 玻璃卡片
- 内置 `Noto Serif SC`

### 3.2 服务端

已实现核心路由：

- `GET /healthz`
- `POST /auth/register`
- `POST /auth/login`
- `GET /auth/me`
- `POST /auth/logout`
- `POST /scene/detect`
- `POST /analyze`
- `POST /retouch`
- `POST /feedback`
- `POST /community/posts`
- `POST /community/posts/direct`
- `POST /community/relay/posts`
- `GET /community/feed`
- `GET /community/recommendations`
- `GET /community/posts/{post_id}/image`
- 点赞、评论、举报、审核相关接口
- `POST /community/compose/jobs`
- `POST /community/cocreate/jobs`
- `GET /community/jobs/{job_id}`
- `POST /community/jobs/{job_id}/retry`
- `POST /community/jobs/{job_id}/cancel`

已实现关键逻辑：

- 全业务鉴权（除健康检查和登录注册）
- 社区发布基础审核：敏感词、Base64 校验、边长限制
- 推荐规则：地点、场景、评分、新鲜度、互动热度、用户偏好
- 场景模型覆盖：`SCENE_YOLO_CUSTOM_MODEL`
- 融合/共创模型覆盖：`COMMUNITY_IMAGE_*` 回退到 `ARK_IMAGE_*`
- 创意任务状态流转：`queued -> running -> success / failed / canceled`
- 失败任务重试与显式取消
- 社区 seed 数据由 `manifest.json` 驱动
- 举报流、审核动作、互动信号表

### 3.3 当前存储与队列

当前真实运行结构：

- 关系数据：SQLite
- 用户上传图：本地文件目录
- 创意结果图：本地文件目录，兼容对象存储键
- 创意任务队列：Redis
- 对象存储抽象：S3 兼容，默认可对接 MinIO

SQLite 当前已启用：

- `PRAGMA foreign_keys=ON`
- `PRAGMA busy_timeout=8000`
- `PRAGMA journal_mode=WAL`
- `PRAGMA synchronous=NORMAL`

## 4. 阶段进度

### 4.1 已完成

截至 2026-04-03，本轮已经完成的重点：

- P0：社区演示素材目录与 `manifest.json` 驱动 seed 已落地
- P1：创意任务补齐独立 worker 入口、Redis 队列、对象存储抽象、事件日志和健康指标
- P2：同款复刻补充姿态/构图/时机提示，本地 fallback 增强人物羽化、色调协调和投影
- P3：社区补充举报流、审核动作、互动信号，推荐开始参考互动热度与用户偏好
- P3：底部三分栏交互收敛，社区首页改成纯朋友圈动态页，发布/玩法改为独立弹出页
- P3：客户端默认后端地址切到 `http://10.0.2.2:8010`，并兼容历史 `8000` 默认值迁移

### 4.2 待推进

- P1：继续打磨生产化部署链路与运行稳定性
- P2：实时姿态纠偏、分割遮罩、深度/遮挡一致性
- P3：审核后台界面、活动/合集、个性化分发
- P4：修图历史与个人中心能力

## 5. 本地运行

### 5.1 启动后端

```bash
mamba run -n Cam python -m pip install -r py/requirements.txt
mamba run -n Cam python -m uvicorn main:app --app-dir py --host 0.0.0.0 --port 8010
```

健康检查：

```bash
curl http://127.0.0.1:8010/healthz
```

### 5.2 演示数据

```bash
mamba run -n Cam python py/scripts/build_demo_seed.py
APP_DB_PATH=py/demo_app_data.db mamba run -n Cam python -m uvicorn main:app --app-dir py --host 0.0.0.0 --port 8010
```

说明：

- 原始素材来自 [`Pictures`](/Users/zhoujianlin/AndroidStudioProjects/Camera/Pictures)
- 压缩后的演示图写入 [`py/demo_assets/community_seed`](/Users/zhoujianlin/AndroidStudioProjects/Camera/py/demo_assets/community_seed)
- 演示数据库位于 [`py/demo_app_data.db`](/Users/zhoujianlin/AndroidStudioProjects/Camera/py/demo_app_data.db)

### 5.3 创意任务 worker

推荐将 API 与 worker 分开启动：

```bash
APP_DB_PATH=py/demo_app_data.db mamba run -n Cam python -m uvicorn main:app --app-dir py --host 0.0.0.0 --port 8010
PYTHONPATH=py APP_DB_PATH=py/demo_app_data.db mamba run -n Cam python py/scripts/run_creative_worker.py
```

如果只是最小化联调，也可以把队列切回进程内模式：

```bash
CREATIVE_QUEUE_BACKEND=inmemory mamba run -n Cam python -m uvicorn main:app --app-dir py --host 0.0.0.0 --port 8010
```

### 5.4 Redis / MinIO 联调

当前仓库没有根目录 `compose.yaml`，所以这里不再把 Docker Compose 作为默认前提。

更准确的现状是：

- Redis 可以连接本机已启动实例，默认 `redis://127.0.0.1:6379/0`
- MinIO 可以连接本机已启动实例，默认 `http://127.0.0.1:9000`
- 如果对象存储暂时不可用，可以先只验证 Redis，或者继续使用本地文件结果目录

验证脚本：

```bash
mamba run -n Cam python py/scripts/integration_redis_minio.py \
  --redis-url redis://127.0.0.1:6379/0 \
  --storage-endpoint http://127.0.0.1:9000 \
  --storage-access-key minioadmin \
  --storage-secret-key minioadmin \
  --storage-region us-east-1 \
  --storage-bucket cammate-creative
```

仅验证 Redis：

```bash
mamba run -n Cam python py/scripts/integration_redis_minio.py \
  --redis-url redis://127.0.0.1:6379/0 \
  --skip-minio
```

## 6. 配置说明

环境变量加载顺序：

1. 进程环境变量
2. `py/.env.local`
3. `py/.env`

关键配置分层：

- 构图分析：`ARK_*`
- 修图与社区图像：`ARK_IMAGE_*`、`COMMUNITY_IMAGE_*`
- 演示库切换：`APP_DB_PATH`
- 场景模型覆盖：`SCENE_YOLO_CUSTOM_MODEL`
- 创意任务：`CREATIVE_QUEUE_BACKEND`、`CREATIVE_REDIS_URL`、`CREATIVE_EMBEDDED_WORKER`
- 对象存储：`CREATIVE_STORAGE_*`
- 社区审核：`COMMUNITY_BLOCKED_WORDS`、`COMMUNITY_ADMIN_TOKEN`

## 7. 创意任务告警与恢复

### 7.1 快速检查

先看 `GET /healthz` 的 `creative_queue`：

- `connected` / `queue_connectivity`
- `storage_connected`
- `ready` / `delayed` / `running`
- `leases_expiring`
- `retry_scheduled`
- `failure_codes_recent`
- `workers` / `workers_active`

也可以直接运行告警脚本：

```bash
PYTHONPATH=py python py/scripts/alert_creative_runtime.py --base-url http://127.0.0.1:8010
```

### 7.2 Redis 异常

常见信号：

- `queue_connectivity = down`
- `retry_scheduled` 增加
- `running` 长时间不下降

恢复步骤：

1. 恢复 Redis 服务
2. 运行恢复脚本 dry-run
3. 确认无误后执行正式恢复

```bash
PYTHONPATH=py python py/scripts/recover_creative_jobs.py --dry-run
PYTHONPATH=py python py/scripts/recover_creative_jobs.py
```

### 7.3 MinIO / 对象存储异常

常见信号：

- `storage_connected = false`
- `failure_codes_recent` 出现网络或超时相关错误

验证方式：

```bash
PYTHONPATH=py python py/scripts/integration_redis_minio.py \
  --redis-url redis://127.0.0.1:6379/0 \
  --storage-endpoint http://127.0.0.1:9000 \
  --storage-access-key minioadmin \
  --storage-secret-key minioadmin \
  --storage-bucket cammate-creative
```

### 7.4 Worker 异常

常见信号：

- `workers_active = 0`
- 但 `queued > 0` 或 `running > 0`

恢复方式：

```bash
chmod +x py/scripts/run_creative_worker_guard.sh
WORKER_CMD="PYTHONPATH=py python py/scripts/run_creative_worker.py" py/scripts/run_creative_worker_guard.sh
PYTHONPATH=py python py/scripts/recover_creative_jobs.py
```

### 7.5 结果清理与补偿

```bash
PYTHONPATH=py python py/scripts/cleanup_creative_objects.py --dry-run --ttl-hours 168
PYTHONPATH=py python py/scripts/cleanup_creative_objects.py --ttl-hours 168 --batch 120
```

清理失败会继续记录在 `community_creative_job_events`，可重复执行脚本补偿。

## 8. MySQL / Redis / MinIO 取舍结论

### 8.1 MySQL

当前结论：`暂不建议立即引入`。

原因：

- 当前服务仍以单机开发和演示联调为主
- SQLite 已经做了 `WAL`、`busy_timeout` 和自动迁移，足以支撑当前阶段
- 代码里所有数据层都直接建立在 SQLite 上，强切 MySQL 不是“换个连接串”这么简单
- 现在更大的瓶颈不在关系库，而在创意任务稳定性、模型链路和社区运营闭环

何时值得迁移：

- 多实例部署
- 更高并发写入
- 需要稳定的数据备份、运维、统计分析
- 需要正式审核后台和更复杂的数据管理

### 8.2 Redis

当前结论：`值得保留，而且已经是高价值组件`。

原因：

- 创意任务已支持独立 worker
- 已有延迟重试、租约、心跳、健康指标、失败码统计
- Redis 队列天然更适合这类异步任务调度
- 后续横向扩 worker 时不需要重做队列模型

如果完全去掉 Redis，会退回进程内队列，开发时可以接受，但稳定性和扩展性都会明显下降。

### 8.3 MinIO

当前结论：`值得保留抽象，但不建议把本地开发强绑到 MinIO`。

原因：

- 单机开发时，本地文件目录最省事
- 结果图、对比图本身很适合对象存储
- 一旦进入多进程、多机器或需要统一清理策略，MinIO/S3 的收益会很高
- 当前代码已经保留了本地路径与对象存储 key 的兼容读取，说明过渡策略是合理的

建议：

- 开发默认继续允许本地文件
- 集成测试或接近生产的环境再启用 MinIO
- 真正上线时优先使用标准 S3，MinIO 作为本地/私有化兼容方案

## 9. 下一步建议

按当前项目状态，更建议优先做这几件事：

1. 把本地联调方式收敛成一套真实可执行的脚本，补齐缺失的 `compose.yaml` 或彻底去掉它的引用。
2. 继续打磨创意任务链路，包括 worker 守护、恢复脚本和故障注入验收。
3. 等社区数据规模、部署方式和后台需求更明确后，再评估 SQLite -> MySQL 的迁移窗口。
