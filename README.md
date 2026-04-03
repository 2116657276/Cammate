# Cammate

Cammate 是一个 `Android + FastAPI` 的 AI 拍摄应用，覆盖从拍前指导到拍后修图、评分发布、社区互动，再到 AI 融合/双人共创的完整链路。

当前演示版本的主导航已经收敛为三个底部分栏：

- 拍摄：辅助拍摄、姿势推荐、AI 融合
- 社区：朋友圈动态流，右上角进入“发布动态”和“玩法入口”独立页
- 设置：仅保留账户与退出登录

当前仓库建议把文档分成两层：

- `README.md`：项目入口、目录结构、快速启动、技术选型结论
- [`DEVELOPMENT.md`](/Users/zhoujianlin/AndroidStudioProjects/Camera/DEVELOPMENT.md)：唯一开发文档，记录当前实现、阶段进度、运行手册和后续计划

## 当前能力

当前主链路已经可运行：

`登录/注册 -> 场景识别 -> AI 构图建议 -> 拍照 -> AI 修图 -> 评分反馈 -> 可选发布社区 -> 社区互动 -> AI 融合/双人共创`

后端当前已实现的核心接口包括：

- 认证：`/auth/register`、`/auth/login`、`/auth/me`、`/auth/logout`
- 拍摄分析：`/scene/detect`、`/analyze`、`/retouch`、`/feedback`
- 社区：`/community/feed`、`/community/recommendations`、`/community/posts`、`/community/posts/direct`
- 互动：点赞、评论、举报、审核动作
- 创意任务：`/community/compose/jobs`、`/community/cocreate/jobs`、`/community/jobs/{job_id}`

## 项目结构

- [`kotlin`](/Users/zhoujianlin/AndroidStudioProjects/Camera/kotlin)：Android 客户端（Jetpack Compose + CameraX）
- [`py`](/Users/zhoujianlin/AndroidStudioProjects/Camera/py)：FastAPI 服务端、AI 能力接入、SQLite 数据层、创意任务 worker
- [`Pictures`](/Users/zhoujianlin/AndroidStudioProjects/Camera/Pictures)：本地演示素材来源
- [`py/demo_assets`](/Users/zhoujianlin/AndroidStudioProjects/Camera/py/demo_assets)：社区演示 seed 资源
- [`py/scripts`](/Users/zhoujianlin/AndroidStudioProjects/Camera/py/scripts)：联调、告警、恢复、清理脚本

## 快速启动

### 启动后端

```bash
mamba run -n Cam python -m pip install -r py/requirements.txt
mamba run -n Cam python -m uvicorn main:app --app-dir py --host 0.0.0.0 --port 8010
```

健康检查：

```bash
curl http://127.0.0.1:8010/healthz
```

### 生成社区演示库

```bash
mamba run -n Cam python py/scripts/build_demo_seed.py
APP_DB_PATH=py/demo_app_data.db mamba run -n Cam python -m uvicorn main:app --app-dir py --host 0.0.0.0 --port 8010
```

### 启动创意任务 worker

如果要体验异步融合/共创任务，推荐把 API 和 worker 分开跑：

```bash
APP_DB_PATH=py/demo_app_data.db mamba run -n Cam python -m uvicorn main:app --app-dir py --host 0.0.0.0 --port 8010
PYTHONPATH=py APP_DB_PATH=py/demo_app_data.db mamba run -n Cam python py/scripts/run_creative_worker.py
```

当前文档里曾提到根目录 `compose.yaml`，但仓库里并不存在该文件；所以本项目当前更准确的联调方式是直接连接本机已启动的 Redis / MinIO，或者把队列切回内存模式。

### 运行 Android 客户端

1. 用 Android Studio 打开 [`kotlin`](/Users/zhoujianlin/AndroidStudioProjects/Camera/kotlin)
2. Gradle Sync 完成后运行 `app`
3. 在设置页配置服务端地址

常用地址：

- 模拟器：`http://10.0.2.2:8010`
- 真机：`http://<局域网 IP>:8010`

说明：

- Android 客户端默认地址已经同步为 `8010`
- 若设备上保留了旧配置，应用应自动把历史默认值 `http://10.0.2.2:8000` 迁移到 `8010`
- 真机联调时仍需改成电脑的局域网 IP

## 当前架构

- 客户端：Jetpack Compose + CameraX
- 服务端：FastAPI
- 当前数据库：SQLite
- 异步队列：Redis
- 创意结果存储：本地文件优先，兼容 S3/MinIO 对象存储

`GET /healthz` 会返回：

- `scene_model`：场景模型加载状态
- `creative_queue`：队列连通性、延迟任务、租约风险、worker 活跃数、近期失败码

## MySQL / Redis / MinIO 是否值得使用

结合当前代码和阶段，建议是：

- MySQL：暂时不值得强切。当前仍是单机开发与联调阶段，SQLite 已经加了 `WAL`、`busy_timeout` 和自动迁移，足够支撑现在的节奏。等到多实例部署、并发明显上升、需要更稳定的运营数据和后台管理时，再迁移更划算。
- Redis：值得保留。现在的创意任务已经实现了独立 worker、延迟重试、租约、worker 心跳和健康指标，这类任务队列用 Redis 明显比进程内队列更稳，也更方便后续扩容。
- MinIO：有价值，但不必在所有环境强依赖。若只是本机开发或单机演示，本地文件就够用；如果要做多进程、多机器、结果持久化、清理策略和统一对象地址，MinIO/S3 就很合适。

一句话总结：当前推荐的现实组合是 `SQLite + Redis + 本地文件`，需要更接近生产环境时再升级到 `SQLite/MySQL + Redis + MinIO/S3`。

## 下一步建议

当前更值得优先推进的是：

- 做一轮真机/模拟器网络连通性核查，彻底排除旧地址残留
- 继续稳定创意任务链路与恢复脚本
- 在需求明确后再决定是否把 SQLite 迁到 MySQL

更完整的开发进度、运行手册和阶段计划见 [`DEVELOPMENT.md`](/Users/zhoujianlin/AndroidStudioProjects/Camera/DEVELOPMENT.md)。
