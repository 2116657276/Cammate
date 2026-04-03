# Cammate 开发文档

本文档是当前仓库唯一的开发主文档，集中记录：

- 项目目标与当前演示范围
- 目录职责与运行约定
- 已实现能力与关键实现点
- 本地运行方式
- 日志、排障、恢复脚本
- 技术选型与后续计划

## 1. 项目目标

Cammate 当前不是一个完整商业化产品仓库，而是一个以“完整演示链路”为核心目标的 AI 拍摄应用工程基线。

当前阶段最重要的目标是：

- 用户能顺畅进入应用
- 三个底部分栏职责清晰
- 社区能正常刷出动态
- 拍摄、姿势参考、AI 融合可以贯通
- 发帖、互动、退出登录这些基础动作稳定可演示
- 服务端与客户端在本地环境中可重复启动、可定位问题、可快速恢复

## 2. 当前状态

- 文档更新时间：`2026-04-03`
- 当前默认演示数据库：`py/demo_app_data.db`
- 当前本地实验数据库：`py/app_data.db`
- 当前统一服务端端口：`8010`
- 当前推荐运行方式：`run_api_server.py + demo_app_data.db`

当前主链路已可运行：

`注册 / 登录 -> 拍摄辅助 -> 拍照 -> 修图 -> 反馈 -> 可选发布社区 -> 浏览朋友圈 -> 点赞 / 评论 / 姿势参考 / AI 融合 -> 保存结果 -> 退出登录`

当前主导航结构：

- 拍摄：辅助拍摄、姿势推荐、AI 融合
- 社区：直接进入朋友圈动态流
- 设置：仅保留账号与退出登录

## 3. 目录与职责

### 3.1 Android 侧

- `kotlin/app/src/main/java/com/liveaicapture/mvp/ui/`
  - 主要页面与 Compose UI
- `kotlin/app/src/main/java/com/liveaicapture/mvp/network/`
  - 客户端接口访问与 DTO 解析
- `kotlin/app/src/main/java/com/liveaicapture/mvp/data/`
  - UI 状态、设置、会话持久化
- `kotlin/app/src/main/java/com/liveaicapture/mvp/log/`
  - 客户端日志文件与 logcat 策略

### 3.2 服务端

- `py/app/main.py`
  - FastAPI 应用创建、启动日志、请求日志中间件
- `py/app/api/routes/`
  - 路由层
- `py/app/services/`
  - 社区、反馈、修图、任务队列等业务逻辑
- `py/app/core/`
  - 配置、数据库、日志
- `py/app/models/`
  - Pydantic schema

### 3.3 数据与脚本

- `py/demo_app_data.db`
  - 默认演示数据库
- `py/app_data.db`
  - 本地实验数据库，不作为演示默认库
- `py/demo_assets/community_seed/`
  - 社区演示图与 `manifest.json`
- `py/uploads/community/`
  - 用户动态图片上传目录
- `py/creative_results/`
  - 创意任务结果落盘目录
- `py/scripts/`
  - 启动、seed、联调、恢复、告警脚本
- `Pictures/`
  - 原始素材来源目录

## 4. 当前已实现能力

### 4.1 客户端能力

已实现页面：

- `splash`
- `login`
- `register`
- `home`
- `camera`
- `pose_recommend`
- `ai_compose`
- `retouch`
- `feedback`
- `community`
- `community_publish`
- `community_tools`
- `settings`

已实现能力：

- 登录 / 注册 / 退出登录
- 登录态恢复与 Bearer Token 持久化
- CameraX 实时取帧
- 场景识别与 AI 引导提示
- 拍后修图与结果保存
- 反馈页提交评分与文案
- 反馈后可选发布社区
- 社区独立发帖页
- 社区动态流、点赞、评论、详情操作
- 社区图片鉴权加载
- 姿势参考页与姿势分析接口联动
- AI 融合与双人共创独立页
- 客户端日志文件落盘
- logcat 精简输出

### 4.2 服务端能力

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

- 除健康检查和登录注册外的统一鉴权
- 社区发帖图片接收、压缩、保存
- 社区动态 feed 分页
- 社区推荐规则
- 评论、点赞、举报、审核动作
- 演示 seed 数据从 `manifest.json` 构建
- 创意任务队列与任务状态流转
- 任务重试与取消
- 健康检查返回队列和模型状态
- 服务端日志分层输出

## 5. 当前产品结构说明

### 5.1 拍摄分栏

当前目标是突出演示主流程，而不是堆叠功能入口。

现状：

- 拍摄页保留辅助拍摄入口
- 姿势推荐直达独立页面
- AI 融合直达独立页面
- 原来容易混淆的设置项已从拍摄分栏移除

### 5.2 社区分栏

当前定义是“先刷朋友圈，再进入动作”。

现状：

- 点开社区，默认直接进入动态流
- 首页主要突出图片与点赞数
- 更多动作收在详情中
- “发布动态”“玩法入口”放在社区页右上角，并且进入独立页面

### 5.3 设置分栏

当前故意做轻：

- 展示账号相关内容
- 提供退出登录
- 去掉不影响演示的其他显示设置

## 6. 数据与演示内容

### 6.1 默认数据库

统一默认使用：

- `py/demo_app_data.db`

不再把以下数据库作为演示默认入口：

- `py/app_data.db`

原因：

- `demo_app_data.db` 对应当前整理后的社区演示图与真实 seed 数据
- `app_data.db` 可能保留旧测试帖子、旧占位图或实验数据

### 6.2 演示图片来源

当前图片链路：

1. 原始图片从 `Pictures/` 整理
2. 演示图输出到 `py/demo_assets/community_seed/`
3. `manifest.json` 记录元信息
4. `build_demo_seed.py` 将这些内容写入 `py/demo_app_data.db`

### 6.3 社区重复图问题处理

之前出现过“两个用户发布同一张图”的现象，原因是接力帖复用了主帖原图。

当前处理结果：

- 接力帖不再直接复用主图
- seed 过程中会生成单独的 relay 变体图
- 当前 demo 库已重建，避免同图重复出现

## 7. 本地运行

### 7.1 安装依赖

```bash
mamba run -n Cam python -m pip install -r py/requirements.txt
```

### 7.2 启动 API

统一使用：

```bash
mamba run -n Cam python py/scripts/run_api_server.py
```

这个脚本会：

- 默认使用 `py/demo_app_data.db`
- 默认监听 `8010`
- 如果 `8010` 已被旧 API 服务占用，先终止旧进程，再启动新服务

如果你明确不想自动重启旧服务，也可以：

```bash
mamba run -n Cam python py/scripts/run_api_server.py --no-restart
```

### 7.3 检查服务是否正常

```bash
curl http://127.0.0.1:8010/healthz
```

重点确认：

- `ok = true`
- `app_db_path` 指向 `py/demo_app_data.db`
- `creative_queue.connected = true`
- `scene_model.ready = true`

### 7.4 重建演示数据库

```bash
mamba run -n Cam python py/scripts/build_demo_seed.py
mamba run -n Cam python py/scripts/run_api_server.py
```

适用场景：

- 更新了 `Pictures/`
- 更新了 `manifest.json`
- 需要重建社区演示内容

### 7.5 启动 worker

如果要完整演示 AI 融合 / 双人共创异步任务：

```bash
mamba run -n Cam python py/scripts/run_api_server.py
PYTHONPATH=py APP_DB_PATH=py/demo_app_data.db mamba run -n Cam python py/scripts/run_creative_worker.py
```

如果只是最小联调：

```bash
CREATIVE_QUEUE_BACKEND=inmemory mamba run -n Cam python py/scripts/run_api_server.py
```

### 7.6 Android 连接说明

模拟器：

- `http://10.0.2.2:8010`

真机：

- `http://<你的局域网 IP>:8010`

注意：

- 客户端历史默认值 `8000` 已迁移到 `8010`
- 真机必须改成电脑局域网 IP，不能继续用 `10.0.2.2`

## 8. 日志机制

### 8.1 服务端日志

当前策略已经优化为两层：

- 终端：启动信息、警告、错误、慢请求
- 文件：完整请求链路与详细诊断信息

文件位置：

- `py/logs/server.log`

当前优化结果：

- 普通成功 `GET` 请求不再刷满终端
- `healthz`、图片请求等低价值输出不会持续干扰调试
- 仍然保留错误、慢请求和启动信息

### 8.2 客户端日志

当前策略：

- `INFO` 级别不再大量刷 `logcat`
- 关键异常继续保留在 `logcat`
- 详细内容同步写入客户端私有目录下的 `client.log`

### 8.3 建议排障顺序

推荐固定顺序：

1. 看 `healthz`
2. 看 `server.log`
3. 看 `adb logcat`
4. 看客户端当前连接的服务端地址
5. 确认服务端连接的数据库是否正确

## 9. 配置说明

环境变量加载顺序：

1. 进程环境变量
2. `py/.env.local`
3. `py/.env`

关键配置：

- `APP_DB_PATH`
  - 控制当前数据库
- `CREATIVE_QUEUE_BACKEND`
  - 控制创意任务队列后端
- `CREATIVE_REDIS_URL`
  - Redis 地址
- `CREATIVE_STORAGE_PROVIDER`
  - 本地文件或对象存储
- `CREATIVE_STORAGE_*`
  - 对象存储配置
- `COMMUNITY_BLOCKED_WORDS`
  - 社区敏感词过滤
- `SCENE_YOLO_CUSTOM_MODEL`
  - 场景识别模型覆盖

## 10. Redis / MinIO 联调

当前仓库不再默认依赖 Docker Compose。

当前更准确的口径是：

- Redis 直接连本机实例
- MinIO 直接连本机实例
- 如果对象存储暂时不可用，可以只验证 Redis，结果图继续落本地文件

Redis / MinIO 联调验证：

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

## 11. 已处理过的关键问题

本轮已明确处理过：

- 社区 feed 返回正常但客户端 JSON 解析因 `null` 崩溃
- 客户端默认服务地址残留旧端口
- 服务端错误连接到 `app_data.db` 而非 `demo_app_data.db`
- 社区接力帖复用主帖图片导致重复图
- 服务端终端日志过度刷屏
- 发布动态时地点被强制必填
- 发布动态时原图直传导致失败风险偏高
- 选图后只显示 URI，缺少真实缩略图预览
- 服务端重复启动时端口占用，无法直接重启

## 12. 当前已知边界

当前仍然存在一些阶段性边界：

- 社区推荐仍然是规则驱动，不是复杂个性化推荐系统
- 姿势推荐仍偏向参考图指导与分析，不是完整实时姿态教练
- AI 融合 / 双人共创仍以演示可用和 fallback 稳定性优先
- 审核后台尚未形成独立完整的可运营界面
- UI 视觉仍可继续升级，但当前优先级低于功能稳定性

## 13. 技术选型结论

### 13.1 MySQL

当前不建议立即切换：

- 现在主要是单机演示和联调
- 现有数据层、seed、脚本都围绕 SQLite 工作
- 当前痛点并不在关系数据库本身

什么时候值得切：

- 多实例部署
- 更高并发写入
- 更复杂的运营后台与统计需求

### 13.2 Redis

当前值得保留：

- 已经用于创意任务异步队列
- 有重试、租约、心跳、健康指标
- 明显优于只靠进程内队列

### 13.3 MinIO

当前保留抽象即可，不必强绑定：

- 本地演示阶段，本地文件够用
- 多机部署或统一对象管理时，再全面切入 MinIO / S3

当前最现实的组合：

`SQLite + Redis + 本地文件`

## 14. 下一步建议

当前更值得投入的方向：

- 继续打磨演示流程的一致性和稳定性
- 收敛 worker 启停与恢复体验
- 继续补足社区细节交互，但不打乱现有演示主路径
- 明确生产化需求后，再决定是否引入 MySQL 和更完整对象存储策略
